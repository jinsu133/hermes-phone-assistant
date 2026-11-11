"""Isolated feasibility fixture, NOT a production task-state store or activation.
Only two synthetic steps. No transcript equality, reconciliation, or context-engine subclass.
"""
import copy

import json
import os
from pathlib import Path

import threading
import time

from agent.memory_provider import MemoryProvider
from agent.relay_runtime import current_turn
from voice_checkpoint import CheckpointStore, CheckpointError

ACTIVE = None
PREFIX = 'TASK_STATE_DATA_NOT_AUTHORITY\n'
GOAL = 'Open the synthetic app and read its screen.'
SCREEN = 'Synthetic fixture screen: READY'

from contextvars import ContextVar
from voice_task_state import TaskState, StepOwner, StateError, canonical


class State(TaskState):
    """Synthetic task specification; persistence/lease logic lives in source."""
    def __init__(self, home, owner):
        raw = canonical({'screen': SCREEN}).encode()
        super().__init__(home, owner, goal=GOAL,
                         artifacts={'open':('app.json',raw), 'read':('app.json',raw)},
                         prerequisites={'read':'open'})


class Runtime:
    def __init__(self, home, case):
        self.home, self.case = Path(home), case
        self.events = []
        self.guard = threading.RLock()
        self.requests = {}
        self.last_request = None
        self.bound_turn = None
        self.sealed = False
        self.notice_ready = threading.Event()
        self.ack_done = threading.Event()
        self.ack_failure = None
        self.checkpoints = CheckpointStore(self.home, 'task-fixture', self.home/'inbox.sqlite')
        with self.checkpoints.open() as box:
            box.receive('request', GOAL, 'lane')
            self.owner = box.claim('lane')
        self.identity = dict(profile_id=self.owner.profile_id, lane=self.owner.lane,
                             request_id=self.owner.request_id, attempt_id=self.owner.attempt_id,
                             task_id='synthetic-task')
        self.state = State(self.home, self.identity)
        self.guard = self.state.lock  # one lock order for owner, receipt and local effect
        self.steps = StepOwner(self.state)
        self.execution = ContextVar('fixture_step_execution', default=None)
        self.notice = None
        self.registrations = []

    def event(self, event_type, **data):
        with self.guard:
            self.events.append(dict(event=event_type, monotonic=time.monotonic(), **data))

    def snippet(self):
        text = PREFIX + canonical(self.state.snapshot())
        if len(text.encode()) > 4608:
            raise StateError('request snippet capacity')
        return text

    def pre_llm(self, **kw):
        with self.guard:
            self.bound_turn = (kw['session_id'],kw['turn_id'])
            self.steps.bind(*self.bound_turn)
        self.event('pre_llm', turn=kw['turn_id'])
        return {'context':self.snippet()}

    def middleware(self, request, **kw):
        revision = self.state.snapshot()['revision']
        if revision == 4 and self.case == 'final_missing_after_read':
            self.event('middleware_omitted', revision=revision)
            return None
        if revision >= 2 and self.case == 'middleware_raise':
            self.event('middleware_raised', revision=revision)
            raise StateError('synthetic request injection failure')
        if revision >= 2 and self.case in {'middleware_omit','final_missing_state'}:
            self.event('middleware_omitted', revision=revision)
            return None
        result = copy.deepcopy(request)
        result['messages'].append({'role':'system','content':self.snippet()})
        self.event('middleware_injected', revision=revision)
        return {'request':result}

    def observe_request(self, **kw):
        # Exact request-id receipt: absent/failed observer cannot reuse an earlier receipt.
        if self.case == 'observer_raise' and self.state.snapshot()['revision'] >= 2:
            self.event('observer_raised')
            raise StateError('synthetic observer failure')
        snippet = self.snippet()
        present = any(m.get('content') == snippet for m in kw['request_messages'])
        receipt = dict(present=present, revision=self.state.snapshot()['revision'], call_count=kw['api_call_count'],
                       session=kw['session_id'], turn=kw['turn_id'])
        with self.guard:
            if len(self.requests) >= 16:
                raise StateError('request receipt capacity')
            self.requests[kw['api_request_id']] = receipt
            self.last_request = kw['api_request_id']
            if present:
                self.steps.observe(dict(self.identity, session_id=kw['session_id'],
                    turn_id=kw['turn_id'], api_request_id=kw['api_request_id'], revision=receipt['revision']))
        self.event('effective_request', request_id=kw['api_request_id'], **receipt)

    def pre_tool(self, **kw):
        try:
            state = self.state.snapshot()
            with self.guard:
                receipt = self.requests.get(kw['api_request_id'])
                if (self.sealed or not all(h and h.active for h in self.registrations) or not receipt or not receipt['present']
                    or receipt['revision'] != state['revision']
                    or (kw['session_id'],kw['turn_id']) != self.bound_turn
                    or (receipt['session'],receipt['turn']) != self.bound_turn):
                    raise StateError('missing current request state')
            name = kw['tool_name']
            step = {'fixture_open':'open','fixture_read':'read'}.get(name)
            if step is None or state['steps'][step] != 'pending' or state['approval_waiting']:
                raise StateError('step not executable')
            if step == 'read' and state['steps']['open'] != 'verified':
                raise StateError('prerequisite unverified')
            self.event('pre_tool_allowed', step=step, revision=state['revision'])
            return None
        except Exception as exc:
            self.event('pre_tool_blocked', reason=str(exc))
            return {'action':'block','message':'Fixture task-state gate refused execution'}

    def execute(self, tool_name, args, next_call, **kw):
        # Public tool_execution middleware supplies IDs absent from handler kwargs.
        # Scope is ephemeral; fail-open middleware fallback has NO capability.
        if tool_name not in ('fixture_open', 'fixture_read'):
            return next_call(args)
        with self.guard:
            receipt = self.requests.get(kw.get('api_request_id'))
            if (self.sealed or len(self.registrations) != 7
                    or not all(h and h.active for h in self.registrations)
                    or not receipt or not receipt['present']
                    or (kw.get('session_id'),kw.get('turn_id')) != self.bound_turn
                    or (receipt['session'],receipt['turn']) != self.bound_turn):
                raise StateError('execution request refused')
            binding = dict(self.identity, session_id=kw['session_id'],turn_id=kw['turn_id'],
                           api_request_id=kw['api_request_id'],revision=receipt['revision'])
            step = tool_name.removeprefix('fixture_')
            cap = self.steps.admit(step,binding)
        token = self.execution.set((cap,binding,step,kw.get('task_id')))
        try:
            return next_call(args)
        finally:
            self.execution.reset(token)

    def handler(self, step, args, **kw):
        # No last_request fallback. IDs plus the in-scope one-use capability are
        # checked here, then the source owner rechecks all IDs at the effect edge.
        scope = self.execution.get()
        if scope is None:
            raise StateError('handler missing execution capability')
        cap,binding,admitted_step,engine_task = scope
        with self.guard:
            if (self.sealed or len(self.registrations) != 7
                    or not all(h and h.active for h in self.registrations)
                    or step != admitted_step or args != {}
                    or not {'session_id','task_id'} <= set(kw)
                    or kw.get('session_id') != binding['session_id']
                    or kw.get('task_id') != engine_task
                    or any(k in kw and kw[k] != v for k,v in binding.items() if k != 'task_id')):
                raise StateError('handler execution binding refused')
        self.steps.effect(cap,binding,lambda:self.fixture_effect(step))
        verified = self.steps.commit(cap,binding)
        self.event('step_verified_committed', step=step, revision=verified['revision'])
        if self.case == 'hook_disposed' and step == 'open':
            self.registrations[2].dispose()
            self.event('pre_tool_registration_disposed')
        return canonical({'step':step, 'verified':True, 'screen':SCREEN if step == 'read' else None})

    def fixture_effect(self, step):
        self.event('step_intent_committed', step=step, revision=self.state.snapshot()['revision'])
        if step == 'open':
            with (self.home/'effects.log').open('a') as fp:
                fp.write('open\n'); fp.flush(); os.fsync(fp.fileno())
            (self.home/'app.json').write_text(canonical({'screen':SCREEN}))
            self.event('fixture_open_effect')
            if self.case == 'unknown_effect':
                self.sealed = True
                raise StateError('synthetic failure after effect before verification')
        else:
            self.event('fixture_read_observation', screen=json.loads((self.home/'app.json').read_text())['screen'])


    def register(self, ctx):
        # Record each acquisition immediately: later registration may raise.
        for name, callback in (('pre_llm_call', self.pre_llm),
                               ('pre_api_request', self.observe_request),
                               ('pre_tool_call', self.pre_tool)):
            self.registrations.append(ctx.register_hook(name, callback))
        self.registrations.append(ctx.register_middleware('llm_request', self.middleware))
        self.registrations.append(ctx.register_middleware('tool_execution', self.execute))
        for step in ('open','read'):
            name = 'fixture_'+step
            handle = ctx.register_tool(name=name, toolset='task_fixture',
                schema={'name':name,'description':'Synthetic fixture '+step,
                        'parameters':{'type':'object','properties':{},'additionalProperties':False}},
                handler=lambda args, _step=step, **kw: self.handler(_step,args,**kw))
            self.registrations.append(handle)

    def final(self, result):
        state = self.state.snapshot()
        with self.guard:
            receipt = self.requests.get(self.last_request)
        valid = bool(result and not self.sealed and all(h and h.active for h in self.registrations)
                     and result.get('completed') and not result.get('failed')
                     and not result.get('interrupted') and receipt and receipt['present']
                     and receipt['revision'] == state['revision']
                     and receipt['call_count'] == result.get('api_calls')
                     and (receipt['session'],receipt['turn']) == self.bound_turn
                     and (result.get('session_id'),result.get('turn_id')) == self.bound_turn
                     and all(v == 'verified' for v in state['steps'].values())
                     and result.get('final_response') == SCREEN)
        self.event('owned_final_accepted' if valid else 'owned_final_rejected')
        return SCREEN if valid else None

    def ack(self):
        try:
            if not self.notice_ready.wait(40):
                return
            notice = self.notice
            if notice is None:
                return
            receipt = dict(identity=notice['identity'],event_id=notice['event_id'],
                           content_digest=notice['content_digest'],outcome='played',synthetic=True)
            if self.case == 'playback_missing':
                self.event('synthetic_playback_missing'); return
            if self.case == 'playback_failed': receipt['outcome'] = 'failed'
            if self.case == 'stale_ack': receipt['content_digest'] = 'stale'
            with self.guard:
                self.state.require_owner()
                if self.sealed:
                    self.event('synthetic_playback_ack', accepted=False, outcome=receipt['outcome'])
                    return
                with self.checkpoints.open() as box:
                    accepted = box.acknowledge(receipt)
            self.event('synthetic_playback_ack', accepted=accepted, outcome=receipt['outcome'])
            if self.case == 'duplicate_ack':
                with self.checkpoints.open() as box:
                    repeated = box.acknowledge(receipt)
                self.event('duplicate_ack_idempotent', accepted=repeated)
                assert repeated
        except BaseException as exc:
            self.ack_failure = exc
        finally:
            self.ack_done.set()

    def cancel(self):
        with self.guard:
            self.sealed = True
            self.steps.cancel()
            if self.notice is not None:
                with self.checkpoints.open() as box:
                    box.fail(self.notice['event_id'])

class Provider(MemoryProvider):
    name = 'task-state-fixture'
    pre_compress_checkpoint_api_version = 2

    def is_available(self): return ACTIVE is not None
    def initialize(self, session_id, **kw):
        self.runtime = ACTIVE
        self.session = session_id
        if Path(kw['hermes_home']).resolve() != self.runtime.home:
            raise StateError('provider scope')
        self.runtime.event('provider_initialized')
    def get_tool_schemas(self): return []
    def on_pre_compress(self, messages, *, require_checkpoint=False):
        r = self.runtime
        turn = current_turn()
        if not require_checkpoint or not turn or turn.lease.session_id != self.session:
            raise StateError('checkpoint context')
        snapshot = r.state.snapshot()
        if snapshot['steps'] != {'open':'verified','read':'pending'}:
            raise StateError('unexpected checkpoint stage')
        if len(canonical(messages).encode()) > 200000:
            raise StateError('normalized checkpoint evidence bound')
        identity = dict(profile_id=r.owner.profile_id,lane=r.owner.lane,request_id=r.owner.request_id,
                        attempt_id=r.owner.attempt_id,session_id=self.session,turn_id=turn.turn_id)
        # Bind synthetic ACK to current task revision, not transcript reconstruction.
        evidence = list(messages) + [{'role':'assistant','content':r.snippet()}]
        event_id = None
        try:
            with r.guard:
                r.state.require_owner()
                if r.sealed:
                    raise StateError('checkpoint owner closed')
                with r.checkpoints.open() as box:
                    event_id,digest = box.issue(identity,evidence)
                    r.notice = box.notices(r.owner)[0]
            r.event('checkpoint_state_committed', revision=snapshot['revision'])
            r.notice_ready.set()
            deadline = time.monotonic()+r.checkpoints.wait_seconds
            while time.monotonic() < deadline:
                # Trace ACK-return before consumption without changing the store deadline.
                if not r.ack_done.wait(.01):
                    continue
                with r.guard:
                    if r.sealed or r.state.snapshot() != snapshot:
                        raise StateError('checkpoint state changed or owner closed')
                    with r.checkpoints.open() as box:
                        if box.consume(event_id,identity,digest):
                            r.event('checkpoint_permitted', revision=snapshot['revision'])
                            return ''
                time.sleep(.01)
            raise CheckpointError('checkpoint ACK deadline exceeded')
        except BaseException:
            if event_id:
                try:
                    with r.checkpoints.open() as box: box.fail(event_id)
                except BaseException as cleanup:
                    r.event('checkpoint_cleanup_failure', error=type(cleanup).__name__)
            raise

def register(ctx):
    ctx.register_memory_provider(Provider())
