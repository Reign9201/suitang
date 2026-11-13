# Changelog

## 0.1.0 - 2026-11-13

首个公开版本。

- 网页原型：按「年级 → 学科 → 册次」归类 B 站校园学习分区的视频，册次、课本版本、内容类型筛选，推荐与整套课程分列，应用内播放（倍速、续播、看完 90% 打勾），一句话搜索，我的（继续学习、收藏），多端同步
- iPad 版（SwiftUI，iPadOS 17 起）：与原型同样的功能，AVPlayer 播放，密钥存系统钥匙串
- Android 版（Kotlin + Jetpack Compose，Android 8.0 起）：手机布局，Media3 ExoPlayer 播放，密钥用 Android Keystore 加密存储
- 大模型整理：每条视频给出是否对口、内容类型、难度、一句话简介、质量分和批注；没有密钥时自动退回标题规则分类
- 一句话搜索：把「我初二，浮力听不懂」解析成年级、学科、册次和 B 站搜索词，给出直达入口
- 多端同步：三端共用一份同步格式（`docs/sync-format.md`），合并用例三边都跑（`python3 sync.py`、iPad 与 Android 的单元测试）
- 界面：「黑板 + 作业本」设计语言，规范见 `designs/brand/README.md`，设计稿画布 8 张在 `designs/learning-videos/project/`
