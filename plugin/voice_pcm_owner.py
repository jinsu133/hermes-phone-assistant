"""Source-only HOST mirror of current stream/receipt ownership.

Not a playback scheduler: Android StreamingPlaybackOwner is the ONLY sink owner.
Trusted controller calls begin/admit before synthesis; admit replaces the host's
receipt target, not permission to start another device sink. Device replacement
still waits for its old worker's confirmed sink cleanup. No HTTP registration.
Authenticated transport/session binding is required before using this live.
"""
from dataclasses import dataclass
import threading
if __package__:
    from .voice_pcm_stream import Identity
    from .voice_pcm_receipt import PlaybackReceiptGate
else:
    from voice_pcm_stream import Identity
    from voice_pcm_receipt import PlaybackReceiptGate


@dataclass(frozen=True)
class ListeningEligibility:
    """Historical event, never microphone/action authorization or user approval."""
    identity: Identity
    kind: str
    frame_count: int


class HostPlaybackOwner:
    def __init__(self):
        self._lock = threading.Lock()
        self._request = None
        self._generation = -1
        self._sequence = -1
        self._enabled = False
        self._terminal = False
        self._seen = set()  # bounded per epoch: 1023 progress + one terminal
        self._gate = None
        self._kind = None
        self._accepted = None
        self._eligibility = None
        self._request_render = False
        self._content_presented = self._renderer_closed = False

    def _revoke(self):
        if self._gate is not None:
            self._gate.invalidate()
        self._gate = None
        self._accepted = None
        self._eligibility = None
        self._content_presented = self._renderer_closed = False

    def reset_generation(self):
        """Start a fresh monotonic baseline for a newly authenticated session.

        The device counts generations per app/service process, so a reinstall or
        service restart legitimately restarts from 1. Only a connect that already
        passed identity/liveness/poison checks may call this; replay protection for
        the old epoch comes from the session/epoch fields in every scope.
        """
        with self._lock:
            self._revoke()
            self._request = None
            self._generation = -1
            self._sequence = -1
            self._enabled = False
            self._terminal = False
            self._seen.clear()
            self._kind = None

    def begin(self, request_id, generation):
        Identity(request_id, 'validation', generation)
        with self._lock:
            if generation <= self._generation:
                return False
            self._revoke()
            self._request, self._generation = request_id, generation
            self._sequence = -1
            self._enabled = True
            self._terminal = False
            self._seen.clear()
            return True

    def admit(self, identity, sequence, kind, *, request_render=False):
        """Reject duplicates/reused IDs/out-of-order, never replay or retry silently.
        This is host receipt-target admission, NOT a cross-process stop ACK.
        """
        if not isinstance(identity, Identity):
            raise ValueError('identity')
        if type(request_render) is not bool or type(sequence) is not int or sequence < 0 or kind not in (
                'progress', 'final', 'approval_notice'):
            raise ValueError('admission')
        with self._lock:
            if (not self._enabled or self._terminal
                    or (identity.request_id, identity.generation) != (self._request, self._generation)
                    or sequence <= self._sequence or identity.audio_id in self._seen
                    or len(self._seen) >= (1023 if kind == 'progress' else 1024)):
                return False
            self._revoke()
            self._sequence = sequence
            self._seen.add(identity.audio_id)
            self._kind = kind
            self._terminal = kind != 'progress'
            self._gate = PlaybackReceiptGate(identity)
            self._request_render = request_render
            return True

    def eos(self, identity, frame_count):
        """Called only for actual locally-produced clean HPS1 EOS, not client input."""
        with self._lock:
            return bool(self._enabled and self._gate is not None
                        and self._gate.identity == identity and self._gate.eos(frame_count))

    def content_presented(self, identity, frame_count):
        """Content-only assertion: no accepted receipt or eligibility event."""
        with self._lock:
            if (not self._enabled or not self._request_render or self._gate is None
                    or self._content_presented or self._renderer_closed
                    or not self._gate.content_matches(identity, frame_count)):
                return False
            self._content_presented = True
            return True

    def render_closed(self, identity):
        """Trusted bridge calls only after exact R closure, under its admission gate."""
        with self._lock:
            if (not self._enabled or not self._request_render or self._gate is None
                    or self._gate.identity != identity or self._kind != 'final'
                    or not self._content_presented or self._renderer_closed):
                return False
            self._renderer_closed = True
            return True

    def accept(self, receipt):
        """Completion linearizes under SAME lock as cancel/admit/epoch changes.
        The return bool is historical, not authority for an action after unlocking.
        Caller must authenticate/bind receipt sender outside this pure source gate.
        """
        # Copy ordinary decoded JSON; caller must not mutate it concurrently.
        if type(receipt) is not dict:
            return False
        value = dict(receipt)
        with self._lock:
            if (not self._enabled or self._gate is None
                    or (self._request_render and (self._kind != 'final'
                        or not self._content_presented or not self._renderer_closed))):
                return False
            identity = self._gate.identity
            if not self._gate.accept(value, identity):
                return False
            self._accepted = value
            if self._kind in ('final', 'approval_notice'):
                self._eligibility = ListeningEligibility(identity, self._kind, value['frame_count'])
            return True

    def cancel(self):
        with self._lock:
            self._enabled = False
            self._revoke()

    off = cancel

    def accepted_receipt(self):
        """Detached diagnostic snapshot. Never a later listening/action permit."""
        with self._lock:
            return None if self._accepted is None else dict(self._accepted)

    def take_eligibility(self):
        """Atomic event removal; cancellation clears unconsumed events.
        No microphone activation. Live transitions must share owner synchronization
        and revalidate controller/session consent, not trust this return snapshot.
        """
        with self._lock:
            result, self._eligibility = self._eligibility, None
            return result
