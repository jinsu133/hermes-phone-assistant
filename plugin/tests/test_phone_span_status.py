"""Offline real plugin fixture; run as a script in a private HOME/TMPDIR.
No phone, provider, recording or audible acceptance. Network audit from fixture.
"""
import asyncio
import importlib
import json
import threading
import unittest
from pathlib import Path
from unittest import mock
import test_task_owner_contract as fixture


class PhoneSpanStatus(fixture.TaskOwnerContract):
    def setUp(self):
        super().setUp()
        self.start(runtime=True)
        self.now = 11
        self.link.events._clock = lambda: self.now
        self.link.events.started = 0
        self.link.events.last = 0

    def bind(self, handler, tool='phone_act'):
        return self.link.bind_tool_voice({}, handler, session_id='fixture-session',
                                         turn_id='task', tool_name=tool)

    def event(self):
        return self.link.events.get(self.request)

    def test_span_sync_success_and_exception(self):
        def handler(args):
            self.assertEqual(self.event()['kind'], 'phone')
            return {'ok': True}
        self.assertEqual(self.bind(handler), {'ok': True})
        self.assertIsNone(self.event())
        self.link.events.begin(self.request); self.now += 11
        def fail(args):
            self.assertEqual(self.event()['kind'], 'phone')
            raise LookupError('fixture')
        with self.assertRaises(LookupError): self.bind(fail)
        self.assertIsNone(self.event())
        self.assertIsNone(self.link.tool_voice_request.get())

    def test_span_async_actual_await_success_exception_cancel(self):
        for ending in ('success', 'error', 'cancel'):
            with self.subTest(ending=ending):
                self.link.events.begin(self.request); self.now += 11
                async def scenario():
                    entered, release = asyncio.Event(), asyncio.Event()
                    async def handler(args):
                        self.assertEqual(self.link.tool_voice_request.get(), self.request)
                        entered.set(); await release.wait()
                        if ending == 'error': raise LookupError('fixture')
                        return {'ok': True}
                    value = self.bind(handler)
                    self.assertIsNone(self.link.tool_voice_request.get())
                    task = asyncio.create_task(value)
                    await entered.wait()
                    self.assertEqual(self.event()['kind'], 'phone')
                    if ending == 'cancel': task.cancel()
                    else: release.set()
                    if ending == 'success': self.assertEqual(await task, {'ok': True})
                    else:
                        with self.assertRaises(asyncio.CancelledError if ending == 'cancel' else LookupError):
                            await task
                    self.assertIsNone(self.event())
                    self.assertIsNone(self.link.tool_voice_request.get())
                asyncio.run(scenario())

    def test_span_nested_suppressed_child_does_not_clear_parent(self):
        def parent(args):
            before = self.event()
            self.bind(lambda a: {'ok': True}, 'phone_observe')
            self.assertEqual(self.event(), before)
            return {'ok': True}
        self.bind(parent)
        self.assertIsNone(self.event())

    def test_span_parallel_suppressed_owner_cannot_clear_emitter(self):
        async def scenario():
            release, entered = asyncio.Event(), asyncio.Event()
            async def first(args): entered.set(); await release.wait(); return {'ok': True}
            task = asyncio.create_task(self.bind(first))
            await entered.wait(); before = self.event()
            async def second(args): return {'ok': True}
            await self.bind(second, 'phone_observe')
            self.assertEqual(self.event(), before)
            release.set(); await task
            self.assertIsNone(self.event())
        asyncio.run(scenario())

    def test_span_emitter_finishes_while_other_tool_remains(self):
        async def scenario():
            gates = [asyncio.Event(), asyncio.Event()]
            entered = [asyncio.Event(), asyncio.Event()]
            async def handler(i): entered[i].set(); await gates[i].wait(); return {'ok': True}
            a = asyncio.create_task(self.bind(lambda _: handler(0)))
            await entered[0].wait()
            b = asyncio.create_task(self.bind(lambda _: handler(1)))
            await entered[1].wait()
            gates[0].set(); await a
            self.assertIsNone(self.event())  # lifetime is triggering span, not global activity
            gates[1].set(); await b
        asyncio.run(scenario())

    def test_span_throttled_phone_preserves_accepted(self):
        self.now = 1
        before = self.event()
        self.bind(lambda _: {'ok': True})
        self.assertEqual(self.event(), before)
        self.assertEqual(before['kind'], 'accepted')

    def test_span_new_request_same_ids_and_seq_survives_old_completion(self):
        def handler(args):
            self.link.events.begin(self.request)  # also cover reused request/seq
            self.now += 11
            self.link.events.emit(self.request, 'phone')
            self.new = self.event()
            return {'ok': True}
        self.bind(handler)
        self.assertEqual(self.event(), self.new)
        def other(args):
            self.link.events.begin('new-request')
            return {'ok': True}
        self.bind(other)
        self.assertEqual(self.link.events.get('new-request')['kind'], 'accepted')

    def test_span_new_generic_event_survives(self):
        def handler(args):
            self.now += 11
            self.link.events.emit(self.request, 'research')
            return {'ok': True}
        self.bind(handler)
        self.assertEqual(self.event()['kind'], 'research')

    def test_span_evidence_explanation_survives(self):
        def handler(args):
            receipt = self.link.events.record_evidence(self.request, 'phone_observe')
            self.link.events.explain(self.request, dict(finding='확인했습니다.', basis='증거를 비교합니다.',
                next_step='다음 단계를 확인합니다.', evidence_ids=[receipt]))
            return {'ok': True}
        self.bind(handler)
        self.assertEqual(self.event()['kind'], 'explanation')

    def test_span_successful_read_receipt_survives_expiry(self):
        value = self.bind(lambda _: json.dumps({'ok': True, 'result': {'nodes': []}}), 'phone_observe')
        receipt = json.loads(value)['voice_evidence_id']
        self.assertIsNone(self.event())
        self.assertEqual(self.link.events.evidence[receipt], 'phone_observe')
        self.link.events.explain(self.request, dict(finding='읽기를 마쳤습니다.', basis='반환 증거가 있습니다.',
            next_step='다음 단계를 확인합니다.', evidence_ids=[receipt]))
        self.assertEqual(self.event()['kind'], 'explanation')

    def test_span_poll_buffered_cache_and_final_reply(self):
        def handler(args):
            self.audio = self.link.reply(self.request, 5)[1]['event']['audio_id']
            self.assertFalse(self.link._stale_progress(self.link.outbox.get(self.audio)))
            return {'ok': True}
        self.bind(handler)
        self.assertNotIn('event', self.link.reply(self.request, 5)[1])
        import tools.tts_tool as tts
        self.link._tts_cache = dict(request_id=self.audio, parts=[b'fixture'], at=__import__('time').monotonic())
        with mock.patch.object(tts, 'text_to_speech_tool') as provider:
            self.assertEqual(self.link.tts(self.audio, protocol=5)[0], 410)
            provider.assert_not_called()
        self.link.observe_reply(session_id='fixture-session', turn_id='task',
            user_message=self.link.format_voice_message('티맵에서 서울역 검색해 줘'), assistant_response='최종 답변')
        self.assertTrue(self.link.reply(self.request, 5)[1]['ready'])
        def synth(text, output_path):
            Path(output_path).write_bytes(b'fixture-not-real-audio')
            return json.dumps(dict(success=True, file_path=output_path))
        with mock.patch.object(tts, 'text_to_speech_tool', side_effect=synth):
            self.assertEqual(self.link.tts(self.request, protocol=5)[0], 200)

    def test_span_buffered_generation_inflight_expired_before_return(self):
        import tools.tts_tool as tts
        entered, release = threading.Event(), threading.Event()
        outputs = []
        def synth(text, output_path):
            entered.set()
            if not release.wait(3): raise RuntimeError('fixture_timeout')
            Path(output_path).write_bytes(b'fixture')
            return json.dumps(dict(success=True, file_path=output_path))
        def handler(args):
            audio = self.link.reply(self.request, 5)[1]['event']['audio_id']
            self.worker = threading.Thread(target=lambda: outputs.append(self.link.tts(audio, protocol=5)[0]))
            self.worker.start(); self.assertTrue(entered.wait(3))
            return {'ok': True}
        with mock.patch.object(tts, 'text_to_speech_tool', side_effect=synth):
            try: self.bind(handler)
            finally: release.set()
            self.worker.join(3)
        self.assertFalse(self.worker.is_alive())
        self.assertEqual(outputs, [410])
        self.assertIsNone(self.link._tts_cache)

    def test_span_normal_pcm_freshness_item_start_and_current_stream(self):
        package = self.link.__class__.__module__.rsplit('.', 1)[0]
        normal = importlib.import_module(package + '.voice_normal_stream')
        bridge = normal.NormalStreamBridge(self.link, 'x'*32, lambda _: self.fail('provider forbidden'))
        hello = bridge.connect(dict(profile='voicelink', device_epoch='fixture-device'))
        bridge.stop_ack(dict(hello, closed=True))  # synthetic stop ACK, not device evidence
        scope = {k: hello[k] for k in normal.CONNECTION}
        def handler(args):
            audio = self.link.reply(self.request, 5)[1]['event']['audio_id']
            self.descriptor = dict(scope, request_id=self.request, audio_id=audio, generation=1, kind='progress')
            bridge.descriptor(self.descriptor)
            bridge._binding_current()
            return {'ok': True}
        self.bind(handler)
        full = {k: self.descriptor[k] for k in normal.FULL}
        for call in (lambda: bridge.descriptor(dict(self.descriptor, generation=2)),
                     bridge._binding_current, lambda: bridge.start(full)):
            with self.assertRaisesRegex(normal.BridgeError, 'stale_scope'): call()
        bridge.shutdown()

    def test_span_normal_pcm_active_stream_rechecked_before_next_chunk(self):
        package = self.link.__class__.__module__.rsplit('.', 1)[0]
        normal = importlib.import_module(package + '.voice_normal_stream')
        bridge = normal.NormalStreamBridge(self.link, 'x'*32, lambda _: self.fail('provider forbidden'))
        hello = bridge.connect(dict(profile='voicelink', device_epoch='fixture-device'))
        bridge.stop_ack(dict(hello, closed=True))
        scope = {k: hello[k] for k in normal.CONNECTION}
        def handler(args):
            audio = self.link.reply(self.request, 5)[1]['event']['audio_id']
            descriptor = dict(scope, request_id=self.request, audio_id=audio, generation=1, kind='progress')
            bridge.descriptor(descriptor)
            self.full = {k: descriptor[k] for k in normal.FULL}
            self.stream = bridge.start(self.full)  # allocates lazy source, no provider I/O
            bridge.current_stream(self.stream[0], self.full)
            return {'ok': True}
        try:
            self.bind(handler)
            with self.assertRaisesRegex(normal.BridgeError, 'stale_scope'):
                bridge.current_stream(self.stream[0], self.full)
        finally:
            if hasattr(self, 'stream'):
                self.stream[2].close()
                bridge.finish(self.stream[0], False, True)
            bridge.shutdown()

    def test_span_private_identity_not_only_reused_public_fields(self):
        events = self.link.events
        # Force equal public IDs to prove the private span identity is the last gate.
        module = importlib.import_module(events.__class__.__module__)
        with mock.patch.object(module.secrets, 'token_urlsafe', return_value='same-audio'):
            old = events.begin_phone_span(self.request)
            before = self.event()
            events.begin(self.request); self.now += 11
            new = events.begin_phone_span(self.request)
            self.assertEqual(self.event(), before)
            events.end_phone_span(old)
            self.assertEqual(self.event(), before)
            events.end_phone_span(new)
            self.assertIsNone(self.event())
            events.end_phone_span(new)  # idempotent

    def test_span_threaded_old_owner_cannot_remove_new_scoped_event(self):
        events = self.link.events
        old = events.begin_phone_span(self.request)
        ready, release = threading.Event(), threading.Event()
        def old_completion():
            ready.set()
            if release.wait(3): events.end_phone_span(old)
        thread = threading.Thread(target=old_completion)
        thread.start(); self.assertTrue(ready.wait(3))
        events.begin('replacement'); self.now += 11
        new = events.begin_phone_span('replacement')
        before = events.get('replacement')
        release.set(); thread.join(3)
        self.assertFalse(thread.is_alive())
        self.assertEqual(events.get('replacement'), before)
        events.end_phone_span(new)
        self.assertIsNone(events.get('replacement'))


if __name__ == '__main__':
    suite = unittest.TestSuite(PhoneSpanStatus(n) for n in unittest.defaultTestLoader.getTestCaseNames(PhoneSpanStatus)
                               if n.startswith('test_span_'))
    result = unittest.TextTestRunner(verbosity=2).run(suite)
    raise SystemExit(not result.wasSuccessful())
