"""Bounded public status and evidence-linked explanations; never reasoning streams."""
import secrets
import threading
import time
import unicodedata

PUBLIC_STATUS = {
    'accepted': '에이전트가 답변을 준비하고 있습니다.',
    'phone': '휴대폰 화면을 확인하며 작업하고 있습니다.',
    'research': '자료를 조회하고 있습니다.',
    'document': '문서와 코드 초안을 작성하거나 확인하고 있습니다.',
    'calculation': '계산을 실행하고 있습니다.',
}
TOOL_STATUS = {
    'phone_observe': 'phone', 'phone_act': 'phone',
    'web_search': 'research', 'web_extract': 'research',
    'voice_draft': 'document', 'read_file': 'document', 'write_file': 'document',
    'terminal': 'calculation',
}


class VoiceEvents:
    def __init__(self, clock=time.monotonic):
        self._lock = threading.RLock()
        self._clock = clock
        self.clear()

    def clear(self):
        with self._lock:
            self.request = None
            self.current = None
            self._phone_span = None
            self.seen = set()
            self.seq = 0
            self.started = self._clock()
            self.last = float('-inf')
            self.closed = True
            self.evidence = {}
            self.used_evidence = set()
            self.explanations = 0
            self.last_explanation = float('-inf')

    def begin(self, request):
        with self._lock:
            self.clear()
            self.request = request
            self.closed = False
            self.emit(request, 'accepted')

    def emit(self, request, kind):
        with self._lock:
            now = self._clock()
            if (self.closed or self.explanations or request != self.request or kind not in PUBLIC_STATUS
                    or kind in self.seen or len(self.seen) >= 4 or now-self.started > 600
                    or now-self.last < 10):
                return None
            self._phone_span = None
            self.seq += 1
            self.last = now
            self.seen.add(kind)
            self.current = {'request_id': request, 'seq': self.seq,
                            'kind': kind, 'text': PUBLIC_STATUS[kind],
                            'audio_id': secrets.token_urlsafe(18)}
            return dict(self.current)

    def begin_phone_span(self, request):
        """Return a private one-span owner only if this call emitted the status.

        Suppressed parallel/nested calls do not acquire the emitting span's event.
        The owner is never included in public event dictionaries or the wire.
        """
        with self._lock:
            event = self.emit(request, 'phone')
            if event is None:
                return None
            owner = (object(), request, event['seq'], event['audio_id'])
            self._phone_span = owner
            return owner

    def end_phone_span(self, owner):
        """Expire only the exact automatic event emitted by the completed span."""
        with self._lock:
            if owner is None or self._phone_span is not owner:
                return
            self._phone_span = None
            event = self.current
            if (not self.closed and self.request == owner[1] and event
                    and event['request_id'] == owner[1] and event['seq'] == owner[2]
                    and event['audio_id'] == owner[3] and event['kind'] == 'phone'):
                self.current = None

    def record_evidence(self, request, tool):
        # Only the host middleware calls this after an eligible successful read.
        # Retain no tool content, paths, credentials or screen text here.
        with self._lock:
            if self.closed or request != self.request or self._clock()-self.started > 600:
                return None
            receipt = secrets.token_urlsafe(18)
            self.evidence[receipt] = tool
            while len(self.evidence) > 24:
                oldest = next(iter(self.evidence))
                self.evidence.pop(oldest)
                self.used_evidence.discard(oldest)
            return receipt

    def explain(self, request, args):
        with self._lock:
            if self.closed or request != self.request or self._clock()-self.started > 600:
                raise ValueError('progress_owner_required')
            fields = ('finding', 'basis', 'next_step')
            if not isinstance(args, dict) or set(args) != set(fields) | {'evidence_ids'}:
                raise ValueError('invalid_progress_arguments')
            ids = args['evidence_ids']
            if (not isinstance(ids, list) or not 1 <= len(ids) <= 3
                    or any(not isinstance(i, str) or i not in self.evidence for i in ids)
                    or len(set(ids)) != len(ids) or not set(ids)-self.used_evidence):
                raise ValueError('progress_evidence_required')
            text_parts = []
            for key in fields:
                value = args[key]
                if (not isinstance(value, str) or not 1 <= len(value.strip()) <= 160
                        or any(unicodedata.category(c).startswith('C') for c in value)
                        or any(marker in value.lower() for marker in ('```', '<think', '</think', '<analysis'))):
                    raise ValueError('invalid_progress_arguments')
                text_parts.append(value.strip())
            text = ' '.join(text_parts)
            if len(text) > 360:
                raise ValueError('invalid_progress_arguments')
            # Best-effort credential redaction; no raw model reasoning is captured.
            try:
                from agent.redact import redact_sensitive_text
                text = redact_sensitive_text(text, force=True, file_read=True,
                                             secret_file=True, redact_url_credentials=True)
                if not isinstance(text, str) or not text.strip() or len(text) > 500:
                    raise ValueError('redaction_result')
            except Exception:
                raise ValueError('progress_redaction_unavailable') from None
            if self.explanations >= 3 or self._clock()-self.last_explanation < 10:
                return {'queued': False, 'reason': 'progress_limit', 'continue_task': True}
            self._phone_span = None
            self.seq += 1
            self.explanations += 1
            self.last_explanation = self._clock()
            self.used_evidence.update(ids)
            self.current = {'request_id': request, 'seq': self.seq, 'kind': 'explanation',
                            'text': text, 'audio_id': secrets.token_urlsafe(18)}
            return {'queued': True, 'seq': self.seq, 'continue_task': True,
                    'spoken': False, 'note': 'Queued public summary, not approval or verified playback.'}

    def finish(self, request):
        with self._lock:
            if request == self.request:
                self.closed = True
                self.current = None
                self._phone_span = None
                self.evidence.clear()
                self.used_evidence.clear()

    def get(self, request, after=0):
        with self._lock:
            if (self.closed or request != self.request or not self.current
                    or self.current['seq'] <= after or self._clock()-self.started > 600):
                return None
            return dict(self.current)
