"""Option B adapter: actual /v1/runs owner -> existing voice event/outbox lifecycle.

No core monkeypatch, guessed strict capability or alternate model loop. A public
post-turn hook is only a candidate; the exact run's terminal status completes it.
"""
from __future__ import annotations

from copy import deepcopy
import hmac
import re
from .voice_runs_owner import RunsOwner, RunOwnerError, FINISHED


class RunsBinding:
    def __init__(self, link, snapshot, client, *, poll_seconds=.3, deadline_seconds=600):
        self.link = link
        self.snapshot = deepcopy(snapshot)
        self.config = self.snapshot['payload']
        if self.config['mode'] != 'runs':
            raise RunOwnerError('RUNS_MODE_REQUIRED')
        self.session_id = self.config['session_id']
        self._request = None
        self._busy_marker = None
        self.owner = RunsOwner(link._home, client,
            on_terminal=self._terminal, on_failure=self._failure,
            on_settled=self._settled,
            poll_seconds=poll_seconds, deadline_seconds=deadline_seconds)

    def admission(self):
        return {'schema_version': 1, 'mode': 'runs', 'strict_voice': False,
            'ok': not self.owner.blocked(),
            'error': 'RUNS_PREVIOUS_UNRESOLVED' if self.owner.blocked() else None,
            'revision': self.snapshot['revision'], 'editable': True,
            'route': {k: deepcopy(self.config[k]) for k in ('model','provider','model_options')},
            'contract': 'hermes-runs-v1', 'settings_cas_scope': 'plugin',
            'strict_physical_route': False}

    def pending_cleanup(self):
        return self.owner.busy() or self.owner.blocked()

    def submit(self, text, pending):
        self._request = (pending['request_id'], self.link._session_generation,
                         pending['session_id'], pending['digest'])
        self._busy_marker = None
        return self.owner.begin(request_id=pending['request_id'],
            session_generation=self.link._session_generation, session_id=self.session_id,
            input_text=text, model=self.config['model'], provider=self.config['provider'],
            model_options=deepcopy(self.config['model_options']))

    def note_turn(self, user_message, session_id, marker):
        # Called under the link gate, including a late pre-hook after cancellation.
        # This remembers availability accounting only; it never restores admission.
        from .voicelink import _text_digest
        expected = self._request
        if (expected is not None and type(user_message) is str
                and session_id == expected[2]
                and hmac.compare_digest(_text_digest(user_message), expected[3])):
            self._busy_marker = marker

    def owns(self, request):
        return self.owner.owns(request)

    def cancel(self, request):
        if request is not None:
            return self.owner.cancel(request)
        return False

    def _current(self, scope):
        link = self.link
        pending = link._pending
        return bool(link.listening and pending and
            link._latest_request_scope == (scope['request_id'], scope['session_generation']) and
            link._session_generation == scope['session_generation'] and
            pending['request_id'] == scope['request_id'] and
            pending.get('session_id') == scope['session_id'])

    def stage_reply(self, pending, kwargs):
        # Public hook IDs + prior exact-runtime admission must already agree.
        if (pending.get('turn_id') is None or pending.get('turn_id') != kwargs.get('turn_id')
                or pending.get('session_id') != kwargs.get('session_id')):
            return
        response = kwargs.get('assistant_response')
        if type(response) is str:
            pending['run_candidate'] = response

    def _terminal(self, scope, result):
        link = self.link
        with link._lock:
            if not self._current(scope):
                # Revocation wins even if the terminal observation raced it.
                return
            pending = link._pending
            output = result.get('output')
            candidate = pending.get('run_candidate')
            if (type(output) is not str or not output.strip() or type(candidate) is not str
                    or output != candidate or pending.get('turn_id') is None):
                raise RunOwnerError('RUNS_FINAL_UNBOUND')
            link.events.finish(scope['request_id'])
            link.outbox.add(request_id=scope['request_id'], session_id=scope['session_id'],
                turn_id=pending['turn_id'], text=output[:4000])
            runtime = result.get('runtime')
            link._last_model = deepcopy(runtime) if type(runtime) is dict else None
            link._pending = None

    def _failure(self, scope, code):
        link = self.link
        with link._lock:
            if not self._current(scope):
                return
            link.events.finish(scope['request_id'])
            link.confirmation.clear()
            link._run_errors[scope['request_id']] = code
            while len(link._run_errors) > 20:
                del link._run_errors[next(iter(link._run_errors))]
            link._pending = None

    def _settled(self, scope):
        with self.link._lock:
            if scope['state'] in FINISHED:
                self.link._request_cleanup_pending.discard(scope['request_id'])
                expected = self._request
                if expected is not None and expected[:3] == (
                        scope['request_id'], scope['session_generation'], scope['session_id']):
                    if self.link._active_sessions.get(scope['session_id']) is self._busy_marker:
                        self.link._active_sessions.pop(scope['session_id'], None)
                    self._request = self._busy_marker = None

    def stop(self):
        scope = self.owner.scope()
        if scope:
            self.owner.cancel(scope['request_id'])
        joined = self.owner.join(3)
        if joined:
            self.owner.close()
        return joined and not self.owner.blocked()
