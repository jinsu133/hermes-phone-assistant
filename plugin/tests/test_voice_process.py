"""Sequential real exec continuity, offline public AIAgent fixtures only."""
import asyncio
from copy import deepcopy
import json
import os
from pathlib import Path
import sqlite3
import sys
import tempfile
import unittest
import uuid

P = Path(__file__).resolve().parents[1]
CORE = Path('/data/data/com.termux/files/home/.hermes/hermes-agent')
SCRATCH = Path('/data/data/com.termux/files/home/.hermes/cache/scratch')
sys.path.insert(0, str(P))


def child(mode, action, fd):
    home = Path(os.environ['HOME'])
    def status(value):
        os.write(fd, (json.dumps(value)+'\n').encode())
    def audit(event, args):
        if event in {'socket.connect','socket.bind','socket.getaddrinfo','subprocess.Popen','os.system'}:
            raise PermissionError('offline fixture')
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
    try:
        from voice_worker import bootstrap, Worker, CleanupError
        scope = bootstrap(home, mode=mode)
        from hermes_cli.plugins import PluginContext, PluginManifest, get_plugin_manager
        manager = get_plugin_manager()
        ctx = PluginContext(PluginManifest(name='voice-process-fixture', path=str(P)), manager)
        effects, calls = [], []
        original = 'PROCESS-PRIVATE-' + uuid.uuid4().hex + ' TOKEN-PRIVATE-' + uuid.uuid4().hex
        def model(**kw):
            from openai.types.chat import ChatCompletion
            request = kw['request']
            calls.append(deepcopy(request))
            if action == 'started':
                status({'stage':'started'})
                assert sys.stdin.readline() == 'stop\n'
            msg = {'role':'assistant','content':original if action == 'seed' else 'offline reply'}
            finish = 'stop'
            if action in ('seed','followup') and len(calls) == 1:
                msg = {'role':'assistant','content':None,'tool_calls':[{'id':'fixture-call' if action == 'seed' else 'new-call','type':'function','function':{'name':'tool_call','arguments':json.dumps({'calls':[{'name':'voice_worker_fixture','arguments':{}}]})}}]}
                finish = 'tool_calls'
            return ChatCompletion.model_validate({'id':'fixture','object':'chat.completion','created':0,'model':'fixture','choices':[{'index':0,'message':msg,'finish_reason':finish}],'usage':{'prompt_tokens':1,'completion_tokens':1,'total_tokens':2}})
        ctx.register_middleware('llm_execution', model)
        ctx.register_tool(name='voice_worker_fixture',toolset='voice_worker_fixture',schema={'name':'voice_worker_fixture','description':'Offline only','parameters':{'type':'object','properties':{}}},handler=lambda args, **kw: effects.append(1) or '{"success":true}')
        if action == 'followup':
            # New process has no restored grant. Synthetic expired policy denies.
            ctx.register_hook('pre_tool_call', lambda **kw: {'action':'block','message':'Expired synthetic approval'})
        options = {'model':'fixture','provider':'openai','api_key':'offline-placeholder','base_url':'https://offline.invalid/v1','max_iterations':3}
        context = ctx
        if action == 'poison':
            from hermes_cli.plugins_ledger import PluginRegistration
            class PoisonContext:
                def register_hook(self, name, callback):
                    handle = ctx.register_hook(name, callback)
                    def fail():
                        raise RuntimeError('fixture release failure')
                    return PluginRegistration('hook', name, fail)
            context = PoisonContext()
        worker = Worker(scope, context, model_options=options, enabled_toolsets=['voice_worker_fixture'],allowed_tools={'voice_worker_fixture'})
        async def run():
            if action in ('idle','hung'):
                status({'stage':'ready'})
                assert sys.stdin.readline() == 'stop\n'
                if action == 'hung': sys.stdin.readline()  # intentionally needs OS kill
            elif action == 'poison':
                for _ in range(2):
                    try: await worker.shutdown()
                    except CleanupError as exc: assert exc.restart_required and worker.cleanup_state == 'cleanup_failed'
                    else: raise AssertionError('poison became clean')
                try: Worker(scope, ctx, model_options=options)
                except RuntimeError: pass
                else: raise AssertionError('singleton lost')
                status({'stage':'restart_required'})
                assert sys.stdin.readline() == 'stop\n'
                return
            elif action == 'followup':
                with worker.store.open() as box:
                    row = box.get('r1'); assert row.state == 'completed'
                    prior = box.history('lane').messages
                    worker.receive('r1',row.original_text,'lane')
                assert await worker.start('lane') is None  # completed duplicate not replayed
                worker.receive('r2','Continue without repeating my original request','lane')
                assert (await worker.start('lane')).engine_turn_verified
                messages = calls[0]['messages']
                # Inspect the previous turn in the actual second-process model payload.
                observed = messages[-len(prior)-1:-1]
                # AIAgent rebuilds stored leaf calls into the wire tool_call
                # transport. Text/result payloads stay exact; assert the wire
                # call's ID/name/parsed arguments separately below.
                for actual, saved in zip(observed, prior, strict=True):
                    assert actual['role'] == saved['role']
                    for key in ('content','tool_call_id'):
                        assert actual.get(key) == saved.get(key)
                assert messages[-1] == {'role':'user','content':'Continue without repeating my original request'}
                historical = [c for m in observed for c in (m.get('tool_calls') or [])]
                assert len(historical) == 1 and historical[0]['id'] == 'fixture-call'
                assert historical[0]['function']['name'] == 'tool_call'
                assert json.loads(historical[0]['function']['arguments']) == {'calls':[{'name':'voice_worker_fixture','arguments':{}}]}
                results = [m for m in prior if m['role'] == 'tool']
                assert len(results) == 1 and results[0]['tool_call_id'] == 'fixture-call'
                assert json.loads(results[0]['content']) == {'success':True}
                assert effects == []
                with worker.store.open() as box:
                    assert box.history('lane').turns == 2
                    assert box.history('lane').delivery_status == 'untracked'
            elif action == 'unknown':
                with worker.store.open() as box:
                    row = box.get('r1'); assert row.state == 'unknown_outcome'
                    worker.receive('r1',row.original_text,'lane')
                    worker.receive('r2','blocked followup','lane')
                assert await worker.start('lane') is None
                assert not calls and not effects
                with worker.store.open() as box:
                    assert box.get('r2').state == 'received' and box.history('lane').turns == 0
            elif action == 'duplicate':
                with worker.store.open() as box:
                    row = box.get('r1'); assert row.state == 'received'
                worker.receive('r1',row.original_text,'lane')
                assert (await worker.start('lane')).engine_turn_verified
                assert await worker.start('lane') is None and len(calls) == 1
            else:
                worker.receive('r1',original,'lane')
                if action == 'before_ack':
                    # COMMIT returned; no Accepted ACK crosses the pipe.
                    status({'stage':'committed_no_ack'})
                    assert sys.stdin.readline() == 'stop\n'
                elif action == 'dispatching':
                    with worker.store.open() as box: assert box.claim('lane') is not None
                    status({'stage':'dispatching'})
                    assert sys.stdin.readline() == 'stop\n'
                else:
                    assert (await worker.start('lane')).engine_turn_verified
                    if action == 'seed': assert effects == [1]
            await worker.shutdown()
        asyncio.run(run())
        if action != 'poison': manager.unload()
        status({'stage':'done'})
    except BaseException as exc:
        # Metadata only; never serialize exception text or locals.
        import traceback
        status({'stage':'failed','line':traceback.extract_tb(exc.__traceback__)[-1].lineno})
        return 1
    return 0


class SchemaTests(unittest.TestCase):
    def fixture(self, delivery):
        from voice_history import HistoryInbox
        from voice_delivery import DeliveryInbox
        temp = tempfile.TemporaryDirectory(prefix='voice-schema-', dir=SCRATCH)
        self.addCleanup(temp.cleanup)
        home = Path(temp.name)
        with (DeliveryInbox if delivery else HistoryInbox)(
                profile_dir=home, profile_id='voice-worker', db_path=home/'voice.sqlite'):
            pass
        return home

    def snapshot(self, home):
        from contextlib import closing
        with closing(sqlite3.connect((home/'voice.sqlite').as_uri()+'?mode=ro', uri=True)) as db:
            return tuple(db.iterdump())

    def mutate(self, home, sql):
        from contextlib import closing
        with closing(sqlite3.connect(home/'voice.sqlite')) as db, db:
            db.executescript(sql)

    def test_exact_old_and_opt_in_delivery_read_only(self):
        from voice_process import _schema
        import hashlib
        from contextlib import closing
        pins = {False:'b2bb0c4365a48eb0dcf9fafa7b17a8b2eea187784afc163aea567c936c400176',
                True:'af694aaee52ab4a3fbcdbb3ca0e67d50b7121a68154fd16f6ea0483e32db4f23'}
        for delivery in (False, True):
            with self.subTest(delivery=delivery):
                home = self.fixture(delivery)
                before = self.snapshot(home)
                with closing(sqlite3.connect(home/'voice.sqlite')) as db:
                    objects = db.execute('SELECT type,name,sql FROM sqlite_master ORDER BY type,name').fetchall()
                self.assertEqual(hashlib.sha256(json.dumps(objects,separators=(',',':')).encode()).hexdigest(), pins[delivery])
                _schema(home)
                self.assertEqual(self.snapshot(home), before)

    def test_unknown_or_tampered_schema_rejected_without_repair(self):
        from voice_process import _schema, ProcessError
        changes = [
            'CREATE TABLE unexpected (value TEXT)',
            'ALTER TABLE requests ADD COLUMN unexpected TEXT',
            'CREATE INDEX unexpected ON requests(lane)',
            'CREATE TRIGGER unexpected AFTER INSERT ON requests BEGIN SELECT 1; END',
            'DROP INDEX lane_owner',
            'DROP TABLE voice_history',
            # Valid SQLite schema with changed constraints must still fail the pin.
            "PRAGMA writable_schema=ON; UPDATE sqlite_master SET sql=replace(sql, 'text_limit INTEGER NOT NULL', 'text_limit INTEGER') WHERE name='metadata'; PRAGMA writable_schema=OFF;",
        ]
        for delivery in (False, True):
            for sql in changes + ([
                'ALTER TABLE voice_delivery ADD COLUMN unexpected TEXT',
                'CREATE INDEX unexpected_delivery ON voice_delivery(response_hash)',
                'DROP TABLE voice_delivery_settings',
                'DROP TABLE voice_delivery',
                "PRAGMA writable_schema=ON; UPDATE sqlite_master SET sql=replace(sql, 'CHECK(version=1)', 'CHECK(version>0)') WHERE name='voice_delivery'; PRAGMA writable_schema=OFF;",
            ] if delivery else []):
                with self.subTest(delivery=delivery, sql=sql):
                    home = self.fixture(delivery)
                    original = self.snapshot(home)
                    self.mutate(home, sql)
                    before = self.snapshot(home)
                    self.assertNotEqual(before, original)
                    with self.assertRaises(ProcessError): _schema(home)
                    self.assertEqual(self.snapshot(home), before)

    def test_settings_rejected_without_repair(self):
        from voice_process import _schema, ProcessError
        for delivery in (False, True):
            changes = ['UPDATE metadata SET version=99',
                       'UPDATE voice_history_settings SET version=99']
            if delivery:
                changes += ['UPDATE voice_delivery_settings SET version=99',
                            'UPDATE voice_delivery_settings SET response_bytes=65535',
                            'DELETE FROM voice_delivery_settings']
            for sql in changes:
                with self.subTest(delivery=delivery, sql=sql):
                    home = self.fixture(delivery)
                    self.mutate(home, sql)
                    before = self.snapshot(home)
                    with self.assertRaises(ProcessError): _schema(home)
                    self.assertEqual(self.snapshot(home), before)


class ProcessTests(unittest.TestCase):
    def setUp(self):
        from voice_process import FixtureController
        self.temp = tempfile.TemporaryDirectory(prefix='voice-process-',dir=SCRATCH)
        self.home = Path(self.temp.name)
        self.control = FixtureController(self.home, CORE)
        self.addCleanup(self.temp.cleanup)
        self.addCleanup(self.control.close)

    def run_child(self, action, mode='resume'):
        self.control.start(action, mode=mode)
        self.assertEqual(self.control.status(timeout=75), {'stage':'done'})
        self.assertEqual(self.control.wait(timeout=15),0)
        self.privacy()

    def privacy(self):
        if not (self.home/'voice.sqlite').exists(): return
        with sqlite3.connect(self.home/'voice.sqlite') as db:
            markers = [m.encode() for (text,) in db.execute('SELECT original_text FROM requests') for m in text.split() if 'PRIVATE-' in m]
        for path in self.home.rglob('*'):
            if path.is_file() and path.name not in {'voice.sqlite','voice.sqlite-journal','voice.sqlite-wal','voice.sqlite-shm'}:
                for marker in markers: self.assertNotIn(marker,path.read_bytes(),str(path))
        state = self.home/'state.db'
        if state.exists():
            with sqlite3.connect(state) as db:
                for table in ('sessions','messages','system_prompts','async_delegations','session_turn_leases'):
                    self.assertEqual(db.execute('SELECT COUNT(*) FROM '+table).fetchone()[0],0)
        self.assertFalse(list(self.home.rglob('*approval*')))
        self.assertFalse(list(self.home.rglob('session_*.json')))

    def test_graceful_new_process_continuity(self):
        self.run_child('seed','init')
        first = self.control.pid
        self.run_child('followup')
        self.assertNotEqual(first,self.control.pid)

    def _forced_death(self, state):
        from voice_process import ProcessError, recover_profile
        from voice_inbox import Busy
        self.control.start(state,mode='init')
        self.assertEqual(self.control.status(timeout=75),{'stage':state})
        with self.assertRaises(ProcessError): self.control.recover()
        with self.assertRaises(Busy): recover_profile(self.home)
        self.assertLess(self.control.kill_and_wait(timeout=15),0)
        self.assertEqual(self.control.recover(),1)
        self.assertEqual(self.control.recover(),0)
        self.run_child('unknown')

    def test_dispatching_death_and_live_exclusion(self):
        self._forced_death('dispatching')

    def test_started_death_and_live_exclusion(self):
        self._forced_death('started')

    def test_commit_before_ack(self):
        self.control.start('before_ack',mode='init')
        self.assertEqual(self.control.status(timeout=75),{'stage':'committed_no_ack'})
        self.control.kill_and_wait(timeout=15)
        self.assertEqual(self.control.recover(),0)
        self.run_child('duplicate')

    def test_poison_requires_process_exit(self):
        from voice_process import recover_profile
        from voice_inbox import Busy
        self.control.start('poison',mode='init')
        self.assertEqual(self.control.status(timeout=75),{'stage':'restart_required'})
        with self.assertRaises(Busy): recover_profile(self.home)
        self.control.kill_and_wait(timeout=15)
        self.assertEqual(self.control.recover(),0)
        self.run_child('seed')

    def test_live_second_controller(self):
        from voice_process import FixtureController, ProcessError
        self.control.start('idle',mode='init')
        self.assertEqual(self.control.status(timeout=75),{'stage':'ready'})
        with FixtureController(self.home,CORE) as other:
            with self.assertRaises(ProcessError): other.start('idle',mode='resume')
        self.assertEqual(self.control.stop(timeout=15),0)

    def test_shutdown_timeout_reaps_only_owned_child(self):
        from voice_process import ProcessError
        self.control.start('hung',mode='init')
        self.assertEqual(self.control.status(timeout=75),{'stage':'ready'})
        with self.assertRaises(ProcessError): self.control.stop(timeout=.1)
        self.assertLess(self.control.wait(timeout=1),0)
        self.assertEqual(self.control.recover(),0)
        self.run_child('seed')

    def test_recovery_releases_exclusive_handles_on_failure(self):
        from voice_history import HistoryStore
        from voice_inbox import Busy
        self.run_child('seed','init')
        with HistoryStore(self.home,'voice-worker',self.home/'voice.sqlite').open():
            with self.assertRaises(Busy): self.control.recover()
        self.assertEqual(self.control.recover(),0)
        self.run_child('followup')

    def test_child_lock_not_only_parent_preflight(self):
        from voice_worker import clean_environment
        import subprocess
        self.control.start('idle',mode='init')
        self.assertEqual(self.control.status(timeout=75),{'stage':'ready'})
        read,write=os.pipe()
        owned=None
        try:
            owned=subprocess.Popen([str(CORE/'venv/bin/python'),'-B',str(Path(__file__).resolve()),
                                    '--child','resume','idle',str(write)],
                                   cwd=self.home,env=clean_environment(self.home,CORE),
                                   pass_fds=(write,),stdin=subprocess.DEVNULL,
                                   stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
            os.close(write); write=None
            self.assertEqual(owned.wait(timeout=15),1)
            self.assertEqual(json.loads(os.read(read,512))['stage'],'failed')
        finally:
            if owned is not None and owned.poll() is None:
                owned.kill(); owned.wait(timeout=15)
            os.close(read)
            if write is not None: os.close(write)
        self.assertEqual(self.control.stop(timeout=15),0)
        self.assertEqual(self.control.recover(),0)

    def test_explicit_init_resume_refuse_adoption(self):
        from voice_process import ProcessError
        with self.assertRaises(ProcessError): self.control.start('idle',mode='resume')
        self.run_child('seed','init')
        manifest=(self.home/'voice-profile.json').read_bytes()
        with self.assertRaises(ProcessError): self.control.start('seed',mode='init')
        self.assertEqual((self.home/'voice-profile.json').read_bytes(),manifest)
        (self.home/'voice-profile.json').unlink()
        with self.assertRaises(ProcessError): self.control.start('idle',mode='resume')
        with self.assertRaises(ProcessError): self.control.start('idle',mode='init')

    def test_resume_refusals(self):
        from voice_process import validate_profile, ProcessError
        self.run_child('seed','init')
        manifest = self.home/'voice-profile.json'
        saved = manifest.read_bytes()
        for key,value in [('version',99),('profile_id','wrong'),('inbox_version',99),('history_version',99),('home','/wrong')]:
            value_dict = json.loads(saved); value_dict[key]=value
            manifest.write_text(json.dumps(value_dict))
            with self.assertRaises(ProcessError): validate_profile(self.home)
            manifest.write_bytes(saved)
        for target in (manifest,self.home/'config.yaml',self.home/'voice.sqlite',self.home/'logs'):
            mode = target.stat().st_mode & 0o777
            target.chmod(0o777)
            with self.assertRaises(ProcessError): validate_profile(self.home)
            target.chmod(mode)
        config = self.home/'config.yaml'; old = config.read_bytes(); config.write_text('memory: {}')
        with self.assertRaises(ProcessError): validate_profile(self.home)
        config.write_bytes(old)
        evil = self.home/'.env'; evil.write_text('synthetic')
        with self.assertRaises(ProcessError): validate_profile(self.home)
        evil.unlink()
        manifest.unlink()
        with self.assertRaises(ProcessError): validate_profile(self.home)

    def test_schema_refusal_no_migration(self):
        from voice_process import validate_profile, ProcessError
        self.run_child('seed','init')
        for table in ('metadata','voice_history_settings'):
            with sqlite3.connect(self.home/'voice.sqlite') as db: db.execute('UPDATE '+table+' SET version=99')
            with self.assertRaises(ProcessError): validate_profile(self.home)
            with sqlite3.connect(self.home/'voice.sqlite') as db:
                self.assertEqual(db.execute('SELECT version FROM '+table).fetchone()[0],99)
                db.execute('UPDATE '+table+' SET version=1')
        with sqlite3.connect(self.home/'voice.sqlite') as db: db.execute('DROP TABLE voice_history')
        with self.assertRaises(ProcessError): validate_profile(self.home)
        with sqlite3.connect(self.home/'voice.sqlite') as db:
            self.assertIsNone(db.execute("SELECT name FROM sqlite_master WHERE name='voice_history'").fetchone())

    def test_path_refusals(self):
        from voice_process import validate_profile, ProcessError
        with self.assertRaises(ProcessError): validate_profile(self.home)
        self.run_child('seed','init')
        with tempfile.TemporaryDirectory(dir=SCRATCH) as tmp:
            alias=Path(tmp)/'alias'; alias.symlink_to(self.home,target_is_directory=True)
            with self.assertRaises(ProcessError): validate_profile(alias)
            import shutil
            copied=Path(tmp)/'copy'; shutil.copytree(self.home,copied)
            with self.assertRaises(ProcessError): validate_profile(copied)
        db=self.home/'voice.sqlite'; saved=db.with_name('held.sqlite')
        db.rename(saved); db.symlink_to(saved)
        with self.assertRaises(ProcessError): validate_profile(self.home)
        db.unlink(); saved.rename(db)
        # Android disallows hard-link creation here (os.link absent, ln EPERM).
        # st_nlink refusal exists, but this suite does not claim a live hardlink test.


if __name__ == '__main__':
    if len(sys.argv) == 5 and sys.argv[1] == '--child':
        raise SystemExit(child(sys.argv[2],sys.argv[3],int(sys.argv[4])))
    unittest.main(verbosity=2)
