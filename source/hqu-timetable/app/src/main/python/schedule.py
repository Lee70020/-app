"""Local timetable scraper. No credentials or unverified endpoint assumptions.

The user opens the timetable in the official WebView. Python can fetch that
exact authenticated URL, then parses its tables. Rendered tables are preferred
for JavaScript applications. This generic adapter requires HQU live validation.
"""
import datetime as dt
import hashlib
import json
import os
import re
import tempfile
import urllib.request
import urllib.parse
from html.parser import HTMLParser

MAX_BYTES = 3 * 1024 * 1024
DAY = {c: n + 1 for n, c in enumerate('一二三四五六日')}
DAY['天'] = 7


def trusted_url(url):
    try:
        p = urllib.parse.urlsplit(url)
        return (p.scheme == 'https' and p.hostname == 'jwapp.hqu.edu.cn'
                and p.port in (None, 443) and not p.username and not p.password)
    except ValueError:
        return False


def safe_source(url):
    p = urllib.parse.urlsplit(url)
    return urllib.parse.urlunsplit((p.scheme, p.netloc, p.path, '', ''))


class SameOriginRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        if not trusted_url(newurl):
            raise ValueError('登录已失效或页面跳转到其他域名，请回到教务页面重新登录。')
        return super().redirect_request(req, fp, code, msg, headers, newurl)


class TableParser(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.tables, self.stack = [], []
        self.skip = 0

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        if tag in ('script', 'style'):
            self.skip += 1
        if tag == 'table':
            self.stack.append({'rows': [], 'row': None, 'cell': None})
        elif self.stack:
            t = self.stack[-1]
            if tag == 'tr':
                t['row'] = []
                t['rows'].append(t['row'])
            elif tag in ('td', 'th') and t['row'] is not None:
                def span(key):
                    try: return max(1, min(100, int(attrs.get(key, 1))))
                    except ValueError: return 1
                t['cell'] = {'text': '', 'rowspan': span('rowspan'), 'colspan': span('colspan')}
                t['row'].append(t['cell'])
            elif tag in ('br', 'div', 'p') and t['cell'] is not None:
                t['cell']['text'] += '\n'

    def handle_endtag(self, tag):
        if tag in ('script', 'style'):
            self.skip = max(0, self.skip - 1)
        if not self.stack: return
        if tag == 'table':
            self.tables.append({'rows': self.stack.pop()['rows']})
        elif tag in ('td', 'th'):
            self.stack[-1]['cell'] = None

    def handle_data(self, data):
        if self.stack and not self.skip and self.stack[-1]['cell'] is not None:
            self.stack[-1]['cell']['text'] += data


def clean(text):
    return re.sub(r'[ \t\u3000]+', ' ', str(text)).strip()


def expand(rows):
    grid, spans = [], {}
    for r, cells in enumerate(rows[:300]):
        row, c = {}, 0
        for (rr, cc), val in list(spans.items()):
            if rr == r: row[cc] = val
        for cell in cells[:100]:
            while c in row: c += 1
            if c > 100: break
            value = clean(cell.get('text', ''))[:12000]
            try:
                rs = max(1, min(100, int(cell.get('rowspan', 1))))
                cs = max(1, min(100, int(cell.get('colspan', 1))))
            except (ValueError, TypeError):
                rs = cs = 1
            for x in range(cs):
                row[c+x] = value
                for y in range(1, rs): spans[(r+y, c+x)] = value
            c += cs
        grid.append([row.get(i, '') for i in range(max(row, default=-1)+1)])
    return grid


def numbers(expr, maximum):
    result = set()
    for a, b in re.findall(r'(\d+)(?:\s*[-~～—至]\s*(\d+))?', expr):
        start, end = int(a), int(b or a)
        if not 1 <= start <= end <= maximum:
            raise ValueError('课表周次或节次超出可识别范围，请检查原页面。')
        result.update(range(start, end+1))
    if '单' in expr: result = {n for n in result if n % 2}
    if '双' in expr: result = {n for n in result if not n % 2}
    return sorted(result)


def weekday(s):
    m = re.search(r'(?:星期|周)([一二三四五六日天])', s)
    if m: return DAY[m[1]]
    return int(s) if re.fullmatch('[1-7]', s.strip()) else None


def weeks(s):
    matches = re.findall(r'(?:第)?([\d,，、 \t~～—至\-]+)周\s*(?:[（(]([单双])(?:周)?[）)])?', s)
    result = set()
    for expr, parity in matches:
        result.update(numbers(expr+parity, 60))
    return sorted(result)


def periods(s):
    s = re.sub(r'第(\d+)节\s*[-~～—至]\s*第(\d+)节', r'\1-\2节', s)
    matches = re.findall(r'(?:第)?([\d,，、 \t~～—至\-]+)节', s)
    return sorted({n for expr in matches for n in numbers(expr, 30)})


def course(name, raw, teacher='', location='', day=None, week_list=None, period_list=None):
    name = clean(name)
    if not name or len(name) > 180: return None
    item = {'name': name, 'teacher': clean(teacher), 'location': clean(location),
            'weekday': day or weekday(raw),
            'weeks': weeks(raw) if week_list is None else week_list,
            'periods': periods(raw) if period_list is None else period_list,
            'raw_text': clean(raw), 'needs_review': True}
    item['id'] = hashlib.sha256(json.dumps(item, ensure_ascii=False, sort_keys=True).encode()).hexdigest()[:16]
    return item


ALIASES = {'name': ('课程名称', '课程名', '科目'), 'teacher': ('任课教师', '授课教师', '教师', '老师'),
           'location': ('上课地点', '教室', '地点'), 'weekday': ('星期', '上课日'),
           'weeks': ('周次', '上课周'), 'periods': ('节次', '上课节'),
           'time': ('上课时间', '时间安排', '时间地点')}


def parse_tables(tables):
    items, warnings = [], []
    for table in tables[:50]:
        grid = expand(table.get('rows', []))
        used = False
        for h, row in enumerate(grid[:12]):
            columns = {}
            for i, cell in enumerate(row):
                for key, names in ALIASES.items():
                    if any(n in cell for n in names) and len(cell) < 30:
                        columns.setdefault(key, i)
            if 'name' not in columns or not any(k in columns for k in ('weekday', 'weeks', 'periods', 'time')):
                continue
            for data in grid[h+1:]:
                def get(key):
                    idx = columns.get(key, 999)
                    return data[idx] if idx < len(data) else ''
                name = get('name')
                if not name or name == row[columns['name']]: continue
                raw = '\n'.join(data)
                w, p = None, None
                if get('weeks'):
                    w = weeks(get('weeks')) or numbers(get('weeks'), 60)
                if get('periods'):
                    p = periods(get('periods')) or numbers(get('periods'), 30)
                item = course(name, raw, get('teacher'), get('location'), weekday(get('weekday')), w, p)
                if item: items.append(item)
            used = True
            break
        if used: continue
        # Generic weekly matrix: explicit weekday headers, original cell retained.
        for h, row in enumerate(grid[:12]):
            days = {i: weekday(cell) for i, cell in enumerate(row) if len(cell) < 12 and weekday(cell)}
            if len(set(days.values())) < 5: continue
            for data in grid[h+1:]:
                label = ' '.join(v for i, v in enumerate(data) if i not in days)
                default_periods = periods(label)
                for i, day in days.items():
                    if i >= len(data): continue
                    cell = data[i]
                    if not cell or cell in ('-', '—', '无', '休息'): continue
                    # Split only at explicit blank lines; never fabricate course names.
                    for block in re.split(r'\n\s*\n', cell):
                        lines = [clean(s) for s in block.splitlines() if clean(s)]
                        if not lines or len(lines[0]) > 180: continue
                        if not weeks(block):
                            warnings.append('部分单元格没有可识别周次，保留原文，需人工核对。')
                        teacher_match = re.search(r'(?:教师|老师)[:：]\s*([^\n]+)', block)
                        location_match = re.search(r'(?:教室|地点)[:：]\s*([^\n]+)', block)
                        item = course(lines[0], block, teacher_match[1] if teacher_match else '',
                                      location_match[1] if location_match else '', day,
                                      period_list=periods(block) or default_periods)
                        if item: items.append(item)
            warnings.append('周视图采用通用解析；同一格内多门课、合并节次需核对原文。')
            break
    unique = {item['id']: item for item in items}
    return list(unique.values()), sorted(set(warnings))


def fetch_tables(url, cookie, user_agent):
    if not trusted_url(url): raise ValueError('只允许读取指定教务系统的 HTTPS 页面。')
    req = urllib.request.Request(url, headers={'Cookie': cookie, 'User-Agent': user_agent,
                                               'Accept': 'text/html,application/xhtml+xml'})
    opener = urllib.request.build_opener(SameOriginRedirect())
    with opener.open(req, timeout=15) as response:
        if not trusted_url(response.url): raise ValueError('请重新登录教务系统。')
        data = response.read(MAX_BYTES+1)
        if len(data) > MAX_BYTES: raise ValueError('页面过大，停止读取。')
        content = data.decode(response.headers.get_content_charset() or 'utf-8', errors='replace')
    parser = TableParser()
    parser.feed(content)
    return parser.tables


def atomic_save(document, directory):
    os.makedirs(directory, exist_ok=True)
    target = os.path.join(directory, 'schedule.json')
    fd, temporary = tempfile.mkstemp(prefix='.schedule-', dir=directory)
    try:
        with os.fdopen(fd, 'w', encoding='utf-8') as f:
            json.dump(document, f, ensure_ascii=False, indent=2)
            f.flush()
            os.fsync(f.fileno())
        os.replace(temporary, target)
    finally:
        if os.path.exists(temporary): os.unlink(temporary)
    return target


def sync(snapshot_json, url, cookie, user_agent, term, directory):
    """Called off the Android UI thread. Cookie exists only for this request."""
    try:
        if not trusted_url(url): raise ValueError('请先在指定教务系统打开课表页面。')
        if not term.strip(): raise ValueError('请填写学期名称，再同步课表。')
        if len(snapshot_json.encode()) > MAX_BYTES: raise ValueError('页面数据过大。')
        snap = json.loads(snapshot_json)
        items, warnings = parse_tables(snap.get('tables', []))
        method = 'rendered_tables_python'
        if not items:
            tables = fetch_tables(url, cookie, user_agent)
            items, warnings = parse_tables(tables)
            method = 'python_https_tables'
        if not items:
            raise ValueError('没有识别到课表。请确认已登录、打开完整学期课表。若仍失败，说明此页面需要专用适配；原有 JSON 未改变。')
        warnings.insert(0, '华侨大学真实页面尚未完成适配验证；本次仅保存当前页面识别结果，不保证覆盖整学期。')
        document = {'schema_version': 1, 'school': '华侨大学', 'semester': term.strip()[:100],
                    'source_url': safe_source(url), 'scraped_at': dt.datetime.now(dt.timezone.utc).isoformat(),
                    'capture_method': method, 'adapter_verified': False, 'coverage': 'current_page_only',
                    'warnings': warnings, 'courses': items}
        atomic_save(document, directory)
        return json.dumps({'ok': True, 'document': document}, ensure_ascii=False)
    except Exception as exc:
        # Do not return request URLs, headers, cookies or full tracebacks to UI/logs.
        message = str(exc) if isinstance(exc, ValueError) else '读取失败：网络不可达、登录失效或页面格式不受支持。原有课表保持不变。'
        return json.dumps({'ok': False, 'error': message}, ensure_ascii=False)


def weeks_from_bitmap(bitmap, maximum=60):
    """jwapp SKZC week bitmap, e.g. '111111111111111100' -> [1..16]."""
    if not isinstance(bitmap, str):
        return []
    return [i + 1 for i, ch in enumerate(bitmap[:maximum]) if ch == '1']


def arrangement_key(item):
    """Only user-visible schedule fields; ignore volatile IDs and formatting."""
    return (clean(item.get('name', '')), clean(item.get('teacher', '')),
            clean(item.get('location', '')), item.get('weekday') or 0,
            tuple(sorted(set(item.get('periods', [])))),
            tuple(sorted(set(item.get('weeks', [])))))


def arrangement_text(key):
    name, teacher, room, day, periods, week_list = key
    def compact(values):
        groups = []
        for value in values:
            if groups and value == groups[-1][-1] + 1: groups[-1].append(value)
            else: groups.append([value])
        return '、'.join(str(g[0]) if len(g) == 1 else '%s-%s' % (g[0], g[-1]) for g in groups)
    day_text = '周' + '一二三四五六日'[day-1] if 1 <= day <= 7 else '星期待核对'
    return '%s · %s 第%s节 · 第%s周 · %s%s' % (
        name, day_text, compact(periods), compact(week_list), room or '教室待核对',
        ' · ' + teacher if teacher else '')


def changes_between(previous, document):
    if not previous or previous.get('capture_method') != 'jwapp_wdkb_cxxszhxqkb': return []
    if previous.get('term_code') != document['term_code']:
        return ['已切换学期：%s → %s' % (previous.get('semester', ''), document['semester'])]
    old = set(arrangement_key(c) for c in previous.get('courses', []))
    new = set(arrangement_key(c) for c in document['courses'])
    changes = ['原安排移除：' + arrangement_text(k) for k in sorted(old-new)]
    changes += ['现安排新增：' + arrangement_text(k) for k in sorted(new-old)]
    def specials(doc):
        return {json.dumps(v, ensure_ascii=False, sort_keys=True) for v in doc.get('special_arrangements', [])}
    for verb, values in [('移除', specials(previous)-specials(document)), ('新增', specials(document)-specials(previous))]:
        for value in sorted(values):
            item = json.loads(value)
            changes.append('特殊安排%s（待核对）：%s' % (verb, ' · '.join(v for v in item.values() if v)))
    return changes


def sync_wdkb(payload_json, directory):
    """Convert the jwapp wdkb timetable payload captured by the WebView into
    the app schedule.json. Pure stdlib: no network, no third-party imports."""
    try:
        if len(payload_json.encode()) > MAX_BYTES: raise ValueError('课表数据过大。')
        obj = json.loads(payload_json)
        if not isinstance(obj, dict) or not obj.get('ok'):
            raise ValueError('课表接口返回失败。')
        rows = obj.get('rows')
        if not isinstance(rows, list) or not 1 <= len(rows) <= 2000:
            raise ValueError('课表数据为空或数量无效。')
        term = str(obj.get('term', ''))[:20]
        display = str(obj.get('termDisplay') or term or '当前学期')[:100]
        items, skipped, special = [], 0, []
        seen = set()
        for r in rows:
            if isinstance(r, dict) and str(r.get('KBLB', '1')) not in ('None', '', '1'):
                # The enum meaning is not verified. Retain for review, never invent a normal class.
                entry = {key: clean(str(r.get(field) or ''))[:500] for key, field in (
                    ('name', 'KCM'), ('type', 'KBLB'), ('teacher', 'SKJS'), ('location', 'JASMC'),
                    ('weekday', 'SKXQ'), ('start_period', 'KSJC'), ('end_period', 'JSJC'),
                    ('weeks_bitmap', 'SKZC'), ('description', 'YPSJDD'))}
                if entry not in special: special.append(entry)
                continue
            if not isinstance(r, dict):
                skipped += 1
                continue
            try:
                ks, js, day = int(r['KSJC']), int(r['JSJC']), int(r['SKXQ'])
            except (KeyError, TypeError, ValueError):
                skipped += 1
                continue
            if not (1 <= day <= 7 and 1 <= ks <= js <= 30):
                skipped += 1
                continue
            weeks = weeks_from_bitmap(r.get('SKZC'))
            name = str(r.get('KCM') or '').strip()
            if not weeks or not name or len(name) > 180:
                skipped += 1
                continue
            periods = list(range(ks, js + 1))
            key = (name, day, ks, js, str(r.get('JASMC')), str(r.get('SKJS')), tuple(weeks))
            if key in seen:
                continue
            seen.add(key)
            raw = str(r.get('YPSJDD') or '%s %s 第%s-%s节 %s' %
                      (r.get('ZCMC') or '', r.get('SKXQ_DISPLAY') or '',
                       ks, js, r.get('JASMC') or '')).strip()
            item = course(name, raw, str(r.get('SKJS') or ''), str(r.get('JASMC') or ''),
                          day, weeks, periods)
            if item:
                item['needs_review'] = False
                item['id'] = hashlib.sha256(json.dumps(
                    item, ensure_ascii=False, sort_keys=True).encode()).hexdigest()[:16]
                items.append(item)
        if not items:
            raise ValueError('课表数据无法识别；原有课表保持不变。')
        items.sort(key=lambda c: (c['weekday'], c['periods'][0], c['name']))
        warnings = []
        if skipped:
            warnings.append('已跳过 %d 条非上课或无法解析的记录。' % skipped)
        if special: warnings.append('另有 %d 条校方特殊安排，请在“调整”页核对。' % len(special))
        start = str(obj.get('termStart', ''))[:10]
        try: start = dt.date.fromisoformat(start).isoformat()
        except ValueError: start = ''
        document = {'schema_version': 1, 'school': '华侨大学', 'semester': display, 'term_start': start,
                    'term_code': term, 'source_url': safe_source('https://jwapp.hqu.edu.cn/'),
                    'scraped_at': dt.datetime.now(dt.timezone.utc).isoformat(),
                    'capture_method': 'jwapp_wdkb_cxxszhxqkb', 'adapter_verified': True,
                    'coverage': 'full_term', 'warnings': warnings, 'courses': items,
                    'special_arrangements': special}
        previous = None
        try:
            with open(os.path.join(directory, 'schedule.json'), encoding='utf-8') as f: previous = json.load(f)
        except (OSError, ValueError): pass
        if previous and skipped:
            raise ValueError('部分课程解析失败，保留旧课表以免误报课程取消。')
        changes = changes_between(previous, document)
        history = (previous or {}).get('change_history', [])
        if not isinstance(history, list): history = []
        if changes:
            visible = changes[:100]
            if len(changes) > 100: visible.append('其余 %d 条安排变化请与教务课表核对。' % (len(changes)-100))
            batch = {'at': document['scraped_at'], 'lines': visible, 'count': len(changes)}
            batch['id'] = hashlib.sha256(json.dumps(batch, ensure_ascii=False, sort_keys=True).encode()).hexdigest()[:20]
            history = [batch] + history
        document['change_history'] = history[:30]
        atomic_save(document, directory)
        return json.dumps({'ok': True, 'document': document, 'changes': changes}, ensure_ascii=False)
    except Exception:
        return json.dumps({'ok': False, 'error': '同步失败：课表数据无效或无法识别；原有课表保持不变。'}, ensure_ascii=False)


def import_json(text, directory):
    try:
        if len(text.encode()) > MAX_BYTES: raise ValueError('JSON 文件过大。')
        obj = json.loads(text)
        if not isinstance(obj, dict) or obj.get('schema_version') != 1: raise ValueError('不支持的课表 JSON 格式。')
        rows = obj.get('courses')
        if not isinstance(rows, list) or not 1 <= len(rows) <= 2000: raise ValueError('课表课程数量无效。')
        clean_rows = []
        for r in rows:
            if not isinstance(r, dict) or not isinstance(r.get('name'), str) or not 1 <= len(r['name']) <= 180:
                raise ValueError('课程名称无效。')
            for key, limit in [('weeks', 60), ('periods', 30)]:
                if not isinstance(r.get(key), list) or any(type(n) is not int or not 1 <= n <= limit for n in r[key]):
                    raise ValueError('课程周次或节次无效。')
            if r.get('weekday') is not None and (type(r['weekday']) is not int or not 1 <= r['weekday'] <= 7):
                raise ValueError('课程星期无效。')
            for field in ('teacher', 'location', 'raw_text'):
                if not isinstance(r.get(field, ''), str) or len(r.get(field, '')) > 12000:
                    raise ValueError('课程字段无效。')
            clean_rows.append(course(r['name'], r.get('raw_text', ''), r.get('teacher', ''),
                                     r.get('location', ''), r.get('weekday'), r['weeks'], r['periods']))
        result = {'schema_version': 1, 'school': '华侨大学', 'semester': str(obj.get('semester', '导入课表'))[:100],
                  'scraped_at': dt.datetime.now(dt.timezone.utc).isoformat(), 'source_url': '',
                  'capture_method': 'local_json_import', 'adapter_verified': False, 'coverage': 'imported',
                  'warnings': ['从本地文件导入，请核对课程。'], 'courses': clean_rows}
        try: result['term_start'] = dt.date.fromisoformat(str(obj.get('term_start', ''))).isoformat()
        except ValueError: result['term_start'] = ''
        result['term_code'] = str(obj.get('term_code', ''))[:20]
        atomic_save(result, directory)
        return json.dumps({'ok': True, 'document': result}, ensure_ascii=False)
    except Exception:
        return json.dumps({'ok': False, 'error': '导入失败，JSON 格式不符合要求；原有课表保持不变。'}, ensure_ascii=False)
