"""网页原型的多端同步：浏览器把本机数据和网盘账号交给本地服务，这里负责读写 WebDAV 和合并。

浏览器不能直接访问坚果云等 WebDAV（它们不给跨域头），做法和 Montage 网页版的 /api/dav 转交一样。
账号随每次请求带上，服务端不保存；只读写账号地址下 bilistudy/ 文件夹里的 .json 文件。
格式和合并规则见 docs/sync-format.md，与 iPad、Android 版一致；python3 sync.py 跑共用用例。
"""
import base64
import json
import os
import re
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET

FOLDER = 'bilistudy'
REMOVAL_MEMORY_MS = 180 * 24 * 3600 * 1000
LIMITS = {'history': 100, 'progress': 3000, 'watched': 3000}
NAME = re.compile(r'^[A-Za-z0-9._-]{1,80}\.json$')


class SyncError(Exception):
    pass


# ---------- 合并 ----------

def merge_sets(sets, now):
    """每个键各自按时间决定，和读文件的顺序无关；删除时间不早于最新修改时删掉并保留墓碑；墓碑 180 天后忘掉。"""
    items, removed = {}, {}
    for s in sets:
        if not isinstance(s, dict):
            continue
        for key, item in (s.get('items') or {}).items():
            if isinstance(item, dict) and isinstance(item.get('at'), (int, float)) and item['at'] > items.get(key, {}).get('at', float('-inf')):
                items[key] = item
        for key, at in (s.get('removals') or {}).items():
            if isinstance(at, (int, float)) and at > removed.get(key, float('-inf')):
                removed[key] = at
    kept = {k: v for k, v in items.items() if v['at'] > removed.get(k, float('-inf'))}
    removals = {k: at for k, at in removed.items() if now - at < REMOVAL_MEMORY_MS and k not in kept}
    return {'items': kept, 'removals': removals}


def trim(name, s):
    """history 只留最新 100 条，progress、watched 各 3000 条（丢掉的不记删除）。"""
    limit = LIMITS.get(name)
    if not limit or len(s['items']) <= limit:
        return s
    keep = sorted(s['items'].items(), key=lambda kv: kv[1]['at'], reverse=True)[:limit]
    return {'items': dict(keep), 'removals': s['removals']}


# ---------- WebDAV ----------

class Dav:
    def __init__(self, account):
        url = (account.get('url') or '').strip()
        if not url.startswith(('http://', 'https://')):
            raise SyncError('网盘地址无效')
        self.base = (url if url.endswith('/') else url + '/') + FOLDER + '/'
        token = '%s:%s' % (account.get('user', ''), account.get('password', ''))
        self.auth = 'Basic ' + base64.b64encode(token.encode('utf-8')).decode()

    def call(self, method, name='', body=None, headers=None):
        url = self.base + urllib.parse.quote(name)
        req = urllib.request.Request(url, data=body, method=method, headers=dict(headers or {}, Authorization=self.auth))
        try:
            with urllib.request.urlopen(req, timeout=20) as r:
                return r.status, r.read(), r.headers
        except urllib.error.HTTPError as e:
            return e.code, e.read(), e.headers
        except (urllib.error.URLError, OSError) as e:
            raise SyncError('连不上网盘：%s' % getattr(e, 'reason', e))

    @staticmethod
    def fail(code):
        return SyncError({401: '账号或应用密码不对', 403: '网盘拒绝访问（403）', 404: '网盘地址不对（404）',
                          507: '网盘空间不足'}.get(code, '网盘返回 %s' % code))

    def ensure_folder(self):
        code, _, _ = self.call('MKCOL')
        if code not in (200, 201, 301, 405, 409):  # 405：已存在；有的服务返回 409 或 301
            raise self.fail(code)

    def list(self):
        body = b'<?xml version="1.0" encoding="utf-8"?><d:propfind xmlns:d="DAV:"><d:prop><d:getetag/></d:prop></d:propfind>'
        code, data, _ = self.call('PROPFIND', body=body, headers={'Depth': '1', 'Content-Type': 'application/xml; charset=utf-8'})
        if code != 207 and not 200 <= code < 300:
            raise self.fail(code)
        return parse_propfind(data)

    def get(self, name):
        code, data, headers = self.call('GET', name)
        if not 200 <= code < 300:
            raise self.fail(code)
        return data, headers.get('ETag', '')

    def put(self, name, data):
        code, _, _ = self.call('PUT', name, body=data, headers={'Content-Type': 'application/json; charset=utf-8'})
        if not 200 <= code < 300:
            raise self.fail(code)


def parse_propfind(data):
    """每个 response 里的 href（取文件名）和 getetag；命名空间前缀各家不同，只看本地名。"""
    files = []
    try:
        root = ET.fromstring(data)
    except ET.ParseError:
        raise SyncError('网盘返回的文件列表无法解析')
    local = lambda el: el.tag.rsplit('}', 1)[-1]
    for resp in (el for el in root.iter() if local(el) == 'response'):
        href = next((el.text or '' for el in resp.iter() if local(el) == 'href'), '').strip()
        etag = next((el.text or '' for el in resp.iter() if local(el) == 'getetag'), '').strip()
        path = urllib.parse.unquote(href)
        if path.endswith('/'):
            continue
        name = path.rsplit('/', 1)[-1]
        if NAME.match(name):
            files.append((name, etag))
    return files


# ---------- 一轮同步 ----------

_cache_lock = threading.Lock()
_remote_cache = {}   # (网盘地址, 账号, 文件名) -> (etag, 文件内容)
_last_uploaded = {}  # (网盘地址, 账号, 设备) -> 上次上传的内容指纹


def run(account, device, device_name, local_sets):
    """读别人的文件 → 和本机合并 → 内容变了才上传自己的文件。返回合并后的集合和其他设备的名字。"""
    if not NAME.match('%s.json' % device):
        raise SyncError('设备编号无效')
    dav = Dav(account)
    dav.ensure_folder()
    own = '%s.json' % device
    owner = (dav.base, account.get('user', ''))
    docs, names = [], []
    for name, etag in dav.list():
        if name == own:
            continue
        with _cache_lock:
            cached = _remote_cache.get(owner + (name,))
        if cached and etag and cached[0] == etag:
            doc = cached[1]
        else:
            data, got = dav.get(name)
            try:
                doc = json.loads(data.decode('utf-8'))
            except ValueError:
                continue
            with _cache_lock:
                _remote_cache[owner + (name,)] = (got or etag, doc)
        docs.append(doc)
        if doc.get('deviceName'):
            names.append(doc['deviceName'])
    now = int(time.time() * 1000)
    local_sets = local_sets if isinstance(local_sets, dict) else {}
    all_names = set(local_sets) | {n for d in docs for n in (d.get('sets') or {})}
    merged = {}
    for n in sorted(all_names):
        merged[n] = trim(n, merge_sets([local_sets.get(n)] + [(d.get('sets') or {}).get(n) for d in docs], now))
    fingerprint = json.dumps(merged, sort_keys=True, ensure_ascii=False)
    key = owner + (device,)
    if _last_uploaded.get(key) != fingerprint:
        doc = {'version': 1, 'device': device, 'deviceName': device_name, 'platform': 'web', 'savedAt': now, 'sets': merged}
        dav.put(own, json.dumps(doc, ensure_ascii=False).encode('utf-8'))
        _last_uploaded[key] = fingerprint
    return {'sets': merged, 'devices': names, 'syncedAt': now}


def check(account):
    """保存账号前先实际访问一次网盘。"""
    dav = Dav(account)
    dav.ensure_folder()
    dav.list()
    return {'ok': True}


if __name__ == '__main__':
    cases = json.load(open(os.path.join(os.path.dirname(os.path.abspath(__file__)), 'docs', 'sync-cases.json'), encoding='utf-8'))
    for c in cases['cases']:
        for order in (c['sets'], list(reversed(c['sets']))):
            got = merge_sets(order, cases['now'])
            assert got == c['expected'], (c['name'], got)
    print('共用合并用例全部通过：%d 个（正序、倒序各跑一遍）' % len(cases['cases']))
