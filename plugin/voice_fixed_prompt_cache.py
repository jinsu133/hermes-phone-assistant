"""Private process-local PCM LRU for exact public progress phrases only.
No scopes, wire headers, receipts, dynamic replies, credentials or disk audio.
Misses stream immediately; only validated STOP + EOF + source cleanup can publish.
"""
import base64
import json
import threading
from collections import OrderedDict

from .voice_events import PUBLIC_STATUS
from .voice_pcm_stream import OwnedSource, vertex_pcm

FIXED_TEXTS = frozenset(PUBLIC_STATUS.values())
PCM_FORMAT = 'pcm_s16le_24000_mono'
CACHE_VERSION = 'fixed-public-pcm-v1'


def event(pcm=None):
    candidate = {'finishReason': 'STOP'} if pcm is None else {
        'content': {'parts': [{'inlineData': {'mimeType': 'audio/pcm;rate=24000',
                                            'data': base64.b64encode(pcm).decode('ascii')}}]}}
    return b'data: ' + json.dumps({'candidates': [candidate]}, separators=(',', ':')).encode() + b'\n\n'


class FixedPromptCache:
    def __init__(self, max_entries=8, max_bytes=2*1024*1024, entry_bytes=480000):
        if not (0 < max_entries <= 8 and 0 < entry_bytes <= 480000 and 0 < max_bytes <= 2*1024*1024):
            raise ValueError('cache_bounds')
        self.max_entries, self.max_bytes, self.entry_bytes = max_entries, max_bytes, entry_bytes
        self.lock = threading.Lock()
        self.entries = OrderedDict()
        self.bytes = 0
        self.recording = False  # at most one bounded in-flight candidate

    def source(self, text, identity, opener):
        if text not in FIXED_TEXTS:
            return opener()
        key = (CACHE_VERSION, text, *identity, PCM_FORMAT)
        with self.lock:
            pcm = self.entries.get(key)
            if pcm is not None:
                self.entries.move_to_end(key)
                return PromptSource(self, key, pcm=pcm)
            record = not self.recording
            if record:
                self.recording = True
        try:
            return PromptSource(self, key, raw=opener(), record=record)
        except BaseException:
            if record:
                with self.lock:
                    self.recording = False
            raise

    def finish(self, key, pcm):
        with self.lock:
            self.recording = False
            if not pcm or len(pcm) > min(self.entry_bytes, self.max_bytes):
                return
            old = self.entries.pop(key, b'')
            self.bytes -= len(old)
            while self.entries and (len(self.entries) >= self.max_entries or self.bytes + len(pcm) > self.max_bytes):
                self.bytes -= len(self.entries.popitem(last=False)[1])
            self.entries[key] = pcm
            self.bytes += len(pcm)


class PromptSource:
    """SSE facade stores PCM only; HPS1 identity is generated anew by the bridge.
    The first parser validates provider completion before emitting our STOP.
    close() can interrupt raw IO without waiting for the read worker.
    """
    def __init__(self, cache, key, *, pcm=None, raw=None, record=False):
        self.cache, self.key, self.record = cache, key, record
        self.raw = OwnedSource.wrap(raw) if raw is not None else None
        self.chunks = vertex_pcm(self.raw) if raw is not None else (
            pcm[i:i+8192] for i in range(0, len(pcm), 8192))
        self.lock = threading.Lock()
        self.pending = b''
        self.candidate = bytearray()
        self.complete = self.stopped = self.closed = self.oversize = False

    def read1(self, size):
        if size <= 0:
            raise ValueError('read_size')
        with self.lock:
            if self.closed:
                raise OSError('source_closed')
            if self.pending:
                out, self.pending = self.pending[:size], self.pending[size:]
                return out
            if self.stopped:
                self.complete = True
                return b''
        try:
            pcm = next(self.chunks)
        except StopIteration:
            pcm = None
        except BaseException:
            with self.lock:
                self.oversize = True
                self.candidate.clear()
            raise
        with self.lock:
            if self.closed:
                raise OSError('source_closed')
            if pcm is None:
                self.stopped = True
            elif self.record and not self.oversize:
                if len(self.candidate) + len(pcm) > self.cache.entry_bytes:
                    self.oversize = True
                    self.candidate.clear()
                else:
                    self.candidate.extend(pcm)
            wire = event(pcm)
            out, self.pending = wire[:size], wire[size:]
            return out

    def close(self):
        with self.lock:
            if self.closed:
                return
            self.closed = True
            candidate = bytes(self.candidate) if self.complete and not self.oversize else None
            self.candidate.clear()
            self.pending = b''
        successful = False
        try:
            if self.raw is not None:
                self.raw.close()
            successful = True
        finally:
            if self.record:
                self.cache.finish(self.key, candidate if successful else None)
