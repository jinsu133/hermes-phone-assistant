"""No model, no network: real descriptor reads of explicitly synthetic text fixtures."""
import hashlib
import importlib.util
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest import mock

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('voice_documents', ROOT / 'voice_documents.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class DocumentsTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.path = self.root / 'report.md'
        self.text = '문서😀\n' * 900
        self.path.write_text(self.text, encoding='utf-8')
        self.catalog = {'report': str(self.path)}
        self.docs = module.VoiceDocuments(self.catalog)

    def read(self, **kw):
        return self.docs.perform(dict(action='read_document', name='report', **kw))

    def test_catalog_bounds_and_aliases(self):
        for catalog in (None, [], {'../report': str(self.path)}, {'report': 123},
                        {str(i):str(self.path) for i in range(33)}):
            with self.subTest(catalog_type=type(catalog).__name__), self.assertRaises(ValueError):
                module.VoiceDocuments(catalog)

    def test_default_does_not_discover_files(self):
        self.assertEqual(module.VoiceDocuments({}).perform({'action':'list_documents'})['documents'], [])

    def test_catalog_never_exposes_paths_or_contents(self):
        value = self.docs.perform({'action':'list_documents'})
        self.assertEqual(value['documents'], ['report'])
        self.assertNotIn(str(self.root), str(value))
        self.assertNotIn('文', str(value))

    def test_unicode_pages_reassemble_redacted_view(self):
        first = self.read()
        self.assertEqual(first['offset_unit'], 'unicode_code_points')
        self.assertTrue(first['has_more'])
        self.assertTrue(first['untrusted_file_data'])
        parts = [first['content']]
        page = first
        while page['has_more']:
            page = self.read(offset=page['next_offset'], expected_sha256=first['sha256'])
            parts.append(page['content'])
        self.assertEqual(''.join(parts), self.text)
        self.assertEqual(page['total_chars'], len(self.text))
        self.assertEqual(first['sha256'], hashlib.sha256(self.path.read_bytes()).hexdigest())

    def test_paging_requires_exact_previous_digest(self):
        with self.assertRaisesRegex(ValueError, 'document_digest_required'):
            self.read(offset=1)
        with self.assertRaisesRegex(ValueError, 'document_changed'):
            self.read(offset=1, expected_sha256='0'*64)

    def test_changed_document_cannot_mix_pages(self):
        first = self.read()
        self.path.write_text('changed', encoding='utf-8')
        with self.assertRaisesRegex(ValueError, 'document_changed'):
            self.read(offset=1, expected_sha256=first['sha256'])

    def test_eof_is_not_infinite_paging(self):
        first = self.read()
        final = self.read(offset=len(self.text), expected_sha256=first['sha256'])
        self.assertEqual(final['content'], '')
        self.assertFalse(final['has_more'])
        with self.assertRaises(ValueError):
            self.read(offset=len(self.text)+1, expected_sha256=first['sha256'])

    def test_unknown_alias_and_paths_are_denied(self):
        for name in ('../report', str(self.path), 'missing'):
            with self.subTest(name=name), self.assertRaises(ValueError):
                self.docs.perform({'action':'read_document', 'name':name})
        with self.assertRaises(ValueError):
            self.docs.perform({'action':'read_document', 'name':'report', 'path':str(self.path)})

    def test_invalid_arguments_never_coerce_values(self):
        for extra in ({'offset':True}, {'offset':-1}, {'offset':'0'}, {'expected_sha256':12}, {'content':'replace'}):
            with self.subTest(extra=extra), self.assertRaises(ValueError):
                self.read(**extra)
        for action in (None, [], 'write', 'delete'):
            with self.assertRaises(ValueError):
                self.docs.perform({'action':action})

    def test_no_writes_or_arbitrary_execution(self):
        before = self.path.read_bytes()
        self.read()
        self.assertEqual(self.path.read_bytes(), before)
        with self.assertRaises(ValueError):
            self.docs.perform({'action':'run_numeric', 'name':'report'})

    def test_symlink_file_and_parent_are_denied(self):
        self.path.unlink()
        target = self.root/'target.md'; target.write_text('not permitted')
        self.path.symlink_to(target)
        with self.assertRaises((OSError, ValueError)):
            self.read()
        actual = self.root/'actual'; actual.mkdir()
        (actual/'report.md').write_text('not permitted')
        link = self.root/'linked'; link.symlink_to(actual, target_is_directory=True)
        docs = module.VoiceDocuments({'report':str(link/'report.md')})
        with self.assertRaises((OSError, ValueError)):
            docs.perform({'action':'read_document','name':'report'})

    def test_hardlink_denied(self):
        result = subprocess.run(['ln', str(self.path), str(self.root/'other.md')], capture_output=True)
        if result.returncode:
            self.skipTest('Host denies real hard-link creation; descriptor policy tested separately')
        with self.assertRaisesRegex(ValueError, 'document_file_denied'):
            self.read()

    def test_multiple_link_descriptor_policy(self):
        from types import SimpleNamespace
        original = module.os.fstat
        def linked(fd):
            info = original(fd)
            return SimpleNamespace(st_mode=info.st_mode, st_nlink=2, st_size=info.st_size)
        with mock.patch.object(module.os, 'fstat', side_effect=linked):
            with self.assertRaisesRegex(ValueError, 'document_file_denied'):
                self.read()

    def test_fifo_and_directory_are_denied_without_blocking(self):
        self.path.unlink(); os.mkfifo(self.path)
        with self.assertRaises(ValueError):
            self.read()
        self.path.unlink(); self.path.mkdir()
        with self.assertRaises(ValueError):
            self.read()

    def test_binary_oversize_and_unsupported_format(self):
        for data in (b'\xff', b'hello\x00world', b'x'*(module.MAX_BYTES+1)):
            self.path.write_bytes(data)
            with self.assertRaises((ValueError, UnicodeError)):
                self.read()
        for name in ('file.pdf','credentials.txt','.env','private-key.md'):
            with self.assertRaises(ValueError):
                module.VoiceDocuments({'report':str(self.root/name)}).perform({'action':'read_document','name':'report'})

    def test_mid_read_replacement_is_denied(self):
        original = module.os.read
        done = False
        def race(fd, amount):
            nonlocal done
            value = original(fd, amount)
            if not done:
                done = True
                newer = self.root/'new.md'; newer.write_text('replacement')
                newer.replace(self.path)
            return value
        with mock.patch.object(module.os, 'read', side_effect=race), self.assertRaises(ValueError):
            self.read()

    def test_secrets_are_redacted_before_paging(self):
        secret = 'x' * 25
        self.path.write_text('가'*1994+'\napi_key="'+secret+'"\n')
        first = self.read()
        second = self.read(offset=first['next_offset'], expected_sha256=first['sha256'])
        self.assertNotIn(secret, first['content']+second['content'])
        self.assertIn('redact', (first['content']+second['content']).lower())

    def test_unavailable_redaction_fails_closed(self):
        with mock.patch.object(module, 'redact_document', side_effect=RuntimeError('private-path')):
            with self.assertRaisesRegex(ValueError, '^document_redaction_unavailable$'):
                self.read()

    def test_profiles_catalogs_do_not_share_state(self):
        other = self.root/'other.md'; other.write_text('profile B')
        b = module.VoiceDocuments({'report':str(other)})
        self.assertTrue(self.read()['content'].startswith('문서'))
        self.assertEqual(b.perform({'action':'read_document','name':'report'})['content'], 'profile B')
        self.assertTrue(self.read()['content'].startswith('문서'))


if __name__ == '__main__':
    unittest.main()
