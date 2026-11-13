# 多端同步格式（WebDAV）

网页原型、iPad、Android 用同一个格式，把学习数据存在用户自己的 WebDAV 网盘里（坚果云、NAS、Alist 等），不需要自建服务器。做法照搬 Montage 项目 `library/vod/.../data/sync`。

## 文件

- 文件夹：账号地址下的 `bilistudy/`，第一次同步时用 `MKCOL` 创建。
- 每台设备只写自己的文件 `bilistudy/<设备 ID>.json`，只读别人的文件，所以两台设备不会互相覆盖。设备 ID 是第一次启动时生成的 UUID，存在本机。
- 列文件用 `PROPFIND`（`Depth: 1`），按 ETag 判断别的设备的文件有没有变，没变就不重新下载。

## 文件内容

```json
{
  "version": 1,
  "device": "5F0C…",
  "deviceName": "iPad Air",
  "platform": "ios",
  "savedAt": 1762912800000,
  "sets": {
    "favorites": { "items": { "BV18pWGzbEvh": { "at": 1762912000000, "data": { "bvid": "BV18pWGzbEvh", "title": "…", "cover": "https://…", "author": "…", "duration": 55153, "episodes": 56, "grade": 7, "subject": "数学", "volume": "上册" } } }, "removals": { "BV1X9jJ6REYN": 1762911000000 } },
    "history":   { "items": { "BV18pWGzbEvh": { "at": 1762912600000, "data": { "bvid": "…", "title": "…", "cover": "…", "author": "…", "lesson": "p3", "index": 2, "part": "1.2.1 有理数的概念", "total": 56, "grade": 7, "subject": "数学", "volume": "上册" } } }, "removals": {} },
    "progress":  { "items": { "BV18pWGzbEvh|p3": { "at": 1762912600000, "data": { "position": 252, "duration": 782 } } }, "removals": {} },
    "watched":   { "items": { "BV18pWGzbEvh|p1": { "at": 1762900000000, "data": true } }, "removals": {} },
    "prefs":     { "items": { "grade": { "at": 1762900000000, "data": 7 }, "edition|数学": { "at": 1762900000000, "data": "人教版" } }, "removals": {} }
  }
}
```

- `platform` 是 `ios`、`android` 或 `web`，只用来显示，合并时不看。
- 时间都是毫秒时间戳。
- `sets` 里每个集合都是同一种结构：`items` 是「键 → 最后一次修改的时间和数据」，`removals` 是「键 → 删除时间」。
- 同步只合并 `sets`；`data` 的内容由各功能自己读写。新增功能时加一个新的集合名，旧版本 App 会原样保留它不认识的集合。

| 集合 | 键 | 数据 |
| --- | --- | --- |
| `favorites` | BV 号 | 收藏时的课程快照（标题、封面、作者、时长、集数、年级、学科、册次），离线也能显示 |
| `history` | BV 号 | 最近在学：上次看到的那一节（`lesson` 是分 P 的 `p3` 或合集里那一节的 BV 号）和来源分类 |
| `progress` | `BV 号|节` | 这一节看到的秒数和总时长 |
| `watched` | `BV 号|节` | 这一节看过 90% |
| `prefs` | `grade`、`subject|年级`、`volume|年级|学科`、`edition|学科` | 选过的年级、学科、册次、课本版本 |

大模型的接口地址、密钥，WebDAV 账号和密码都不进同步文件，只存在本机的系统钥匙串（iOS Keychain、Android Keystore）。

## 合并规则

所有设备的文件（包括本机的）合并成一个结果，每个集合、每个键各自按时间决定，和读文件的顺序无关，所以每台设备最后都得到同样的结果：

1. 同一个键在多份 `items` 里出现，取 `at` 最大的那份。
2. 同一个键在多份 `removals` 里出现，取最大的删除时间。
3. 某个键的删除时间不早于它最新的 `at`（时间相同算删除），这个键被删掉，删除记录保留；否则保留这个键，删除记录作废。
4. 删除记录保留 180 天，之后忘掉（离线超过 180 天的设备可能把删掉的东西带回来，可以接受）。
5. 集合大小限制：`history` 只留最新 100 条，`progress` 和 `watched` 各留最新 3000 条，超出的按 `at` 从旧到新丢掉（丢掉不记删除）。

和 Montage 相同的地方：一个设备一个文件、按时间合并、删除留墓碑、180 天遗忘、ETag 缓存。不同的地方：Montage 的收藏和历史是专门的字段，这里全部用通用集合（Montage 的 `SyncSet`），三个平台只需要实现一套合并。

`docs/sync-cases.json` 是三端共用的合并测试用例：网页原型的 `python3 sync.py`、iOS 和 Android 的单元测试都跑它。

## 什么时候同步

- 回到前台或回到首页时，最多每分钟一次。
- 收藏、取消收藏后约 3 秒。
- 退出播放页时。
- 「我的」和「设置」里点「立即同步」。

每轮：`MKCOL`（只在第一次）→ `PROPFIND` 列文件 → 下载 ETag 变了的别人的文件 → 合并 → 应用到本机 → 本机内容和上次上传的不同时 `PUT` 自己的文件。

## 坚果云

- 地址 `https://dav.jianguoyun.com/dav/`，账号是坚果云邮箱，密码是「应用密码」：网页版「账户信息 → 安全选项 → 第三方应用管理」生成，不是登录密码。
- 保存账号时先实际访问一次（`MKCOL` 加 `PROPFIND`），账号或密码不对直接提示。

## 术语说明

**WebDAV**：一种通过 HTTP 读写网盘文件的协议，坚果云、群晖 NAS、Alist 等都支持。

**MKCOL / PROPFIND / GET / PUT**：WebDAV 的四种请求，分别是建文件夹、列出文件、下载文件、上传文件。

**ETag**：服务器给每个文件版本的标记，文件内容变了它就变，用来判断要不要重新下载。

**墓碑（removals）**：删除记录。只删数据不留记录的话，别的设备上还没删的旧副本会在下次合并时把它带回来。
