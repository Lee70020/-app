import json
import os
import sys
import tempfile
import unittest
from unittest.mock import patch
sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..', 'app', 'src', 'main', 'python'))
import schedule as s


def table(rows):
    return {'rows': [[{'text': t} for t in row] for row in rows]}


class ParsingTests(unittest.TestCase):
    def test_list_format_unicode_and_timing(self):
        rows=table([['课程名称','任课教师','上课时间','上课地点'],
                    ['示例课程甲','示例老师','1-16周 星期四 第6节-第7节','D2-302']])
        result,w=s.parse_tables([rows])
        self.assertEqual(len(result),1)
        self.assertEqual(result[0]['weekday'],4)
        self.assertEqual(result[0]['periods'],[6,7])
        self.assertEqual(result[0]['weeks'],list(range(1,17)))
        self.assertEqual(result[0]['location'],'D2-302')

    def test_discrete_and_odd_even_weeks(self):
        self.assertEqual(s.weeks('1-8周(单)'),[1,3,5,7])
        self.assertEqual(s.weeks('2-10周（双）'),[2,4,6,8,10])
        self.assertEqual(s.weeks('1-3,5,7周'),[1,2,3,5,7])
        self.assertEqual(s.weekday('星期日'),7)

    def test_separate_columns(self):
        rows=table([['课程名称','周次','星期','节次'],['示例课','1-3,5','3','9-10']])
        data,_=s.parse_tables([rows])
        self.assertEqual(data[0]['weeks'],[1,2,3,5])
        self.assertEqual(data[0]['periods'],[9,10])
        self.assertEqual(data[0]['weekday'],3)

    def test_weekly_grid(self):
        rows=table([['节次','星期一','星期二','星期三','星期四','星期五'],
                    ['第1-2节','示例课程\n教师：示例老师\n地点：A101\n1-16周','','','','']])
        data,_=s.parse_tables([rows])
        self.assertEqual(data[0]['periods'],[1,2])
        self.assertEqual(data[0]['teacher'],'示例老师')
        self.assertEqual(data[0]['location'],'A101')

    def test_html_tables_exclude_script(self):
        p=s.TableParser();p.feed('<table><tr><th>课程名称</th><th>上课时间</th></tr><tr><td>示例课</td><td>星期一 第1-2节 1-16周<script>secret()</script></td></tr></table>')
        data,_=s.parse_tables(p.tables)
        self.assertEqual(len(data),1)
        self.assertNotIn('secret',data[0]['raw_text'])

    def test_reject_untrusted_urls(self):
        for url in ['http://jwapp.hqu.edu.cn/','https://jwapp.hqu.edu.cn.evil.test/',
                    'https://user:password@jwapp.hqu.edu.cn/', 'https://jwapp.hqu.edu.cn:444/',
                    'file:///etc/passwd']:
            self.assertFalse(s.trusted_url(url))
        self.assertTrue(s.trusted_url('https://jwapp.hqu.edu.cn/timetable'))

    def test_redirect_does_not_forward_cookie(self):
        import urllib.request
        request=urllib.request.Request('https://jwapp.hqu.edu.cn/',headers={'Cookie':'private=value'})
        with self.assertRaises(ValueError):
            s.SameOriginRedirect().redirect_request(request,None,302,'redirect',{},'https://example.com/')

    def test_sync_atomic_unicode_and_no_secret(self):
        snapshot=json.dumps({'tables':[table([['课程名称','上课时间'],['示例课','1-16周 星期一 第1-2节']])]})
        with tempfile.TemporaryDirectory() as folder:
            result=json.loads(s.sync(snapshot,'https://jwapp.hqu.edu.cn/timetable?token=secret','auth=secret','Test','2026-2027-1',folder))
            self.assertTrue(result['ok'])
            with open(os.path.join(folder,'schedule.json'),encoding='utf8') as f: content=f.read()
            self.assertIn('示例课',content)
            self.assertNotIn('secret',content)
            self.assertFalse(json.loads(content)['adapter_verified'])
            self.assertEqual(os.listdir(folder),['schedule.json'])

    def test_empty_or_network_failure_keeps_previous(self):
        with tempfile.TemporaryDirectory() as folder:
            s.atomic_save({'old':True},folder)
            with patch.object(s,'fetch_tables',return_value=[]):
                r=json.loads(s.sync('{"tables":[]}','https://jwapp.hqu.edu.cn/','','Test','T',folder))
                self.assertFalse(r['ok'])
            with patch.object(s,'fetch_tables',side_effect=OSError('credential=secret')):
                r=json.loads(s.sync('{"tables":[]}','https://jwapp.hqu.edu.cn/','','Test','T',folder))
                self.assertFalse(r['ok']);self.assertNotIn('secret',r['error'])
            with open(os.path.join(folder,'schedule.json')) as f:self.assertEqual(json.load(f),{'old':True})

    def test_unrelated_tables_not_courses(self):
        data,_=s.parse_tables([table([['姓名','学号'],['示例','000000']])])
        self.assertEqual(data,[])

    def test_weeks_from_bitmap(self):
        self.assertEqual(s.weeks_from_bitmap('111111111111111100'), list(range(1, 17)))
        self.assertEqual(s.weeks_from_bitmap('10101'), [1, 3, 5])
        self.assertEqual(s.weeks_from_bitmap(None), [])
        self.assertEqual(s.weeks_from_bitmap(7), [])

    def _wdkb_row(self, **over):
        row = {'KCM': '世界经济概论', 'SKJS': '郑燕霞', 'JASMC': 'D2-302',
               'SKXQ': 4, 'KSJC': 6, 'JSJC': 7, 'SKZC': '111111111111111100',
               'KBLB': '1', 'YPSJDD': '1-16周 星期四 第6节-第7节 D2-302'}
        row.update(over)
        return row

    def test_sync_wdkb_real_shape(self):
        payload = json.dumps({'ok': True, 'term': '2026-2027-1',
                             'termDisplay': '2026-2027学年第1学期',
                             'rows': [self._wdkb_row(),
                                      self._wdkb_row(),  # duplicate -> merged
                                      self._wdkb_row(KBLB='2'),  # non-class row
                                      self._wdkb_row(KSJC=None, JSJC=None),
                                      self._wdkb_row(SKZC='000000000000000000')]})
        with tempfile.TemporaryDirectory() as folder:
            result = json.loads(s.sync_wdkb(payload, folder))
            self.assertTrue(result['ok'])
            doc = result['document']
            self.assertTrue(doc['adapter_verified'])
            self.assertEqual(doc['semester'], '2026-2027学年第1学期')
            self.assertEqual(len(doc['courses']), 1)
            c = doc['courses'][0]
            self.assertEqual(c['name'], '世界经济概论')
            self.assertEqual(c['weekday'], 4)
            self.assertEqual(c['periods'], [6, 7])
            self.assertEqual(c['weeks'], list(range(1, 17)))
            self.assertEqual(c['location'], 'D2-302')
            self.assertFalse(c['needs_review'])
            with open(os.path.join(folder, 'schedule.json'), encoding='utf8') as f:
                saved = json.load(f)
            self.assertEqual(len(saved['courses']), 1)
            self.assertIn('已跳过', saved['warnings'][0])

    def test_sync_wdkb_bad_payload_preserves_data(self):
        with tempfile.TemporaryDirectory() as folder:
            s.atomic_save({'old': True}, folder)
            for bad in ['not json', '{"ok":false}', '{"ok":true,"rows":[]}',
                        '{"ok":true,"rows":"x"}',
                        '{"ok":true,"rows":[{"KCM":"x"}]}']:
                r = json.loads(s.sync_wdkb(bad, folder))
                self.assertFalse(r['ok'])
            with open(os.path.join(folder, 'schedule.json')) as f:
                self.assertEqual(json.load(f), {'old': True})

    def test_json_import_and_invalid_preserves_data(self):
        with tempfile.TemporaryDirectory() as folder:
            valid={'schema_version':1,'semester':'示例','courses':[{'name':'示例课','weeks':[1,2],'periods':[3,4],'weekday':2}]}
            self.assertTrue(json.loads(s.import_json(json.dumps(valid),folder))['ok'])
            with open(os.path.join(folder,'schedule.json')) as f: previous=f.read()
            valid['courses'][0]['weekday']=9
            self.assertFalse(json.loads(s.import_json(json.dumps(valid),folder))['ok'])
            with open(os.path.join(folder,'schedule.json')) as f:self.assertEqual(f.read(),previous)

if __name__=='__main__': unittest.main()
