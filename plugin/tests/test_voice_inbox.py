"""Offline source-only TDD suite; each case runs in an isolated bounded child."""
import dataclasses
import json
import os
from pathlib import Path
import resource
import signal
import sqlite3
import subprocess
import sys
import tempfile
import unittest

PLUGIN = Path(__file__).resolve().parents[1]
SCRATCH = Path('/data/data/com.termux/files/home/.hermes/cache/scratch')
sys.path.insert(0, str(PLUGIN))


def limits():
    # Android's allocator reserves large virtual ranges; bound heap, not address space.
    resource.setrlimit(resource.RLIMIT_DATA, (256 * 1024 * 1024,) * 2)
    resource.setrlimit(resource.RLIMIT_CPU, (15, 15))
    signal.alarm(25)


def load():
    from voice_inbox import Inbox, InboxError, Ownership, UnsafePath, Busy
    return Inbox, InboxError, Ownership, UnsafePath, Busy


class InboxTests(unittest.TestCase):
    def setUp(self):
        self.Inbox, self.Error, self.Owner, self.Unsafe, self.Busy = load()
        self.root = Path(os.environ['HOME']) / 'profile'
        self.root.mkdir(mode=0o700)
        self.path = self.root / 'inbox.sqlite'
        self.opened = []

    def box(self, **kw):
        defaults = dict(profile_dir=self.root, profile_id='test', db_path=self.path,
                        max_requests=4, max_text_bytes=32)
        defaults.update(kw)
        b = self.Inbox(**defaults)
        self.opened.append(b)
        return b

    def tearDown(self):
        for b in self.opened:
            b.close()

    def test_receive(self):
        b = self.box()
        receipt = b.receive('r1', 'synthetic original', 'voice')
        self.assertTrue(receipt.accepted)
        with sqlite3.connect(self.path) as db:
            self.assertEqual(db.execute('SELECT request_id, original_text, lane FROM requests').fetchone(),
                             ('r1', 'synthetic original', 'voice'))
        self.assertEqual(receipt, b.receive('r1', 'synthetic original', 'voice'))
        for text, lane in [('changed', 'voice'), ('synthetic original', 'other')]:
            with self.assertRaises(self.Error): b.receive('r1', text, lane)
        self.assertNotIn('synthetic original', repr(b.get('r1')))
        b.close()
        self.assertEqual(self.box().get('r1').original_text, 'synthetic original')

    def test_bounds_retention(self):
        b = self.box(max_requests=1)
        with self.assertRaises(self.Error): b.receive('r0', '가' * 11, 'voice')
        b.receive('r1', 'synthetic', 'voice')
        owner = b.claim('voice')
        self.assertTrue(b.bind_start(owner, 'sid', 'turn'))
        self.assertTrue(b.complete(owner, 'sid', 'turn'))
        with self.assertRaises(self.Error): b.receive('r2', 'synthetic', 'voice')
        self.assertTrue(b.receive('r1', 'synthetic', 'voice').accepted)
        self.assertEqual(b.get('r1').state, 'completed')
        with self.assertRaises(self.Error): self.box(max_requests=2)
        with self.assertRaises(self.Error): b.receive('x'*129, 'synthetic', 'voice')
        with self.assertRaises(self.Error): b.receive('r3', '\ud800', 'voice')
        with self.assertRaises(TypeError): b.receive('r3', 'synthetic', 'voice', approval=True)

    def test_ownership(self):
        b = self.box()
        b.receive('r1', 'synthetic', 'voice'); b.receive('r2', 'synthetic', 'voice')
        a = b.claim('voice')
        with self.assertRaises(dataclasses.FrozenInstanceError): a.request_id = 'r2'
        self.assertIsNone(b.claim('voice'))
        for field in ('request_id', 'attempt_id', 'profile_id', 'lane'):
            wrong = dataclasses.replace(a, **{field: 'wrong'})
            self.assertFalse(b.bind_start(wrong, 'sid', 'turn'))
            self.assertFalse(b.mark_unknown(wrong))
        self.assertTrue(b.bind_start(a, 'sid', 'turn'))
        self.assertFalse(b.bind_start(a, 'sid2', 'turn2'))
        self.assertFalse(b.complete(a, 'wrong', 'turn'))
        self.assertFalse(b.complete(a, 'sid', 'wrong'))
        self.assertTrue(b.complete(a, 'sid', 'turn'))
        newer = b.claim('voice')
        self.assertNotEqual(a.attempt_id, newer.attempt_id)
        self.assertFalse(b.complete(a, 'sid', 'turn'))
        self.assertFalse(b.mark_unknown(a))
        self.assertEqual(b.get('r2').state, 'dispatching')

    def test_unknown_and_recovery(self):
        b = self.box(); b.receive('r1', 'synthetic', 'voice')
        a = b.claim('voice'); b.bind_start(a, 'sid', 'turn')
        other = self.box()
        self.assertEqual(other.get('r1').state, 'started')
        with self.assertRaises(self.Error): other.recover_inflight()
        with self.assertRaises(self.Busy): self.box(exclusive_startup=True)
        b.close(); other.close()
        startup = self.box(exclusive_startup=True)
        with self.assertRaises(self.Busy): self.box()
        self.assertEqual(startup.recover_inflight(), 1)
        self.assertEqual(startup.recover_inflight(), 0)
        startup.close()
        b = self.box(); b.receive('r2', 'synthetic', 'voice')
        self.assertIsNone(b.claim('voice'))
        self.assertFalse(b.complete(a, 'sid', 'turn'))
        self.assertEqual(b.get('r1').state, 'unknown_outcome')
        b.receive('r3', 'synthetic', 'other')
        self.assertIsNotNone(b.claim('other'))

    def test_permissions(self):
        self.root.chmod(0o755)
        with self.assertRaises(self.Unsafe): self.box()
        self.root.chmod(0o700)
        with self.assertRaises(self.Unsafe): self.box(db_path=Path('relative.sqlite'))
        with self.assertRaises(self.Unsafe): self.box(db_path=self.root.parent/'outside.sqlite')
        target = self.root/'target'; target.touch(mode=0o600)
        self.path.symlink_to(target)
        with self.assertRaises(self.Unsafe): self.box()
        self.path.unlink(); self.path.touch(mode=0o600); self.path.chmod(0o644)
        with self.assertRaises(self.Unsafe): self.box()
        self.path.unlink()
        b = self.box()
        self.assertEqual(self.path.stat().st_mode & 0o777, 0o600)
        with self.assertRaises(self.Error): self.box(profile_id='other')
        b.close()
        journal = Path(str(self.path)+'-journal'); journal.touch(mode=0o600); journal.chmod(0o644)
        with self.assertRaises(self.Unsafe): self.box()

    def test_cancellation_blocks_lane(self):
        b = self.box(); b.receive('r1', 'synthetic', 'voice')
        a = b.claim('voice')
        self.assertTrue(b.mark_unknown(a))
        self.assertFalse(b.bind_start(a, 'sid', 'turn'))
        b.receive('r2', 'synthetic', 'voice')
        self.assertIsNone(b.claim('voice'))
        b.close()
        b = self.box()
        self.assertIsNone(b.claim('voice'))
        self.assertEqual(b.get('r1').original_text, 'synthetic')
        self.assertEqual(b.get('r1').attempt_id, a.attempt_id)

    def test_storage_refusal_no_ack(self):
        b = self.box()
        with sqlite3.connect(self.path) as db:
            db.execute("CREATE TRIGGER refuse BEFORE INSERT ON requests BEGIN SELECT RAISE(ABORT,'synthetic-sensitive'); END")
        with self.assertRaises(self.Error) as caught:
            b.receive('r1', 'synthetic-sensitive', 'voice')
        self.assertNotIn('synthetic-sensitive', str(caught.exception))
        self.assertIsNone(b.get('r1'))
        with sqlite3.connect(self.path) as db:
            db.execute('DROP TRIGGER refuse')
            cols = [r[1] for r in db.execute('PRAGMA table_info(requests)')]
            self.assertFalse(any('approv' in col or 'grant' in col for col in cols))
        self.assertTrue(b.receive('r1', 'synthetic', 'voice').accepted)
        self.path.chmod(0o644)
        with self.assertRaises(self.Unsafe): b.receive('r2', 'synthetic', 'voice')
        self.path.chmod(0o600)
        self.assertIsNone(b.get('r2'))

    def test_private_file_variants(self):
        lock = Path(str(self.path)+'.lock')
        target = self.root/'target'; target.touch(mode=0o600)
        lock.symlink_to(target)
        with self.assertRaises(self.Unsafe): self.box()
        lock.unlink()
        # This Android Python omits os.link; exercise the nlink refusal via stat metadata.
        from unittest.mock import patch
        import voice_inbox
        real_stat = target.lstat()
        class LinkedStat:
            st_mode = real_stat.st_mode
            st_uid = real_stat.st_uid
            st_nlink = 2
        with patch.object(Path, 'lstat', return_value=LinkedStat()):
            with self.assertRaises(self.Unsafe): voice_inbox._private(target)
        self.path.mkdir(mode=0o700)
        with self.assertRaises(self.Unsafe): self.box()

    def child(self, action):
        return subprocess.Popen([sys.executable, '-B', __file__, '--worker', action],
                                stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                stderr=subprocess.PIPE, text=True, env=os.environ.copy())

    def external_writer(self, label):
        p = self.child('probe_writer')
        out, err = p.communicate(timeout=10)
        self.assertEqual(p.returncode, 0, err)
        result = out.strip()
        self.assertIn(result, ('locked', 'acquired'))
        print(f'{label}: external BEGIN IMMEDIATE {result}', flush=True)
        return result

    def construction_preserves_lock(self, finish):
        b = self.box()
        b._db.execute('BEGIN IMMEDIATE')
        try:
            before = self.external_writer('before_second_handle')
            try:
                second = self.box()
            except self.Error as exc:
                print(f'second_handle: {type(exc).__name__}: {exc}', flush=True)
            else:
                print('second_handle: success', flush=True)
                second.close()
            active = b._db.in_transaction
            print(f'first_connection_in_transaction: {active}', flush=True)
            after = self.external_writer('after_second_handle')
        finally:
            b._db.execute(finish)
        released = self.external_writer('after_' + finish.lower())
        self.assertEqual(before, 'locked')
        self.assertTrue(active)
        self.assertEqual(after, 'locked', 'second construction released the first writer lock')
        self.assertEqual(released, 'acquired')

    def test_constructor_preserves_writer_lock_commit(self):
        self.construction_preserves_lock('COMMIT')

    def test_constructor_preserves_writer_lock_rollback(self):
        self.construction_preserves_lock('ROLLBACK')

    def test_creation_excludes_second_constructor_until_fd_closed(self):
        from unittest.mock import patch
        import voice_inbox
        real_open, real_close = os.open, os.close
        created = []
        checked = []

        def track_open(path, flags, *args, **kw):
            fd = real_open(path, flags, *args, **kw)
            if Path(path) == self.path:
                created.append(fd)
            return fd

        def check_close(fd):
            try:
                if fd in created and not checked:
                    # The new inode is visible but the raw creation fd is still open.
                    # A reentrant same-process constructor must not reach SQLite yet.
                    checked.append(fd)
                    with self.assertRaises(self.Busy):
                        self.box()
            finally:
                real_close(fd)

        with patch.object(voice_inbox.os, 'open', side_effect=track_open), \
             patch.object(voice_inbox.os, 'close', side_effect=check_close):
            b = self.box()
        self.assertEqual(len(created), 1)
        self.assertEqual(checked, created)
        # Creation-only exclusive flock must have been downgraded to shared.
        other = self.box()
        other.close()
        b.receive('r1', 'synthetic', 'voice')
        self.assertEqual(b.get('r1').state, 'received')

    def test_creation_failure_closes_descriptor_and_releases_flock(self):
        from unittest.mock import patch
        import voice_inbox
        real_open, real_fstat = os.open, os.fstat
        created = []

        def track_open(path, flags, *args, **kw):
            fd = real_open(path, flags, *args, **kw)
            if Path(path) == self.path:
                created.append(fd)
            return fd

        def fail_fstat(fd):
            if fd in created:
                raise OSError('synthetic descriptor failure')
            return real_fstat(fd)

        with patch.object(voice_inbox.os, 'open', side_effect=track_open), \
             patch.object(voice_inbox.os, 'fstat', side_effect=fail_fstat):
            with self.assertRaises((OSError, self.Error)):
                self.box()
        self.assertEqual(len(created), 1)
        with self.assertRaises(OSError):
            real_fstat(created[0])
        # Failed creation leaves a private empty file, not a leaked exclusive lock.
        self.assertEqual(self.path.stat().st_mode & 0o777, 0o600)
        self.box(exclusive_startup=True).close()
        self.box().receive('r1', 'synthetic', 'voice')

    def test_existing_database_never_raw_opened(self):
        from unittest.mock import patch
        import voice_inbox
        b = self.box()
        real_open = os.open

        def no_db_open(path, flags, *args, **kw):
            self.assertNotEqual(Path(path), self.path, 'raw DB open bypasses SQLite VFS')
            return real_open(path, flags, *args, **kw)

        with patch.object(voice_inbox.os, 'open', side_effect=no_db_open):
            self.box().close()
            with self.assertRaises(self.Error):
                self.box(profile_id='other')
        b.receive('r1', 'synthetic', 'voice')

    def test_creation_race_existing_inode_has_no_raw_fallback(self):
        from unittest.mock import patch
        import voice_inbox
        real_lexists, real_open = os.path.lexists, os.open
        raced = []
        flags_seen = []

        def stale_missing(path):
            if Path(path) == self.path and not raced:
                raced.append(True)
                # Another creator finishes between our missing-path observation
                # and acquisition of the exclusive creation flock.
                self.box().close()
                return False
            return real_lexists(path)

        def check_open(path, flags, *args, **kw):
            if Path(path) == self.path:
                flags_seen.append(flags)
                self.assertTrue(flags & os.O_EXCL)
                self.assertTrue(flags & os.O_NOFOLLOW)
            return real_open(path, flags, *args, **kw)

        with patch.object(voice_inbox.os.path, 'lexists', side_effect=stale_missing), \
             patch.object(voice_inbox.os, 'open', side_effect=check_open):
            b = self.box()
        self.assertEqual(len(flags_seen), 2)  # creator succeeds; stale creator gets EEXIST
        b.receive('r1', 'synthetic', 'voice')
        self.box().close()  # the temporary exclusive flock was downgraded

    def test_failed_sqlite_initialization_closes_connection_preserves_lock(self):
        from unittest.mock import patch
        import voice_inbox
        b = self.box()
        real_connect = sqlite3.connect
        connections = []
        closed = []

        class FailPragma:
            def __init__(self, *args, **kw):
                self.db = real_connect(*args, **kw)
                connections.append(self.db)

            def execute(self, *args, **kw):
                raise sqlite3.OperationalError('synthetic initialization failure')

            def close(self):
                self.db.close()
                closed.append(True)

        b._db.execute('BEGIN IMMEDIATE')
        try:
            with patch.object(voice_inbox.sqlite3, 'connect', side_effect=FailPragma):
                with self.assertRaisesRegex(self.Error, '^store initialization failed$'):
                    self.box()
            self.assertEqual(closed, [True])
            with self.assertRaises(sqlite3.ProgrammingError):
                connections[0].execute('SELECT 1')
            self.assertTrue(b._db.in_transaction)
            self.assertEqual(self.external_writer('after_initialization_error'), 'locked')
        finally:
            b._db.execute('ROLLBACK')
        self.assertEqual(self.external_writer('after_error_rollback'), 'acquired')
        b.close()
        self.box(exclusive_startup=True).close()

    def test_private_owner_and_replacement_checks(self):
        from unittest.mock import patch
        import voice_inbox
        b = self.box()
        with patch.object(voice_inbox.os, 'getuid', return_value=os.getuid() + 1):
            with self.assertRaises(self.Unsafe):
                self.box()
        b.close()
        # Existing same-UID replacement is still rejected by an already-open handle.
        b = self.box()
        self.path.rename(self.root/'old.sqlite')
        self.path.touch(mode=0o600)
        with self.assertRaises(self.Unsafe):
            b.receive('r1', 'synthetic', 'voice')
        b.close()
        alias = self.root.parent/'profile-link'
        alias.symlink_to(self.root, target_is_directory=True)
        with self.assertRaises(self.Unsafe):
            self.box(profile_dir=alias, db_path=alias/'inbox.sqlite')

    def test_new_exclusive_startup_retains_exclusive_flock(self):
        startup = self.box(exclusive_startup=True)
        with self.assertRaises(self.Busy):
            self.box()
        with self.assertRaises(self.Error):
            startup.claim('voice')
        self.assertEqual(startup.recover_inflight(), 0)
        startup.close()
        self.box().receive('r1', 'synthetic', 'voice')

    def test_two_process_claim(self):
        b = self.box(); b.receive('r1', 'synthetic', 'voice'); b.receive('r2', 'synthetic', 'voice')
        children = [self.child('claim') for _ in range(2)]
        try:
            for p in children: self.assertEqual(p.stdout.readline().strip(), 'ready')
            for p in children: p.stdin.write('go\n'); p.stdin.flush()
            results = []
            for p in children:
                out, err = p.communicate(timeout=10)
                self.assertEqual(p.returncode, 0, err); results.append(json.loads(out))
            self.assertEqual(sum(results), 1)
            self.assertEqual([b.get(r).state for r in ('r1','r2')].count('dispatching'), 1)
        finally:
            for p in children:
                if p.poll() is None: p.kill()
                p.wait(timeout=5)

    def test_commit_before_ack_crash(self):
        p = self.child('receive_crash')
        out, err = p.communicate(timeout=10)
        self.assertEqual(p.returncode, 73, err); self.assertEqual(out, '')
        b = self.box()
        self.assertEqual(b.get('r1').state, 'received')
        self.assertTrue(b.receive('r1', 'synthetic', 'voice').accepted)

    def test_inflight_process_crashes(self):
        for action, expected in [('dispatch_crash', 'dispatching'), ('started_crash', 'started')]:
            p = self.child(action); out, err = p.communicate(timeout=10)
            self.assertEqual(p.returncode, 74, err)
            b = self.box(); self.assertEqual(b.get(action).state, expected); b.close()
            startup = self.box(exclusive_startup=True)
            self.assertEqual(startup.recover_inflight(), 1); startup.close()
            b = self.box(); self.assertEqual(b.get(action).state, 'unknown_outcome')
            self.assertIsNone(b.claim(action)); b.close()


def worker(action):
    limits()
    Inbox, *_ = load()
    root = Path(os.environ['HOME'])/'profile'
    if action == 'probe_writer':
        # Deliberately bypass Inbox/flock: observe the actual SQLite POSIX lock.
        db = sqlite3.connect((root/'inbox.sqlite').as_uri() + '?mode=rw',
                             uri=True, timeout=0, isolation_level=None)
        try:
            try:
                db.execute('BEGIN IMMEDIATE')
            except sqlite3.OperationalError as exc:
                if exc.sqlite_errorcode != sqlite3.SQLITE_BUSY:
                    raise
                print('locked', flush=True)
            else:
                print('acquired', flush=True)
                db.execute('ROLLBACK')
        finally:
            db.close()
        return
    with Inbox(profile_dir=root, profile_id='test', db_path=root/'inbox.sqlite',
               max_requests=4, max_text_bytes=32) as b:
        if action == 'claim':
            print('ready', flush=True); sys.stdin.readline()
            print(json.dumps(b.claim('voice') is not None), flush=True)
        elif action == 'receive_crash':
            # Kill at receipt construction, which must occur after commit but before return/ACK.
            import voice_inbox
            voice_inbox.Accepted = lambda *a, **k: os._exit(73)
            b.receive('r1', 'synthetic', 'voice')
            raise AssertionError('crash seam not reached')
        else:
            b.receive(action, 'synthetic', action)
            a = b.claim(action)
            if action == 'started_crash': b.bind_start(a, 'sid', 'turn')
            os._exit(74)


if __name__ == '__main__':
    if len(sys.argv) > 1 and sys.argv[1] == '--worker':
        worker(sys.argv[2])
    elif len(sys.argv) > 1:
        limits()
        unittest.main(argv=[sys.argv[0], sys.argv[1]], verbosity=2)
    else:
        failures = []
        cases = unittest.defaultTestLoader.getTestCaseNames(InboxTests)
        for case in cases:
            with tempfile.TemporaryDirectory(prefix='voice-inbox-', dir=SCRATCH) as home:
                env = {k: os.environ[k] for k in ('PATH','LD_LIBRARY_PATH','PREFIX') if k in os.environ}
                env.update(HOME=home, HERMES_HOME=home, TMPDIR=home, PYTHONDONTWRITEBYTECODE='1')
                p = subprocess.run([sys.executable, '-B', __file__, 'InboxTests.'+case],
                                   env=env, capture_output=True, text=True, timeout=35)
                print(p.stdout+p.stderr, end='')
                if p.returncode: failures.append(case)
        print(json.dumps(dict(cases=len(cases), passed=len(cases)-len(failures), failed=failures)))
        sys.exit(bool(failures))
