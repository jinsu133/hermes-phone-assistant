"""Source opt-in ownership contracts; private HOME, no live runtime or core patch."""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
import unittest

P = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(P))
HOME = Path(os.environ['HOME']).resolve()
SCRATCH = Path('/data/data/com.termux/files/home/.hermes/cache/scratch')
if not HOME.is_relative_to(SCRATCH) or any(os.environ.get(k) != str(HOME) for k in ('HERMES_HOME', 'TMPDIR')):
    raise RuntimeError('private HOME required')

OWNER = dict(profile_id='p', lane='l', request_id='r', attempt_id='a', task_id='task')
GOAL = 'bounded goal'
RAW = b'{"screen":"READY"}'

class DirectHandler(unittest.TestCase):
    def test_missing_or_foreign_binding_cannot_use_last_request(self):
        import task_continuity_fixture as f
        class Handle:
            active = True
        for supplied in ({}, {'session_id':'foreign'}, {'session_id':'s','turn_id':'foreign'},
                         {'session_id':'s','task_id':'foreign'}):
            with self.subTest(supplied=supplied):
                home = Path(tempfile.mkdtemp(dir=HOME))
                r = f.Runtime(home, 'success')
                try:
                    r.registrations = [Handle() for _ in range(7)]
                    r.bound_turn = ('s','t')
                    r.requests['api'] = dict(present=True,revision=0,session='s',turn='t',call_count=1)
                    r.last_request = 'api'
                    before = r.state.snapshot()
                    with self.assertRaises(f.StateError):
                        r.handler('open', {}, **supplied)
                    self.assertEqual(r.state.snapshot(), before)
                    self.assertFalse((home/'effects.log').exists())
                finally:
                    r.state.close()

    def test_handler_rechecks_scoped_binding_without_pretool(self):
        import task_continuity_fixture as f
        class Handle: active = True
        home = Path(tempfile.mkdtemp(dir=HOME))
        r = f.Runtime(home, 'success')
        try:
            r.registrations = [Handle() for _ in range(7)]
            r.steps.bind('s','t')
            binding = dict(r.identity,session_id='s',turn_id='t',api_request_id='api',revision=0)
            r.steps.observe(binding)
            cap = r.steps.admit('open',binding)
            token = r.execution.set((cap,binding,'open','engine-task'))
            try:
                good = dict(session_id='s',task_id='engine-task')
                bads = [{}, {'session_id':'s'}, {'task_id':'engine-task'}]
                bads += [dict(good,**{key:'foreign'}) for key in binding]
                for kw in bads:
                    with self.subTest(kw=kw), self.assertRaises(f.StateError): r.handler('open',{},**kw)
                self.assertFalse((home/'effects.log').exists())
                r.handler('open',{},**good)
                with self.assertRaises(f.StateError): r.handler('open',{},**good)
                self.assertEqual((home/'effects.log').read_text(),'open\n')
            finally: r.execution.reset(token)
            with self.assertRaises(f.StateError): r.handler('read',{},session_id='s',task_id='engine-task')
        finally: r.state.close()

    def test_late_checkpoint_ack_after_cancel_never_restores_authority(self):
        import task_continuity_fixture as f
        home = Path(tempfile.mkdtemp(dir=HOME))
        r = f.Runtime(home, 'success')
        try:
            identity = {k:v for k,v in r.identity.items() if k != 'task_id'}
            identity.update(session_id='s', turn_id='t')
            with r.checkpoints.open() as box:
                event,digest = box.issue(identity,[{'role':'user','content':'synthetic'}])
                r.notice = box.notices(r.owner)[0]
            r.cancel()
            r.notice_ready.set()
            r.ack()
            self.assertIsNone(r.ack_failure)
            self.assertTrue(r.ack_done.is_set())
            with r.checkpoints.open() as box:
                receipt = dict(identity=identity,event_id=event,content_digest=digest,outcome='played',synthetic=True)
                self.assertFalse(box.acknowledge(receipt))
                self.assertEqual(box.notices(r.owner)[0]['state'],'failed')
            with self.assertRaises(f.StateError): r.handler('open',{})
            self.assertFalse((home/'effects.log').exists())
        finally: r.state.close()

class Source(unittest.TestCase):
    def setUp(self):
        import voice_task_state as m
        self.m = m
        self.home = Path(tempfile.mkdtemp(dir=HOME))
        self.state = m.TaskState(self.home, OWNER, goal=GOAL, artifacts={'open':('app.json',RAW),'read':('app.json',RAW)}, prerequisites={'read':'open'})
        self.addCleanup(self.state.close)
        self.gate = m.StepOwner(self.state)
        self.gate.bind('session','turn')
        self.binding = dict(OWNER, session_id='session', turn_id='turn', api_request_id='api', revision=0)
        self.gate.observe(self.binding)
        self.effects = []

    def effect(self):
        self.effects.append('open')
        (self.home/'app.json').write_bytes(RAW)

    def test_exact_binding_each_field_missing_wrong_and_boolean_revision(self):
        for key in self.binding:
            for value in (None, 'foreign'):
                bad = dict(self.binding)
                if value is None: bad.pop(key)
                else: bad[key] = value
                with self.subTest(key=key, value=value):
                    with self.assertRaises(self.m.StateError):
                        self.gate.admit('open', bad)
        bad = dict(self.binding, revision=False)
        with self.assertRaises(self.m.StateError): self.gate.admit('open', bad)
        self.assertEqual(self.state.snapshot()['revision'],0)
        self.assertEqual(self.effects,[])

    def test_capability_rechecks_binding_at_effect_and_commit(self):
        cap = self.gate.admit('open', self.binding)
        for key in self.binding:
            bad = dict(self.binding, **{key:'foreign'})
            with self.subTest(key=key):
                with self.assertRaises(self.m.StateError): self.gate.effect(cap,bad,self.effect)
        with self.assertRaises(self.m.StateError): self.gate.effect(object(),self.binding,self.effect)
        self.assertEqual(self.effects,[])
        self.gate.effect(cap,self.binding,self.effect)
        with self.assertRaises(self.m.StateError): self.gate.commit(cap,dict(self.binding,turn_id='foreign'))
        self.gate.commit(cap,self.binding)
        with self.assertRaises(self.m.StateError): self.gate.effect(cap,self.binding,self.effect)
        self.assertEqual(self.effects,['open'])

    def test_concurrent_admission_one_winner(self):
        barrier = threading.Barrier(3)
        won, lost = [], []
        def run():
            barrier.wait(3)
            try: won.append(self.gate.admit('open',self.binding))
            except self.m.StateError: lost.append(True)
        threads = [threading.Thread(target=run) for _ in range(2)]
        for t in threads: t.start()
        try: barrier.wait(3)
        finally:
            for t in threads: t.join(4)
        self.assertFalse(any(t.is_alive() for t in threads))
        self.assertEqual((len(won),len(lost)),(1,1))
        self.gate.effect(won[0],self.binding,self.effect)
        self.gate.commit(won[0],self.binding)
        self.assertEqual(self.effects,['open'])

    def test_cancel_before_effect_no_effect(self):
        cap = self.gate.admit('open', self.binding)
        self.gate.cancel()
        with self.assertRaises(self.m.StateError): self.gate.effect(cap,self.binding,self.effect)
        self.assertEqual(self.effects,[])
        self.assertEqual(self.state.snapshot()['steps']['open'],'pending')
        with self.assertRaises(self.m.StateError): self.gate.bind('session','other')

    def test_cancel_after_effect_before_commit_unknown_no_replay(self):
        cap = self.gate.admit('open',self.binding)
        done, release = threading.Event(), threading.Event()
        failures = []
        def run():
            try:
                self.gate.effect(cap,self.binding,self.effect)
                done.set()
                if not release.wait(3): raise AssertionError('release timeout')
                self.gate.commit(cap,self.binding)
            except self.m.StateError: failures.append('revoked')
        t = threading.Thread(target=run); t.start()
        try:
            self.assertTrue(done.wait(3))
            self.gate.cancel()
        finally: release.set(); t.join(4)
        self.assertFalse(t.is_alive())
        self.assertEqual(failures,['revoked'])
        self.assertEqual(self.state.snapshot()['steps']['open'],'unknown')
        self.assertEqual(self.effects,['open'])
        with self.assertRaises(self.m.StateError): self.state.begin('open',self.state.snapshot()['revision'])

    def test_effect_failure_remains_unknown_on_reopen(self):
        cap = self.gate.admit('open',self.binding)
        def fail():
            self.effect()
            raise ValueError('after effect')
        with self.assertRaises(ValueError): self.gate.effect(cap,self.binding,fail)
        self.state.close()
        new = self.m.TaskState(self.home,OWNER,goal=GOAL,artifacts={'open':('app.json',RAW),'read':('app.json',RAW)},prerequisites={'read':'open'})
        self.addCleanup(new.close)
        self.assertEqual(new.snapshot()['steps']['open'],'unknown')
        with self.assertRaises(self.m.StateError): new.begin('open',new.snapshot()['revision'])
        with self.assertRaises(self.m.StateError): self.gate.commit(cap,self.binding)
        self.assertEqual(self.effects,['open'])

    def test_handoff_revokes_old_cap_and_late_ack(self):
        cap = self.gate.admit('open',self.binding)
        self.state.close()
        new = self.m.TaskState(self.home,OWNER,goal=GOAL,artifacts={'open':('app.json',RAW),'read':('app.json',RAW)},prerequisites={'read':'open'})
        self.addCleanup(new.close)
        gate = self.m.StepOwner(new); gate.bind('session','new-turn')
        before = new.snapshot()
        with self.assertRaises(self.m.StateError): self.gate.effect(cap,self.binding,self.effect)
        with self.assertRaises(self.m.StateError): gate.commit(cap,self.binding)
        self.assertEqual(new.snapshot(),before)
        self.assertEqual(self.effects,[])
        self.state.close()
        with self.assertRaises(BlockingIOError): self.m.TaskState(self.home,OWNER,goal=GOAL,artifacts={'open':('app.json',RAW),'read':('app.json',RAW)},prerequisites={'read':'open'})

    def test_verified_evidence_immutable_and_not_model_promotable(self):
        with self.assertRaises(self.m.StateError):
            self.state.update(0,lambda s:(s['steps'].update(open='verified'),s['evidence'].update(open=self.state.artifact_evidence('open'))))
        cap = self.gate.admit('open',self.binding)
        self.gate.effect(cap,self.binding,self.effect); self.gate.commit(cap,self.binding)
        before = self.state.snapshot()
        with self.assertRaises(self.m.StateError):
            self.state.update(2,lambda s:(s['steps'].update(open='pending'),s['evidence'].clear()))
        before['evidence']['open']['sha256']='foreign'
        self.assertNotEqual(self.state.snapshot(),before)

    def test_reentrant_commit_cannot_verify_before_callback_returns(self):
        cap = self.gate.admit('open',self.binding)
        def effect():
            self.effect()
            with self.assertRaises(self.m.StateError): self.gate.commit(cap,self.binding)
        self.gate.effect(cap,self.binding,effect)
        self.gate.commit(cap,self.binding)
        self.assertEqual(self.state.snapshot()['steps']['open'],'verified')

    def test_failed_callback_cannot_be_late_committed(self):
        cap = self.gate.admit('open',self.binding)
        def effect():
            self.effect()
            raise ValueError('effect uncertainty')
        with self.assertRaises(ValueError): self.gate.effect(cap,self.binding,effect)
        with self.assertRaises(self.m.StateError): self.gate.commit(cap,self.binding)
        self.gate.cancel()
        self.assertEqual(self.state.snapshot()['steps']['open'],'unknown')

    def test_revision_budget_reserved_before_effect(self):
        for revision in range(63): self.state.update(revision,lambda s:None)
        binding = dict(self.binding,revision=63)
        self.gate.observe(binding)
        with self.assertRaises(self.m.StateError): self.gate.admit('open',binding)
        self.assertEqual(self.effects,[])

    def test_revision_change_invalidates_admission(self):
        cap = self.gate.admit('open',self.binding)
        self.state.update(0,lambda s:s.update(approval_waiting=True))
        with self.assertRaises(self.m.StateError): self.gate.effect(cap,self.binding,self.effect)
        self.assertEqual(self.effects,[])

    def test_second_gate_and_foreign_attempt_reopen_refused(self):
        with self.assertRaises(self.m.StateError): self.m.StepOwner(self.state)
        self.state.close()
        with self.assertRaises(self.m.StateError):
            self.m.TaskState(self.home,dict(OWNER,attempt_id='new'),goal=GOAL,
                artifacts={'open':('app.json',RAW),'read':('app.json',RAW)},prerequisites={'read':'open'})

    def test_close_contends_at_effect_boundary_and_retains_lease(self):
        cap = self.gate.admit('open',self.binding)
        entered, release, contended = threading.Event(),threading.Event(),threading.Event()
        errors = []
        def effect():
            self.effect(); entered.set()
            if not release.wait(4): raise AssertionError('effect release timeout')
        def worker():
            try: self.gate.effect(cap,self.binding,effect)
            except BaseException as exc: errors.append(exc)
        def closer():
            try:
                acquired = self.state.lock.acquire(blocking=False)
                if acquired:
                    self.state.lock.release()
                    raise AssertionError('expected actual effect lock contention')
                contended.set()
                self.state.close()
            except BaseException as exc: errors.append(exc)
        t=threading.Thread(target=worker); c=threading.Thread(target=closer); t.start()
        try:
            self.assertTrue(entered.wait(3)); c.start(); self.assertTrue(contended.wait(3))
            with self.assertRaises(BlockingIOError):
                self.m.TaskState(self.home,OWNER,goal=GOAL,artifacts={'open':('app.json',RAW),'read':('app.json',RAW)},prerequisites={'read':'open'})
        finally:
            release.set(); t.join(5)
            if c.ident is not None: c.join(5)
        self.assertFalse(t.is_alive() or c.is_alive())
        self.assertEqual(errors,[])
        with self.assertRaises(self.m.StateError): self.gate.commit(cap,self.binding)
        self.assertEqual(self.effects,['open'])

    def test_clean_process_unknown_recovery_has_no_automatic_replay(self):
        cap = self.gate.admit('open',self.binding)
        self.gate.effect(cap,self.binding,self.effect)
        self.state.close()
        code = ('from voice_task_state import TaskState,StepOwner,StateError; '
                's=TaskState('+repr(str(self.home))+','+repr(OWNER)+',goal='+repr(GOAL)+',artifacts='+repr({'open':('app.json',RAW),'read':('app.json',RAW)})+",prerequisites={'read':'open'}); "
                "assert s.snapshot()['steps']['open']=='unknown'; "
                "g=StepOwner(s); assert g.bound is None\n"
                "try: s.begin('open',s.snapshot()['revision'])\n"
                "except StateError: print('unknown-replay-refused')\n"
                "else: raise AssertionError('replayed')\n"
                "s.close()")
        result = subprocess.run([sys.executable,'-B','-c',code],env=dict(os.environ,PYTHONPATH=str(P)),cwd=HOME,capture_output=True,text=True,timeout=20)
        self.assertEqual(result.returncode,0,result.stderr)
        self.assertEqual(result.stdout.strip(),'unknown-replay-refused')
        self.assertEqual(self.effects,['open'])

    def test_clean_process_reopen_preserves_evidence_and_pending_no_authority(self):
        cap = self.gate.admit('open',self.binding)
        self.gate.effect(cap,self.binding,self.effect); self.gate.commit(cap,self.binding)
        self.state.update(2,lambda s:s.update(approval_waiting=True))
        before = self.state.snapshot(); self.state.close()
        code = ('import json; from voice_task_state import TaskState,StepOwner,StateError; '
                's=TaskState('+repr(str(self.home))+','+repr(OWNER)+',goal='+repr(GOAL)+',artifacts='+repr({'open':('app.json',RAW),'read':('app.json',RAW)})+",prerequisites={'read':'open'}); "
                'print(json.dumps(s.snapshot())); g=StepOwner(s); '
                'print(g.bound is None); s.close()')
        env = dict(os.environ, PYTHONPATH=str(P))
        result = subprocess.run([sys.executable,'-B','-c',code],env=env,cwd=HOME,capture_output=True,text=True,timeout=20)
        self.assertEqual(result.returncode,0,result.stderr)
        lines=result.stdout.splitlines()
        self.assertEqual(json.loads(lines[0]),before)
        self.assertEqual(lines[1],'True')

class SpecificationReopen(unittest.TestCase):
    """Trusted caller contract, not authentication against arbitrary Python code."""
    def spec(self):
        return dict(owner=dict(OWNER, metadata={'number':1, 'items':['a','b']}),
                    goal=GOAL, artifacts={'open':('app.json',RAW),'read':('app.json',RAW)},
                    prerequisites={'read':'open'})

    def create(self, spec, inflight=False):
        from voice_task_state import TaskState
        home = Path(tempfile.mkdtemp(dir=HOME))
        state = TaskState(home, **spec)
        try:
            if inflight: state.begin('open',0)
        finally: state.close()
        return home

    def database(self, home):
        import sqlite3
        with sqlite3.connect(f'file:{home / "task.sqlite"}?mode=ro',uri=True) as db:
            dump = tuple(db.iterdump())
        return dump, {f.name:f.read_bytes() for f in home.glob('task.sqlite*')}

    def child(self, home, spec, accepted=False):
        code = ('import json; from voice_task_state import TaskState,StateError\n'
                'try: s=TaskState('+repr(str(home))+',**'+repr(spec)+')\n'
                "except StateError: print('refused')\n"
                "else:\n print(json.dumps(s.snapshot(),sort_keys=True)); s.close()\n")
        result = subprocess.run([sys.executable,'-B','-c',code],cwd=HOME,
            env=dict(os.environ,PYTHONPATH=str(P)),capture_output=True,text=True,timeout=20)
        self.assertEqual(result.returncode,0,result.stderr)
        if accepted: return json.loads(result.stdout)
        self.assertEqual(result.stdout.strip(),'refused')

    def test_fresh_process_changed_spec_refused_without_repair_or_db_mutation(self):
        import copy
        base = self.spec()
        changes = []
        for name in ('target','content','dependency','goal','owner','bool','float','nested-order','nested-type','steps'):
            spec = copy.deepcopy(base)
            if name == 'target': spec['artifacts']['read'] = ('different.json',RAW)
            elif name == 'content': spec['artifacts']['read'] = ('app.json',b'changed')
            elif name == 'dependency': spec['prerequisites'] = {}
            elif name == 'goal': spec['goal'] += '!'
            elif name == 'owner': spec['owner']['attempt_id'] = 'other'
            elif name == 'bool': spec['owner']['metadata']['number'] = True
            elif name == 'float': spec['owner']['metadata']['number'] = 1.0
            elif name == 'nested-order': spec['owner']['metadata']['items'].reverse()
            elif name == 'nested-type': spec['owner']['metadata']['items'] = ('a','b')
            else:
                spec['artifacts']['extra'] = ('extra.json',b'')
            changes.append((name,spec))
        for inflight in (False,True):
            home = self.create(base,inflight)
            before = self.database(home)
            for name,spec in changes:
                with self.subTest(inflight=inflight,change=name):
                    try: self.child(home,spec)
                    finally: self.assertEqual(self.database(home),before)

    def test_fresh_process_matching_key_order_and_pair_container_reopen(self):
        base = self.spec(); home = self.create(base)
        before = self.database(home)
        reordered = dict(base, owner=dict(reversed(list(base['owner'].items()))),
                         artifacts={k:list(v) for k,v in reversed(list(base['artifacts'].items()))})
        reordered['owner']['metadata'] = {'items':['a','b'],'number':1}
        for spec in (reordered,base,reordered):
            state = self.child(home,spec,accepted=True)
            self.assertEqual(state['revision'],0)
            self.assertEqual(self.database(home),before)
        self.assertEqual(state['schema_version'],2)
        self.assertEqual(state['spec_version'],1)
        self.assertRegex(state['spec_fingerprint'],r'^[0-9a-f]{64}$')

    def test_old_missing_or_invalid_identity_refused_without_backfill(self):
        import sqlite3
        for mutation in ('old','missing','fingerprint','version','bool-version','empty'):
            with self.subTest(mutation=mutation):
                spec=self.spec(); home=self.create(spec,True)
                with sqlite3.connect(home/'task.sqlite') as db:
                    state=json.loads(db.execute('SELECT body FROM task').fetchone()[0])
                    if mutation == 'old':
                        state['schema_version']=1
                        state.pop('spec_version',None); state.pop('spec_fingerprint',None)
                    elif mutation == 'missing': state.pop('spec_fingerprint',None)
                    elif mutation == 'fingerprint': state['spec_fingerprint']='0'*64
                    elif mutation == 'version': state['spec_version']=999
                    elif mutation == 'bool-version': state['spec_version']=True
                    if mutation == 'empty': db.execute('DELETE FROM task')
                    else: db.execute('UPDATE task SET body=?',(json.dumps(state),))
                before=self.database(home)
                try: self.child(home,spec)
                finally: self.assertEqual(self.database(home),before)

    def test_typed_owner_rejects_ambiguous_non_json_values_before_storage(self):
        from voice_task_state import TaskState, StateError
        for metadata in ({1:'integer-key'},{'tuple':('a','b')},{'nan':float('nan')}):
            spec=self.spec(); spec['owner']['metadata']=metadata
            home=Path(tempfile.mkdtemp(dir=HOME))
            with self.subTest(metadata=metadata), self.assertRaises(StateError):
                state=TaskState(home,**spec)
                state.close()
            self.assertFalse((home/'task.sqlite').exists())

if __name__ == '__main__': unittest.main()
