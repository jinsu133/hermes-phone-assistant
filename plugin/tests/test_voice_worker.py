"""Offline subprocess acceptance tests; no live profile/provider imports in parent."""
import asyncio
from contextlib import closing
from copy import deepcopy
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
import unittest
import uuid

P = Path(__file__).resolve().parents[1]
CORE = Path('/data/data/com.termux/files/home/.hermes/hermes-agent')
SCRATCH = Path('/data/data/com.termux/files/home/.hermes/cache/scratch')
sys.path.insert(0, str(P))


def child(case):
    from voice_worker import bootstrap, Worker
    home = Path(os.environ['HOME'])
    def audit(event, args):
        if event in {'socket.connect','socket.bind','socket.getaddrinfo','subprocess.Popen','os.system'}:
            raise PermissionError('offline test')
        if event == 'open' and isinstance(args[0], (str,bytes,os.PathLike)):
            path = Path(os.fsdecode(args[0])).resolve()
            writing = (isinstance(args[1], str) and any(c in args[1] for c in 'wax+')) or (isinstance(args[2], int) and args[2] & (os.O_WRONLY|os.O_RDWR|os.O_CREAT))
            if writing and not path.is_relative_to(home) and str(path) != '/dev/null':
                raise PermissionError('outside fixture')
            if path.is_relative_to(CORE.parent) and not path.is_relative_to(CORE) and not path.is_relative_to(home):
                raise PermissionError('live profile')
            if path.is_relative_to(CORE) and (path.name == '.env' or path.name.startswith('auth.json')):
                raise PermissionError('credentials')
    sys.addaudithook(audit)
    if case == 'dirty_environment':
        os.environ['OPENAI_API_KEY'] = 'synthetic-not-a-key'
        try: bootstrap(home)
        except RuntimeError: return
        raise AssertionError('dirty environment accepted')
    scope = bootstrap(home)
    from hermes_cli.plugins import PluginContext, PluginManifest, get_plugin_manager
    from voice_inbox import Busy
    manager = get_plugin_manager()
    ctx = PluginContext(PluginManifest(name='voice-worker-fixture', path=str(P)), manager)
    calls, effects = [], []
    entered, release = threading.Event(), threading.Event()
    marker = 'VOICE-PRIVATE-' + uuid.uuid4().hex
    token = 'sk-synthetic-' + uuid.uuid4().hex
    original = marker + ' ' + token
    def model(**kw):
        from openai.types.chat import ChatCompletion
        calls.append(deepcopy(kw['request']))
        if case in ('cancel', 'orderly'):
            entered.set()
            if not release.wait(10): raise TimeoutError('fixture barrier')
        msg = {'role':'assistant','content':original}
        finish = 'stop'
        if case == 'iteration_limit' or case in ('two_turns', 'unsupported', 'approval', 'background_args') and len(calls) == 1:
            names = ['voice_worker_fixture'] if case != 'unsupported' else ['delegate_task','terminal','execute_code','tool_call','cronjob','process_manage','unknown_tool']
            msg = {'role':'assistant','content':None,'tool_calls':[{'id':'fixture-call','type':'function','function':{'name':'tool_call','arguments':json.dumps({'calls':[{'name':name,'arguments':({'background':True} if case=='background_args' else {})} for name in names]})}}]}
            finish = 'tool_calls'
        return ChatCompletion.model_validate({'id':'fixture','object':'chat.completion','created':0,'model':'fixture','choices':[{'index':0,'message':msg,'finish_reason':finish}],'usage':{'prompt_tokens':1,'completion_tokens':1,'total_tokens':2}})
    ctx.register_middleware('llm_execution', model)
    names = ['voice_worker_fixture','delegate_task','terminal','execute_code','cronjob','process_manage','unknown_tool']
    for name in names:
        ctx.register_tool(name=name, toolset='voice_worker_fixture', schema={'name':name,'description':'Offline fixture only','parameters':{'type':'object','properties':{}}}, handler=lambda args, **kw: effects.append(1) or '{"success":true}')
    if case == 'approval':
        ctx.register_hook('pre_tool_call', lambda **kw: {'action':'block','message':'Synthetic policy denial'})
    options = {'model':'fixture','provider':'openai','api_key':'offline-placeholder','base_url':'https://offline.invalid/v1','max_iterations':3}
    for kwargs in (
        {'model_options':dict(options, save_trajectories=True)},
        {'model_options':options, 'allowed_tools':{'terminal'}},
        {'model_options':options, 'enabled_toolsets':['terminal']},
    ):
        try: Worker(scope, ctx, **kwargs)
        except ValueError: pass
        else: raise AssertionError('unsafe configuration accepted')
    assert not (home/'voice.sqlite').exists()
    if case.startswith(('constructor_', 'shutdown_', 'lifetime_')):
        awaitable = cleanup_regression(case, scope, ctx, options)
        asyncio.run(awaitable)
        if not case.endswith('_poison'): manager.unload()
        return
    worker = Worker(scope, ctx, model_options=options, enabled_toolsets=['voice_worker_fixture'], allowed_tools={'voice_worker_fixture'})
    try: Worker(scope, ctx, model_options=options)
    except RuntimeError: pass
    else: raise AssertionError('second active supervisor accepted')
    async def run():
        if case == 'disposed_guard':
            worker.registrations[0].dispose()
            try: worker.receive('r1', original, 'lane')
            except RuntimeError: pass
            else: raise AssertionError('receive without guard')
            with worker.store.open() as box: assert box.get('r1') is None
            await worker.shutdown()
            return
        worker.receive('r1', original, 'lane')
        if case == 'lost_guard_before_claim':
            task = worker.start('lane')
            worker.registrations[0].dispose()
            try: await task
            except RuntimeError: pass
            else: raise AssertionError('claim without policy')
            with worker.store.open() as box: assert box.get('r1').state == 'received'
            assert worker.diagnostics['dispatch_error'] == 1
            await worker.shutdown()
        elif case == 'queued_shutdown':
            task = worker.start('lane')
            await worker.shutdown()
            assert task.result().engine_turn_verified
        elif case in ('cancel','orderly'):
            task = worker.start('lane')
            for _ in range(4000):
                if entered.is_set(): break
                await asyncio.sleep(.01)
            assert entered.is_set()
            shutdown = asyncio.create_task(worker.shutdown(cancel=case=='cancel'))
            await asyncio.sleep(.05)
            assert not shutdown.done() and not task.done()
            assert all(h.active for h in worker.registrations)
            try: worker.store.open(exclusive_startup=True)
            except Busy: pass
            else: raise AssertionError('lifetime lock lost')
            if case == 'cancel': shutdown.cancel()
            release.set()
            try: await shutdown
            except asyncio.CancelledError: assert case == 'cancel'
            with worker.store.open() as box:
                assert box.get('r1').state == ('unknown_outcome' if case=='cancel' else 'completed')
                assert box.history('lane').turns == (0 if case=='cancel' else 1)
        else:
            result = await worker.start('lane')
            assert result.engine_turn_verified == (case != 'iteration_limit')
            if case == 'two_turns':
                assert effects == [1]
                worker.receive('r2','Recall the previous private text','lane')
                result = await worker.start('lane')
                assert result.engine_turn_verified
                assert original in json.dumps(calls[-1])
                messages = calls[-1]['messages']
                historical = [(i, call) for i, msg in enumerate(messages)
                              if msg['role'] == 'assistant'
                              for call in (msg.get('tool_calls') or [])]
                assert len(historical) == 1
                call_index, call = historical[0]
                assert call['id'] == 'fixture-call'
                assert call['type'] == 'function'
                assert call['function']['name'] == 'tool_call'
                assert json.loads(call['function']['arguments']) == {
                    'calls':[{'name':'voice_worker_fixture','arguments':{}}]}
                results = [(i, msg) for i, msg in enumerate(messages) if msg['role'] == 'tool']
                assert len(results) == 1
                result_index, prior_result = results[0]
                assert result_index > call_index
                assert prior_result['tool_call_id'] == call['id']
                # Compare the exact result from the first turn's post-tool model
                # request, not just the existence of a tool-role message.
                first_results = [msg for msg in calls[1]['messages'] if msg['role'] == 'tool']
                assert prior_result == first_results[0]
                assert json.loads(prior_result['content']) == {'success':True}
                assert messages[-1] == {'role':'user','content':'Recall the previous private text'}
                assert effects == [1]  # History is context, not a replay.
                with worker.store.open() as box:
                    assert box.history('lane').turns == 2
                    assert box.history('lane').delivery_status == 'untracked'
            if case in ('unsupported','approval','background_args'): assert effects == []
            if case in ('unsupported','background_args'): assert worker.diagnostics['tool_denied'] > 0
            if case == 'iteration_limit': assert worker.diagnostics['unverified'] == 1
            await worker.shutdown()
        assert all(not h.active for h in worker.registrations)
        assert not any(t.name.startswith('voice-turn') for t in threading.enumerate())
        try: worker.receive('late','late','lane')
        except RuntimeError: pass
        else: raise AssertionError('receive after shutdown')
        with worker.store.open() as box: assert box.get('r1').original_text == original
    asyncio.run(run())
    manager.unload()


async def cleanup_regression(case, scope, ctx, options):
    from voice_worker import Worker
    from hermes_cli.plugins_ledger import PluginRegistration
    from voice_inbox import Busy
    constructor = case.startswith('constructor_')
    poison = case.endswith('_poison')
    failures = set() if case == 'lifetime_failure' else ({0, 1} if constructor or case == 'shutdown_multiple' else {3})
    attempts, originals, handles = [], [], []
    armed = True

    class RetryableHandle:
        def __init__(self, handle, index):
            self.handle, self.index = handle, index
        @property
        def active(self): return self.handle.active
        def dispose(self):
            attempts.append(self.index)
            if armed and self.index in failures: raise RuntimeError('fixture cleanup')
            self.handle.dispose()

    class Context:
        def register_hook(self, name, callback):
            index = len(handles)
            if constructor and index == 2: raise RuntimeError('fixture registration')
            handle = ctx.register_hook(name, callback)
            originals.append(handle)
            if poison:
                def release():
                    attempts.append(index)
                    if armed and index in failures: raise RuntimeError('fixture cleanup')
                    handle.dispose()
                wrapped = PluginRegistration('hook', name, release)
            else:
                wrapped = RetryableHandle(handle, index)
            handles.append(wrapped)
            return wrapped

    if constructor:
        try: Worker(scope, Context(), model_options=options, allowed_tools={'voice_worker_fixture'})
        except RuntimeError as exc:
            assert attempts == [1, 0], 'rollback skipped resources'
            # A partial constructor must export a tracked, shutdown-only owner.
            assert hasattr(exc, 'worker'), 'constructor orphaned its cleanup owner'
            worker = exc.worker
        else: raise AssertionError('registration failure accepted')
    else:
        worker = Worker(scope, Context(), model_options=options, allowed_tools={'voice_worker_fixture'})
        assert worker._tool_guard('voice_worker_fixture', {}) is None
        lifetime = worker._lifetime
        class Lifetime:
            def close(self):
                attempts.append('lifetime')
                if armed and case == 'lifetime_failure': raise RuntimeError('fixture close')
                lifetime.close()
        worker._lifetime = Lifetime()
        try: await worker.shutdown()
        except RuntimeError as exc:
            assert attempts == ['lifetime', 3, 2, 1, 0], 'shutdown skipped resources'
            assert getattr(exc, 'worker', None) is worker
        else: raise AssertionError('cleanup failure hidden')
    expected = list(reversed(range(len(handles))))
    assert attempts == (expected if constructor else ['lifetime'] + expected), attempts
    assert worker.cleanup_state == 'cleanup_failed'
    assert not worker._closed
    assert all(not h.active for i, h in enumerate(originals) if i not in failures)
    if case == 'lifetime_failure':
        try: worker.store.open(exclusive_startup=True)
        except Busy: pass
        else: raise AssertionError('unclosed lifetime lost')
    async def refused():
        for action in (lambda: worker.receive('late','late','lane'),
                       lambda: worker.start('lane'),
                       lambda: Worker(scope, ctx, model_options=options)):
            try: action()
            except RuntimeError: pass
            else: raise AssertionError('new work allowed while cleanup unresolved')
        assert worker._tool_guard('voice_worker_fixture', {})['action'] == 'block'
    await refused()
    # Repeated failure cannot accidentally turn an inactive poisoned handle
    # (real PluginRegistration sets disposed BEFORE release) into success.
    for _ in range(2):
        try: await worker.shutdown()
        except RuntimeError as exc: assert exc.worker is worker
        else: raise AssertionError('failed retry became healthy')
        assert worker.cleanup_state == 'cleanup_failed'
        await refused()
    armed = False
    if poison:
        before = list(attempts)
        try: await worker.shutdown()
        except RuntimeError as exc: assert exc.restart_required
        else: raise AssertionError('inactive failed release treated as clean')
        assert attempts == before
        assert any(h.active for h in originals)
        await refused()
        # No safe public retry for this host API. Dedicated child exit is the
        # containment boundary; do not unload or claim these orphan hooks gone.
        return
    await worker.shutdown()
    assert worker.cleanup_state == 'closed' and worker._closed
    assert all(not h.active for h in originals)
    before = list(attempts)
    await worker.shutdown()
    assert attempts == before
    # Successful resources were not disposed twice during retries.
    for i in range(len(handles)):
        if i not in failures: assert attempts.count(i) == 1
    replacement = Worker(scope, ctx, model_options=options)
    await replacement.shutdown()
    assert not any(t.name.startswith('voice-turn') for t in threading.enumerate())


class Tests(unittest.TestCase):
    def test_isolated_workers(self):
        from voice_worker import clean_environment
        for case in ('two_turns','unsupported','approval','orderly','cancel','disposed_guard','dirty_environment','iteration_limit','lost_guard_before_claim','queued_shutdown','background_args','constructor_retry','constructor_poison','shutdown_single','shutdown_multiple','shutdown_poison','lifetime_failure'):
            with self.subTest(case=case), tempfile.TemporaryDirectory(prefix='voice-worker-test-',dir=SCRATCH) as root:
                home = Path(root)
                env = clean_environment(home, CORE)
                proc = subprocess.run([sys.executable,'-B',str(Path(__file__).resolve()),'--child',case],cwd=home,env=env,capture_output=True,timeout=90)
                report = json.loads((home/'result.json').read_text()) if (home/'result.json').exists() else {}
                self.assertEqual(proc.returncode,0,(case,report,proc.stdout,proc.stderr))
                self.assertEqual(proc.stdout,b'')
                self.assertEqual(proc.stderr,b'')
                self.assertEqual(report,{'ok':True})
                import sqlite3
                dbpath = home/'voice.sqlite'
                if dbpath.exists():
                    with closing(sqlite3.connect(dbpath)) as db, db:
                        row = db.execute('SELECT original_text FROM requests LIMIT 1').fetchone()
                    if row:
                        for path in home.rglob('*'):
                            if path.is_file() and path.name not in {'voice.sqlite','voice.sqlite-wal','voice.sqlite-shm','voice.sqlite-journal'}:
                                self.assertNotIn(row[0].split()[0].encode(),path.read_bytes(),str(path))
                                self.assertNotIn(row[0].split()[1].encode(),path.read_bytes(),str(path))
                self.assertFalse(list(home.rglob('*approval*')))
                # Core context-engine coordination may create an empty schema;
                # no duplicate sessions/transcripts may be stored there.
                state = home/'state.db'
                if state.exists():
                    with closing(sqlite3.connect(state)) as db, db:
                        for table in ('sessions','messages','system_prompts','async_delegations','session_turn_leases'):
                            self.assertEqual(db.execute('SELECT COUNT(*) FROM '+table).fetchone()[0],0)
                self.assertFalse(list(home.rglob('session_*.json')))
                print('PASS',case)


if __name__ == '__main__':
    if len(sys.argv)==3 and sys.argv[1]=='--child':
        try:
            child(sys.argv[2]); report={'ok':True}; code=0
        except BaseException as exc:
            import traceback
            report={'ok':False,'type':type(exc).__name__,'frames':[(Path(f.filename).name,f.lineno,f.name) for f in traceback.extract_tb(exc.__traceback__)]}; code=1
        (Path(os.environ['HOME'])/'result.json').write_text(json.dumps(report))
        raise SystemExit(code)
    unittest.main()
