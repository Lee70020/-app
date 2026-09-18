import json
import os
import sys
import tempfile
import unittest
from pathlib import Path
sys.path.insert(0, str(Path(__file__).parents[1] / 'app/src/main/python'))
import schedule as s


def row(**kw):
    value = dict(KCM='示例课程', SKJS='示例教师', JASMC='A101', SKXQ=1,
                 KSJC=1, JSJC=2, SKZC='1111', KBLB='1', YPSJDD='安排原文')
    value.update(kw)
    return value


class UpdatesTests(unittest.TestCase):
    def sync(self, folder, rows=None, **kw):
        payload = dict(ok=True, term='2026-2027-1', termStart='2026-09-14',
                       rows=rows if rows is not None else [row()])
        payload.update(kw)
        return json.loads(s.sync_wdkb(json.dumps(payload), folder))

    def test_initial_sync_is_baseline_with_term_start(self):
        with tempfile.TemporaryDirectory() as d:
            r = self.sync(d)
            self.assertEqual(r.get('changes'), [])
            self.assertEqual(r['document'].get('term_start'), '2026-09-14')

    def test_room_change_is_described_once_and_history_survives_no_change(self):
        with tempfile.TemporaryDirectory() as d:
            self.sync(d)
            r = self.sync(d, [row(JASMC='B202')])
            text = json.dumps(r.get('changes', []), ensure_ascii=False)
            self.assertIn('A101', text)
            self.assertIn('B202', text)
            again = self.sync(d, [row(JASMC='B202')])
            self.assertEqual(again['changes'], [])
            self.assertEqual(len(again['document']['change_history']), 1)

    def test_ignore_order_duplicates_raw_text_and_server_ids(self):
        with tempfile.TemporaryDirectory() as d:
            a, b = row(), row(KCM='另一门课', SKXQ=2)
            self.sync(d, [a, b])
            a.update(YPSJDD='只改排版', WID='volatile-id')
            self.assertEqual(self.sync(d, [b, a, a]).get('changes'), [])

    def test_teacher_and_week_and_period_changes_detected(self):
        for values in [dict(SKJS='另一位教师'), dict(SKZC='1011'), dict(KSJC=3, JSJC=4), dict(SKXQ=3)]:
            with self.subTest(values=values), tempfile.TemporaryDirectory() as d:
                self.sync(d)
                self.assertTrue(self.sync(d, [row(**values)]).get('changes'))

    def test_special_records_are_kept_and_changes_alert_without_becoming_classes(self):
        with tempfile.TemporaryDirectory() as d:
            self.sync(d, [row(), row(KBLB='2', JASMC='B202')])
            r = self.sync(d, [row(), row(KBLB='2', JASMC='C303')])
            self.assertEqual(len(r['document']['courses']), 1)
            self.assertEqual(len(r['document'].get('special_arrangements', [])), 1)
            self.assertIn('特殊', json.dumps(r.get('changes'), ensure_ascii=False))
            self.assertIn('C303', json.dumps(r.get('changes'), ensure_ascii=False))

    def test_bad_response_keeps_history_and_schedule(self):
        with tempfile.TemporaryDirectory() as d:
            self.sync(d)
            self.sync(d, [row(JASMC='B202')])
            before = Path(d, 'schedule.json').read_bytes()
            self.assertFalse(self.sync(d, [])['ok'])
            self.assertEqual(before, Path(d, 'schedule.json').read_bytes())

    def test_history_bounded_and_term_switch_is_one_notice(self):
        with tempfile.TemporaryDirectory() as d:
            self.sync(d)
            for i in range(35): self.sync(d, [row(JASMC=str(i))])
            r = self.sync(d, term='2026-2027-2')
            self.assertEqual(len(r['changes']), 1)
            self.assertIn('学期', r['changes'][0])
            self.assertLessEqual(len(r['document']['change_history']), 30)

    def test_imported_file_does_not_create_false_change_alert(self):
        with tempfile.TemporaryDirectory() as d:
            doc = self.sync(d)['document']
            doc['capture_method'] = 'local_json_import'
            s.atomic_save(doc, d)
            self.assertEqual(self.sync(d, [row(JASMC='B202')]).get('changes'), [])

    def test_export_import_preserves_calendar_but_does_not_restore_history(self):
        with tempfile.TemporaryDirectory() as d:
            original = self.sync(d)['document']
            result = json.loads(s.import_json(json.dumps(original), d))
            self.assertEqual(result['document'].get('term_start'), '2026-09-14')
            self.assertEqual(result['document'].get('term_code'), '2026-2027-1')
            self.assertEqual(result['document'].get('change_history', []), [])

    def test_partial_invalid_response_cannot_mimic_a_cancelled_class(self):
        with tempfile.TemporaryDirectory() as d:
            self.sync(d, [row(), row(KCM='另一门课')])
            before = Path(d, 'schedule.json').read_bytes()
            result = self.sync(d, [row(), row(KCM='另一门课', KSJC=None)])
            self.assertFalse(result['ok'])
            self.assertEqual(Path(d, 'schedule.json').read_bytes(), before)


if __name__ == '__main__': unittest.main()
