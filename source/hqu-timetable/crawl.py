#!/usr/bin/env python3
"""Desktop entry point for the same Python engine used inside the APK."""
import argparse
import json
import os
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).parent / 'app/src/main/python'))
import schedule


def main():
    parser = argparse.ArgumentParser(description='读取已登录的课表页，保存 schedule.json；不自动处理统一认证。')
    parser.add_argument('--url', default='https://jwapp.hqu.edu.cn/', help='已知的完整课表 URL，不能使用猜测的接口')
    parser.add_argument('--html', type=Path, help='可选：从浏览器另存的课表 HTML 文件')
    parser.add_argument('--semester', required=True)
    parser.add_argument('--output-dir', default='output')
    args = parser.parse_args()
    tables = []
    if args.html:
        if args.html.stat().st_size > schedule.MAX_BYTES:
            parser.error('HTML 大于 3 MiB')
        p = schedule.TableParser()
        p.feed(args.html.read_text(encoding='utf-8'))
        tables = p.tables
    result = json.loads(schedule.sync(json.dumps({'tables': tables}), args.url,
                                      os.environ.get('HQU_SESSION_COOKIE', ''),
                                      'HquTimetable/0.1 Python', args.semester, args.output_dir))
    if result['ok']:
        print(f"已保存 {len(result['document']['courses'])} 条记录至 {args.output_dir}/schedule.json；需核对识别结果。")
        return 0
    print(result['error'], file=sys.stderr)
    return 1


if __name__ == '__main__':
    raise SystemExit(main())
