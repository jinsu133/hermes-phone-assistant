"""Bounded fixture-only defensive regressions; no engine imports or product writes."""
import argparse
import copy
import hashlib
import json
import os
from pathlib import Path
import sqlite3
import subprocess
import sys
import tempfile
import unittest

from reconcile_protocol_fixture import Journal, Refused, encode

EVIDENCE = None


class ReconcileRegressions(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(dir=os.environ['TMPDIR'])
        self.addCleanup(self.tmp.cleanup)
        self.path = Path(self.tmp.name)/'fixture.sqlite'
        self.journal = Journal(self.path)

    def seed(self, state='candidate', reason=None):
        row = copy.deepcopy(EVIDENCE['journal'])
        row.update(state=state, result=None, active=row['original'], version=0,
                   ledger=row['original'], plugin_completed=False)
        if reason is not None:
            row['reason'] = reason
        db = self.journal.db()
        try:
            db.execute('CREATE TABLE recon_fixture(id INTEGER PRIMARY KEY, payload TEXT)')
            db.execute('INSERT INTO recon_fixture VALUES(1,?)', (encode(row),))
            db.execute('CREATE TABLE requests(request_id TEXT, lane TEXT, attempt_id TEXT, state TEXT, sid TEXT, turn_id TEXT, original_text TEXT)')
            owner, ident = row['owner'], row['boundary']['identity']
            db.execute('INSERT INTO requests VALUES(?,?,?,?,?,?,?)', (owner['request_id'], owner['lane'], owner['attempt_id'], 'started', ident['session_id'], ident['turn_id'], row['user']))
        finally:
            db.close()
        return row

    def fingerprint(self):
        return hashlib.sha256(self.path.read_bytes()).hexdigest()

    def refused_unchanged(self, operation, reason):
        before = self.fingerprint()
        with self.assertRaises(Refused) as raised:
            operation()
        self.assertIs(type(raised.exception), Refused)
        self.assertEqual(str(raised.exception), reason)
        self.assertEqual(self.fingerprint(), before)

    def reject_conflict(self, state, target, reason='original reason'):
        self.seed(state, 'original reason')
        self.refused_unchanged(lambda: self.journal.reject(reason, proven_abort=target == 'aborted'),
                               'terminal conflict')

    def test_unknown_cannot_become_aborted(self):
        self.reject_conflict('unknown', 'aborted')

    def test_aborted_cannot_become_unknown(self):
        self.reject_conflict('aborted', 'unknown')

    def test_unknown_reason_cannot_change(self):
        self.reject_conflict('unknown', 'unknown', 'different reason')

    def test_aborted_reason_cannot_change(self):
        self.reject_conflict('aborted', 'aborted', 'different reason')

    def terminal_idempotence(self, state):
        self.seed(state, 'original reason')
        before = self.fingerprint()
        self.journal.reject('original reason', proven_abort=state == 'aborted')
        self.assertEqual(self.fingerprint(), before)
        self.journal.recover()
        self.assertEqual(self.fingerprint(), before)

    def test_unknown_exact_repeat_no_write(self):
        self.terminal_idempotence('unknown')

    def test_aborted_exact_repeat_no_write(self):
        self.terminal_idempotence('aborted')

    def test_committed_reject_remains_refused(self):
        self.seed('plugin_committed')
        self.refused_unchanged(lambda: self.journal.reject('anything'), 'terminal commit')

    def malformed_suffix(self, suffix):
        self.seed()
        result = copy.deepcopy(EVIDENCE['envelopes'][0])
        result['messages'][-1] = suffix
        self.refused_unchanged(lambda: self.journal.finish(result, EVIDENCE['binding'], joined=True, closed=True),
                               'unproven current-turn delta')

    def test_null_suffix(self):
        self.malformed_suffix(None)

    def test_string_suffix(self):
        self.malformed_suffix('assistant')

    def test_list_suffix(self):
        self.malformed_suffix([])

    def test_number_suffix(self):
        self.malformed_suffix(1)

    def test_boolean_suffix(self):
        self.malformed_suffix(False)

    def test_dict_with_null_content(self):
        self.malformed_suffix(dict(role='assistant', content=None))

    def test_legacy_schema_without_row_recovery(self):
        # Reconstruct the old durable DDL/first-INSERT crash boundary in a child.
        code = "import sqlite3,os,sys; d=sqlite3.connect(sys.argv[1],isolation_level=None); d.execute('CREATE TABLE recon_fixture(id INTEGER PRIMARY KEY, payload TEXT)'); os._exit(75)"
        result = subprocess.run([sys.executable, '-B', '-c', code, str(self.path)], timeout=10)
        self.assertEqual(result.returncode, 75)
        self.refused_unchanged(self.journal.recover, 'incomplete fixture initialization')
        self.assertIsNone(self.journal.read())

    def test_empty_database_recovery(self):
        sqlite3.connect(self.path).close()
        self.refused_unchanged(self.journal.recover, 'incomplete fixture initialization')
        self.assertIsNone(self.journal.read())

    def test_prepare_serialization_failure_leaves_no_table(self):
        with self.assertRaises(ValueError):
            self.journal.prepare({}, 'fixture', [float('nan')], 'synthetic')
        with sqlite3.connect(self.path) as db:
            self.assertEqual(db.execute("SELECT name FROM sqlite_master WHERE name='recon_fixture'").fetchall(), [])

    def test_prepare_crash_after_schema_is_atomic(self):
        code = "from reconcile_protocol_fixture import Journal; import sys; Journal(sys.argv[1]).prepare({},'fixture',[],'synthetic',crash='after_schema')"
        env = dict(os.environ, PYTHONPATH=str(Path(__file__).resolve().parent))
        result = subprocess.run([sys.executable, '-B', '-c', code, str(self.path)], env=env,
                                capture_output=True, text=True, timeout=10)
        self.assertEqual(result.returncode, 75, result.stderr)
        with sqlite3.connect(self.path) as db:
            self.assertEqual(db.execute("SELECT name FROM sqlite_master WHERE name='recon_fixture'").fetchall(), [])
        self.refused_unchanged(self.journal.recover, 'incomplete fixture initialization')

    def test_valid_prepare_still_persists(self):
        self.journal.prepare({}, 'fixture', [], 'synthetic')
        self.assertEqual(self.journal.read()['state'], 'prepared')
        self.journal.recover()
        self.assertEqual(self.journal.read()['state'], 'unknown')
        before = self.fingerprint()
        self.journal.recover()
        self.assertEqual(self.fingerprint(), before)


def main():
    global EVIDENCE
    parser = argparse.ArgumentParser()
    parser.add_argument('--evidence', type=Path, required=True)
    args = parser.parse_args()
    home = Path(os.environ['HOME']).resolve()
    scratch = Path('/data/data/com.termux/files/home/.hermes/cache/scratch')
    assert home.is_relative_to(scratch) and Path.cwd().resolve() == home
    assert not home.stat().st_mode & 0o077
    assert all(os.environ.get(k) == str(home) for k in ('HOME', 'HERMES_HOME', 'TMPDIR'))
    EVIDENCE = json.loads(args.evidence.read_text())
    result = unittest.TextTestRunner(verbosity=2).run(unittest.defaultTestLoader.loadTestsFromTestCase(ReconcileRegressions))
    print(json.dumps(dict(tests=result.testsRun, failures=len(result.failures), errors=len(result.errors), core_runs=0)))
    raise SystemExit(not result.wasSuccessful())


if __name__ == '__main__':
    main()
