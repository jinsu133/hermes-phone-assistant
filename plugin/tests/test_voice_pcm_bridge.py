"""Real ephemeral authenticated HTTP, synthetic sources/credentials only."""
import base64
from contextlib import closing
import http.client
import io
import json
from pathlib import Path
import secrets
import socket
import struct
import sys
import threading
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from voice_pcm_bridge import OfflineBridge, OfflineBridgeServer, BridgeError, CONNECTION
from voice_pcm_stream import Identity


def sse(stop=True):
    candidate = {'content': {'parts': [{'inlineData': {
        'mimeType': 'audio/pcm;rate=24000', 'data': base64.b64encode(b'\0\0' * 16).decode()}}]}}
    data = b'data: ' + json.dumps({'candidates': [candidate]}).encode() + b'\n\n'
    if stop:
        data += b'data: {"candidates":[{"finishReason":"STOP"}]}\n\n'
    return data


class Fixture:
    def __init__(self, factory=None, clock=None):
        self.token = secrets.token_urlsafe(32)
        self.sources = []
        def source():
            result = io.BytesIO(sse()) if factory is None else factory()
            self.sources.append(result)
            return result
        kwargs = {} if clock is None else {'clock': clock}
        self.bridge = OfflineBridge('fixture', self.token, source, **kwargs)
        self.server = OfflineBridgeServer(self.bridge)
        self.thread = threading.Thread(target=self.server.serve_forever, kwargs={'poll_interval': .02})
        self.thread.start()

    def close(self):
        self.bridge.shutdown()
        self.server.shutdown()
        self.thread.join(3)
        self.server.server_close()
        assert not self.thread.is_alive()
        assert not getattr(self.server, 'unexpected_error', False)

    def post(self, path, data, token=None, headers=None):
        body = json.dumps(data).encode() if not isinstance(data, bytes) else data
        h = {'Authorization': 'Bearer ' + (self.token if token is None else token),
             'Content-Type': 'application/json'}
        h.update(headers or {})
        with closing(http.client.HTTPConnection(*self.server.server_address, timeout=3)) as conn:
            conn.request('POST', path, body, h)
            response = conn.getresponse()
            status, result = response.status, response.read()
            if response.getheader('Content-Type') == 'application/json':
                result = json.loads(result)
            return status, result

    def connect(self, generation=1):
        code, hello = self.post('/connect', {'profile': 'fixture', 'device_epoch': 'device-process-A'})
        assert code == 200
        self.scope = {k: hello[k] for k in CONNECTION}
        self.ack = dict(hello, closed=True)
        self.full = self.bridge.arm(self.scope, Identity('request', 'audio', generation))
        return hello

    def ready(self):
        self.connect()
        assert self.post('/stop-ack', self.ack)[0] == 200

    def played(self):
        status, wire = self.post('/stream', self.full)
        assert status == 200
        assert wire.endswith(b'\x02' + struct.pack('>Iq', 8, 16))
        return dict(self.full, frame_count=16, status='played')


class BridgeHTTPTest(unittest.TestCase):
    def setUp(self):
        self.f = Fixture()

    def tearDown(self):
        self.f.close()

    def test_valid_stream_receipt_duplicate_and_effect_gate(self):
        f = self.f
        revision = f.bridge.set_consent(True)
        f.ready()
        receipt = f.played()
        code, result = f.post('/receipt', receipt)
        self.assertEqual(code, 200)
        self.assertEqual(f.post('/receipt', receipt)[0], 409)
        effects = []
        f.bridge.consume_listening(f.full, result['listening_ticket'], revision, lambda: effects.append('fixture'))
        self.assertEqual(effects, ['fixture'])
        with self.assertRaises(BridgeError):
            f.bridge.consume_listening(f.full, result['listening_ticket'], revision, lambda: effects.append('bad'))
        self.assertTrue(all(s.closed for s in f.sources))

    def test_invalid_auth_before_body_or_source(self):
        f = self.f
        f.ready()
        for path in ('/stream', '/receipt', '/cancel', '/stop-ack', '/connect'):
            self.assertEqual(f.post(path, b'synthetic-secret-body', token='wrong')[0], 401)
        self.assertEqual(f.sources, [])
        # Auth failure must not wait for declared body bytes.
        with socket.create_connection(f.server.server_address, timeout=2) as sock:
            sock.sendall(b'POST /stream HTTP/1.0\r\nAuthorization: Bearer invalid\r\nContent-Length: 4096\r\n\r\n')
            self.assertIn(b' 401 ', sock.recv(4096))

    def test_full_scope_checked_before_source(self):
        f = self.f
        f.ready()
        for key in f.full:
            wrong = dict(f.full)
            wrong[key] = wrong[key] + 1 if type(wrong[key]) is int else wrong[key] + 'x'
            self.assertIn(f.post('/stream', wrong)[0], (403, 409), key)
        wrong = dict(f.full, generation=True)
        self.assertEqual(f.post('/stream', wrong)[0], 409)
        self.assertEqual(f.sources, [])

    def test_full_receipt_scope_checked(self):
        f = self.f
        f.ready()
        receipt = f.played()
        for key in f.full:
            wrong = dict(receipt)
            wrong[key] = wrong[key] + 1 if type(wrong[key]) is int else wrong[key] + 'x'
            self.assertIn(f.post('/receipt', wrong)[0], (403, 409), key)
        self.assertEqual(f.post('/receipt', dict(receipt, frame_count=True))[0], 409)
        self.assertEqual(f.post('/receipt', receipt)[0], 200)

    def test_missing_and_duplicate_stop_ack(self):
        f = self.f
        f.connect()
        self.assertEqual(f.post('/stream', f.full)[0], 409)
        self.assertEqual(f.sources, [])
        self.assertEqual(f.post('/stop-ack', dict(f.ack, closed=False))[0], 409)
        self.assertEqual(f.post('/stop-ack', f.ack)[0], 200)
        self.assertEqual(f.post('/stop-ack', f.ack)[0], 409)
        f.played()
        self.assertEqual(f.post('/stream', f.full)[0], 409)

    def test_expired_stop_ack(self):
        self.f.close()
        now = [0.0]
        self.f = f = Fixture(clock=lambda: now[0])
        f.connect()
        now[0] = 4
        self.assertEqual(f.post('/stop-ack', f.ack), (410, {'error': 'ack_expired'}))
        self.assertEqual(f.post('/stream', f.full)[0], 409)
        self.assertFalse(f.sources)

    def test_reconnect_old_ack_receipt_and_no_auto_replay(self):
        f = self.f
        revision = f.bridge.set_consent(True)
        f.ready()
        receipt = f.played()
        ticket = f.post('/receipt', receipt)[1]['listening_ticket']
        old_scope, old_full, old_ack = f.scope, f.full, f.ack
        f.connect(2)
        self.assertEqual(f.post('/stop-ack', old_ack)[0], 409)
        self.assertEqual(f.post('/receipt', receipt)[0], 409)
        self.assertEqual(f.post('/cancel', old_scope)[0], 409)
        with self.assertRaises(BridgeError):
            f.bridge.consume_listening(old_full, ticket, revision, lambda: self.fail('stale effect'))
        self.assertEqual(len(f.sources), 1)
        self.assertEqual(f.post('/stream', f.full)[0], 409)
        self.assertEqual(f.post('/stop-ack', f.ack)[0], 200)
        f.played()

    def test_same_device_restart_resets_the_generation_baseline(self):
        """An app/service restart keeps device_epoch but restarts its generation
        counter at 1. A reconnect must start a fresh monotonic baseline, otherwise
        every later stream is refused with 409 admission forever."""
        f = self.f
        f.ready()
        f.connect(9)
        self.assertEqual(f.post('/stop-ack', f.ack)[0], 200)
        self.assertEqual(f.played()['generation'], 9)
        # Keep the OLD full scope, not just its generation number: the safety claim
        # is that the previous epoch survives on its own session/epoch fields.
        old_full = dict(f.full)
        # Same device identity, restarted counter.
        f.connect(1)
        self.assertEqual(f.post('/stop-ack', f.ack)[0], 200)
        status, wire = f.post('/stream', f.full)
        self.assertEqual(status, 200)
        # The old epoch must still be rejected even with its original full scope.
        self.assertEqual(old_full['session'] == f.full['session'], False)
        self.assertEqual(f.post('/stream', old_full)[0], 409)
        self.assertEqual(f.post('/stream', dict(old_full, generation=9))[0], 409)

    def test_rejected_device_swap_does_not_clear_the_liveness_evidence(self):
        """A refused cross-device connect must leave the old lease intact.

        Previously _revoke() ran before the device check, so the first refusal
        cleared _lease_deadline and an immediate retry looked uncontested.
        """
        f = self.f
        f.ready()
        self.assertEqual(f.post('/heartbeat', f.scope)[0], 200)
        self.assertIsNotNone(f.bridge._lease_deadline)
        other = {'profile': 'fixture', 'device_epoch': 'device-process-B'}
        self.assertEqual(f.post('/connect', other)[0], 423)
        # Evidence must survive the rejection.
        self.assertIsNotNone(f.bridge._lease_deadline)
        self.assertEqual(f.post('/connect', other)[0], 423)
        self.assertEqual(f.bridge._device, 'device-process-A')

    def test_reconnect_requires_explicit_arm(self):
        f = self.f
        f.ready()
        old_full = f.full
        code, hello = f.post('/connect', {'profile': 'fixture', 'device_epoch': 'device-process-A'})
        self.assertEqual(code, 200)
        self.assertEqual(f.post('/stop-ack', dict(hello, closed=True))[0], 200)
        self.assertEqual(f.post('/stream', old_full)[0], 409)
        self.assertFalse(f.sources)

    def test_device_restart_fails_closed(self):
        f = self.f
        f.ready()
        self.assertEqual(f.post('/connect', {'profile': 'fixture', 'device_epoch': 'device-process-B'})[0], 423)
        # The refusal must not have destroyed the live session it was protecting:
        # the old scope still streams, and the new device stays refused.
        self.assertEqual(f.post('/stream', f.full)[0], 200)
        self.assertEqual(f.post('/connect', {'profile': 'fixture', 'device_epoch': 'device-process-B'})[0], 423)

    def test_new_device_identity_is_accepted_once_the_old_lease_is_gone(self):
        """A different device_epoch is only a hijack risk while the previous owner
        can still run. After its lease lapses nothing owns the stream, so refusing
        the new identity forever would just wedge the app."""
        f = self.f
        f.ready()
        # While the previous session is live, a new identity is still refused.
        self.assertEqual(f.post('/connect', {'profile': 'fixture', 'device_epoch': 'device-process-B'})[0], 423)
        # Let the lease lapse with no stream active, then the new identity is fine.
        f.bridge._lease_deadline = 0.0
        self.assertEqual(f.post('/connect', {'profile': 'fixture', 'device_epoch': 'device-process-B'})[0], 200)

    def test_host_restart_old_credentials_and_scope_invalid(self):
        f = self.f
        f.ready()
        receipt = f.played()
        other = Fixture()
        try:
            other.ready()
            self.assertEqual(other.post('/receipt', receipt, token=f.token)[0], 401)
            self.assertEqual(other.post('/receipt', receipt)[0], 409)
            self.assertNotEqual(other.full['host_epoch'], f.full['host_epoch'])
        finally:
            other.close()

    def test_receipt_before_eos_and_cancel_before_receipt(self):
        f = self.f
        f.ready()
        receipt = dict(f.full, frame_count=16, status='played')
        self.assertEqual(f.post('/receipt', receipt)[0], 409)
        f.played()
        self.assertEqual(f.post('/cancel', f.scope)[0], 200)
        self.assertEqual(f.post('/receipt', receipt)[0], 409)

    def test_receipt_before_cancel_revokes_ticket(self):
        f = self.f
        revision = f.bridge.set_consent(True)
        f.ready()
        ticket = f.post('/receipt', f.played())[1]['listening_ticket']
        self.assertEqual(f.post('/cancel', f.scope)[0], 200)
        with self.assertRaises(BridgeError):
            f.bridge.consume_listening(f.full, ticket, revision, lambda: self.fail('cancelled effect'))

    def test_consent_revision_checked_at_effect(self):
        f = self.f
        revision = f.bridge.set_consent(True)
        f.ready()
        ticket = f.post('/receipt', f.played())[1]['listening_ticket']
        f.bridge.set_consent(False)
        f.bridge.set_consent(True)
        with self.assertRaises(BridgeError):
            f.bridge.consume_listening(f.full, ticket, revision, lambda: self.fail('old consent'))

    def test_malformed_and_bounded_http(self):
        f = self.f
        for body in (b'{secret', b'{"profile":"fixture","profile":"fixture","device_epoch":"A"}', b'[]'):
            code, result = f.post('/connect', body)
            self.assertEqual(code, 400)
            self.assertNotIn('secret', str(result))
        self.assertEqual(f.post('/connect', b'x' * 4097)[0], 413)
        self.assertEqual(f.post('/connect', {}, headers={'Content-Type': 'text/plain'})[0], 415)
        self.assertEqual(f.post('/unknown', {})[0], 404)
        self.assertEqual(f.post('/connect?token=secret', {})[0], 404)
        self.assertEqual(f.post('/connect', {}, headers={'Transfer-Encoding': 'chunked'})[0], 400)

    def test_truncated_provider_closes_no_receipt(self):
        self.f.close()
        self.f = f = Fixture(factory=lambda: io.BytesIO(sse(False)))
        f.ready()
        status, wire = f.post('/stream', f.full)
        self.assertEqual(status, 200)  # headers preceded parser failure
        self.assertFalse(wire.endswith(b'\x02' + struct.pack('>Iq', 8, 16)))
        self.assertEqual(f.post('/receipt', dict(f.full, frame_count=16, status='played'))[0], 409)
        self.assertTrue(f.sources[0].closed)

    def test_close_failure_poison_survives_reconnect(self):
        self.f.close()
        class BrokenClose(io.BytesIO):
            def close(self):
                super().close()
                raise OSError('synthetic-close-secret')
        self.f = f = Fixture(factory=lambda: BrokenClose(sse()))
        f.ready()
        self.assertEqual(f.post('/stream', f.full)[0], 200)
        self.assertEqual(f.post('/receipt', dict(f.full, frame_count=16, status='played'))[0], 409)
        self.assertEqual(f.post('/connect', {'profile': 'fixture', 'device_epoch': 'device-process-A'}),
                         (423, {'error': 'source_cleanup_unproven'}))

    def test_poison_remains_after_elapsed_time_without_verified_cleanup(self):
        """A clock deadline cannot prove that the provider source was closed."""
        self.f.close()
        now = [1000.0]
        class BrokenClose(io.BytesIO):
            def close(self):
                super().close()
                raise OSError('synthetic-close-secret')
        self.f = f = Fixture(factory=lambda: BrokenClose(sse()), clock=lambda: now[0])
        f.ready()
        self.assertEqual(f.post('/stream', f.full)[0], 200)
        self.assertTrue(f.bridge._poisoned)
        connect = {'profile': 'fixture', 'device_epoch': 'device-process-A'}
        # The unverified cleanup is still a hard stop inside the time window.
        now[0] += 60
        self.assertEqual(f.post('/connect', connect), (423, {'error': 'source_cleanup_unproven'}))
        self.assertTrue(f.bridge._poisoned)
        # Parser and read deadlines are not proof of termination or closure.
        now[0] += f.bridge._limits.seconds + f.bridge._limits.read_timeout
        self.assertEqual(f.post('/connect', connect), (423, {'error': 'source_cleanup_unproven'}))
        self.assertTrue(f.bridge._poisoned)
        self.assertIsNotNone(f.bridge._poisoned_at)

    def test_eos_candidate_cannot_race_cleanup_cancel(self):
        self.f.close()
        entered, release = threading.Event(), threading.Event()
        class LateClose(io.BytesIO):
            calls = 0
            def close(self):
                self.calls += 1
                # A single owned close now precedes EOS; block that real attempt.
                if self.calls == 1:
                    entered.set()
                    if not release.wait(3):
                        raise OSError('fixture_timeout')
                super().close()
        self.f = f = Fixture(factory=lambda: LateClose(sse()))
        f.ready()
        results = []
        worker = threading.Thread(target=lambda: results.append(f.post('/stream', f.full)))
        worker.start()
        try:
            self.assertTrue(entered.wait(2))
            receipt = dict(f.full, frame_count=16, status='played')
            self.assertEqual(f.post('/receipt', receipt)[0], 409)
            self.assertEqual(f.post('/cancel', f.scope)[0], 200)
        finally:
            release.set()
            worker.join(4)
        self.assertFalse(worker.is_alive())
        self.assertEqual(f.post('/receipt', receipt)[0], 409)
        self.assertTrue(f.sources[0].closed)

    def test_concurrent_duplicate_stream_only_opens_once(self):
        f = self.f
        f.ready()
        barrier = threading.Barrier(3)
        results = []
        def download():
            barrier.wait(3)
            results.append(f.post('/stream', f.full)[0])
        workers = [threading.Thread(target=download) for _ in range(2)]
        for worker in workers:
            worker.start()
        barrier.wait(3)
        for worker in workers:
            worker.join(4)
            self.assertFalse(worker.is_alive())
        self.assertEqual(sorted(results), [200, 409])
        self.assertEqual(len(f.sources), 1)

    def test_cancel_reconnect_waits_for_old_source_close(self):
        self.f.close()
        entered, release, closed = threading.Event(), threading.Event(), threading.Event()
        class Paused(io.BytesIO):
            def read1(self, n):
                entered.set()
                if not release.wait(3):
                    raise OSError('fixture_timeout')
                return super().read1(n)
            def close(self):
                super().close()
                closed.set()
        self.f = f = Fixture(factory=lambda: Paused(sse()))
        f.ready()
        old_ack, old_full = f.ack, f.full
        result = []
        worker = threading.Thread(target=lambda: result.append(f.post('/stream', old_full)))
        worker.start()
        try:
            self.assertTrue(entered.wait(2))
            f.connect(2)
            self.assertEqual(f.post('/stop-ack', old_ack)[0], 409)
            self.assertEqual(f.post('/stop-ack', f.ack), (409, {'error': 'host_cleanup_pending'}))
            self.assertEqual(f.post('/stream', f.full)[0], 409)
        finally:
            release.set()
            worker.join(4)
        self.assertFalse(worker.is_alive())
        self.assertTrue(closed.is_set())
        self.assertEqual(f.post('/stop-ack', f.ack)[0], 200)
        self.assertEqual(f.post('/receipt', dict(old_full, frame_count=16, status='played'))[0], 409)

    def test_stream_socket_disconnect_cleans_up(self):
        self.f.close()
        entered, release, closed = threading.Event(), threading.Event(), threading.Event()
        class Paused(io.BytesIO):
            def read1(self, n):
                entered.set()
                if not release.wait(3):
                    raise OSError('fixture_timeout')
                return super().read1(n)
            def close(self):
                super().close()
                closed.set()
        self.f = f = Fixture(factory=lambda: Paused(sse()))
        f.ready()
        payload = json.dumps(f.full).encode()
        sock = socket.create_connection(f.server.server_address, timeout=2)
        sock.sendall(('POST /stream HTTP/1.0\r\nAuthorization: Bearer ' + f.token +
                      '\r\nContent-Type: application/json\r\nContent-Length: ' + str(len(payload)) +
                      '\r\n\r\n').encode() + payload)
        try:
            self.assertTrue(entered.wait(2))
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_LINGER, struct.pack('ii', 1, 0))
        finally:
            sock.close()
            release.set()
        self.assertTrue(closed.wait(3))
        # server_close joins exact owned request workers before final state assertion.
        f.server.shutdown()
        f.thread.join(3)
        f.server.server_close()
        self.assertIsNone(f.bridge._owner.accepted_receipt())


if __name__ == '__main__':
    unittest.main()
