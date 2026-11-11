"""SOURCE-only fake-clock gates, ephemeral HTTP, and secret-marker diagnostics."""
import io
import json
import threading
import unittest
from test_voice_pcm_bridge import Fixture, sse
from voice_pcm_bridge import OfflineBridge, BridgeError, CONNECTION
from voice_pcm_stream import Identity


class LeaseTests(unittest.TestCase):
    def setUp(self):
        self.now = 0.0
        self.opened = []
        self.b = OfflineBridge('fixture', 'x' * 32, self.source,
                               clock=lambda: self.now, lease_seconds=5)
        self.connect()

    def source(self):
        self.opened.append(True)
        return io.BytesIO(sse())

    def connect(self, generation=1):
        self.hello = self.b.connect({'profile': 'fixture', 'device_epoch': 'device'})
        self.scope = {k: self.hello[k] for k in CONNECTION}
        self.full = self.b.arm(self.scope, Identity('request', 'audio', generation))

    def ack(self):
        return self.b.stop_ack(dict(self.hello, closed=True))

    def test_missing_lease_and_exact_deadline_block_admission(self):
        self.ack()
        self.b._lease_deadline = None
        with self.assertRaises(BridgeError):
            self.b.start(self.full)
        self.assertFalse(self.b._acked)
        self.connect(2)
        self.ack()
        self.now = 5
        self.assertTrue(self.b.expire_lease())
        with self.assertRaises(BridgeError):
            self.b.start(self.full)
        self.assertEqual(self.opened, [])

    def test_renew_only_matching_session_and_epoch_before_deadline(self):
        self.ack()
        self.now = 4
        for key in ('session', 'host_epoch', 'device_epoch', 'epoch'):
            value = dict(self.scope)
            value[key] = 99 if key == 'epoch' else 'old'
            with self.assertRaises(BridgeError):
                self.b.heartbeat(value)
        self.assertEqual(self.b._lease_deadline, 5)
        self.assertEqual(self.b.heartbeat(self.scope)['status'], 'renewed')
        self.now = 5
        self.assertFalse(self.b.expire_lease())
        self.now = 9
        with self.assertRaises(BridgeError):
            self.b.heartbeat(self.scope)
        self.assertFalse(self.b._enabled)

    def test_reconnect_needs_new_stop_ack_no_automatic_replay(self):
        self.ack()
        old = dict(self.scope)
        old_ack = dict(self.hello, closed=True)
        self.now = 5
        self.b.expire_lease()
        self.connect(2)
        self.b.heartbeat(self.scope)
        with self.assertRaises(BridgeError):
            self.b.heartbeat(old)
        with self.assertRaises(BridgeError):
            self.b.stop_ack(old_ack)
        with self.assertRaises(BridgeError):
            self.b.start(self.full)
        self.assertFalse(self.b._acked)
        self.assertEqual(self.opened, [])
        self.ack()

    def test_expiry_signals_active_cancel_and_late_finish_cannot_restore(self):
        self.ack()
        marker, cancel, source, identity = self.b.start(self.full)
        self.now = 5
        self.b.expire_lease()
        self.assertTrue(cancel.event.is_set())
        source.close()
        self.b.finish(marker, True, True)
        self.assertFalse(self.b._completed)
        self.assertIsNone(self.b._owner.accepted_receipt())

    def test_heartbeat_off_linearization_both_orderings(self):
        # Both real public methods contend on the retained real mutex.
        for first in ('off', 'heartbeat'):
            self.connect(self.b._last_generation + 1)
            outcomes = {}
            entered, release, contended = (threading.Event() for _ in range(3))
            real_lock, real_check = self.b._lock, self.b._check
            class ObservedLock:
                def __enter__(inner):
                    if not real_lock.acquire(False):
                        contended.set()
                        real_lock.acquire()
                def __exit__(inner, *args):
                    real_lock.release()
            def check(*args, **kwargs):
                real_check(*args, **kwargs)
                if threading.current_thread().name == 'first':
                    entered.set()
                    if not release.wait(2):
                        raise AssertionError('barrier_timeout')
            self.b._lock, self.b._check = ObservedLock(), check
            def run(name, operation):
                try:
                    operation(self.scope)
                    outcomes[name] = 'ok'
                except BridgeError:
                    outcomes[name] = 'rejected'
            operations = (self.b.cancel, self.b.heartbeat) if first == 'off' else (self.b.heartbeat, self.b.cancel)
            a = threading.Thread(name='first', target=run, args=('first', operations[0]))
            b = threading.Thread(name='second', target=run, args=('second', operations[1]))
            try:
                a.start()
                self.assertTrue(entered.wait(2))
                b.start()
                self.assertTrue(contended.wait(2))
            finally:
                release.set()
                a.join(2)
                if b.ident is not None:
                    b.join(2)
                self.b._lock, self.b._check = real_lock, real_check
            self.assertFalse(a.is_alive() or b.is_alive())
            self.assertEqual(outcomes, {'first': 'ok', 'second': 'rejected' if first == 'off' else 'ok'})
            self.assertFalse(self.b._enabled)
            with self.assertRaises(BridgeError):
                self.b.heartbeat(self.scope)

    def test_expiry_at_receipt_and_effect_boundaries_without_sweep(self):
        for boundary in ('receipt', 'effect'):
            self.connect(self.b._last_generation + 1)
            self.ack()
            revision = self.b.set_consent(True)
            marker, cancel, source, identity = self.b.start(self.full)
            self.b.eos(marker, self.full, identity, 16)
            source.close()
            self.b.finish(marker, True, True)
            receipt = dict(self.full, frame_count=16, status='played')
            ticket = self.b.receipt(receipt)['listening_ticket'] if boundary == 'effect' else None
            self.now += 5
            with self.assertRaises(BridgeError) as caught:
                if boundary == 'receipt':
                    self.b.receipt(receipt)
                else:
                    self.b.consume_listening(self.full, ticket, revision, lambda: self.fail('expired effect'))
            self.assertEqual(caught.exception.code, 'lease_expired')
            self.assertIsNone(self.b._owner.accepted_receipt())
            self.assertIsNone(self.b._ticket)

    def test_heartbeat_does_not_extend_stop_ack_or_create_stop_evidence(self):
        self.now = 2
        self.b.heartbeat(self.scope)
        self.assertFalse(self.b._acked)
        self.now = 3
        with self.assertRaises(BridgeError) as caught:
            self.ack()
        self.assertEqual(caught.exception.code, 'ack_expired')
        self.assertEqual(self.opened, [])

    def test_epoch_exhaustion_is_revoked_not_wrapped(self):
        self.b._epoch = 2**63 - 1
        with self.assertRaises(BridgeError) as caught:
            self.connect(2)
        self.assertEqual(caught.exception.code, 'epoch_exhausted')
        self.assertFalse(self.b._enabled)
        self.assertEqual(self.b._epoch, 2**63 - 1)

    def test_invalid_lease_bounds(self):
        for seconds in (0, -1, 61, float('inf'), float('nan'), True):
            with self.assertRaises(ValueError):
                OfflineBridge('fixture', 'x'*32, self.source, lease_seconds=seconds)


class HTTPLeaseTests(unittest.TestCase):
    def test_auth_before_body_and_no_untrusted_correlation(self):
        f = Fixture(clock=lambda: 0)
        try:
            f.ready()
            for token in ('SYNTHETIC_SECRET_TOKEN', 'wrong'):
                self.assertEqual(f.post('/heartbeat', {'session': 'SYNTHETIC_SECRET_BODY'}, token=token)[0], 401)
            self.assertEqual(f.post('/heartbeat', f.scope)[0], 200)
            self.assertEqual(f.post('/heartbeat', dict(f.scope, epoch=True))[0], 409)
            events = f.bridge.diagnostics.snapshot()
            denied = [e for e in events if e['stage'] == 'auth' and e['outcome'] == 'denied']
            self.assertEqual(len(denied), 2)
            self.assertTrue(all('correlation' not in e for e in denied))
            self.assertNotIn('SYNTHETIC_SECRET', f.bridge.diagnostics.export_json())
        finally:
            f.close()

    def test_expiry_revokes_receipt_ticket_and_effect(self):
        now = [0.0]
        f = Fixture(clock=lambda: now[0])
        try:
            revision = f.bridge.set_consent(True)
            f.ready()
            receipt = f.played()
            ticket = f.post('/receipt', receipt)[1]['listening_ticket']
            now[0] = 30
            # The server's cooperative sweep may revoke before this handler's check.
            status, body = f.post('/heartbeat', f.scope)
            self.assertIn((status, body.get('error')),
                          ((409, 'revoked'), (410, 'lease_expired')))
            self.assertEqual(f.post('/receipt', receipt)[0], 409)
            with self.assertRaises(BridgeError):
                f.bridge.consume_listening(f.full, ticket, revision, lambda: self.fail('expired effect'))
            self.assertIsNone(f.bridge._ticket)
            self.assertIsNone(f.bridge._owner.accepted_receipt())
        finally:
            f.close()

    def test_expiry_assertion_rejects_wrong_status_code_pairs(self):
        from unittest.mock import patch
        real_post = Fixture.post
        for wrong in ((409, 'stale_scope'), (409, 'lease_expired'), (410, 'revoked')):
            def post(fixture, path, *args, **kwargs):
                status, body = real_post(fixture, path, *args, **kwargs)
                if path == '/heartbeat':
                    return wrong[0], {'error': wrong[1]}
                return status, body
            with self.subTest(wrong=wrong), patch.object(Fixture, 'post', post):
                with self.assertRaises(AssertionError):
                    self.test_expiry_revokes_receipt_ticket_and_effect()

    def test_stream_metrics_are_transport_not_acoustic(self):
        f = Fixture()
        try:
            f.ready()
            self.assertEqual(f.post('/receipt', f.played())[0], 200)
            events = f.bridge.diagnostics.snapshot()
            stages = [e['stage'] for e in events]
            for stage in ('connect', 'stop_ack', 'admit', 'first_chunk', 'eos', 'cleanup', 'receipt'):
                self.assertIn(stage, stages)
            for stage in ('first_chunk', 'eos', 'receipt'):
                event = next(e for e in events if e['stage'] == stage)
                self.assertGreaterEqual(event['elapsed_ms'], 0)
                self.assertIn('request_id', event['correlation'])
            self.assertNotIn('acoustic', f.bridge.diagnostics.export_json())
        finally:
            f.close()


class DiagnosticsTests(unittest.TestCase):
    def test_shared_cause_context_keeps_both_labeled_references(self):
        from voice_pcm_diagnostics import safe_errors
        root, shared = RuntimeError('SYNTHETIC_SECRET'), ValueError('SYNTHETIC_SECRET')
        root.__cause__ = root.__context__ = shared
        nodes = safe_errors(root)
        self.assertEqual(len(nodes), 2)
        self.assertEqual(nodes[0]['links'], [
            {'edge': 'cause', 'ref': 1}, {'edge': 'context', 'ref': 1}])
        self.assertEqual(nodes[1]['links'], [])
        self.assertTrue(nodes[0]['suppressed'])
        self.assertEqual((nodes[1]['index'], nodes[1]['parent'], nodes[1]['edge']),
                         (1, 0, 'cause'))  # Legacy first-discovery fields remain.

    def test_cycles_keep_self_and_back_references_without_duplicate_nodes(self):
        from voice_pcm_diagnostics import safe_errors
        root, child = RuntimeError(), ValueError()
        root.__cause__ = child
        root.__context__ = root
        child.__cause__ = child.__context__ = root
        nodes = safe_errors(root)
        self.assertEqual(len(nodes), 2)
        self.assertEqual(nodes[0]['links'], [
            {'edge': 'cause', 'ref': 1}, {'edge': 'context', 'ref': 0}])
        self.assertEqual(nodes[1]['links'], [
            {'edge': 'cause', 'ref': 0}, {'edge': 'context', 'ref': 0}])

    def test_truncated_graph_retains_bounded_edges_and_redacts_payloads(self):
        from voice_pcm_diagnostics import DiagnosticRing
        from http.client import IncompleteRead
        secret = 'SYNTHETIC_SECRET_BODY_TOKEN_PCM_LOCAL'
        errors = [json.JSONDecodeError(secret, secret, 0) for _ in range(9)]
        errors[1] = IncompleteRead(secret.encode(), 99)
        for index, error in enumerate(errors):
            error.__cause__ = errors[(index + 1) % len(errors)]
            error.__context__ = errors[0]
            error.doc = error.partial = error.code = secret
        try:
            raise errors[0]
        except Exception as error:
            ring = DiagnosticRing()
            ring.emit('stream_error', error=error)
        nodes = ring.snapshot()[0]['errors']
        self.assertEqual(len(nodes), 8)
        self.assertEqual(sum(len(n['links']) for n in nodes), 16)
        for index, node in enumerate(nodes):
            self.assertEqual(node['index'], index)
            self.assertEqual(node['links'][1], {'edge': 'context', 'ref': 0})
            self.assertEqual(node['links'][0],
                             {'edge': 'cause', 'ref': index + 1} if index < 7 else
                             {'edge': 'cause', 'ref': None, 'truncated': True})
            self.assertEqual(set(node), {'index', 'parent', 'edge', 'type', 'suppressed', 'links'})
        self.assertNotIn('SYNTHETIC_SECRET', ring.export_json())

    def test_retention_copy_and_exception_graph_redaction(self):
        from voice_pcm_diagnostics import DiagnosticRing
        from http.client import IncompleteRead
        now = [0.0]
        ring = DiagnosticRing(capacity=3, retention_seconds=5, clock=lambda: now[0], wall_clock=lambda: 100)
        secret = 'SYNTHETIC_SECRET_BODY_TOKEN_PCM'
        try:
            try:
                raise json.JSONDecodeError(secret, secret, 0)
            except ValueError:
                raise IncompleteRead(secret.encode(), 90) from None
        except Exception as error:
            ring.emit('stream_error', error=error, first_error=True,
                      scope={'request_id': secret, 'audio_id': secret, 'session': secret, 'generation': 1})
        ring.emit('cleanup', outcome='unproven', error=OSError(secret), first_error=False)
        events = ring.snapshot()
        self.assertEqual(len(events[0]['errors']), 2)
        self.assertTrue(events[0]['errors'][0]['suppressed'])
        self.assertTrue(events[0]['first_error'])
        self.assertFalse(events[1]['first_error'])
        self.assertNotIn(secret, ring.export_json())
        events[0]['errors'][0]['type'] = secret
        self.assertNotIn(secret, ring.export_json())
        for _ in range(8):
            ring.emit('auth', outcome='denied')
        self.assertEqual(len(ring.snapshot()), 3)
        now[0] = 5
        self.assertEqual(ring.snapshot(), [])

    def test_exception_graph_cycles_bounded_unknown_types_and_codes(self):
        from voice_pcm_diagnostics import DiagnosticRing
        class SYNTHETIC_SECRET_CLASS(Exception):
            pass
        error = SYNTHETIC_SECRET_CLASS('SYNTHETIC_SECRET_ARGS')
        error.__context__ = error
        error.code = 'SYNTHETIC_SECRET_CODE'
        error.doc = error.partial = 'SYNTHETIC_SECRET_FIELDS'
        ring = DiagnosticRing()
        ring.emit('stream_error', error=error)
        self.assertNotIn('SYNTHETIC_SECRET', ring.export_json())
        self.assertEqual(len(ring.snapshot()[0]['errors']), 1)

    def test_primary_failure_distinct_from_cleanup_uncertainty(self):
        class Broken(io.BytesIO):
            def close(self):
                raise OSError('SYNTHETIC_SECRET_CLOSE')
        f = Fixture(factory=lambda: Broken(b'data: {\n\n'))
        try:
            f.ready()
            f.post('/stream', f.full)
            events = f.bridge.diagnostics.snapshot()
            primary = next(e for e in events if e['stage'] == 'stream_error')
            cleanup = next(e for e in events if e['stage'] == 'cleanup')
            self.assertTrue(primary['first_error'])
            self.assertFalse(cleanup['first_error'])
            self.assertEqual(cleanup['outcome'], 'unproven')
            self.assertLess(primary['sequence'], cleanup['sequence'])
            self.assertNotIn('SYNTHETIC_SECRET', f.bridge.diagnostics.export_json())
        finally:
            f.close()
            for source in f.sources:
                io.BytesIO.close(source)


if __name__ == '__main__':
    unittest.main()
