import base64
import contextlib
import io
import json
import sys
import unittest
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from voice_pcm_stream import (Cancellation, Identity, Limits, StreamError, hps1_stream, open_vertex, vertex_pcm)


def event(pcm=None, reason=None, **extra):
    c = {}
    if pcm is not None:
        c['content'] = {'parts': [{'inlineData': {'mimeType':'audio/pcm;rate=24000',
                                               'data':base64.b64encode(pcm).decode()}}]}
    if reason: c['finishReason'] = reason
    return b'data: ' + json.dumps({'candidates':[c], **extra}).encode() + b'\r\n\r\n'


class ParserTests(unittest.TestCase):
    def test_early_pcm_and_clean_stop(self):
        class Fragmented(io.BytesIO):
            def read1(self, n): return super().read1(min(n, 8))
        body=Fragmented(event(b'\x01\x00'*100)+event(reason='STOP'))
        chunks=vertex_pcm(body)
        self.assertEqual(next(chunks),b'\x01\x00'*100)
        self.assertLess(body.tell(),len(body.getvalue()))
        self.assertEqual(list(chunks),[])
        self.assertTrue(body.closed)

    def test_failures_close(self):
        cases=[(b'data: broken\n\n','invalid_json'),(b'data: []\n\n','invalid_event'),
               (b'data: {}\n','partial_event'),(b'data: {}','partial_line'),
               (event(b'\x00'),'pcm_alignment'),(event(b'\x00\x00'),'incomplete'),
               (event(b'\x00\x00','MAX_TOKENS'),'provider_finish_error'),
               (b'data: {"error":{"message":"private"}}\n\n','provider_error'),
               (event(b'\x00\x00','STOP')+event(b'\x00\x00'),'after_finish'),
               (b'event: error\ndata: {}\n\n','sse_event'),
               (b'data: {"candidates":[{"content":42}]}\n\n','invalid_event')]
        for raw,code in cases:
            with self.subTest(code=code):
                body=io.BytesIO(raw)
                with self.assertRaisesRegex(StreamError,'^'+code+'$'): list(vertex_pcm(body))
                self.assertTrue(body.closed)

    def test_bad_base64_and_format(self):
        raw=event(b'\0\0','STOP')
        for original,replacement,code in [(b'AAA=',b'!!!=','base64'),
                (b'rate=24000',b'rate=16000','pcm_format')]:
            with self.assertRaisesRegex(StreamError,code):
                list(vertex_pcm(io.BytesIO(raw.replace(original,replacement))))

    def test_bounds(self):
        for raw,limits,code in [(b':'+b'x'*100+b'\n\n',Limits(event_bytes=32),'wire_limit'),
                (event(b'\0\0'*10,'STOP'),Limits(pcm_bytes=4),'pcm_limit'),
                (b': hi\n\n'*10,Limits(wire_bytes=20),'wire_limit')]:
            with self.assertRaisesRegex(StreamError,code): list(vertex_pcm(io.BytesIO(raw),limits=limits))
        chunks=list(vertex_pcm(io.BytesIO(event(b'\0\0'*10000,'STOP'))))
        self.assertTrue(all(len(c)<=8192 for c in chunks))
        self.assertEqual(sum(map(len,chunks)),20000)

    def test_generator_close(self):
        for header_only in [False,True]:
            body=io.BytesIO(event(b'\0\0','STOP'))
            gen=hps1_stream(Identity('r','a',1),body)
            next(gen)
            if not header_only: next(gen)
            gen.close()
            self.assertTrue(body.closed)

    def test_cancel_no_eos(self):
        body=io.BytesIO(event(b'\0\0')+event(reason='STOP'))
        cancel=Cancellation()
        gen=hps1_stream(Identity('r','a',1),body,cancel)
        next(gen); self.assertEqual(next(gen)[0],1)
        cancel.cancel()
        with self.assertRaisesRegex(StreamError,'cancelled'): next(gen)
        self.assertTrue(body.closed)

    def test_no_live_by_default(self):
        with self.assertRaisesRegex(StreamError,'live_not_authorized'): open_vertex('text',None)

    def test_transport_reuses_wrapper_without_live_network(self):
        from unittest.mock import patch
        import types
        from email.message import Message
        calls=[]
        class Response(io.BytesIO):
            headers=Message()
        Response.headers['Content-Type']='text/event-stream'
        response=Response(event(b'\0\0','STOP'))
        class Opener:
            def open(self, req, timeout):
                calls.append((req.full_url, json.loads(req.data),timeout))
                return response
        wrapper=types.SimpleNamespace(DEFAULT_MODEL='gemini-2.5-flash-tts',DEFAULT_VOICE='Kore',
            build_url=lambda model:'https://aiplatform.googleapis.com/v1/projects/test/locations/global/publishers/google/models/'+model+':generateContent',
            compose_prompt=lambda text,style:text,load_style_prompt=lambda:'',
            mint_access_token=lambda path:'fixture-not-a-real-token',resolve_credential_path=lambda:'unused-fixture',
            build_payload=lambda prompt,voice:dict(prompt=prompt,voice=voice))
        with patch('voice_pcm_stream.build_opener',return_value=Opener()), patch.dict('os.environ',{},clear=True):
            result=open_vertex('fixture text',wrapper,authorized=True)
            self.assertEqual(list(vertex_pcm(result)),[b'\0\0'])
        self.assertTrue(calls[0][0].endswith('gemini-2.5-flash-tts:streamGenerateContent?alt=sse'))
        self.assertEqual(calls[0][1],dict(prompt='fixture text',voice='Kore'))
        self.assertEqual(len(calls),1)

        # All transport seams below are fixtures; no credentials or external I/O.
        from http.client import IncompleteRead, BadStatusLine
        from urllib.error import HTTPError
        errors = [(IncompleteRead(b'PRIVATE_PARTIAL', 9), 'transport_error'),
                  (BadStatusLine('PRIVATE_STATUS'), 'transport_error'),
                  (HTTPError('https://fixture.invalid', 500, 'PRIVATE_HTTP', {}, io.BytesIO(b'PRIVATE_BODY')), 'http_error')]
        for error, code in errors:
            with patch('voice_pcm_stream.build_opener') as factory:
                factory.return_value.open.side_effect = error
                with self.assertRaisesRegex(StreamError, '^' + code + '$') as caught:
                    open_vertex('fixture text', wrapper, authorized=True)
                self.assertIsNone(caught.exception.__context__)
                self.assertFalse(hasattr(caught.exception, 'partial'))
        for error in (AttributeError('fixture bug'), TypeError('fixture bug'), RuntimeError('fixture bug')):
            with patch('voice_pcm_stream.build_opener') as factory:
                factory.return_value.open.side_effect = error
                with self.assertRaises(type(error)):
                    open_vertex('fixture text', wrapper, authorized=True)

    def test_deadline_and_multiline_sse(self):
        from unittest.mock import patch
        with patch('voice_pcm_stream.time.monotonic',side_effect=[0,0,999]):
            with self.assertRaisesRegex(StreamError,'deadline'):
                list(vertex_pcm(io.BytesIO(event(b'\0\0','STOP'))))
        raw=event(b'\0\0','STOP').replace(b'{"candidates":',b'{\r\ndata: "candidates":')
        self.assertEqual(list(vertex_pcm(io.BytesIO(raw))),[b'\0\0'])

    def test_identity_and_eos(self):
        with self.assertRaises(ValueError): Identity('../r','a',1)
        with self.assertRaises(ValueError): Identity('r','a',True)
        records=list(hps1_stream(Identity('r','a',7),io.BytesIO(event(b'\0\0'*4,'STOP'))))
        self.assertEqual(records[-1],b'\x02\0\0\0\x08'+(4).to_bytes(8,'big'))

class ReceiptTests(unittest.TestCase):
    def test_gate_rejects_early_stale_partial_failed_and_duplicate(self):
        from voice_pcm_receipt import PlaybackReceiptGate
        identity=Identity('r','a',7)
        receipt=dict(request_id='r',audio_id='a',generation=7,frame_count=12,status='played')
        gate=PlaybackReceiptGate(identity)
        self.assertFalse(gate.accept(receipt,identity))
        self.assertTrue(gate.eos(12))
        for change in [dict(generation=8),dict(audio_id='other'),dict(frame_count=11),
                       dict(status='failed'),dict(status='cancelled'),dict(generation=True)]:
            self.assertFalse(gate.accept({**receipt,**change},identity))
        self.assertFalse(gate.accept(receipt,Identity('r','a',8)))
        self.assertTrue(gate.accept(receipt,identity))
        self.assertFalse(gate.accept(receipt,identity))
        cancelled=PlaybackReceiptGate(identity);cancelled.eos(12);cancelled.invalidate()
        self.assertFalse(cancelled.accept(receipt,identity));self.assertFalse(cancelled.eos(12))


class ParserRegressionTests(unittest.TestCase):
    def test_deadline_after_each_blocking_read_including_eof(self):
        from unittest.mock import patch
        for delayed_eof in (False, True):
            with self.subTest(delayed_eof=delayed_eof):
                now = [0.0]
                class Slow(io.BytesIO):
                    def read1(self, n):
                        chunk = super().read1(n)
                        if bool(chunk) != delayed_eof:
                            now[0] = 151.0
                        return chunk
                records = []
                body = Slow(event(b'\0\0', 'STOP'))
                with patch('voice_pcm_stream.time.monotonic', side_effect=lambda: now[0]):
                    with self.assertRaisesRegex(StreamError, '^deadline$'):
                        records.extend(hps1_stream(Identity('r', 'a', 1), body))
                self.assertFalse(any(record[0] == 2 for record in records))
                if not delayed_eof:
                    self.assertEqual(len(records), 1)  # no late PCM either
                self.assertTrue(body.closed)

    def test_network_errors_are_safe_and_never_emit_eos(self):
        from http.client import IncompleteRead, BadStatusLine, RemoteDisconnected
        import traceback
        for error in (IncompleteRead(b'PRIVATE_PARTIAL', 100),
                      BadStatusLine('PRIVATE_STATUS'), RemoteDisconnected('PRIVATE_REMOTE'),
                      ConnectionResetError('PRIVATE_RESET'), TimeoutError('PRIVATE_TIMEOUT')):
            with self.subTest(error=type(error).__name__):
                class Broken(io.BytesIO):
                    def read1(self, n):
                        raise error
                body = Broken()
                records = []
                try:
                    records.extend(hps1_stream(Identity('r', 'a', 1), body))
                except StreamError as exc:
                    self.assertEqual(str(exc), 'timeout' if isinstance(error, TimeoutError) else 'transport_error')
                    self.assertFalse(hasattr(exc, 'partial'))
                    self.assertIsNone(exc.__context__)
                    diagnostic = json.dumps({'code': str(exc), 'traceback': ''.join(
                        traceback.format_exception(type(exc), exc, exc.__traceback__))})
                    self.assertNotIn('PRIVATE_', diagnostic)
                else:
                    self.fail('expected safe StreamError')
                self.assertFalse(any(record[0] == 2 for record in records))
                self.assertTrue(body.closed)

    def test_programming_errors_are_not_network_or_event_errors(self):
        for error in (RuntimeError('fixture bug'), AttributeError('fixture bug'), TypeError('fixture bug')):
            class Broken(io.BytesIO):
                def read1(self, n):
                    raise error
            with self.assertRaises(type(error)):
                list(vertex_pcm(Broken()))

    def test_sse_delimiters_and_initial_bom_at_every_split(self):
        for delimiter in (b'\r', b'\n', b'\r\n'):
            for bom in (b'', b'\xef\xbb\xbf'):
                raw = bom + event(b'\0\0', 'STOP').replace(b'\r\n', delimiter)
                for split in range(1, len(raw)):
                    class Split(io.BytesIO):
                        def read1(self, n):
                            return super().read1(min(n, split if self.tell() == 0 else 1))
                    with self.subTest(delimiter=delimiter, bom=bool(bom), split=split):
                        self.assertEqual(list(vertex_pcm(Split(raw))), [b'\0\0'])

    def test_sse_bom_and_delimiter_bytes_count_toward_limits(self):
        for delimiter in (b'\r', b'\n', b'\r\n'):
            raw = b'\xef\xbb\xbf' + event(b'\0\0', 'STOP').replace(b'\r\n', delimiter)
            class SingleByte(io.BytesIO):
                def read1(self, n): return super().read1(1)
            for limits in (Limits(wire_bytes=len(raw)), Limits(event_bytes=len(raw))):
                self.assertEqual(list(vertex_pcm(SingleByte(raw), limits=limits)), [b'\0\0'])
            for limits in (Limits(wire_bytes=len(raw)-1), Limits(event_bytes=len(raw)-1)):
                with self.assertRaisesRegex(StreamError, '^wire_limit$'):
                    list(vertex_pcm(SingleByte(raw), limits=limits))
            with self.assertRaisesRegex(StreamError, '^wire_limit$'):
                list(vertex_pcm(SingleByte(b':' + b'x'*40 + delimiter), limits=Limits(event_bytes=32)))

    def test_network_error_after_pcm_has_no_eos(self):
        from http.client import IncompleteRead
        class Broken(io.BytesIO):
            def read1(self, n):
                chunk = super().read1(n)
                if not chunk:
                    raise IncompleteRead(b'PRIVATE_PARTIAL', 100)
                return chunk
        records = []
        with self.assertRaisesRegex(StreamError, '^transport_error$'):
            records.extend(hps1_stream(Identity('r', 'a', 1), Broken(event(b'\0\0', 'STOP'))))
        self.assertEqual([record[0] for record in records], [ord('H'), 1])

    def test_close_network_errors_are_safe(self):
        from http.client import IncompleteRead
        class Broken(io.BytesIO):
            def close(self):
                super().close()
                raise IncompleteRead(b'PRIVATE_CLOSE', 100)
        with self.assertRaisesRegex(StreamError, '^transport_error$') as caught:
            list(hps1_stream(Identity('r', 'a', 1), Broken(event(b'\0\0', 'STOP'))))
        self.assertFalse(hasattr(caught.exception, 'partial'))

    def test_cr_event_is_emitted_without_lookahead_read(self):
        class Paused(io.BytesIO):
            def read1(self, n):
                if self.tell():
                    raise AssertionError('CR event incorrectly waits for another read')
                return super().read1(n)
        body = Paused(event(b'\0\0').replace(b'\r\n', b'\r'))
        chunks = vertex_pcm(body)
        try:
            self.assertEqual(next(chunks), b'\0\0')
        finally:
            chunks.close()

    def test_mixed_delimiters_multiline_and_partial_bom(self):
        from voice_pcm_stream import sse_json
        raw = b'\xef\xbb\xbf: comment\rdata: {\ndata: "ok": true}\r\n\r'
        self.assertEqual(list(sse_json(io.BytesIO(raw), Cancellation(), Limits())), [{'ok': True}])
        for raw in (b'\xef', b'\xef\xbb', b'data: {}\r'):
            with self.assertRaisesRegex(StreamError, '^partial_(line|event)$'):
                list(vertex_pcm(io.BytesIO(raw)))

    def test_bom_is_stripped_only_at_stream_start(self):
        raw = b': comment\n\n\xef\xbb\xbf' + event(b'\0\0', 'STOP')
        with self.assertRaisesRegex(StreamError, '^incomplete$'):
            list(vertex_pcm(io.BytesIO(raw)))

class ErrorPathRegressionTests(unittest.TestCase):
    @staticmethod
    def diagnostic(exc):
        # Follow BOTH links even when __suppress_context__ is true. Locals are
        # intentionally excluded from the safe diagnostic contract.
        import traceback
        seen = set()
        def graph(error):
            if error is None or id(error) in seen:
                return None
            seen.add(id(error))
            return dict(type=type(error).__name__, args=repr(error.args),
                        attributes=repr(vars(error)),
                        traceback=''.join(traceback.format_exception(
                            type(error), error, error.__traceback__)),
                        cause=graph(error.__cause__), context=graph(error.__context__))
        return json.dumps(graph(exc))

    def assert_safe(self, exc):
        self.assertNotIn('SYNTHETIC_SECRET_', self.diagnostic(exc))
        self.assertIsNone(exc.__cause__)
        self.assertIsNone(exc.__context__)

    def test_malformed_json_exception_graph_has_no_payload(self):
        cases = [b'{"SYNTHETIC_SECRET_JSON":',
                 b'{"SYNTHETIC_SECRET_UTF8":"\xff"}',
                 b'{"SYNTHETIC_SECRET_DEPTH":' + b'[' * 2000,
                 b'{"SYNTHETIC_SECRET_INTEGER":' + b'9' * 10000 + b'}']
        for payload in cases:
            with self.subTest(payload_kind=payload[:32]):
                body = io.BytesIO(b'data: ' + payload + b'\n\n')
                records = []
                with self.assertRaisesRegex(StreamError, '^invalid_json$') as caught:
                    records.extend(hps1_stream(Identity('r', 'a', 1), body))
                self.assert_safe(caught.exception)
                self.assertTrue(body.closed)
                self.assertFalse(any(record[0] == 2 for record in records))

    def test_expected_json_parser_exception_branches_are_detached(self):
        from unittest.mock import patch
        from voice_pcm_stream import sse_json
        errors = [json.JSONDecodeError('fixture invalid', 'SYNTHETIC_SECRET_DOC', 0),
                  UnicodeDecodeError('utf8', b'SYNTHETIC_SECRET_BYTES', 0, 1, 'fixture invalid'),
                  RecursionError('SYNTHETIC_SECRET_DEPTH'),
                  ValueError('SYNTHETIC_SECRET_INTEGER')]
        for error in errors:
            with self.subTest(kind=type(error).__name__):
                with patch('voice_pcm_stream.json.loads', side_effect=error):
                    with self.assertRaisesRegex(StreamError, '^invalid_json$') as caught:
                        list(sse_json(io.BytesIO(b'data: {}\n\n'), Cancellation(), Limits()))
                self.assert_safe(caught.exception)

    def test_json_programming_errors_remain_visible(self):
        from unittest.mock import patch
        for error in (TypeError('fixture bug'), AttributeError('fixture bug'), RuntimeError('fixture bug')):
            with patch('voice_pcm_stream.json.loads', side_effect=error):
                with self.assertRaises(type(error)) as caught:
                    list(vertex_pcm(io.BytesIO(b'data: {}\n\n')))
            self.assertIs(caught.exception, error)

    def test_compound_cleanup_preserves_primary_failure(self):
        from http.client import IncompleteRead
        from unittest.mock import patch
        # Includes active GeneratorExit, safe parser errors and programmer bugs.
        for primary in ('programming', 'invalid_json', 'incomplete', 'cancelled', 'deadline', 'transport_error', 'generator_close', 'success'):
            for cleanup in ('network', 'programming'):
                with self.subTest(primary=primary, cleanup=cleanup):
                    cancel = Cancellation()
                    now = [0.0]
                    bug = TypeError('fixture primary bug')
                    close_bug = RuntimeError('fixture cleanup bug')
                    class Broken(io.BytesIO):
                        close_calls = 0
                        def read1(self, n):
                            if primary == 'programming':
                                raise bug
                            if primary == 'cancelled':
                                cancel.event.set()
                                raise IncompleteRead(b'SYNTHETIC_SECRET_READ', 99)
                            if primary == 'deadline':
                                now[0] = 151.0
                                raise IncompleteRead(b'SYNTHETIC_SECRET_READ', 99)
                            if primary == 'transport_error':
                                raise IncompleteRead(b'SYNTHETIC_SECRET_READ', 99)
                            return super().read1(n)
                        def close(self):
                            self.close_calls += 1
                            super().close()
                            if cleanup == 'network':
                                raise IncompleteRead(b'SYNTHETIC_SECRET_CLOSE', 99)
                            raise close_bug
                    raw = (b'data: {"SYNTHETIC_SECRET_JSON":\n\n' if primary == 'invalid_json'
                           else event(b'\0\0', None if primary == 'incomplete' else 'STOP'))
                    body = Broken(raw)
                    records = []
                    gen = hps1_stream(Identity('r', 'a', 1), body, cancel)
                    with patch('voice_pcm_stream.time.monotonic', side_effect=lambda: now[0]):
                        if primary == 'generator_close':
                            records.extend([next(gen), next(gen)])
                            gen.close()  # cleanup must not replace GeneratorExit
                        else:
                            expected = (TypeError if primary == 'programming' else
                                        RuntimeError if primary == 'success' and cleanup == 'programming' else StreamError)
                            with self.assertRaises(expected) as caught:
                                records.extend(gen)
                            if primary == 'programming':
                                self.assertIs(caught.exception, bug)
                            elif primary == 'success' and cleanup == 'programming':
                                self.assertIs(caught.exception, close_bug)
                            else:
                                self.assertEqual(str(caught.exception), 'transport_error' if primary == 'success' else primary)
                            self.assert_safe(caught.exception)
                    self.assertIsNone(cancel.response)
                    self.assertTrue(body.closed)
                    self.assertEqual(body.close_calls, 1)
                    self.assertFalse(any(record[0] == 2 for record in records))


if __name__=='__main__': unittest.main()
