"""Real task-state close refusal + public registration fault wrappers, clean children.

No core overrides, live provider, dispatch or device activation. Actual dispatch
join ordering is covered by test_voice_task_worker and test_voice_worker.
"""
import asyncio
import fcntl
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile

P = Path(__file__).resolve().parents[1]
CORE = Path('/data/data/com.termux/files/home/.hermes/hermes-agent')
SCRATCH = CORE.parent/'cache/scratch'
sys.path.insert(0, str(P))
CASES = ('task_only', 'task_multiple', 'task_poison')


def child(case):
    from voice_worker import bootstrap, Worker, CleanupError
    from voice_task_state import TaskState
    home = Path(os.environ['HOME'])
    def audit(event, args):
        if event in {'socket.connect', 'socket.bind', 'socket.getaddrinfo', 'subprocess.Popen', 'os.system'}:
            raise PermissionError('offline fixture')
        if event == 'open' and isinstance(args[0], (str, bytes, os.PathLike)):
            path = Path(os.fsdecode(args[0])).resolve()
            writing = (isinstance(args[1], str) and any(c in args[1] for c in 'wax+')) or (isinstance(args[2], int) and args[2] & (os.O_WRONLY | os.O_RDWR | os.O_CREAT))
            if writing and not path.is_relative_to(home) and str(path) != '/dev/null':
                raise PermissionError('outside fixture')
            if path.name in ('.env', 'auth.json', 'auth.json.lock'):
                raise PermissionError('credentials')
            if path.is_relative_to(CORE.parent) and not path.is_relative_to(CORE) and not path.is_relative_to(home):
                raise PermissionError('live profile')
    sys.addaudithook(audit)
    url = 'http://127.0.0.1:1/v1'
    scope = bootstrap(home, task_state_url=url)
    from hermes_cli.plugins import PluginContext, PluginManifest, get_plugin_manager
    from hermes_cli.plugins_ledger import PluginRegistration
    manager = get_plugin_manager()
    ctx = PluginContext(PluginManifest(name='task-cleanup-offline', path=str(P)), manager)
    attempts, handles, originals, kinds = [], [], [], []
    armed = True
    failures = set() if case == 'task_only' else {0, 5, 8}
    marker = 'synthetic-private-cleanup-payload'

    class RetryableHandle:
        def __init__(self, original, index):
            self.original, self.index = original, index
        @property
        def active(self): return self.original.active
        def dispose(self):
            attempts.append(self.index)
            if armed and self.index in failures: raise OSError(marker)
            self.original.dispose()

    class Context:
        def register(self, kind, *args, **kwargs):
            index = len(handles)
            original = getattr(ctx, 'register_' + kind)(*args, **kwargs)
            originals.append(original); kinds.append(kind)
            if case == 'task_poison':
                def release():
                    attempts.append(index)
                    if armed and index in failures: raise OSError(marker)
                    original.dispose()
                handle = PluginRegistration(kind, str(index), release)
            else:
                handle = RetryableHandle(original, index)
            handles.append(handle)
            return handle
        def register_hook(self, *a, **kw): return self.register('hook', *a, **kw)
        def register_tool(self, *a, **kw): return self.register('tool', *a, **kw)
        def register_middleware(self, *a, **kw): return self.register('middleware', *a, **kw)

    options = dict(model='fixture', provider='openai', api_key='offline-placeholder', base_url=url)
    worker = Worker(scope, Context(), model_options=options)
    adapter = worker.task_adapter
    # Public owned state slot: use real transaction-based close refusal, not a
    # replacement close method or private worker/core patch.
    state = adapter.state = TaskState(home, {'task_id':'cleanup'}, goal='fixture', artifacts={'open':('app.json', b'{}')})
    assert set(kinds) == {'hook', 'tool', 'middleware'}
    expected = list(reversed(range(len(handles))))

    def lease_held(path):
        fd = os.open(path, os.O_RDWR)
        try:
            try: fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
            except BlockingIOError: return
            raise AssertionError('unresolved ownership lease released')
        finally: os.close(fd)

    def refused():
        for action in (worker.check_admission, lambda: worker.receive('late', 'late', 'lane'),
                       lambda: worker.start('lane'), lambda: Worker(scope, ctx, model_options=options)):
            try: action()
            except RuntimeError: pass
            else: raise AssertionError('admission before cleanup resolved')
        assert worker.cleanup_state == 'cleanup_failed'
        os.fstat(scope.process_lease)

    async def run():
        nonlocal armed
        primary = ValueError(marker)
        with state.connection():
            for retry in range(2):
                try:
                    try: raise primary
                    except ValueError: await worker.shutdown()
                except CleanupError as exc:
                    assert exc.worker is worker
                    if retry == 0:
                        assert attempts == expected, 'independent cleanup skipped'
                    assert exc.__context__ is primary, 'primary error lost'
                    assert all(not h.active for i, h in enumerate(originals) if i not in failures)
                    assert {'stage':'task_adapter', 'type':'StateError'} in exc.cleanup_errors
                    assert marker not in str(exc) + repr(exc.cleanup_errors)
                    assert exc.restart_required == (case == 'task_poison')
                else: raise AssertionError('task close failure hidden')
                assert not state.closed and state.fd is not None
                lease_held(home/'task.lock')
                refused()
        # Task close can now succeed, but simultaneous registration failure must
        # keep the singleton quarantined; a later successful task close is not
        # evidence that an inactive failed registration has been released.
        if failures:
            try: await worker.shutdown()
            except CleanupError as exc:
                assert state.closed and state.fd is None
                assert not any(e['stage'] == 'task_adapter' for e in exc.cleanup_errors)
            else: raise AssertionError('registration failure hidden')
            refused()
        armed = False
        if case == 'task_poison':
            before = list(attempts)
            for _ in range(2):
                try: await worker.shutdown()
                except CleanupError as exc: assert exc.restart_required
                else: raise AssertionError('inactive failed handle became successful')
                assert attempts == before
                refused()
            assert any(h.active for h in originals)
        else:
            await worker.shutdown()
            assert worker.cleanup_state == 'closed' and state.closed and state.fd is None
            assert all(not h.active for h in originals)
            before = list(attempts)
            await worker.shutdown()
            assert attempts == before
            replacement = Worker(scope, ctx, model_options=options)
            await replacement.shutdown()
            manager.unload()
        for index in range(len(handles)):
            if index not in failures: assert attempts.count(index) == 1
    asyncio.run(run())


def suite(out):
    from voice_worker import clean_environment
    out.mkdir(mode=0o700, parents=True, exist_ok=False)
    failed = False
    for case in CASES:
        home = out/case; home.mkdir(mode=0o700)
        proc = subprocess.run([str(CORE/'venv/bin/python'), '-B', str(Path(__file__).resolve()), '--child', case],
                              cwd=home, env=clean_environment(home, CORE), capture_output=True, timeout=90)
        report = json.loads((home/'result.json').read_text()) if (home/'result.json').exists() else {}
        (out/(case+'-process.json')).write_text(json.dumps(dict(returncode=proc.returncode, report=report,
            stdout=proc.stdout.decode(), stderr=proc.stderr.decode()), indent=2))
        print(case, proc.returncode, report, flush=True)
        failed |= bool(proc.returncode or proc.stdout or proc.stderr or report != {'ok':True})
    return int(failed)


if __name__ == '__main__':
    if '--child' in sys.argv:
        try:
            child(sys.argv[2]); report = {'ok':True}; code = 0
        except BaseException as exc:
            from test_voice_task_worker import graph
            report = {'ok':False, 'error':graph(exc)}; code = 1
        (Path(os.environ['HOME'])/'result.json').write_text(json.dumps(report, indent=2))
        raise SystemExit(code)
    out = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(tempfile.mkdtemp(prefix='task-cleanup-parent-', dir=SCRATCH))/'runs'
    raise SystemExit(suite(out))
