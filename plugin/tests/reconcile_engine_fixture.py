"""ISOLATED PROBE ONLY. Official engine subclass; no production import/selection.

Only public compress/name/lifecycle methods are overridden. Actual reduction is
unchanged ContextCompressor.compress on the same object; inherited host state
handling is retained, not forwarded through private attribute proxies.
Captured output is an ENGINE CANDIDATE, never a host-commit receipt.
"""
import copy
import os
from pathlib import Path
import threading
import yaml
from agent.context_compressor import ContextCompressor
from agent.relay_runtime import current_turn
from voice_dispatch import CURRENT


class ReconcileFixture(ContextCompressor):
    @property
    def name(self):
        return 'voice-reconcile-fixture'

    def __init__(self):
        config = yaml.safe_load((Path(os.environ['HERMES_HOME'])/'config.yaml').read_text())
        aux = config['auxiliary']['compression']
        super().__init__(model='fixture', quiet_mode=True, base_url=aux['base_url'],
                         api_key='offline-placeholder', config_context_length=65536,
                         provider='openai', api_mode='chat_completions', threshold_tokens_cap=6000,
                         protect_first_n=1, protect_last_n=2, min_tail_user_messages=1,
                         proactive_prune_tokens=0, abort_on_summary_failure=True)
        run = CURRENT.get()
        self.probe_owner = run.identity
        self.boundaries = []
        self.lifecycle = []
        self.candidate_ready = threading.Event()
        self.allow_return = threading.Event()
        self.case = config['fixture_case']
        self.registration_count = 0

    def clone_for_agent(self):
        return ReconcileFixture()

    def on_session_start(self, session_id, **kwargs):
        self.lifecycle.append(dict(session_id=session_id, kwargs={k:v for k,v in kwargs.items() if k != 'session_db'}))
        return super().on_session_start(session_id, **kwargs)

    def compress(self, messages, current_tokens=None, focus_topic=None, force=False,
                 memory_context='', bypass_cooldown=False):
        run, turn = CURRENT.get(), current_turn()
        assert run.identity == self.probe_owner and turn is not None
        owner = run.identity.owner
        identity = dict(profile_id=owner.profile_id,lane=owner.lane,request_id=owner.request_id,
                        attempt_id=owner.attempt_id,session_id=run.identity.sid,turn_id=turn.turn_id)
        assert turn.lease.session_id == identity['session_id']
        with run.store.open() as box:
            notices = box.notices(owner)
        assert len(notices) == 1 and notices[0]['state'] == 'permitted'
        assert notices[0]['identity'] == identity
        receipt = notices[0]['receipt']
        assert receipt['identity'] == identity
        assert receipt['event_id'] == notices[0]['event_id']
        assert receipt['content_digest'] == notices[0]['content_digest']
        assert receipt['outcome'] == 'played' and receipt['synthetic'] is True
        boundary = dict(identity=identity, event_id=notices[0]['event_id'],
                        pre_llm_started=run.started, before=copy.deepcopy(messages),
                        state='entered', committed=False, evidence_kind='engine_candidate_not_commit',
                        durable_at_entry=dict(state=notices[0]['state'], receipt=copy.deepcopy(receipt)))
        self.boundaries.append(boundary)
        from reconcile_protocol_fixture import Journal
        journal = Journal(Path(os.environ['HOME'])/'approved.sqlite')
        journal.enter(identity, copy.deepcopy(messages), notices[0])
        try:
            result = super().compress(messages, current_tokens=current_tokens,
                                      focus_topic=focus_topic, force=force,
                                      memory_context=memory_context, bypass_cooldown=bypass_cooldown)
            boundary.update(candidate=copy.deepcopy(result), state='returned')
            journal.candidate(result)
            if self.case == 'crash_candidate': os._exit(72)
            self.candidate_ready.set()
            if self.case == 'cancel_candidate':
                assert self.allow_return.wait(5), 'bounded cancel fixture timed out'
            return result
        except Exception:
            boundary['state'] = 'raised'
            raise


def register(ctx):
    engine = ReconcileFixture()
    engine.registration_count = 1
    ctx.register_context_engine(engine)
