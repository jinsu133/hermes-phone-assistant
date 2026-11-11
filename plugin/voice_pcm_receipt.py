"""Source-only receipt gate. Network EOS never grants action/listening approval.
Caller must serialize request-generation invalidation with accept() via the same
external owner lock; this gate does not coordinate production task-state itself.
"""
import threading
if __package__:
    from .voice_pcm_stream import Identity
else:
    from voice_pcm_stream import Identity


class PlaybackReceiptGate:
    def __init__(self, identity: Identity):
        self.identity = identity
        self._lock = threading.Lock()
        self._frames = None
        self._terminal = False

    def eos(self, frame_count):
        with self._lock:
            if (self._terminal or self._frames is not None or type(frame_count) is not int
                    or not 0 < frame_count <= 2880000):
                return False
            self._frames = frame_count
            return True

    def content_matches(self, identity, frame_count):
        """Read EOS evidence without consuming legacy played completion."""
        with self._lock:
            return (not self._terminal and self._frames is not None
                    and identity == self.identity and type(frame_count) is int
                    and frame_count == self._frames)

    def invalidate(self):
        """Failed/cancelled/stale stream is terminal; never resurrect it."""
        with self._lock:
            self._terminal = True

    def accept(self, receipt, current_identity):
        with self._lock:
            if self._terminal or self._frames is None or current_identity != self.identity:
                return False
            if not isinstance(receipt, dict) or set(receipt) != {
                    'request_id', 'audio_id', 'generation', 'frame_count', 'status'}:
                return False
            expected = {'request_id': self.identity.request_id, 'audio_id': self.identity.audio_id,
                        'generation': self.identity.generation, 'frame_count': self._frames, 'status': 'played'}
            if (type(receipt['generation']) is not int or type(receipt['frame_count']) is not int
                    or receipt != expected):
                return False
            self._terminal = True
            return True
