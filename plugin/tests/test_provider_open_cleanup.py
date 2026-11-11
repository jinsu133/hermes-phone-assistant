"""Offline provider-open cleanup regressions. No credentials, network or devices."""
import importlib
import json
import sys
import threading
import types
import unittest
from email.message import Message
from http.client import HTTPException
from pathlib import Path
from unittest.mock import Mock, patch
from urllib.error import HTTPError

pkg = types.ModuleType('_provider_cleanup_test')
pkg.__path__ = [str(Path(__file__).resolve().parents[1])]
sys.modules[pkg.__name__] = pkg
stream = importlib.import_module(pkg.__name__ + '.voice_pcm_stream')
normal = importlib.import_module(pkg.__name__ + '.voice_normal_stream')
diag = importlib.import_module(pkg.__name__ + '.voice_pcm_diagnostics')
cachemod = importlib.import_module(pkg.__name__ + '.voice_fixed_prompt_cache')


class Body:
    def __init__(self, error_type=None, content_type='text/event-stream'):
        self.error_type, self.closes = error_type, 0
        self.headers = Message()
        self.headers['Content-Type'] = content_type
        self.status = 200

    def close(self):
        self.closes += 1
        if self.error_type:
            # First-only failure: an inappropriate retry would appear successful.
            if self.closes == 1:
                raise self.error_type('PRIVATE_CLOSE_SENTINEL')

    def read1(self, size):
        return b''


class CleanupTests(unittest.TestCase):
    def provider(self, body, http=True):
        wrapper = Mock(DEFAULT_MODEL='fixture', DEFAULT_VOICE='Kore')
        wrapper.build_url.return_value = 'https://aiplatform.googleapis.com/v1/fixture:generateContent'
        wrapper.compose_prompt.return_value = 'fixture'
        wrapper.build_payload.return_value = {}
        wrapper.mint_access_token.return_value = 'PRIVATE_TOKEN_SENTINEL'
        fake = Mock()
        if http:
            fake.open.side_effect = HTTPError('https://private.invalid', 503, 'PRIVATE_HTTP_SENTINEL', {}, body)
        else:
            fake.open.return_value = body
        return wrapper, fake

    def assert_safe(self, failure):
        self.assertIsNone(failure.__context__)
        self.assertIsNone(failure.__cause__)
        self.assertNotIn('PRIVATE_', str(failure))
        cleanup = getattr(failure, 'cleanup_error', None)
        if cleanup is not None:
            self.assertEqual(cleanup.args, ())
            self.assertIsNone(cleanup.__context__)
            self.assertIsNone(cleanup.__cause__)
            self.assertIsNone(cleanup.__traceback__)

    def check_open(self, error_type, http=True, cached=False, bad_observer=False):
        body = Body(error_type, 'application/json' if not http else 'text/event-stream')
        wrapper, fake = self.provider(body, http)
        ring = diag.DiagnosticRing()
        cache = cachemod.FixedPromptCache()
        text = sorted(cachemod.FIXED_TEXTS)[0]
        def opener(text):
            return stream.open_vertex(text, wrapper, authorized=True)
        def observer(stage, **values):
            if bad_observer:
                raise RuntimeError('PRIVATE_OBSERVER')
            ring.emit(stage, correlation_id='a'*32, **values)
        selected = (lambda text: cache.source(text, ('fixture',), lambda: opener(text))) if cached else opener
        lazy = normal.LazyVertexSource(selected, text, observer)
        source = stream.OwnedSource(lazy)
        with patch.object(stream, 'build_opener', return_value=fake):
            with self.assertRaises(stream.StreamError) as caught:
                list(stream.vertex_pcm(source))
        failure = caught.exception
        self.assertEqual(failure.args, ('http_error' if http else 'content_type',))
        self.assertEqual(failure.http_status, 503 if http else None)
        self.assert_safe(failure)
        self.assertFalse(lazy.opening)
        self.assertEqual(body.closes, 1)
        for _ in range(3):
            self.assertEqual(stream._close_response(source, unwinding=True), error_type is None)
            self.assertEqual(stream._close_response(lazy, unwinding=True), error_type is None)
        self.assertEqual(source.close_confirmed, error_type is None)
        self.assertEqual(body.closes, 1)
        self.assertFalse(cache.entries)
        self.assertFalse(cache.recording)
        self.assertEqual(cache.bytes, 0)
        exported = ring.bounded_export()
        self.assertNotIn('PRIVATE_', json.dumps(exported))
        if not bad_observer:
            first = exported['events'][0]
            self.assertEqual(first['stage'], 'provider_error')
            self.assertEqual(first['errors'][0]['code'], failure.args[0])
            if http:
                self.assertEqual(first['http_status'], 503)
            cleanup = [e for e in exported['events'] if e['stage'] == 'cleanup']
            self.assertEqual(len(cleanup), int(error_type is not None))
            if cleanup:
                self.assertEqual(cleanup[0]['code'], 'source_cleanup_unproven')
                expected = error_type.__name__ if error_type in (OSError, RuntimeError) else 'Exception'
                self.assertEqual(cleanup[0]['errors'][0]['type'], expected)

    def test_http_oserror(self): self.check_open(OSError)
    def test_http_runtimeerror(self): self.check_open(RuntimeError)
    def test_http_httpexception(self): self.check_open(HTTPException)
    def test_http_baseexception(self): self.check_open(BaseException)
    def test_http_clean_control(self): self.check_open(None)
    def test_content_type_oserror(self): self.check_open(OSError, http=False)
    def test_content_type_runtimeerror(self): self.check_open(RuntimeError, http=False)
    def test_content_type_clean_control(self): self.check_open(None, http=False)
    def test_cache_http_oserror(self): self.check_open(OSError, cached=True)
    def test_cache_http_runtimeerror(self): self.check_open(RuntimeError, cached=True)
    def test_observer_cannot_mask_http_or_cleanup(self): self.check_open(RuntimeError, bad_observer=True)

    def late(self, error_type, http):
        body = Body(error_type)
        wrapper, fake = self.provider(body, http)
        entered, release = threading.Event(), threading.Event()
        outcomes, ring = [], diag.DiagnosticRing()
        def opener(text):
            entered.set()
            if not release.wait(3):
                raise AssertionError('fixture_timeout')
            return stream.open_vertex(text, wrapper, authorized=True)
        lazy = normal.LazyVertexSource(opener, 'fixture', lambda stage, **kw: ring.emit(stage, **kw))
        owned = stream.OwnedSource(lazy)
        cancel = stream.Cancellation()
        def worker():
            try:
                list(stream.vertex_pcm(owned, cancel))
            except BaseException as failure:
                outcomes.append(failure)
        with patch.object(stream, 'build_opener', return_value=fake):
            thread = threading.Thread(target=worker)
            thread.start()
            try:
                self.assertTrue(entered.wait(3))
                cancel.cancel()
                self.assertFalse(owned.close_confirmed)
                self.assertFalse(stream._close_response(lazy, unwinding=True))
            finally:
                release.set()
                thread.join(3)
        self.assertFalse(thread.is_alive())
        self.assertEqual(len(outcomes), 1)
        self.assertEqual(outcomes[0].args, ('http_error' if http else 'cancelled',))
        self.assert_safe(outcomes[0])
        self.assertEqual(body.closes, 1)
        self.assertFalse(lazy.opening)
        for _ in range(3):
            self.assertFalse(stream._close_response(owned, unwinding=True))
            self.assertFalse(stream._close_response(lazy, unwinding=True))
        self.assertFalse(owned.close_confirmed)
        self.assertEqual(body.closes, 1)
        export = ring.bounded_export()
        self.assertNotIn('PRIVATE_', json.dumps(export))
        cleanup = [e for e in export['events'] if e['stage'] == 'cleanup']
        self.assertEqual(len(cleanup), int(error_type is not None))
        if cleanup:
            self.assertEqual(cleanup[0]['errors'][0]['type'], error_type.__name__)

    def test_cancel_late_http_oserror(self): self.late(OSError, True)
    def test_cancel_late_http_runtimeerror(self): self.late(RuntimeError, True)
    def test_cancel_late_http_clean(self): self.late(None, True)
    def test_cancel_late_response_oserror(self): self.late(OSError, False)
    def test_cancel_late_response_runtimeerror(self): self.late(RuntimeError, False)
    def test_cancel_late_response_clean(self): self.late(None, False)

    def test_cancel_before_open_no_provider_call(self):
        opener = Mock()
        source = stream.OwnedSource(normal.LazyVertexSource(opener, 'fixture'))
        cancel = stream.Cancellation()
        cancel.cancel()
        with self.assertRaisesRegex(stream.StreamError, '^cancelled$'):
            list(stream.vertex_pcm(source, cancel))
        opener.assert_not_called()
        self.assertTrue(source.close_confirmed)

    def test_nonhttp_open_failure_without_resource_closes_cleanly(self):
        wrapper, fake = self.provider(Body())
        fake.open.side_effect = TimeoutError('PRIVATE_TIMEOUT')
        lazy = normal.LazyVertexSource(lambda text: stream.open_vertex(text, wrapper, authorized=True), 'fixture')
        source = stream.OwnedSource(lazy)
        with patch.object(stream, 'build_opener', return_value=fake):
            with self.assertRaises(stream.StreamError) as caught:
                list(stream.vertex_pcm(source))
        self.assertEqual(caught.exception.args, ('timeout',))
        self.assert_safe(caught.exception)
        self.assertTrue(source.close_confirmed)
        self.assertFalse(lazy.opening)


if __name__ == '__main__':
    unittest.main(verbosity=2)
