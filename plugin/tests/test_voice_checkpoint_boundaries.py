"""Source-only store boundary tests. No core override, model, audio or network.
Run explicitly with core/venv/bin/python -B this_file; never generic discovery.
Cancellation here means durable mark_unknown, not an AIAgent cancellation test.
"""
import json
import os
from pathlib import Path
import signal
import sqlite3
import subprocess
import sys
import tempfile
import threading

P = Path(__file__).resolve().parents[1]
CORE = Path('/data/data/com.termux/files/home/.hermes/hermes-agent')
SCRATCH = CORE.parent / 'cache/scratch'
CASES = ('crash_acked', 'crash_permitted', 'competing_consume',
         'cancel_first', 'consume_first', 'cancel_race', 'commit_ack', 'commit_consume')


def isolate():
    home = Path(os.environ['HOME']).resolve()
    assert home.is_relative_to(SCRATCH) and home.name.startswith('voice-boundaries-')
    assert os.environ['HERMES_HOME'] == str(home)
    os.umask(0o077)
    def audit(event, args):
        if event in ('socket.connect', 'socket.bind', 'socket.getaddrinfo', 'subprocess.Popen', 'os.system'):
            raise PermissionError('offline child')
        if event == 'open' and isinstance(args[0], (str, bytes, os.PathLike)):
            path = Path(os.fsdecode(args[0])).resolve()
            writing = (isinstance(args[1], str) and any(x in args[1] for x in 'wax+')) or (isinstance(args[2], int) and args[2] & (os.O_WRONLY | os.O_RDWR | os.O_CREAT))
            if writing and not path.is_relative_to(home):
                raise PermissionError('outside fixture write')
            if path.is_relative_to(CORE.parent) and not path.is_relative_to(CORE) and not path.is_relative_to(home):
                raise PermissionError('live profile read')
            if path.name == '.env' or path.name.startswith('auth.json'):
                raise PermissionError('credential read')
    sys.addaudithook(audit)
    sys.path[:0] = [str(P), str(CORE)]
    return home


def child(case, stage):
    home = isolate()
    from voice_checkpoint import CheckpointStore, CheckpointError
    from voice_inbox import Ownership, InboxError
    store = CheckpointStore(home, 'fixture-profile', home/'approved.sqlite', wait_seconds=10)
    if stage == 'restart':
        with store.open() as box:
            row = box.get('request')
            owner = Ownership('fixture-profile', row.lane, row.request_id, row.attempt_id)
            notice, = box.notices(owner)
            expected = case.removeprefix('crash_')
            assert notice['state'] == expected
            identity, event, digest = notice['identity'], notice['event_id'], notice['content_digest']
            assert not box.acknowledge(notice['receipt'])
            try:
                box.consume(event, identity, digest)
            except CheckpointError as exc:
                assert str(exc) == 'stale checkpoint'
            else:
                raise AssertionError('restart permitted stale consume')
            assert box.claim('lane') is None
            assert box.history('lane').messages == []
            assert row.state != 'completed'
        with store.open(exclusive_startup=True) as box:
            assert box.recover_checkpoints() == (1 if expected == 'acked' else 0)
            assert box.recover_checkpoints() == 0
            assert box.notices(owner)[0]['state'] == ('failed' if expected == 'acked' else 'permitted')
        with store.open() as box:
            assert box.claim('lane') is None
            assert box.get('request').state != 'completed'
        print('RESULT '+json.dumps(dict(case=case, passed=True, replay=False, completed=False)), flush=True)
        return
    with store.open() as box:
        box.receive('request', 'synthetic boundary request', 'lane')
        owner = box.claim('lane')
        identity = dict(profile_id=owner.profile_id, lane=owner.lane, request_id=owner.request_id,
                        attempt_id=owner.attempt_id, session_id='session', turn_id='turn')
        event, digest = box.issue(identity, [dict(role='user', content='synthetic evidence')])
        receipt = dict(identity=identity, event_id=event, content_digest=digest, outcome='played', synthetic=True)
        if case != 'commit_ack':
            assert box.acknowledge(receipt)
    if stage == 'seed':
        with store.open() as box:
            if case == 'crash_permitted':
                assert box.consume(event, identity, digest)
            assert box.notices(owner)[0]['state'] == case.removeprefix('crash_')
        # Explicit durable boundary, parent kills this owned PID without cleanup.
        print('READY', flush=True)
        signal.pause()
        raise AssertionError('seed unexpectedly resumed')
    results = []
    errors = []
    def consume():
        with store.open() as box:
            try:
                return box.consume(event, identity, digest)
            except CheckpointError as exc:
                assert str(exc) == 'checkpoint owner unavailable'
                return 'owner_closed'
    def cancel():
        with store.open() as box:
            return box.mark_unknown(owner)
    if case.startswith('commit_'):
        # Owned synthetic SQLite schema: deferred FK violation occurs at COMMIT,
        # not UPDATE. No patched connection, transaction method or core API.
        with store.open() as box:
            fixture = sqlite3.connect(home/'approved.sqlite', isolation_level=None)
            try:
                fixture.executescript('''
                    CREATE TABLE fixture_parent(id INTEGER PRIMARY KEY);
                    CREATE TABLE fixture_deferred(id INTEGER REFERENCES fixture_parent(id) DEFERRABLE INITIALLY DEFERRED);
                ''')
                target = 'acked' if case == 'commit_ack' else 'permitted'
                fixture.execute("CREATE TRIGGER fixture_commit_failure AFTER UPDATE OF state ON voice_checkpoints WHEN NEW.state='"+target+"' BEGIN INSERT INTO fixture_deferred VALUES (99); END")
                # Independently prove this fixture's UPDATE succeeds, COMMIT fails.
                fixture.execute('PRAGMA foreign_keys=ON')
                fixture.execute('BEGIN IMMEDIATE')
                fixture.execute('UPDATE voice_checkpoints SET state=? WHERE event_id=?', (target,event))
                try:
                    fixture.execute('COMMIT')
                except sqlite3.IntegrityError:
                    fixture.execute('ROLLBACK')
                else:
                    raise AssertionError('fixture failed to reject COMMIT')
                try:
                    if case == 'commit_ack':
                        box.acknowledge(receipt)
                    else:
                        box.consume(event,identity,digest)
                except InboxError as exc:
                    assert str(exc) == 'store transaction failed'
                else:
                    raise AssertionError('failed commit returned success')
                saved, = box.notices(owner)
                assert saved['state'] == ('pending' if case == 'commit_ack' else 'acked')
                assert saved['receipt'] == (None if case == 'commit_ack' else receipt)
                assert fixture.execute('SELECT COUNT(*) FROM fixture_deferred').fetchone()[0] == 0
                fixture.execute('DROP TRIGGER fixture_commit_failure')
                if case == 'commit_ack':
                    assert box.acknowledge(receipt)
                assert box.consume(event,identity,digest)
                assert not box.consume(event,identity,digest)
            finally:
                fixture.close()
        results = ['commit_rejected', 'rollback_verified', 'same_process_retry_once']
    elif case in ('cancel_first', 'consume_first'):
        if case == 'cancel_first':
            cancel(); assert consume() == 'owner_closed'
        else:
            assert consume() is True; cancel(); assert consume() == 'owner_closed'
    else:
        count = 8 if case == 'competing_consume' else 2
        barrier = threading.Barrier(count)
        def worker(index):
            try:
                barrier.wait(timeout=5)
                value = cancel() if case == 'cancel_race' and index == 0 else consume()
                results.append((index, value))
            except BaseException as exc:
                errors.append(repr(exc))
        threads = [threading.Thread(target=worker, args=(i,)) for i in range(count)]
        for thread in threads: thread.start()
        for thread in threads: thread.join(15)
        assert not any(t.is_alive() for t in threads), 'worker did not exit'
        assert not errors, errors
        if case == 'competing_consume':
            assert sum(v is True for _,v in results) == 1, results
            assert sum(v is False for _,v in results) == 7, results
        else:
            assert dict(results)[1] in (True, 'owner_closed'), results
    with store.open() as box:
        saved, = box.notices(owner)
        if case in ('cancel_first','cancel_race','consume_first'):
            assert box.get('request').state == 'unknown_outcome'
            assert consume() == 'owner_closed'
            expected = 'acked' if case == 'cancel_first' or (case == 'cancel_race' and dict(results)[1] == 'owner_closed') else 'permitted'
            assert saved['state'] == expected
        else:
            assert saved['state'] == 'permitted'
            assert not box.consume(event,identity,digest)
        assert box.claim('lane') is None
        assert box.get('request').original_text == 'synthetic boundary request'
        assert box.get('request').state != 'completed'
        assert box.history('lane').messages == []
    print('RESULT '+json.dumps(dict(case=case, passed=True, results=results, completed=False)), flush=True)


def main():
    if len(sys.argv) == 4 and sys.argv[1] == '--child':
        return child(sys.argv[2], sys.argv[3])
    for case in CASES:
        with tempfile.TemporaryDirectory(prefix='voice-boundaries-', dir=SCRATCH) as tmp:
            env = dict(HOME=tmp,HERMES_HOME=tmp,TMPDIR=tmp,PATH='/data/data/com.termux/files/usr/bin',
                       LANG='C.UTF-8',PYTHONDONTWRITEBYTECODE='1',PYTHONPATH=str(CORE))
            command = [sys.executable,'-B',str(Path(__file__).resolve()),'--child',case]
            if case.startswith('crash_'):
                import selectors
                proc = subprocess.Popen(command+['seed'],cwd=tmp,env=env,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True)
                try:
                    with selectors.DefaultSelector() as selector:
                        selector.register(proc.stdout, selectors.EVENT_READ)
                        assert selector.select(30), 'seed readiness timeout'
                        assert proc.stdout.readline().strip() == 'READY', 'seed failed before boundary'
                    proc.kill()
                    out, err = proc.communicate(timeout=10)
                    assert proc.returncode == -signal.SIGKILL, (out,err,proc.returncode)
                    print('CRASH '+json.dumps(dict(case=case,signal='SIGKILL',returncode=proc.returncode)),flush=True)
                finally:
                    if proc.poll() is None: proc.kill()
                    proc.communicate(timeout=10)
            proc = subprocess.run(command+['restart' if case.startswith('crash_') else 'run'],cwd=tmp,env=env,capture_output=True,text=True,timeout=60)
            assert proc.returncode == 0, (case,proc.stdout,proc.stderr)
            lines = [x for x in proc.stdout.splitlines() if x.startswith('RESULT ')]
            assert len(lines) == 1, proc.stdout
            print(lines[0],flush=True)
    print(json.dumps(dict(passed=len(CASES),cases=len(CASES),network_calls=0,real_audio=False)))


if __name__ == '__main__': main()
