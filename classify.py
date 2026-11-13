"""年级、学科、册次的目录表，以及把 B 站搜索结果归到目录里的规则。

前端和后端共用这里的目录表（通过 /api/taxonomy 下发），改年级或学科只改这一个文件。
"""
import math
import re
import time

CN = '一二三四五六七八九'
CN_DIGIT = {c: i + 1 for i, c in enumerate(CN)}
CN_DIGIT.update({str(i): i for i in range(1, 10)})

# 校园学习分区。只收这个分区的视频，娱乐区、生活区里的搬运号直接过滤掉。
STUDY_TID = 208

# 义务教育阶段从 2024 年秋季开始逐年换新教材，这之后发布的视频优先。
NEW_TEXTBOOK_SINCE = int(time.mktime((2024, 7, 1, 0, 0, 0, 0, 0, -1)))


def grade_name(n):
    return '%s年级' % CN[n - 1] if n <= 9 else '高' + CN[n - 10]


def grade_short(n):
    return CN[n - 1] if n <= 9 else '高' + CN[n - 10]


def stage_of(n):
    return '小学' if n <= 6 else ('初中' if n <= 9 else '高中')


def subjects_for(n):
    if n <= 2:
        return ['语文', '数学', '道德与法治', '科学']
    if n <= 6:
        return ['语文', '数学', '英语', '科学', '道德与法治']
    if n == 7:
        return ['语文', '数学', '英语', '生物', '地理', '历史', '道德与法治']
    if n == 8:
        return ['语文', '数学', '英语', '物理', '生物', '地理', '历史', '道德与法治']
    if n == 9:
        return ['语文', '数学', '英语', '物理', '化学', '历史', '道德与法治']
    return ['语文', '数学', '英语', '物理', '化学', '生物', '历史', '地理', '政治']


def volumes_for(n, subject):
    if n == 9 and subject == '物理':
        return ['全一册']
    if n <= 9:
        return ['上册', '下册']
    if n == 10:
        return ['必修一', '必修二']
    if n == 11:
        return ['选择性必修一', '选择性必修二']
    return ['一轮复习', '二轮复习']


def volume_short(n, volume):
    """书脊上的小字，例如「七上」「必修一」。"""
    if n <= 9 and volume in ('上册', '下册'):
        return CN[n - 1] + volume[0]
    if volume.startswith('选择性必修'):
        return '选必' + volume[-1]
    if volume.endswith('轮复习'):
        return volume[:2]
    return volume


def taxonomy():
    stages = []
    for stage, lo, hi in (('小学', 1, 6), ('初中', 7, 9), ('高中', 10, 12)):
        grades = []
        for n in range(lo, hi + 1):
            grades.append({
                'id': 'g%d' % n,
                'n': n,
                'name': grade_name(n),
                'short': grade_short(n),
                'subjects': [
                    {
                        'name': s,
                        'volumes': [
                            {'name': v, 'short': volume_short(n, v)} for v in volumes_for(n, s)
                        ],
                    }
                    for s in subjects_for(n)
                ],
            })
        stages.append({'name': stage, 'grades': grades})
    return {'stages': stages}


# ---------- 搜索词 ----------

def _volume_query_text(subject, volume):
    if subject == '语文':
        return {'必修一': '必修上册', '必修二': '必修下册',
                '选择性必修一': '选择性必修上册', '选择性必修二': '选择性必修中册'}.get(volume, volume)
    return volume


def make_queries(n, subject, volume):
    v = _volume_query_text(subject, volume)
    if n <= 6:
        return ['%s%s%s' % (grade_name(n), subject, v), '%s%s%s 全集' % (grade_name(n), subject, v)]
    if n <= 9:
        return ['%s%s%s' % (grade_name(n), subject, v), '初%s%s%s' % (CN[n - 7], subject, v)]
    if n <= 11:
        return ['高中%s%s' % (subject, v), '%s%s%s' % (grade_short(n), subject, v)]
    return ['高考%s%s' % (subject, v), '高三%s%s' % (subject, v)]


# ---------- 从标题里认出年级、学科、版本、册次 ----------

_RANGE = r'\s*[到至~\-—－]\s*'


def parse_grades(text):
    """返回 (明确提到的年级, 只能推断的年级)。

    明确：七年级、初一、高二、七上；推断：小升初、中考、高考。
    明确年级存在时只用它，避免「七年级……直击中考」被归进九年级。
    """
    strong = set()
    for m in re.finditer(r'([一二三四五六七八九1-9])' + _RANGE + r'([一二三四五六七八九1-9])\s*年级', text):
        a, b = CN_DIGIT[m.group(1)], CN_DIGIT[m.group(2)]
        strong.update(range(min(a, b), max(a, b) + 1))
    for m in re.finditer(r'([一二三四五六七八九1-9])\s*年级', text):
        strong.add(CN_DIGIT[m.group(1)])
    for m in re.finditer(r'初([一二三])' + _RANGE + r'初?([一二三])', text):
        a, b = CN_DIGIT[m.group(1)], CN_DIGIT[m.group(2)]
        strong.update(range(6 + min(a, b), 6 + max(a, b) + 1))
    for m in re.finditer(r'初([一二三])(?![级中])', text):
        strong.add(6 + CN_DIGIT[m.group(1)])
    for m in re.finditer(r'高([一二三])' + _RANGE + r'高?([一二三])', text):
        a, b = CN_DIGIT[m.group(1)], CN_DIGIT[m.group(2)]
        strong.update(range(9 + min(a, b), 9 + max(a, b) + 1))
    for m in re.finditer(r'(?<![提最升])高([一二三])(?![级点倍])', text):
        strong.add(9 + CN_DIGIT[m.group(1)])
    # 「七上」「三下」这类简写，后面要跟着册、学科或分隔符才算，避免误认成语
    for m in re.finditer(
            r'(?<![第每其之初高一二三四五六七八九十])([一二三四五六七八九])([上下])'
            r'(?=册|\s|语文|数学|英语|物理|化学|生物|历史|地理|道法|道德|科学|$|[】\]｜|，,）)])', text):
        strong.add(CN_DIGIT[m.group(1)])
    weak = set()
    if '小升初' in text:
        weak.add(6)
    if '中考' in text:
        weak.add(9)
    if '高考' in text:
        weak.add(12)
    if re.search(r'选择性必修', text):
        weak.add(11)
    elif re.search(r'必修', text):
        weak.add(10)
    return strong, weak


SUBJECT_PATTERNS = [
    ('语文', r'语文|文言文|古诗文'),
    ('数学', r'数学|奥数'),
    ('英语', r'英语|English|新概念'),
    ('物理', r'物理'),
    ('化学', r'化学'),
    ('生物', r'生物'),
    ('历史', r'历史'),
    ('地理', r'地理'),
    ('道德与法治', r'道德与法治|道法|思想品德|思想政治|政治|思政'),
    ('科学', r'科学(?!普)'),
]


def canonical_subject(subject):
    # 初中叫道德与法治，高中叫政治，比对时视为同一科
    return '道德与法治' if subject == '政治' else subject


def parse_subjects(text):
    found = set()
    for name, pattern in SUBJECT_PATTERNS:
        if re.search(pattern, text, re.I):
            found.add(name)
    return found


EDITION_PATTERNS = [
    ('人教版', r'人教[AB]?版|新人教|人教'),
    ('统编版', r'统编|部编'),
    ('北师大版', r'北师'),
    ('苏教版', r'苏教'),
    ('沪教版', r'沪教'),
    ('外研版', r'外研'),
    ('译林版', r'译林'),
    ('青岛版', r'青岛版'),
    ('浙教版', r'浙教'),
    ('湘教版', r'湘教'),
    ('冀教版', r'冀教'),
    ('鲁教版', r'鲁教'),
    ('沪科版', r'沪科'),
    ('华师大版', r'华师'),
    ('北京版', r'北京版'),
    ('教科版', r'教科版'),
    ('粤教版', r'粤教'),
    ('科粤版', r'科粤'),
    ('仁爱版', r'仁爱'),
    ('牛津版', r'牛津'),
]

# 这三科全国统一用统编版，人教社出版，很多 UP 主写成「人教版」
UNIFIED_SUBJECTS = {'语文', '历史', '道德与法治'}


def parse_editions(text, subject=None):
    found = []
    for name, pattern in EDITION_PATTERNS:
        if re.search(pattern, text) and name not in found:
            found.append(name)
    if subject and canonical_subject(subject) in UNIFIED_SUBJECTS:
        found = ['统编版' if e == '人教版' else e for e in found]
        found = list(dict.fromkeys(found))
    return found


def parse_volumes(text):
    found = set()
    for m in re.finditer(r'选择性必修\s*(?:第)?([一二三123上中下])', text):
        found.add('选择性必修' + {'上': '一', '中': '二', '下': '三'}.get(m.group(1), CN[CN_DIGIT.get(m.group(1), 1) - 1]))
    for m in re.finditer(r'(?<!选择性)必修\s*(?:第)?([一二三123上下])', text):
        found.add('必修' + {'上': '一', '下': '二'}.get(m.group(1), CN[CN_DIGIT.get(m.group(1), 1) - 1]))
    if '全一册' in text:
        found.add('全一册')
    if re.search(r'上下(?:全)?册|上[/、和及]下册', text):
        found.update(('上册', '下册'))
    if re.search(r'(?<!必修)(?<!选择性必修)上册|上学期|[一二三四五六七八九]上(?=册|\s|语文|数学|英语|物理|化学|生物|历史|地理|道法|科学|$|[】\]｜，,）)])', text):
        found.add('上册')
    if re.search(r'(?<!必修)(?<!选择性必修)下册|下学期|[一二三四五六七八九]下(?=册|\s|语文|数学|英语|物理|化学|生物|历史|地理|道法|科学|$|[】\]｜，,）)])', text):
        found.add('下册')
    if '一轮' in text:
        found.add('一轮复习')
    if '二轮' in text:
        found.add('二轮复习')
    return found


def parse_episodes(text):
    m = re.search(r'(?:全|共)\s*(\d{1,4})\s*[集讲节课期P]', text) \
        or re.search(r'(\d{1,4})\s*集全', text) \
        or re.search(r'(?<![第\d.])(\d{1,4})\s*(?:集|讲|节课?|[Pp](?![a-zA-Z]))', text)
    return int(m.group(1)) if m else 0


REVIEW = re.compile(r'复习|期中|期末|冲刺|真题|押题|备考|考前|速通|速成|刷完|总结|压轴')


def parse_duration(text):
    """搜索结果里的时长是「分:秒」，分可能超过 60，例如 1239:55。"""
    parts = [p for p in str(text).split(':') if p.isdigit()]
    if not parts:
        return 0
    seconds = 0
    for p in parts:
        seconds = seconds * 60 + int(p)
    return seconds


_TAG = re.compile(r'<[^>]+>')
_EMOJI = re.compile('[\U0001F000-\U0001FAFF☀-➿⬀-⯿️]')


def clean_text(raw):
    text = _TAG.sub('', raw or '')
    for a, b in (('&amp;', '&'), ('&lt;', '<'), ('&gt;', '>'), ('&quot;', '"'), ('&#39;', "'"), ('&nbsp;', ' ')):
        text = text.replace(a, b)
    text = _EMOJI.sub('', text)
    return re.sub(r'\s+', ' ', text).strip()


def https(url):
    if url.startswith('//'):
        return 'https:' + url
    if url.startswith('http://'):
        return 'https://' + url[len('http://'):]
    return url


def base_item(row):
    """搜索结果里与分类无关的字段。返回 None 表示这条没法在应用里播放。"""
    bvid = (row.get('bvid') or '').strip()
    if not re.match(r'^BV[0-9A-Za-z]{10}$', bvid):
        return None, '无法播放'
    if row.get('is_pay') or row.get('is_charge_video') or row.get('badgepay'):
        return None, '付费或充电专属'
    title = clean_text(row.get('title'))
    tags = clean_text(row.get('tag'))
    duration = parse_duration(row.get('duration'))
    return {
        'bvid': bvid,
        'title': title,
        'author': clean_text(row.get('author')),
        'mid': row.get('mid'),
        'cover': https((row.get('pic') or '').strip()),
        'duration': duration,
        'play': int(row.get('play') or 0) if str(row.get('play') or '0').isdigit() else 0,
        'favorites': int(row.get('favorites') or 0),
        'pubdate': int(row.get('pubdate') or 0),
        'typeid': int(row.get('typeid') or 0) if str(row.get('typeid') or '').isdigit() else 0,
        'typename': row.get('typename') or '',
        'episodes': parse_episodes(title),
        'desc': clean_text(row.get('description'))[:160],
        '_tags': tags,
    }, None


def describe(item, subject=None):
    """给任意一条视频认出年级、学科、版本、册次，用于搜索结果的标签。"""
    title, tags = item['title'], item['_tags']
    strong, weak = parse_grades(title)
    grades = strong or weak or parse_grades(tags)[0]
    subjects = parse_subjects(title) or parse_subjects(tags)
    subj = subject or (next(iter(subjects)) if len(subjects) == 1 else None)
    editions = parse_editions(title, subj) or parse_editions(tags, subj)[:1]
    volumes = parse_volumes(title)
    return grades, subjects, editions, volumes


def classify(item, n, subject, volume):
    """判断一条视频是否属于 (年级 n, 学科, 册次)。返回 (是否收录, 拒绝原因)。

    规则按「宁缺毋滥」：标题里写明的年级、学科、册次和目标不一致就不收；没写的不扣分但排序靠后。
    """
    title = item['title']
    if item['typeid'] != STUDY_TID:
        return False, '不在学习分区'
    if item['author'] in ('账号已注销', ''):
        return False, '账号已注销'
    if item['duration'] and item['duration'] < 180:
        return False, '短于 3 分钟'

    strong, weak = parse_grades(title)
    grades = strong or weak
    if grades and n not in grades:
        return False, '年级不符'

    subjects = {canonical_subject(s) for s in parse_subjects(title)}
    if subjects and canonical_subject(subject) not in subjects:
        return False, '学科不符'

    volumes = parse_volumes(title)
    if volumes and volume not in volumes:
        return False, '册次不符'

    # 标题完全没提年级时，靠视频标签补充判断；标签写了别的年级也不收
    if not grades:
        tag_grades = parse_grades(item['_tags'])[0]
        if tag_grades and n not in tag_grades:
            return False, '年级不符'
    return True, None


def score(item, n, volume):
    s = math.log10(item['play'] + 1) + 1.5 * math.log10(item['favorites'] + 1)
    title = item['title']
    strong, _ = parse_grades(title)
    if n in strong:
        s += 0.6
    if volume in parse_volumes(title):
        s += 1.0
    if item['play'] and item['favorites'] / item['play'] > 0.02:
        s += 0.5  # 收藏率高的通常是真正拿来学的课
    if n <= 9 and item['new_textbook']:
        s += 0.8
    if item['series']:
        s += 0.5
    return round(s, 3)


def finish(item, n, subject, volume=None):
    """补齐前端要用的派生字段，删掉内部字段。"""
    title = item['title']
    grades, subjects, editions, volumes = describe(item, subject)
    item['editions'] = editions
    item['volumes'] = sorted(volumes)
    item['grades'] = sorted(grades)
    item['subjects'] = sorted(subjects)
    item['series'] = item['episodes'] >= 4 or item['duration'] >= 3600
    item['review'] = bool(REVIEW.search(title))
    item['new_textbook'] = bool(n and n <= 9 and (
        item['pubdate'] >= NEW_TEXTBOOK_SINCE or re.search(r'新教材|新版|202[4-9]', title)))
    if volume is not None:
        item['score'] = score(item, n, volume)
    item['tagtext'] = item.pop('_tags', '')[:100]  # 交给大模型判断时用
    return item
