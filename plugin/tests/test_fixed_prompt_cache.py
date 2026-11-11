"""Offline production opener/source/bridge tests. No provider or device calls."""
import importlib
import io
import os
from pathlib import Path
import sys
import tempfile
import threading
import types
import unittest
from unittest.mock import patch

PACKAGE = '_fixed_prompt_test_plugin'
pkg = types.ModuleType(PACKAGE)
pkg.__path__ = [str(Path(__file__).resolve().parents[1])]
sys.modules[PACKAGE] = pkg
cachemod = importlib.import_module(PACKAGE+'.voice_fixed_prompt_cache')
normal = importlib.import_module(PACKAGE+'.voice_normal_stream')
stream = importlib.import_module(PACKAGE+'.voice_pcm_stream')
TEXT = sorted(cachemod.FIXED_TEXTS)[0]
PCM = b'\x10\x00'*100
KEY = ('vertex','endpoint','model','voice','instructions-v1','payload')

class Source(io.BytesIO):
    def __init__(self, suffix=None, fail_close=False):
        super().__init__(cachemod.event(PCM)+(cachemod.event() if suffix is None else suffix))
        self.fail_close = fail_close
        self.reads = 0
    def read1(self, size):
        self.reads += 1
        # Separate first audio from STOP to establish true incremental misses.
        return super().read1(min(size, len(cachemod.event(PCM))))
    def close(self):
        super().close()
        if self.fail_close: raise OSError('fixture_close')

class CacheTests(unittest.TestCase):
    def consume(self, source):
        return b''.join(stream.vertex_pcm(source))

    def test_miss_incremental_hit_fresh_headers_original_frames(self):
        cache = cachemod.FixedPromptCache()
        raw = Source()
        source = cache.source(TEXT, KEY, lambda: raw)
        chunks = stream.hps1_stream(stream.Identity('old','a',1),source)
        next(chunks)
        self.assertEqual(next(chunks)[5:], PCM)
        self.assertFalse(raw.closed)
        self.assertEqual(len(cache.entries),0)
        self.assertEqual(list(chunks)[-1][-8:], (100).to_bytes(8,'big'))
        self.assertEqual(len(cache.entries),1)
        hit = cache.source(TEXT,KEY,lambda: self.fail('paid opener on hit'))
        wire = list(stream.hps1_stream(stream.Identity('new','b',2),hit))
        self.assertEqual(wire[0],stream.Identity('new','b',2).header())
        self.assertEqual(wire[1][5:],PCM)
        self.assertEqual(wire[-1][-8:], (100).to_bytes(8,'big'))

    def test_cancel_failure_truncation_and_cleanup_never_publish(self):
        for suffix,close_failure,cancel in [(b'',False,False),(b'data: broken\n\n',False,False),
                                            (None,True,False),(None,False,True)]:
            with self.subTest(suffix=suffix,close_failure=close_failure,cancel=cancel):
                cache = cachemod.FixedPromptCache()
                source = cache.source(TEXT,KEY,lambda: Source(suffix,close_failure))
                if cancel:
                    gen = stream.vertex_pcm(source)
                    self.assertEqual(next(gen),PCM)
                    gen.close()
                else:
                    with self.assertRaises(stream.StreamError): self.consume(source)
                self.assertFalse(cache.entries)
                self.assertFalse(cache.recording)

    def test_bounds_eviction_oversize_and_inflight(self):
        cache = cachemod.FixedPromptCache(max_entries=2,max_bytes=400,entry_bytes=200)
        for version in range(3):
            self.assertEqual(self.consume(cache.source(TEXT,KEY+(version,),Source)),PCM)
        self.assertEqual(len(cache.entries),2)
        self.assertEqual(cache.bytes,400)
        self.assertNotIn((cachemod.CACHE_VERSION,TEXT,*KEY,0,cachemod.PCM_FORMAT),cache.entries)
        small = cachemod.FixedPromptCache(entry_bytes=2)
        self.consume(small.source(TEXT,KEY,Source))
        self.assertFalse(small.entries)
        first=cache.source(TEXT,KEY+('first',),Source)
        second=cache.source(TEXT,KEY+('second',),Source)
        self.assertTrue(first.record); self.assertFalse(second.record)
        first.close();second.close()
        self.assertFalse(cache.recording)

    def test_exact_allowlist_and_identity_invalidation(self):
        cache = cachemod.FixedPromptCache()
        for text in ('recipient amount body',TEXT+' ',TEXT+' private'):
            source = Source()
            self.assertIs(cache.source(text,KEY,lambda:source),source)
            self.consume(source)
        self.assertFalse(cache.entries)
        self.consume(cache.source(TEXT,KEY,Source))
        for index in range(len(KEY)):
            key=list(KEY);key[index]+='changed'
            calls=[]
            self.consume(cache.source(TEXT,tuple(key),lambda:(calls.append(True) or Source())))
            self.assertEqual(calls,[True])

    def test_tiny_read_reassembly(self):
        for size in (1,7,4096,32768):
            cache = cachemod.FixedPromptCache()
            source = cache.source(TEXT,KEY,Source)
            parts=[]
            while chunk:=source.read1(size):
                self.assertLessEqual(len(chunk),size);parts.append(chunk)
            source.close()
            self.assertEqual(self.consume(io.BytesIO(b''.join(parts))),PCM)

    def test_lazy_cancel_during_open_closes_late_source(self):
        entered,release=threading.Event(),threading.Event()
        raw=Source();errors=[]
        def opener(text):
            entered.set();self.assertTrue(release.wait(2));return raw
        source=normal.LazyVertexSource(opener,TEXT)
        def read():
            try: source.read1(4096)
            except OSError as e: errors.append(str(e))
        worker=threading.Thread(target=read);worker.start()
        self.assertTrue(entered.wait(2))
        with self.assertRaisesRegex(OSError,'source_open_pending'): source.close()
        release.set();worker.join(2)
        self.assertFalse(worker.is_alive());self.assertTrue(raw.closed)
        self.assertEqual(errors,['source_closed'])

    def test_normal_source_only_progress_uses_fixed_entry(self):
        calls=[]
        def opener(text): calls.append('dynamic');return Source()
        opener.fixed_prompt=lambda text:(calls.append('fixed') or Source())
        bridge=normal.NormalStreamBridge(types.SimpleNamespace(), 'x'*32, opener)
        for kind,text,expected in [('progress',TEXT,'fixed'),('final',TEXT,'dynamic'),
                                   ('approval_notice',TEXT,'dynamic'),('progress','private','dynamic')]:
            bridge.binding={'text':text,'kind':kind}
            before=len(calls)
            source=bridge._source()
            self.assertEqual(before,len(calls))
            self.consume(source);self.assertEqual(calls[-1],expected)

    def test_actual_existing_opener_key_and_no_prewarm(self):
        wrapper = """DEFAULT_MODEL='fixture-model'
DEFAULT_VOICE='fixture-voice'
def load_style_prompt(): return 'fixture-style'
def compose_prompt(text,style): return style+text
def build_payload(prompt,voice): return {'prompt':prompt,'voice':voice}
def build_url(model): return 'https://aiplatform.googleapis.com/'+model+':generateContent'
"""
        with tempfile.TemporaryDirectory() as home, patch.dict(os.environ,{'VERTEX_TTS_MODEL':'','VERTEX_TTS_VOICE':''}):
            path=Path(home)/'bin/hermes-vertex-tts-portable.py'
            path.parent.mkdir();path.write_text(wrapper)
            calls=[]
            def provider(text,module,**kwargs):
                calls.append((text,kwargs,module.load_style_prompt()));return Source()
            with patch.object(normal,'open_vertex',side_effect=provider):
                opener=normal.existing_vertex_opener(home)
                self.assertEqual(calls,[])
                self.consume(opener.fixed_prompt(TEXT));self.consume(opener.fixed_prompt(TEXT))
                self.assertEqual(len(calls),1)
                self.assertEqual(calls[0][1],dict(authorized=True,model='fixture-model',voice='fixture-voice'))
                os.environ['VERTEX_TTS_VOICE']='other-voice'
                self.consume(opener.fixed_prompt(TEXT));self.assertEqual(len(calls),2)
                os.environ['VERTEX_TTS_MODEL']='other-model'
                self.consume(opener.fixed_prompt(TEXT));self.assertEqual(len(calls),3)
                path.write_text(wrapper.replace('fixture-style','different-style'))
                self.consume(opener.fixed_prompt(TEXT));self.assertEqual(len(calls),4)
                # Dynamic entry bypasses even if exact text happens to match a public phrase.
                self.consume(opener(TEXT));self.consume(opener(TEXT));self.assertEqual(len(calls),6)
                self.consume(opener.fixed_prompt(TEXT+' private'));self.assertEqual(len(calls),7)

if __name__=='__main__': unittest.main()
