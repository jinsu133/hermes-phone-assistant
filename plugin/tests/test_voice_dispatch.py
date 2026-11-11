"""Sequential, isolated, offline coordinator tests. No runtime installation."""
import asyncio
from contextlib import closing
from dataclasses import FrozenInstanceError, replace
import json
import logging
import os
from pathlib import Path
import sqlite3
import subprocess
import sys
import tempfile
import threading
import unittest

P = Path(__file__).resolve().parents[1]
CORE = Path('/data/data/com.termux/files/home/.hermes/hermes-agent')
SCRATCH = Path('/data/data/com.termux/files/home/.hermes/cache/scratch')
sys.path.insert(0, str(P))


class Registry:
    def __init__(self): self.hooks = {}
    def register_hook(self, name, callback): self.hooks[name] = callback


class Tests(unittest.TestCase):
    def setUp(self):
        from voice_dispatch import Dispatcher, DispatchHooks, Store, CURRENT
        self.Store, self.Dispatcher, self.CURRENT = Store, Dispatcher, CURRENT
        self.registry = Registry()
        self.hooks = DispatchHooks(self.registry)
        self.store = Store(profile_dir=Path(os.environ['HOME']), profile_id='profile-a',
                           db_path=Path(os.environ['HOME']) / 'inbox.sqlite')
        self.launches = []
        self.mode = 'happy'
        self.entered, self.release = threading.Event(), threading.Event()
        self.saved = []
        self.d = Dispatcher(self.store, self.hooks, self.factory)

    def row(self, rid='r1'):
        with self.store.open() as box: return box.get(rid)

    def receive(self, rid='r1', lane='lane'):
        return self.d.receive(rid, 'synthetic request', lane)

    def factory(self, identity):
        test = self
        test.assertEqual(test.row(identity.owner.request_id).state, 'dispatching')
        test.launches.append(identity)
        test.worker_thread = threading.current_thread()
        with test.assertRaises(FrozenInstanceError): identity.sid = 'changed'
        with test.assertRaises(FrozenInstanceError): identity.owner.attempt_id = 'changed'
        class Agent:
            def run_conversation(self, text):
                test.assertEqual(text, 'synthetic request')
                runtime = test.CURRENT.get()
                test.saved.append(runtime)
                kw = dict(session_id=identity.sid, turn_id='turn-' + identity.owner.request_id)
                if test.mode == 'raises': raise RuntimeError('synthetic exception')
                if test.mode != 'missing_pre': test.registry.hooks['pre_llm_call'](**kw)
                if test.mode == 'cancel':
                    test.entered.set()
                    if not test.release.wait(10): raise TimeoutError('test barrier')
                result = test.registry.hooks['pre_tool_call'](**kw)
                test.guard_result = result
                if test.mode != 'missing_post': test.registry.hooks['post_llm_call'](**kw)
                result = dict(kw, completed=True, failed=False, partial=False, interrupted=False,
                              turn_exit_reason='text_response(stop)')
                if test.mode == 'bad_envelope': result['turn_id'] = 'wrong'
                if test.mode == 'none': return None
                if test.mode == 'interrupted': result['interrupted'] = True
                return result
            def close(self):
                test.assertNotEqual(test.row(identity.owner.request_id).state, 'completed')
                test.closed = True
                if test.mode == 'cancel_close':
                    test.entered.set()
                    if not test.release.wait(10): raise TimeoutError('close barrier')
                if test.mode == 'close_failure': raise RuntimeError('close failure')
        return Agent()

    def run_dispatch(self, lane='lane'): return asyncio.run(self.d.dispatch(lane))

    def test_happy_duplicate(self):
        self.receive(); self.receive()
        result = self.run_dispatch()
        self.assertTrue(result.engine_turn_verified)
        self.assertEqual(self.row().state, 'completed')
        self.assertTrue(self.closed)
        self.assertFalse(self.worker_thread.is_alive())
        self.receive()
        self.assertIsNone(self.run_dispatch())
        self.assertEqual(len(self.launches), 1)
        self.assertIsNone(self.CURRENT.get())

    def test_unknown_cases(self):
        for mode in ('missing_pre', 'missing_post', 'bad_envelope', 'none', 'raises', 'interrupted', 'close_failure'):
            self.mode = mode
            rid = mode
            self.receive(rid, mode)
            result = self.run_dispatch(mode)
            self.assertFalse(result.engine_turn_verified, mode)
            self.assertEqual(self.row(rid).state, 'unknown_outcome', mode)
            self.assertIsNone(self.run_dispatch(mode))

    def test_failed_start_commit(self):
        self.receive()
        with closing(sqlite3.connect(self.store.db_path)) as db, db:
            db.execute("CREATE TRIGGER refuse_start BEFORE UPDATE ON requests WHEN NEW.state='started' BEGIN SELECT RAISE(ABORT,'synthetic'); END")
        self.assertFalse(self.run_dispatch().engine_turn_verified)
        self.assertEqual(self.guard_result['action'], 'block')
        self.assertEqual(self.row().state, 'unknown_outcome')

    def test_failed_complete_commit(self):
        self.receive()
        with closing(sqlite3.connect(self.store.db_path)) as db, db:
            db.execute("CREATE TRIGGER refuse_complete BEFORE UPDATE ON requests WHEN NEW.state='completed' BEGIN SELECT RAISE(ABORT,'synthetic'); END")
        self.assertFalse(self.run_dispatch().engine_turn_verified)
        self.assertEqual(self.row().state, 'unknown_outcome')

    def test_guard_default_and_stale(self):
        self.assertEqual(self.registry.hooks['pre_tool_call'](session_id='s', turn_id='t')['action'], 'block')
        self.receive(); self.run_dispatch()
        old = self.saved[0]
        old_row = self.row()
        self.receive('r2'); self.run_dispatch()
        new_row = self.row('r2')
        token = self.CURRENT.set(old)
        try:
            self.registry.hooks['pre_llm_call'](session_id=new_row.sid, turn_id=new_row.turn_id)
            self.registry.hooks['post_llm_call'](session_id=new_row.sid, turn_id=new_row.turn_id)
            self.assertEqual(self.registry.hooks['pre_tool_call'](session_id=old_row.sid, turn_id=old_row.turn_id)['action'], 'block')
        finally: self.CURRENT.reset(token)
        with self.store.open() as box:
            owner = old.identity.owner
            self.assertFalse(box.complete(replace(owner, attempt_id='stale'), old_row.sid, old_row.turn_id))
            self.assertFalse(box.mark_unknown(owner))
        self.assertEqual(self.row(), old_row)
        self.assertEqual(self.row('r2'), new_row)

    def test_cancel_worker_lifetime(self):
        from voice_inbox import Busy
        self.mode = 'cancel'; self.receive()
        async def exercise():
            task = asyncio.create_task(self.d.dispatch('lane'))
            while not self.entered.is_set(): await asyncio.sleep(.01)
            task.cancel()
            await asyncio.sleep(.03)
            self.assertFalse(task.done())
            self.receive()
            self.assertIsNone(await self.d.dispatch('lane'))
            self.receive('r2')
            self.assertIsNone(await self.d.dispatch('lane'))
            with self.assertRaises(Busy):
                self.store.open(exclusive_startup=True)
            task.cancel()
            await asyncio.sleep(.03)
            self.assertFalse(task.done())
            self.release.set()
            with self.assertRaises(asyncio.CancelledError): await task
        try: asyncio.run(exercise())
        finally: self.release.set()
        self.assertEqual(len(self.launches), 1)
        self.assertEqual(self.row().state, 'unknown_outcome')
        self.assertEqual(self.row('r2').state, 'received')
        self.assertIsNone(self.run_dispatch())
        with self.store.open(exclusive_startup=True) as box:
            self.assertEqual(box.recover_inflight(), 0)

    def test_cancel_all_async_tasks(self):
        # Loop shutdown cancels ALL tasks, not merely the adapter task. There
        # must be no cancellable to_thread wrapper able to masquerade as exit.
        self.mode = 'cancel'; self.receive()
        async def exercise():
            task = asyncio.create_task(self.d.dispatch('lane'))
            while not self.entered.is_set(): await asyncio.sleep(.01)
            others = asyncio.all_tasks() - {asyncio.current_task(), task}
            try:
                self.assertFalse(others, 'worker lifetime must not depend on an asyncio Task')
                for other in asyncio.all_tasks() - {asyncio.current_task()}: other.cancel()
                await asyncio.sleep(.03)
                self.assertFalse(task.done())
            finally:
                self.release.set()
                with self.assertRaises(asyncio.CancelledError): await task
        asyncio.run(exercise())

    def test_cancel_during_close(self):
        from voice_inbox import Busy
        self.mode = 'cancel_close'; self.receive()
        async def exercise():
            task = asyncio.create_task(self.d.dispatch('lane'))
            try:
                while not self.entered.is_set(): await asyncio.sleep(.01)
                task.cancel()
                await asyncio.sleep(.03)
                self.assertFalse(task.done())
                self.assertEqual(self.row().state, 'started')
                self.receive('r2')
                self.assertIsNone(await self.d.dispatch('lane'))
                with self.assertRaises(Busy): self.store.open(exclusive_startup=True)
                task.cancel()
                await asyncio.sleep(.03)
                self.assertFalse(task.done())
            finally:
                self.release.set()
                with self.assertRaises(asyncio.CancelledError): await task
        asyncio.run(exercise())
        self.assertFalse(self.worker_thread.is_alive())
        self.assertEqual(self.row().state, 'unknown_outcome')
        self.assertEqual(self.row('r2').state, 'received')
        self.assertIsNone(self.run_dispatch())
        with self.store.open(exclusive_startup=True) as box:
            self.assertEqual(box.recover_inflight(), 0)

    def test_stale_active_attempt(self):
        from voice_dispatch import _Run, Attempt
        self.receive()
        with self.store.open() as box:
            owner = box.claim('lane')
        stale = _Run(self.store, self.hooks, Attempt(replace(owner, attempt_id='obsolete'), 'sid'))
        token = self.CURRENT.set(stale)
        try:
            self.registry.hooks['pre_llm_call'](session_id='sid', turn_id='turn')
            self.assertEqual(self.registry.hooks['pre_tool_call'](session_id='sid', turn_id='turn')['action'], 'block')
            self.registry.hooks['post_llm_call'](session_id='sid', turn_id='turn')
        finally: self.CURRENT.reset(token)
        result = stale.finish(dict(session_id='sid',turn_id='turn',completed=True,turn_exit_reason='text_response(stop)'))
        self.assertFalse(result.engine_turn_verified)
        self.assertEqual(self.row().state, 'dispatching')
        self.assertEqual(self.row().attempt_id, owner.attempt_id)
        self.assertIsNone(self.run_dispatch())
        self.assertEqual(self.launches, [])

    def test_privacy_prerequisite(self):
        self.receive()
        logging.disable(logging.NOTSET)
        try:
            with self.assertRaises(RuntimeError): self.run_dispatch()
        finally: logging.disable(logging.CRITICAL)
        self.assertEqual(self.row().state, 'received')
        self.assertEqual(self.launches, [])

    def test_registration_failure(self):
        from voice_dispatch import DispatchHooks
        class FailingRegistry:
            def register_hook(self, name, callback):
                if name == 'pre_tool_call': raise RuntimeError('registration refused')
        with self.assertRaises(RuntimeError): DispatchHooks(FailingRegistry())
        with self.assertRaises(ValueError): self.Dispatcher(self.store, None, self.factory)
        self.assertEqual(self.launches, [])

    def test_profile_lane_separation(self):
        self.mode = 'missing_post'; self.receive(); self.run_dispatch()
        self.mode = 'happy'; self.receive('r2', 'other-lane')
        self.assertTrue(self.run_dispatch('other-lane').engine_turn_verified)
        directory = Path(os.environ['HOME']) / 'profile-b'; directory.mkdir(mode=0o700)
        other = self.Store(profile_dir=directory, profile_id='profile-b', db_path=directory/'inbox.sqlite')
        previous = self.store
        self.store = other
        try:
            d = self.Dispatcher(other, self.hooks, self.factory)
            d.receive('r1', 'synthetic request', 'lane')
            self.assertTrue(asyncio.run(d.dispatch('lane')).engine_turn_verified)
        finally: self.store = previous
        self.assertEqual(self.row().state, 'unknown_outcome')

    def test_actual_agent(self):
        from hermes_cli.plugins import PluginContext, PluginManifest, get_plugin_manager
        from voice_dispatch import DispatchHooks, Dispatcher
        from run_agent import AIAgent
        manager = get_plugin_manager()
        ctx = PluginContext(PluginManifest(name='dispatch-offline', path=str(P)), manager)
        self.addCleanup(manager.unload)
        hooks = DispatchHooks(ctx)
        effects, calls = [], []
        def model(**kw):
            from openai.types.chat import ChatCompletion
            calls.append(1)
            msg = {'role': 'assistant', 'content': 'Synthetic final.'}
            finish = 'stop'
            if len(calls) % 2 == 1:
                msg = {'role': 'assistant', 'content': None, 'tool_calls': [{'id': 'fixture-call', 'type': 'function', 'function': {'name': 'tool_call', 'arguments': json.dumps({'calls': [{'name': 'dispatch_fixture', 'arguments': {}}]})}}]}
                finish = 'tool_calls'
            return ChatCompletion.model_validate({'id':'fixture', 'object':'chat.completion', 'created':0, 'model':'fixture', 'choices':[{'index':0, 'message':msg, 'finish_reason':finish}], 'usage':{'prompt_tokens':1,'completion_tokens':1,'total_tokens':2}})
        ctx.register_middleware('llm_execution', model)
        def broken_middleware(**kw):
            raise OSError('synthetic middleware exception')
        ctx.register_middleware('tool_execution', broken_middleware)
        ctx.register_tool(name='dispatch_fixture', toolset='dispatch_fixture', schema={'name':'dispatch_fixture','description':'Synthetic counter','parameters':{'type':'object','properties':{}}}, handler=lambda args, **kw: effects.append(1) or '{"success":true}')
        def factory(identity):
            self.assertEqual(self.row(identity.owner.request_id).state, 'dispatching')
            return AIAgent(model='fixture', provider='openai', api_key='offline-placeholder', base_url='https://offline.invalid/v1', enabled_toolsets=['dispatch_fixture'], max_iterations=3, quiet_mode=True, skip_memory=True, skip_context_files=True, skip_background_review=True, platform='dispatch_fixture', session_id=identity.sid, cwd=os.environ['HOME'])
        d = Dispatcher(self.store, hooks, factory)
        d.receive('r1','synthetic request','lane')
        result = asyncio.run(d.dispatch('lane'))
        self.assertTrue(result.engine_turn_verified)
        self.assertEqual(effects, [1])
        self.assertEqual(len(calls), 2)
        self.assertEqual(self.row().state, 'completed')
        d.receive('r1','synthetic request','lane')
        self.assertIsNone(asyncio.run(d.dispatch('lane')))
        self.assertEqual(len(calls), 2)
        with closing(sqlite3.connect(self.store.db_path)) as db, db:
            db.execute("CREATE TRIGGER refuse_start BEFORE UPDATE ON requests WHEN NEW.state='started' BEGIN SELECT RAISE(ABORT,'synthetic'); END")
        d.receive('r2', 'synthetic request', 'lane')
        denied = asyncio.run(d.dispatch('lane'))
        self.assertFalse(denied.engine_turn_verified)
        self.assertEqual(self.row('r2').state, 'unknown_outcome')
        self.assertEqual(effects, [1])
        self.assertEqual(len(calls), 4)
        for path in Path(os.environ['HOME']).rglob('*'):
            if path.is_file() and path != self.store.db_path:
                self.assertNotIn(b'synthetic request', path.read_bytes(), str(path))
        print('ACTUAL_AGENT engine_turn_verified; committed start, matching pre/post/envelope; effect=1; replay calls=0; original only in private inbox')


def isolate():
    # Dedicated synthetic worker process only, never alter parent/runtime logging.
    # Core INFO logs include the user message even with quiet_mode=True.
    logging.disable(logging.CRITICAL)
    home = Path(os.environ['HOME']).resolve()
    assert home.is_relative_to(SCRATCH) and home.name.startswith('voice-dispatch-test-')
    sys.path.insert(0, str(CORE))
    def audit(event, args):
        if event in {'socket.connect', 'socket.bind', 'socket.getaddrinfo', 'subprocess.Popen', 'os.system'}:
            raise PermissionError('offline test')
        if event == 'open' and isinstance(args[0], (str, bytes, os.PathLike)):
            p = Path(os.fsdecode(args[0])).resolve()
            writing = (isinstance(args[1], str) and any(x in args[1] for x in 'wax+')) or (isinstance(args[2], int) and args[2] & (os.O_WRONLY | os.O_RDWR | os.O_CREAT))
            if writing and not p.is_relative_to(home): raise PermissionError('outside isolated HOME')
            if p.is_relative_to(CORE.parent) and not p.is_relative_to(CORE) and not p.is_relative_to(home): raise PermissionError('production profile')
            if p.is_relative_to(CORE) and (p.name == '.env' or p.name.startswith('auth.json')): raise PermissionError('credentials')
    sys.addaudithook(audit)
    (home/'config.yaml').write_text('memory:\n  memory_enabled: false\n  user_profile_enabled: false\ncompression:\n  enabled: false\n')


def main():
    if len(sys.argv) == 3 and sys.argv[1] == '--case':
        isolate()
        return not unittest.TextTestRunner(verbosity=2).run(unittest.TestSuite([Tests(sys.argv[2])])).wasSuccessful()
    cases = unittest.defaultTestLoader.getTestCaseNames(Tests)
    failed = []
    for case in cases:
        with tempfile.TemporaryDirectory(prefix='voice-dispatch-test-', dir=SCRATCH) as home:
            env = {'HOME':home,'HERMES_HOME':home,'TMPDIR':home,'PATH':'/data/data/com.termux/files/usr/bin','LANG':'C.UTF-8','PYTHONDONTWRITEBYTECODE':'1','PYTHONPATH':str(CORE)}
            try:
                p = subprocess.run([sys.executable,'-B',str(Path(__file__).resolve()),'--case',case],cwd=home,env=env,text=True,capture_output=True,timeout=75)
                print(p.stdout, end=''); print(p.stderr, end='')
                if p.returncode: failed.append(case)
            except subprocess.TimeoutExpired:
                failed.append(case); print('TIMEOUT', case)
    print(json.dumps({'cases':len(cases),'passed':len(cases)-len(failed),'failed':failed}))
    return bool(failed)

if __name__ == '__main__': raise SystemExit(main())
