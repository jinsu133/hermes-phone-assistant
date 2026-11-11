"""Speech-correction store: user-confirmed pairs only, fail-closed on damage."""
import os
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import voice_corrections as store  # noqa: E402


class VoiceCorrections(unittest.TestCase):
    def setUp(self):
        self.home = Path(tempfile.mkdtemp(prefix='voicefix-'))

    def test_absent_store_is_empty_and_text_untouched(self):
        self.assertEqual(store.load(self.home), [])
        self.assertEqual(store.apply(self.home, '가격력 a슈장'), ('가격력 a슈장', []))

    def test_apply_ignores_spacing_and_latin_case_but_keeps_other_spacing(self):
        store.put(self.home, '가격력 a슈장', '가능역 A주차장')
        repaired, hits = store.apply(self.home, '  가격력   A슈장  검색')
        self.assertEqual(repaired, '  가능역 A주차장  검색')
        self.assertEqual(hits, ['가능역 A주차장'])

    def test_unknown_speech_is_never_guessed(self):
        store.put(self.home, '가격력 a슈장', '가능역 A주차장')
        self.assertEqual(store.apply(self.home, '가격력 에이슈장')[1], [])
        self.assertEqual(store.apply(self.home, '서울역 가줘')[1], [])

    def test_longest_key_wins_and_replacements_are_not_rescanned(self):
        store.put(self.home, 'A', 'X')
        store.put(self.home, 'AB', 'Y')
        self.assertEqual(store.apply(self.home, 'AB A')[0], 'Y X')
        chained = Path(tempfile.mkdtemp(prefix='voicefix-'))
        store.put(chained, '고성', '고성군')
        store.put(chained, '고성군', '삼척')
        self.assertEqual(store.apply(chained, '고성')[0], '고성군')

    def test_identity_and_oversized_entries_are_refused(self):
        for wrong, right in (('가능역', '가 능 역'), ('', '값'), ('a' * 61, 'b')):
            with self.assertRaises(store.CorrectionError):
                store.put(self.home, wrong, right)

    def test_put_replaces_same_spelling_and_keeps_file_private(self):
        store.put(self.home, '가격력 a슈장', '가능역 A주차장')
        store.put(self.home, '가격력A슈장', '가능역 A 주차장')
        entries = store.load(self.home)
        self.assertEqual(len(entries), 1)
        self.assertEqual(entries[0]['right'], '가능역 A 주차장')
        path = store.store_path(self.home)
        self.assertEqual(os.stat(path).st_mode & 0o077, 0)

    def test_damaged_store_is_refused_and_leaves_speech_untouched(self):
        path = store.store_path(self.home)
        for payload in ('{bad', '{"version":2,"entries":[]}',
                        '{"version":1,"entries":[{"wrong":"a","right":"b","extra":1}]}',
                        '{"version":true,"entries":[]}'):
            path.write_text(payload)
            with self.assertRaises(store.CorrectionError):
                store.load(self.home)
            self.assertEqual(store.apply(self.home, '가격력 a슈장'), ('가격력 a슈장', []))

    def test_remove_is_exact_after_squashing(self):
        store.put(self.home, '가격력 a슈장', '가능역 A주차장')
        self.assertEqual(store.remove(self.home, '가격력A슈장'), [])
        self.assertEqual(store.load(self.home), [])

    def test_capacity_is_bounded(self):
        for index in range(store.MAX_ENTRIES):
            store.put(self.home, f'가짜{index}', f'진짜{index}')
        with self.assertRaises(store.CorrectionError):
            store.put(self.home, '하나더', '진짜')


if __name__ == '__main__':
    unittest.main()
