#!/usr/bin/env python3
"""学习视频原型的本地服务：静态页面 + B 站接口转发与分类。

只用 Python 标准库，运行：python3 server.py（默认端口 5188，可用 PORT 环境变量改）。

接口
  GET /api/taxonomy                         年级、学科、册次目录
  GET /api/catalog?grade=7&subject=数学&volume=上册   某个分类下整理好的视频
  GET /api/view?bvid=BV...                  视频分集（课本目录）
  GET /api/search?q=...&page=1              站内搜索，结果带自动识别的年级/学科标签

B 站接入方式参考 Montage 项目 library/vod 的 BilibiliSearch / BilibiliVideoClient：
只用访客身份（buvid3 Cookie），不登录、不处理付费内容。
"""
import collections
import copy
import gzip
import hashlib
import json
import os
import re
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer

import ai
import classify as C
import sync

ROOT = os.path.dirname(os.path.abspath(__file__))
WEB = os.path.join(ROOT, 'web')
CACHE_DIR = os.path.join(ROOT, 'cache')
PORT = int(os.environ.get('PORT', '5188'))
UA = ('Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 '
      '(KHTML, like Gecko) Chrome/126.0 Safari/537.36')

CATALOG_TTL = 24 * 3600
VIEW_TTL = 7 * 24 * 3600
SEARCH_TTL = 3600


class BiliError(Exception):
    pass


class Bili:
    """B 站公开网页接口。全进程共用一个访客 Cookie，请求之间至少间隔 0.35 秒。"""

    def __init__(self):
        self.throttle = threading.Lock()
        self.cookie_lock = threading.Lock()
        self.cookie = ''
        self.cookie_at = 0.0
        self.last_call = 0.0

    def _open(self, url, referer, cookie=''):
        with self.throttle:
            wait = self.last_call + 0.35 - time.time()
            if wait > 0:
                time.sleep(wait)
            self.last_call = time.time()
        headers = {
            'User-Agent': UA,
            'Referer': referer,
            'Accept': 'application/json, text/plain, */*',
            'Accept-Encoding': 'gzip',
        }
        if cookie:
            headers['Cookie'] = cookie
        request = urllib.request.Request(url, headers=headers)
        try:
            with urllib.request.urlopen(request, timeout=8) as response:
                body = response.read()
                if response.headers.get('Content-Encoding') == 'gzip':
                    body = gzip.decompress(body)
                return response.status, body.decode('utf-8', 'replace')
        except urllib.error.HTTPError as failure:
            return failure.code, ''
        except (urllib.error.URLError, TimeoutError, OSError) as failure:
            raise BiliError('连不上 B 站：%s' % failure)

    def visitor_cookie(self, force=False):
        with self.cookie_lock:
            if not force and self.cookie and time.time() - self.cookie_at < 12 * 3600:
                return self.cookie
            status, body = self._open('https://api.bilibili.com/x/frontend/finger/spi',
                                      'https://www.bilibili.com/')
            try:
                data = json.loads(body)['data']
                b3, b4 = data.get('b_3', ''), data.get('b_4', '')
                self.cookie = ('buvid3=%s; buvid4=%s' % (b3, b4)) if b4 else ('buvid3=%s' % b3)
                self.cookie_at = time.time()
            except (ValueError, KeyError, TypeError):
                self.cookie = ''
            return self.cookie

    def get(self, url, referer):
        cookie = self.visitor_cookie()
        for attempt in range(3):
            status, body = self._open(url, referer, cookie)
            data = None
            if status == 200:
                try:
                    data = json.loads(body)
                except ValueError:
                    data = None
            blocked = status == 412 or (data is not None and data.get('code') == -412)
            if not blocked:
                if data is None:
                    raise BiliError('B 站返回 HTTP %s' % status)
                if data.get('code') != 0:
                    raise BiliError('B 站返回错误 %s：%s' % (data.get('code'), data.get('message')))
                return data.get('data') or {}
            time.sleep(0.4 * (attempt + 1))
            cookie = self.visitor_cookie(force=True)
        raise BiliError('B 站暂时拒绝了请求，过一会儿再试')

    def search(self, keyword, page=1, page_size=30, tids=C.STUDY_TID):
        params = {
            'search_type': 'video',
            'keyword': keyword,
            'order': 'totalrank',
            'page': page,
            'page_size': page_size,
        }
        if tids:
            params['tids'] = tids
        url = 'https://api.bilibili.com/x/web-interface/search/type?' + urllib.parse.urlencode(params)
        return self.get(url, 'https://search.bilibili.com/')

    def view(self, bvid):
        url = 'https://api.bilibili.com/x/web-interface/view?' + urllib.parse.urlencode({'bvid': bvid})
        return self.get(url, 'https://www.bilibili.com/video/%s/' % bvid)

    def playurl(self, bvid, cid):
        """访客可取的 MP4 地址（目前只有 360P）。与 Montage 的 BilibiliVideoClient.playback 相同。"""
        url = 'https://api.bilibili.com/x/player/playurl?' + urllib.parse.urlencode({
            'bvid': bvid, 'cid': cid, 'qn': 64, 'fnval': 1, 'fnver': 0, 'fourk': 0,
        })
        return self.get(url, 'https://www.bilibili.com/video/%s/' % bvid)


class Cache:
    """内存 + 磁盘 JSON 缓存，重启服务后已整理过的分类不用重新请求。"""

    def __init__(self, path):
        os.makedirs(path, exist_ok=True)
        self.path = path
        self.mem = {}
        self.lock = threading.Lock()

    def _file(self, key):
        return os.path.join(self.path, hashlib.sha1(key.encode('utf-8')).hexdigest()[:16] + '.json')

    def get(self, key, ttl):
        with self.lock:
            item = self.mem.get(key)
            if item is None and os.path.exists(self._file(key)):
                try:
                    with open(self._file(key), encoding='utf-8') as f:
                        item = json.load(f)
                    self.mem[key] = item
                except (OSError, ValueError):
                    item = None
        if item and time.time() - item['at'] < ttl:
            return item['value']
        return None

    def put(self, key, value):
        item = {'at': time.time(), 'value': value}
        with self.lock:
            self.mem[key] = item
            with open(self._file(key), 'w', encoding='utf-8') as f:
                json.dump(item, f, ensure_ascii=False)


bili = Bili()
cache = Cache(CACHE_DIR)


def build_catalog(n, subject, volume):
    queries = C.make_queries(n, subject, volume)
    seen = set()
    kept = []
    rejected = collections.Counter()
    samples = collections.defaultdict(list)
    for query in queries:
        result = bili.search(query).get('result') or []
        for row in result:
            item, reason = C.base_item(row)
            key = item['bvid'] if item else id(row)
            if key in seen:
                continue
            seen.add(key)
            if item is None:
                rejected[reason] += 1
                continue
            ok, reason = C.classify(item, n, subject, volume)
            if not ok:
                rejected[reason] += 1
                if len(samples[reason]) < 3:
                    samples[reason].append(item['title'][:60])
                continue
            kept.append(C.finish(item, n, subject, volume))
    kept.sort(key=lambda x: x['score'], reverse=True)
    return {
        'grade': n,
        'subject': subject,
        'volume': volume,
        'queries': queries,
        'seen': len(seen),
        'items': kept,
        'rejected': [{'reason': r, 'count': c, 'samples': samples.get(r, [])}
                     for r, c in rejected.most_common()],
        'builtAt': int(time.time()),
    }


def catalog(params):
    n = int(params.get('grade', '7'))
    subject = params.get('subject', '数学')
    volumes = C.volumes_for(n, subject)
    volume = params.get('volume') or volumes[0]
    if subject not in C.subjects_for(n) or volume not in volumes:
        raise ValueError('没有这个分类')
    key = 'catalog|%d|%s|%s' % (n, subject, volume)
    fresh = params.get('refresh') == '1'
    value = None if fresh else cache.get(key, CATALOG_TTL)
    if value is None:
        value = build_catalog(n, subject, volume)
        cache.put(key, value)
    result = copy.deepcopy(value)  # 缓存里的对象不能被改
    state = ensure_ai(n, subject, volume, value)
    if state['status'] == 'ready':
        apply_ai(result, state.pop('judged'), subject)
    result['ai'] = state
    for item in result['items']:
        item.pop('tagtext', None)
        item.pop('desc', None)
    return result


# ---------- 大模型整理（后台进行，页面轮询） ----------

ai_lock = threading.Lock()
ai_running = set()
ai_failed = {}  # key -> (时间, 原因)；失败后 10 分钟内不重试，改了配置文件则立即重试


def ai_key(model, n, subject, volume, items):
    # 模型名在缓存键里：换了模型会重新整理，不会沿用别的模型的结果
    ids = ','.join(sorted(i['bvid'] for i in items))
    return 'ai%d|%s|%d|%s|%s|%s' % (ai.ORGANIZE_PROMPT_VERSION, model, n, subject, volume,
                                    hashlib.sha1(ids.encode()).hexdigest()[:10])


def ensure_ai(n, subject, volume, base):
    state = ai.status()
    if not state['enabled']:
        return {'status': 'off', 'message': state['reason']}
    model = state['organize_model']
    if not base['items']:
        return {'status': 'ready', 'judged': {}, 'model': model}
    key = ai_key(model, n, subject, volume, base['items'])
    judged = cache.get(key, CATALOG_TTL)
    if judged is not None:
        return {'status': 'ready', 'judged': judged, 'model': model}
    with ai_lock:
        failed = ai_failed.get(key)
        if failed and time.time() - failed[0] < 600 and failed[2] == ai.config_version():
            return {'status': 'error', 'message': failed[1], 'model': model}
        if key not in ai_running:
            ai_running.add(key)
            threading.Thread(target=run_ai, daemon=True,
                             args=(key, model, n, subject, volume, copy.deepcopy(base['items']))).start()
    return {'status': 'pending', 'model': model}


def run_ai(key, model, n, subject, volume, items):
    started = time.time()
    try:
        judged = ai.organize(C.grade_name(n), C.stage_of(n), subject, volume, items, model=model)
        cache.put(key, judged)
        ai_failed.pop(key, None)
        sys.stderr.write('大模型整理完成：%s %s %s，%s，%d 条，%.1f 秒\n'
                         % (C.grade_name(n), subject, volume, model, len(judged), time.time() - started))
    except ai.AIError as failure:
        ai_failed[key] = (time.time(), str(failure), ai.config_version())
    except Exception as failure:  # 后台线程出错也要让页面知道，不能一直显示「整理中」
        ai_failed[key] = (time.time(), '大模型整理出错：%s' % failure, ai.config_version())
    finally:
        with ai_lock:
            ai_running.discard(key)


def apply_ai(result, judged, subject):
    """把大模型的判断合进规则结果：去掉不对口的，调整排序，选出最多 3 个推荐。"""
    kept, dropped = [], []
    for item in result['items']:
        j = judged.get(item['bvid'])
        if j is None:
            kept.append(item)
            continue
        if j['fit'] == '不对口':
            dropped.append(item)
            continue
        item['ai'] = {k: j[k] for k in ('fit', 'kind', 'level', 'summary', 'quality', 'comment')}
        if j.get('edition') and not item['editions']:
            item['editions'] = C.parse_editions(j['edition'], subject)[:1]
        item['score'] = round(item['score'] + (j['quality'] - 3) * 1.5 - (2 if j['fit'] == '相关' else 0), 3)
        kept.append(item)
    kept.sort(key=lambda x: x['score'], reverse=True)
    result['items'] = kept
    if dropped:
        result['rejected'].insert(0, {'reason': '大模型判断不对口', 'count': len(dropped),
                                      'samples': [d['title'][:60] for d in dropped[:3]]})
    # 推荐以大模型的质量判断为主：先比质量分，再比是不是新教材，最后才看播放收藏算出的分数
    candidates = sorted((i for i in kept if i.get('ai', {}).get('fit') == '对口' and i['ai']['quality'] >= 4),
                        key=lambda i: (i['ai']['quality'], i.get('new_textbook', False), i['score']), reverse=True)
    picks = []
    for item in candidates:
        if len(picks) == 3:
            break
        # 同一套课常被多个账号搬运：集数相同或标题高度相似的只推荐一条
        if any(same_course(item, p) for p in picks):
            continue
        picks.append(item)
    result['picks'] = [p['bvid'] for p in picks]


def same_course(a, b):
    if a['episodes'] and a['episodes'] == b['episodes']:
        return True
    grams = lambda t: {t[i:i + 2] for i in range(len(t) - 1)}
    ga, gb = grams(a['title']), grams(b['title'])
    return bool(ga and gb) and len(ga & gb) / float(min(len(ga), len(gb))) > 0.6


def season_of(data):
    """B 站「合集」：每一节是独立的 BV 号。分 P 视频没有这个字段。"""
    season = data.get('ugc_season') or {}
    episodes = []
    for section in season.get('sections') or []:
        for ep in section.get('episodes') or []:
            if not ep.get('bvid'):
                continue
            episodes.append({
                'bvid': ep['bvid'],
                'cid': ep.get('cid') or ((ep.get('page') or {}).get('cid')) or 0,
                'part': C.clean_text(ep.get('title')),
                'duration': ((ep.get('arc') or {}).get('duration')) or ((ep.get('page') or {}).get('duration')) or 0,
            })
    if len(episodes) < 2:
        return None
    return {'title': C.clean_text(season.get('title')), 'episodes': episodes}


def view(params):
    bvid = params.get('bvid', '')
    key = 'view|' + bvid
    value = cache.get(key, VIEW_TTL)
    if value is None:
        data = bili.view(bvid)
        value = {
            'bvid': data.get('bvid'),
            'title': C.clean_text(data.get('title')),
            'author': (data.get('owner') or {}).get('name', ''),
            'cover': C.https(data.get('pic') or ''),
            'desc': data.get('desc') or '',
            'duration': data.get('duration') or 0,
            'pubdate': data.get('pubdate') or 0,
            'play': (data.get('stat') or {}).get('view', 0),
            'favorites': (data.get('stat') or {}).get('favorite', 0),
            'pages': [{'page': p.get('page'), 'cid': p.get('cid'), 'part': C.clean_text(p.get('part')),
                       'duration': p.get('duration', 0)} for p in data.get('pages') or []],
            'season': season_of(data),
        }
        cache.put(key, value)
    return value


def search(params):
    q = (params.get('q') or '').strip()
    page = max(1, int(params.get('page', '1')))
    user_grade = int(params.get('grade') or 0)
    if not q:
        return {'items': [], 'page': 1, 'pages': 0}
    intent = understand(q, user_grade)
    keyword = (intent or {}).get('query') or q
    key = 'search|%s|%d' % (keyword, page)
    value = cache.get(key, SEARCH_TTL)
    if value is None:
        data = bili.search(keyword, page=page, page_size=20)
        items = []
        for row in data.get('result') or []:
            item, _ = C.base_item(row)
            if item is None:
                continue
            grades, subjects, editions, volumes = C.describe(item)
            n = max(grades) if len(grades) == 1 else 0
            item = C.finish(item, n, None)
            item.pop('tagtext', None)
            item.pop('desc', None)
            items.append(item)
        value = {'items': items, 'page': page, 'pages': data.get('numPages', 0),
                 'total': data.get('numResults', 0)}
        cache.put(key, value)
    value = copy.deepcopy(value)
    if intent and intent.get('grade'):
        # 和解析出的年级、学科一致的排前面，其余保持 B 站的顺序
        g, subj = intent['grade'], C.canonical_subject(intent.get('subject') or '')
        def rank(item):
            same_grade = g in item['grades']
            same_subject = not subj or subj in {C.canonical_subject(s) for s in item['subjects']}
            return 0 if same_grade and same_subject else (1 if same_subject else 2)
        value['items'].sort(key=rank)
    value['intent'] = intent
    value['ai'] = ai.status()['enabled']
    return value


def understand(q, user_grade):
    """把一句话解析成年级、学科、册次、知识点和 B 站搜索词。没有大模型或解析失败时返回 None。"""
    state = ai.status()
    if not state['enabled']:
        return None
    model = state['intent_model']
    key = 'intent%d|%s|%d|%s|%s' % (ai.INTENT_PROMPT_VERSION, model, user_grade, time.strftime('%Y%m'), q)
    intent = cache.get(key, 7 * 24 * 3600)
    if intent is not None:
        return intent
    try:
        intent = ai.parse_intent(q, C.grade_name(user_grade) if 1 <= user_grade <= 12 else '', model=model)
    except ai.AIError as failure:
        return {'error': str(failure)}
    n = intent['grade']
    subject = intent.get('subject') or ''
    if n:
        # 初中叫道德与法治，高中叫政治；不在这个年级科目表里的学科不给跳转
        if subject in ('道德与法治', '政治'):
            subject = '政治' if n >= 10 else '道德与法治'
        if subject not in C.subjects_for(n):
            subject = ''
    intent['subject'] = subject
    if not (n and subject and intent.get('volume') in C.volumes_for(n, subject)):
        intent['volume'] = ''
    intent['gradeName'] = C.grade_name(n) if n else ''
    cache.put(key, intent)
    return intent


QUALITY = {16: '360P', 32: '480P', 64: '720P', 80: '1080P'}
PLAY_TTL = 30 * 60  # B 站视频地址约 2 小时过期，留足余量


def playback(bvid, cid, fresh=False):
    if not re.match(r'^BV[0-9A-Za-z]{10}$', bvid) or not str(cid).isdigit():
        raise ValueError('不是有效的视频')
    key = 'play|%s|%s' % (bvid, cid)
    value = None if fresh else cache.get(key, PLAY_TTL)
    if value is None:
        data = bili.playurl(bvid, cid)
        segments = [[d['url']] + list(d.get('backup_url') or []) for d in data.get('durl') or [] if d.get('url')]
        if not segments:
            raise BiliError('这个视频暂时只能在 B 站播放')
        value = {'quality': QUALITY.get(data.get('quality'), '%sP' % data.get('quality')), 'segments': segments}
        cache.put(key, value)
    return value


def play(params):
    bvid, cid = params.get('bvid', ''), params.get('cid', '')
    value = playback(bvid, cid)
    query = urllib.parse.urlencode({'bvid': bvid, 'cid': cid})
    # 绝大多数视频只有一段；多段的原型只播第一段，安卓端按 Montage 的做法拼接
    return {'quality': value['quality'], 'src': '/media?' + query, 'segments': len(value['segments'])}


ROUTES = {
    '/api/taxonomy': lambda params: dict(C.taxonomy(), ai=ai.status()),
    '/api/catalog': catalog,
    '/api/view': view,
    '/api/search': search,
    '/api/play': play,
}

MEDIA_UA = ('Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 '
            '(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36')


class Handler(SimpleHTTPRequestHandler):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=WEB, **kwargs)

    def end_headers(self):
        self.send_header('Cache-Control', 'no-store')
        super().end_headers()

    def stream_media(self, params):
        """把 B 站视频流转给页面：浏览器没法自己带 B 站的 Referer，CDN 会拒绝。支持拖动进度（Range）。"""
        bvid, cid = params.get('bvid', ''), params.get('cid', '')
        upstream = None
        for fresh in (False, True):
            try:
                urls = playback(bvid, cid, fresh=fresh)['segments'][0]
            except (ValueError, BiliError) as failure:
                self.send_error(502, str(failure))
                return
            headers = {'User-Agent': MEDIA_UA, 'Referer': 'https://www.bilibili.com/video/%s/' % bvid,
                       'Origin': 'https://www.bilibili.com', 'Accept': '*/*'}
            if self.headers.get('Range'):
                headers['Range'] = self.headers['Range']
            for url in urls:
                try:
                    upstream = urllib.request.urlopen(urllib.request.Request(url, headers=headers), timeout=10)
                    break
                except urllib.error.HTTPError as failure:
                    if failure.code == 416:
                        upstream = failure
                        break
                except (urllib.error.URLError, OSError):
                    continue
            if upstream is not None:
                break  # 地址都失效时，换一次新地址再试
        if upstream is None:
            self.send_error(502, 'B 站视频地址不可用')
            return
        with upstream:
            self.send_response(upstream.status if hasattr(upstream, 'status') else upstream.code)
            for name in ('Content-Type', 'Content-Length', 'Content-Range', 'Accept-Ranges'):
                if upstream.headers.get(name):
                    self.send_header(name, upstream.headers[name])
            self.end_headers()
            try:
                while True:
                    chunk = upstream.read(64 * 1024)
                    if not chunk:
                        break
                    self.wfile.write(chunk)
            except (BrokenPipeError, ConnectionResetError):
                pass  # 拖动进度时浏览器会主动断开旧请求

    def do_GET(self):
        parsed = urllib.parse.urlparse(self.path)
        params = {k: v[0] for k, v in urllib.parse.parse_qs(parsed.query).items()}
        if parsed.path == '/media':
            return self.stream_media(params)
        route = ROUTES.get(parsed.path)
        if route is None:
            return super().do_GET()
        try:
            body, status = route(params), 200
        except ValueError as failure:
            body, status = {'error': str(failure)}, 400
        except BiliError as failure:
            body, status = {'error': str(failure)}, 502
        self.send_json(body, status)

    def send_json(self, body, status=200):
        payload = json.dumps(body, ensure_ascii=False).encode('utf-8')
        self.send_response(status)
        self.send_header('Content-Type', 'application/json; charset=utf-8')
        self.send_header('Content-Length', str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def do_POST(self):
        """多端同步：账号放在请求体里（不进网址和日志），服务端不保存，见 sync.py。"""
        path = urllib.parse.urlparse(self.path).path
        if path not in ('/api/sync', '/api/sync/check'):
            return self.send_json({'error': '没有这个接口'}, 404)
        try:
            length = int(self.headers.get('Content-Length') or 0)
            if length > 8 * 1024 * 1024:
                return self.send_json({'error': '数据太大'}, 413)
            req = json.loads(self.rfile.read(length).decode('utf-8') or '{}')
            account = req.get('account') or {}
            if path == '/api/sync/check':
                body = sync.check(account)
            else:
                body = sync.run(account, str(req.get('device', '')), str(req.get('deviceName', '网页'))[:40], req.get('sets'))
            self.send_json(body)
        except sync.SyncError as failure:
            self.send_json({'error': str(failure)}, 502)
        except ValueError:
            self.send_json({'error': '请求格式不对'}, 400)

    def log_message(self, fmt, *args):
        sys.stderr.write('%s %s\n' % (self.log_date_time_string(), fmt % args))


if __name__ == '__main__':
    try:
        server = ThreadingHTTPServer(('127.0.0.1', PORT), Handler)
    except OSError as failure:
        if failure.errno != 48:  # 48：macOS 上的「端口已被占用」
            raise
        print('端口 %d 已被占用，服务可能已经在运行，直接打开 http://localhost:%d 即可。\n'
              '要换端口：PORT=5189 python3 server.py' % (PORT, PORT), file=sys.stderr)
        sys.exit(1)
    print('学习视频原型：http://localhost:%d' % PORT, flush=True)
    state = ai.status()
    print('大模型整理：%s' % ('已启用，整理用 %s，搜索解析用 %s' % (state['organize_model'], state['intent_model'])
                         if state['enabled'] else '未启用，' + state['reason']), flush=True)
    server.serve_forever()
