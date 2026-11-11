"""Narrow wire-shape compatibility for already-admitted phone runs.

This is request middleware, not route enforcement or an admission mechanism.
The host fails open on middleware errors, and later middleware can rewrite it.
Never select a model/provider or derive canonical reasoning from legacy effort.
No config, credentials, core globals, sockets or provider clients are accessed.
"""
from urllib.parse import urlsplit


class CodexRequestCompatibility:
    def __init__(self, link):
        self.link = link

    def __call__(self, request, *, provider=None, base_url=None, api_mode=None,
                 session_id=None, turn_id=None, **kwargs):
        # Require actual host route metadata, never a model-name prefix or saved
        # selection: Responses also serves non-Codex endpoints with other rules.
        if (provider != 'openai-codex' or api_mode != 'codex_responses'
                or not isinstance(base_url, str) or not isinstance(request, dict)
                or not isinstance(session_id, str) or not session_id
                or not isinstance(turn_id, str) or not turn_id):
            return None
        try:
            url = urlsplit(base_url)
            if (url.scheme != 'https' or url.hostname != 'chatgpt.com'
                    or url.port not in (None, 443) or url.username is not None
                    or url.password is not None or url.query or url.fragment
                    or url.path.rstrip('/') != '/backend-api/codex'):
                return None
        except ValueError:
            return None

        extra = request.get('extra_body')
        remove_extra = isinstance(extra, dict) and 'reasoning_effort' in extra
        if 'reasoning_effort' not in request and not remove_extra:
            return None

        # Reuse the plugin's existing admission, lifetime and cancellation checks.
        # These are plugin-owned state; no private core registry/table is read.
        link = self.link
        with link._lock:
            if not link.listening or link._runs is None or link._cancel_cleanup_blocked:
                return None
            owner = link.voice_request(session_id, turn_id)
            if (not owner or not link._runs.owns(owner)
                    or link._admitted_owner((session_id, turn_id)) != owner):
                return None
            corrected = dict(request)
            corrected.pop('reasoning_effort', None)
            if remove_extra and isinstance(extra, dict):
                corrected['extra_body'] = dict(extra)
                corrected['extra_body'].pop('reasoning_effort')
            # Keep every other field, including model and both possible canonical
            # reasoning objects, byte-for-value. Do not repair conflicting options.
            return {'request': corrected, 'source': 'hermes-phone-control',
                    'reason': 'remove unsupported legacy Codex reasoning_effort'}
