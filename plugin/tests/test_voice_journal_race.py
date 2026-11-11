"""Source-only journal regressions; run explicitly under a private scratch HOME.
Race cases schedule real public ACK commits with line tracing/Event barriers, not
replacement functions, private overrides or artificial journal deletion. Metadata
and errno unit controls use a fake path, not real OS attacks. Tracing is no sandbox.
"""
import errno
import inspect
import json
import os
from pathlib import Path
import sqlite3
import stat
import sys
import tempfile
import threading
from types import SimpleNamespace
import unittest

P = Path(__file__).resolve().parents[1]
CORE = Path('/data/data/com.termux/files/home/.hermes/hermes-agent')
sys.path[:0] = [str(P), str(CORE)]
from voice_inbox import Inbox, UnsafePath, _private
from voice_reconcile import ReconcileStore


def line_of(function, text):
    lines, start = inspect.getsourcelines(function)
    matches = [start+i for i, line in enumerate(lines) if text in line]
    assert len(matches) == 1, (function, text, matches)
    return matches[0]


class JournalRace(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(dir=os.environ['TMPDIR'])
        self.addCleanup(self.tmp.cleanup)
        self.home = Path(self.tmp.name)
        self.path = self.home/'test.sqlite'
        self.journal = Path(str(self.path)+'-journal')
        self.store = ReconcileStore(self.home, 'fixture', self.path, wait_seconds=1.5)

    def race(self, schedule):
        with self.store.open() as box:
            box.receive('request', 'synthetic', 'lane')
            owner = box.claim('lane')
            identity = dict(profile_id=owner.profile_id, lane=owner.lane,
                            request_id=owner.request_id, attempt_id=owner.attempt_id,
                            session_id='session', turn_id='turn')
            event, digest = box.issue(identity, [dict(role='user', content='synthetic')])
        receipt = dict(identity=identity, event_id=event, content_digest=digest,
                       outcome='played', synthetic=True)
        ready, release, done = threading.Event(), threading.Event(), threading.Event()
        errors, accepted, events = [], [], []
        commit_line = line_of(Inbox._transaction, "self._db.execute('COMMIT')")
        stat_line = line_of(_private, 's = path.lstat()')
        validate_lines, start = inspect.getsourcelines(Inbox._validate)
        # Immediately before journal validation: before the old exists check, or
        # before the new single metadata call. Independent of source line numbers.
        p_line = start + next(i for i, s in enumerate(validate_lines) if 'p = Path(' in s)
        before_line = p_line + 1
        scheduled = False

        def wait(event):
            self.assertTrue(event.wait(8), 'barrier timeout')

        def writer_trace(frame, event, arg):
            if event == 'line' and frame.f_code is Inbox._transaction.__wrapped__.__code__ and frame.f_lineno == commit_line:
                s = self.journal.lstat()
                self.assertTrue(stat.S_ISREG(s.st_mode))
                self.assertEqual((stat.S_IMODE(s.st_mode), s.st_nlink), (0o600, 1))
                events.append('ack.before_commit')
                ready.set(); wait(release)
            return writer_trace

        def writer():
            try:
                with self.store.open() as box:
                    sys.settrace(writer_trace)
                    try: accepted.append(box.acknowledge(receipt))
                    finally: sys.settrace(None)
                    self.assertFalse(os.path.lexists(self.journal))
                    events.append('ack.return')
            except BaseException as exc:
                errors.append(exc)
            finally: done.set()

        def reader_trace(frame, event, arg):
            nonlocal scheduled
            if scheduled: return reader_trace
            journal_frame = frame.f_code is _private.__code__ and frame.f_locals.get('path') == self.journal
            hit = (schedule == 'before-check' and event == 'line' and frame.f_code is Inbox._validate.__code__ and frame.f_lineno == before_line and frame.f_locals.get('p') == self.journal)
            hit |= schedule == 'before-lstat' and journal_frame and event == 'line' and frame.f_lineno == stat_line
            hit |= schedule == 'after-stat' and journal_frame and event == 'return' and arg is not None
            if hit:
                scheduled = True
                self.assertTrue(os.path.lexists(self.journal))
                events.append('reader.barrier')
                release.set(); wait(done)
                self.assertFalse(os.path.lexists(self.journal))
                events.append('reader.resume')
            return reader_trace

        thread = threading.Thread(target=writer, name='legitimate-ack')
        thread.start()
        try:
            wait(ready)
            sys.settrace(reader_trace)
            try:
                with self.store.open(): pass
                events.append('reader.open.success')
            except UnsafePath as exc:
                print(json.dumps(dict(open_error=type(exc).__name__, message=str(exc),
                    context=type(exc.__context__).__name__, errno=getattr(exc.__context__, 'errno', None),
                    suppress_context=exc.__suppress_context__)), flush=True)
                raise
            finally: sys.settrace(None)
        finally:
            release.set(); thread.join(10)
            self.assertFalse(thread.is_alive(), 'writer not joined')
            print(json.dumps(dict(schedule=schedule, events=events, ack=accepted,
                                  writer_errors=[type(e).__name__ for e in errors])), flush=True)
        self.assertEqual(errors, [])
        self.assertEqual(accepted, [True])
        self.assertTrue(scheduled)
        self.assertLess(events.index('ack.return'), events.index('reader.resume'))
        with sqlite3.connect(self.path.as_uri()+'?mode=ro', uri=True) as db:
            self.assertEqual(db.execute('SELECT state FROM voice_checkpoints').fetchone()[0], 'acked')
            self.assertEqual(db.execute('PRAGMA integrity_check').fetchone()[0], 'ok')
        with self.store.open(): pass

    def test_ack_before_check(self): self.race('before-check')
    def test_ack_before_lstat(self): self.race('before-lstat')
    def test_ack_before_lstat_repeat(self): self.race('before-lstat')
    def test_ack_after_stat(self): self.race('after-stat')

    def test_real_journal_security_controls(self):
        with self.store.open(): pass
        target = self.home/'target'; target.touch(mode=0o600)
        for kind in ('symlink', 'dangling_symlink', 'directory', 'mode'):
            with self.subTest(kind=kind):
                if kind == 'symlink': self.journal.symlink_to(target)
                elif kind == 'dangling_symlink': self.journal.symlink_to(self.home/'absent')
                elif kind == 'directory': self.journal.mkdir(mode=0o700)
                else: self.journal.touch(mode=0o644); self.journal.chmod(0o644)
                try:
                    with self.assertRaises(UnsafePath): self.store.open()
                finally:
                    if kind == 'directory': self.journal.rmdir()
                    else: self.journal.unlink()

    def test_required_database_and_lock_absence(self):
        for which in ('database', 'lock'):
            with self.subTest(which=which), self.store.open() as box:
                path = self.path if which == 'database' else Path(str(self.path)+'.lock')
                moved = Path(str(path)+'.moved')
                path.rename(moved)
                try:
                    with self.assertRaises(UnsafePath): box.get('request')
                finally: moved.rename(path)


class MetadataControls(unittest.TestCase):
    class Probe:
        def __init__(self, value): self.value, self.calls = value, 0
        def lstat(self):
            self.calls += 1
            if isinstance(self.value, BaseException): raise self.value
            return self.value

    def test_only_enoent_is_optional(self):
        for code in (errno.ENOENT, errno.EACCES, errno.EPERM, errno.EIO, errno.ENOTDIR, errno.ELOOP):
            for optional in (False, True):
                with self.subTest(errno=code, optional=optional):
                    path = self.Probe(OSError(code, 'synthetic'))
                    if optional and code == errno.ENOENT:
                        self.assertIsNone(_private(path, missing_ok=True))
                    else:
                        with self.assertRaises(UnsafePath): _private(path, missing_ok=optional)
                    self.assertEqual(path.calls, 1)
        path = self.Probe(FileNotFoundError(errno.EACCES, 'not ENOENT'))
        with self.assertRaises(UnsafePath): _private(path, missing_ok=True)

    def test_same_stat_security_metadata(self):
        good = dict(st_mode=stat.S_IFREG|0o600, st_uid=os.getuid(), st_nlink=1, st_dev=1, st_ino=2)
        for mutation in ({}, {'st_uid':os.getuid()+1}, {'st_mode':stat.S_IFREG|0o644},
                         {'st_mode':stat.S_IFLNK|0o600}, {'st_mode':stat.S_IFIFO|0o600},
                         {'st_nlink':2}, {'st_nlink':0}):
            with self.subTest(mutation=mutation):
                path = self.Probe(SimpleNamespace(**(good|mutation)))
                if mutation:
                    with self.assertRaises(UnsafePath): _private(path, missing_ok=True)
                else: self.assertEqual(_private(path, missing_ok=True), (1, 2))
                self.assertEqual(path.calls, 1)

    def test_required_directory_absence(self):
        with self.assertRaises(UnsafePath):
            _private(self.Probe(FileNotFoundError(errno.ENOENT, 'missing')), directory=True)


if __name__ == '__main__':
    home = Path(os.environ['HOME']).resolve()
    assert home.is_relative_to(CORE.parent/'cache/scratch')
    assert all(os.environ.get(k) == str(home) for k in ('HOME', 'HERMES_HOME', 'TMPDIR'))
    assert Path.cwd() == home and stat.S_IMODE(home.stat().st_mode) == 0o700
    os.umask(0o077)
    unittest.main(verbosity=2)
