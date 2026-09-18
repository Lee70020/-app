# -*- coding: utf-8 -*-
"""HQU jwapp timetable crawler.

Pipeline: CAS auto login (slider captcha) -> register wdkb app -> semester
list -> cxxszhxqkb.do (full scheduled timetable with weekday/period/room/
week-bitmap fields) -> save raw JSON + app-schema schedule.json.

Usage:
    set HQU_USER / HQU_PASS
    python hqu_crawl.py [--term 2026-2027-1] [--out DIR]
"""
import argparse
import datetime as dt
import hashlib
import json
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import autologin
import requests

BASE = 'https://jwapp.hqu.edu.cn'
APPSHOW = BASE + '/jwapp/sys/emaphome/appShow.do?id=0676d3a0fc704f488ba53d3f45cfd348'
WDKB_PAGE = BASE + '/jwapp/sys/wdkb/*default/index.do?EMAP_LANG=zh'
HDRS = {'Referer': WDKB_PAGE, 'X-Requested-With': 'XMLHttpRequest',
        'Content-Type': 'application/x-www-form-urlencoded; charset=UTF-8',
        'Accept': 'application/json, text/javascript, */*; q=0.01',
        'Origin': BASE}


def open_session():
    user = os.environ.get('HQU_USER', '')
    pwd = os.environ.get('HQU_PASS', '')
    if not user or not pwd:
        raise SystemExit('set HQU_USER / HQU_PASS first')
    s = requests.Session()
    s.headers.update({'User-Agent': autologin.UA})
    last = None
    for i in range(4):
        try:
            autologin.login(s, user, pwd)
            s.get(APPSHOW, timeout=20)
            s.get(WDKB_PAGE, timeout=20)
            return s
        except (requests.exceptions.ConnectionError, requests.exceptions.Timeout) as e:
            last = e
            print('network retry %d: %s' % (i, e))
            time.sleep(2)
    raise SystemExit('cannot reach server: %s' % last)


def post_json(s, ep, data=None):
    r = s.post(BASE + '/jwapp/sys/wdkb/' + ep, headers=HDRS, data=data or {},
               timeout=20)
    r.raise_for_status()
    return r.json()


def current_term(s):
    """Latest semester whose start date <= today."""
    d = post_json(s, 'modules/xskcb/cxxljc.do')
    rows = d['datas']['cxxljc']['rows']
    today = dt.date.today()
    best = None
    for r in rows:
        try:
            start = dt.datetime.strptime(r['XQKSRQ'][:10], '%Y-%m-%d').date()
        except (TypeError, ValueError):
            continue
        if start <= today and (best is None or start > best[0]):
            best = (start, r)
    if not best:
        raise SystemExit('no active semester found')
    r = best[1]
    return '%s-%s' % (r['XN'], r['XQ']), r


def fetch_timetable(s, term):
    """Full scheduled rows for one term (server requires XNXQDM x3)."""
    d = post_json(s, 'modules/xskcb/cxxszhxqkb.do',
                  {'XNXQDM': term, 'XNXQDM2': term, 'XNXQDM3': term})
    return d['datas']['cxxszhxqkb']['rows']


def weeks_from_bitmap(bitmap):
    """SKZC like '111111111111111100' -> [1..16] (1-based positions of '1')."""
    return [i + 1 for i, ch in enumerate(bitmap or '') if ch == '1']


def to_schema(rows, term, term_display):
    """Convert raw rows to the app schedule.json course schema."""
    seen, courses, skipped = set(), [], 0
    for r in rows:
        if r.get('KBLB') not in (None, '', '1'):  # 1 = normal class rows
            skipped += 1
            continue
        try:
            ks, js = int(r['KSJC']), int(r['JSJC'])
            day = int(r['SKXQ'])
        except (TypeError, ValueError):
            skipped += 1
            continue
        if not (1 <= day <= 7 and 1 <= ks <= js <= 30):
            skipped += 1
            continue
        weeks = weeks_from_bitmap(r.get('SKZC'))
        if not weeks:
            skipped += 1
            continue
        name = (r.get('KCM') or '').strip()
        if not name:
            skipped += 1
            continue
        periods = list(range(ks, js + 1))
        key = (name, day, ks, js, r.get('JASMC'), tuple(weeks))
        if key in seen:
            continue
        seen.add(key)
        raw = (r.get('YPSJDD') or '%s %s 第%s-%s节 %s' %
               (r.get('ZCMC') or '', r.get('SKXQ_DISPLAY') or '', ks, js,
                r.get('JASMC') or ''))
        item = {
            'name': name,
            'teacher': (r.get('SKJS') or '').strip(),
            'location': (r.get('JASMC') or '').strip(),
            'weekday': day,
            'weeks': weeks,
            'periods': periods,
            'raw_text': raw.strip(),
            'needs_review': False,
        }
        item['id'] = hashlib.sha256(
            json.dumps(item, ensure_ascii=False, sort_keys=True)
            .encode()).hexdigest()[:16]
        courses.append(item)
    courses.sort(key=lambda c: (c['weekday'], c['periods'][0], c['name']))
    document = {
        'schema_version': 1,
        'school': '华侨大学',
        'semester': term_display or term,
        'term_code': term,
        'source_url': BASE,
        'scraped_at': dt.datetime.now(dt.timezone.utc).isoformat(),
        'capture_method': 'jwapp_wdkb_cxxszhxqkb',
        'adapter_verified': True,
        'coverage': 'full_term',
        'warnings': [],
        'courses': courses,
    }
    if skipped:
        document['warnings'].append('已跳过 %d 条无法解析或非上课的记录。' % skipped)
    return document


def save(path, obj):
    with open(path, 'w', encoding='utf-8') as f:
        json.dump(obj, f, ensure_ascii=False, indent=2)
    print('saved %s (%d courses)' % (path, len(obj.get('courses', obj.get('rows', [])))))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--term', help='e.g. 2026-2027-1 (default: current)')
    ap.add_argument('--out', default='.', help='output directory')
    args = ap.parse_args()

    s = open_session()
    print('login ok')
    term, term_row = current_term(s)
    if args.term:
        term = args.term
    display = '%s学年第%s学期' % (term_row['XN'], term_row['XQ']) if not args.term else term
    print('term:', term)
    rows = fetch_timetable(s, term)
    print('rows:', len(rows))
    os.makedirs(args.out, exist_ok=True)
    save(os.path.join(args.out, 'courses_raw.json'),
         {'term': term, 'fetched_at': dt.datetime.now().isoformat(), 'rows': rows})
    doc = to_schema(rows, term, display)
    save(os.path.join(args.out, 'schedule.json'), doc)
    print('courses:', len(doc['courses']), 'warnings:', doc['warnings'])


if __name__ == '__main__':
    main()
