"""Offline regressions: source ownership and untrusted HTTP connection isolation."""
import io
import socket
import struct
import threading
import time
import unittest
from test_voice_pcm_bridge import Fixture, sse


class CloseHistoryTests(unittest.TestCase):
    def test_each_former_close_position_has_one_owned_attempt(self):
        # The old parser/framer/HTTP cleanup called the raw source 3 times.
        # Only position 1 exists with a single owner; positions 2/3 must not run.
        for fail_call in (1, 2, 3):
            with self.subTest(fail_call=fail_call):
                class TransientClose(io.BytesIO):
                    calls = 0
                    def close(self):
                        self.calls += 1
                        if self.calls == fail_call:
                            raise OSError('SYNTHETIC_SECRET_CLOSE')
                        super().close()
                f = Fixture(factory=lambda: TransientClose(sse()))
                try:
                    f.ready()
                    status, wire = f.post('/stream', f.full)
                    receipt = f.post('/receipt', dict(f.full, frame_count=16, status='played'))[0]
                    reconnect = f.post('/connect', {'profile': 'fixture', 'device_epoch': 'device-process-A'})[0]
                    self.assertEqual((status, f.sources[0].calls, receipt, reconnect, f.bridge._poisoned),
                                     (200, 1, 409, 423, True) if fail_call == 1 else
                                     (200, 1, 200, 200, False))
                    self.assertEqual(wire.endswith(b'\x02' + struct.pack('>Iq', 8, 16)), fail_call != 1)
                    if fail_call == 1:
                        f.sources[0].close()  # later raw success is not recovery
                        self.assertEqual(f.post('/connect', {'profile': 'fixture', 'device_epoch': 'device-process-A'})[0], 423)
                        self.assertTrue(f.bridge._poisoned)
                finally:
                    f.close()
                    # Test-owned release only after every bridge assertion.
                    io.BytesIO.close(f.sources[0])


class ConnectionIsolationTests(unittest.TestCase):
    def test_real_slow_headers_capacity_and_authenticated_session_survive(self):
        f = Fixture()
        socks = []
        f.ready()
        scope = dict(f.scope)
        try:
            for _ in range(8):
                sock = socket.create_connection(f.server.server_address, timeout=2)
                sock.sendall(b'POST /connect HTTP/1.0\r\nX-Drip: ')
                socks.append(sock)
            deadline = time.monotonic() + 2
            while f.server._slots._value and time.monotonic() < deadline:
                time.sleep(.01)
            self.assertEqual(f.server._slots._value, 0)
            with socket.create_connection(f.server.server_address, timeout=2) as extra:
                extra.sendall(b'POST /connect HTTP/1.0\r\n')
                self.assertIn(b' 503 ', extra.recv(1024))
            # Real 10s watchdog; don't weaken the production timeout for a test.
            deadline = time.monotonic() + 12
            while f.server._slots._value != 8 and time.monotonic() < deadline:
                for sock in socks:
                    try:
                        sock.sendall(b'x')
                    except OSError:
                        pass
                time.sleep(.2)
            self.assertEqual(f.server._slots._value, 8)
            self.assertEqual(f.sources, [])
            self.assertTrue(f.bridge._enabled)
            self.assertTrue(f.bridge._acked)
            self.assertEqual(f.bridge._scope, scope)
            self.assertFalse(getattr(f.server, 'unexpected_error', False))
            self.assertEqual(f.post('/cancel', scope)[0], 200)
        finally:
            for sock in socks:
                sock.close()
            f.close()


class OwnedCleanupTests(unittest.TestCase):
    def test_cancel_header_abandon_and_parser_failure_close_once(self):
        from http.client import IncompleteRead
        from voice_pcm_stream import OwnedSource, Cancellation, Identity, StreamError, hps1_stream
        from test_voice_pcm_stream import ErrorPathRegressionTests
        for phase in ('header', 'precancel', 'cancel', 'invalid_json', 'eos'):
            with self.subTest(phase=phase):
                class Broken(io.BytesIO):
                    calls = 0
                    def close(self):
                        self.calls += 1
                        if self.calls == 1:
                            raise IncompleteRead(b'SYNTHETIC_SECRET_CLOSE', 99)
                        super().close()
                raw = Broken(b'data: {\n\n' if phase == 'invalid_json' else sse())
                owner = OwnedSource.wrap(raw)
                cancel = Cancellation()
                gen = hps1_stream(Identity('r', 'a', 1), owner, cancel)
                records = [next(gen)]
                try:
                    if phase == 'header':
                        gen.close()
                    else:
                        if phase == 'precancel':
                            cancel.event.set()
                        if phase == 'cancel':
                            records.append(next(gen))
                            cancel.cancel()
                        with self.assertRaises(StreamError) as caught:
                            records.extend(gen)
                        self.assertEqual(str(caught.exception), {
                            'precancel': 'cancelled', 'cancel': 'cancelled', 'invalid_json': 'invalid_json',
                            'eos': 'transport_error'}[phase])
                        ErrorPathRegressionTests().assert_safe(caught.exception)
                    self.assertEqual(raw.calls, 1)
                    self.assertFalse(owner.close_confirmed)
                    self.assertFalse(any(record[0] == 2 for record in records))
                    self.assertIsNone(cancel.response)
                    # Even an out-of-band raw close success cannot absolve history.
                    raw.close()
                    with self.assertRaisesRegex(OSError, '^source_cleanup_unproven$'):
                        owner.close()
                    self.assertEqual(raw.calls, 2)
                    self.assertFalse(owner.close_confirmed)
                finally:
                    gen.close()
                    io.BytesIO.close(raw)

    def test_http_header_write_failure_still_owns_cleanup_and_poison(self):
        from voice_pcm_bridge import BridgeHandler, ConnectionFile
        for fail_close in (False, True):
            with self.subTest(fail_close=fail_close):
                class Source(io.BytesIO):
                    calls = 0
                    def close(self):
                        self.calls += 1
                        if fail_close:
                            raise OSError('SYNTHETIC_SECRET_CLOSE')
                        super().close()
                class Disconnected:
                    def write(self, data):
                        raise BrokenPipeError('SYNTHETIC_SECRET_SOCKET')
                f = Fixture(factory=lambda: Source(sse()))
                try:
                    f.ready()
                    handler = object.__new__(BridgeHandler)
                    handler.server = f.server
                    handler.request_version = 'HTTP/1.0'
                    handler.requestline = 'POST /stream HTTP/1.0'
                    handler.command = 'POST'
                    handler.wfile = ConnectionFile(Disconnected())
                    handler.stream(f.full)
                    self.assertEqual(f.sources[0].calls, 1)
                    self.assertEqual(f.bridge._poisoned, fail_close)
                    self.assertIsNone(f.bridge._active)
                    self.assertFalse(f.bridge._completed)
                    self.assertEqual(f.post('/receipt', dict(f.full, frame_count=16, status='played'))[0], 409)
                    self.assertEqual(f.post('/connect', {'profile': 'fixture', 'device_epoch': 'device-process-A'})[0],
                                     423 if fail_close else 200)
                finally:
                    f.close()
                    io.BytesIO.close(f.sources[0])

    def test_late_failed_close_poison_revokes_reconnected_scope(self):
        entered, release = threading.Event(), threading.Event()
        class Source(io.BytesIO):
            calls = 0
            def close(self):
                self.calls += 1
                entered.set()
                if not release.wait(3):
                    raise AssertionError('fixture timeout')
                raise OSError('SYNTHETIC_SECRET_CLOSE')
        f = Fixture(factory=lambda: Source(sse()))
        f.ready()
        old_full = dict(f.full)
        results = []
        worker = threading.Thread(target=lambda: results.append(f.post('/stream', old_full)))
        worker.start()
        try:
            self.assertTrue(entered.wait(2))
            self.assertEqual(f.post('/cancel', f.scope)[0], 200)
            f.connect(2)
            self.assertEqual(f.post('/stop-ack', f.ack)[0], 409)
            release.set()
            worker.join(4)
            self.assertFalse(worker.is_alive())
            self.assertEqual(f.sources[0].calls, 1)
            self.assertFalse(results[0][1].endswith(b'\x02' + struct.pack('>Iq', 8, 16)))
            self.assertTrue(f.bridge._poisoned)
            self.assertFalse(f.bridge._enabled)
            self.assertEqual(f.post('/stop-ack', f.ack)[0], 409)
            self.assertEqual(f.post('/receipt', dict(old_full, frame_count=16, status='played'))[0], 409)
            self.assertEqual(f.post('/connect', {'profile': 'fixture', 'device_epoch': 'device-process-A'})[0], 423)
        finally:
            release.set()
            worker.join(4)
            f.close()
            io.BytesIO.close(f.sources[0])

    def test_programming_primary_survives_bridge_cleanup_failure(self):
        from voice_pcm_bridge import BridgeHandler
        from test_voice_pcm_stream import ErrorPathRegressionTests
        bug = TypeError('fixture primary bug')
        class Source(io.BytesIO):
            calls = 0
            def read1(self, size):
                raise bug
            def close(self):
                self.calls += 1
                raise RuntimeError('SYNTHETIC_SECRET_CLOSE')
        f = Fixture(factory=lambda: Source(sse()))
        try:
            f.ready()
            handler = object.__new__(BridgeHandler)
            handler.server = f.server
            handler.request_version = 'HTTP/1.0'
            handler.requestline = 'POST /stream HTTP/1.0'
            handler.command = 'POST'
            handler.wfile = io.BytesIO()
            with self.assertRaises(TypeError) as caught:
                handler.stream(f.full)
            self.assertIs(caught.exception, bug)
            ErrorPathRegressionTests().assert_safe(caught.exception)
            self.assertEqual(f.sources[0].calls, 1)
            self.assertTrue(f.bridge._poisoned)
            self.assertFalse(f.bridge._completed)
            self.assertIsNone(f.bridge._active)
        finally:
            f.close()
            io.BytesIO.close(f.sources[0])


class BoundaryClassificationTests(unittest.TestCase):
    def test_socket_errors_detached_but_programming_errors_not_hidden(self):
        from voice_pcm_bridge import ConnectionFile, ConnectionFailure
        from test_voice_pcm_stream import ErrorPathRegressionTests
        for error in (BrokenPipeError('SYNTHETIC_SECRET_WRITE'),
                      TimeoutError('SYNTHETIC_SECRET_TIMEOUT'),
                      ConnectionResetError('SYNTHETIC_SECRET_RESET')):
            for method, args in (('read', (1,)), ('readline', (1,)), ('write', (b'x',)),
                                 ('flush', ()), ('close', ())):
                class SocketFile:
                    pass
                raw = SocketFile()
                def fail(*args):
                    raise error
                setattr(raw, method, fail)
                with self.assertRaises(ConnectionFailure) as caught:
                    getattr(ConnectionFile(raw), method)(*args)
                self.assertEqual(str(caught.exception), 'connection_io')
                ErrorPathRegressionTests().assert_safe(caught.exception)
        bug = RuntimeError('fixture bug')
        class Broken:
            def read(self, size):
                raise bug
        with self.assertRaises(RuntimeError) as caught:
            ConnectionFile(Broken()).read(1)
        self.assertIs(caught.exception, bug)

    def test_non_socket_internal_oserror_is_still_global_failure(self):
        # A blanket `except OSError` in handle_error would incorrectly pass this.
        import http.client
        f = Fixture()
        original = f.bridge.authenticated
        def broken(value):
            raise OSError('SYNTHETIC_SECRET_INTERNAL')
        try:
            f.ready()
            f.bridge.authenticated = broken
            with self.assertRaises(http.client.RemoteDisconnected):
                f.post('/cancel', f.scope)
            self.assertTrue(f.server.unexpected_error)
            self.assertFalse(f.bridge._enabled)
            self.assertFalse(f.bridge._completed)
        finally:
            f.bridge.authenticated = original
            # This test deliberately exercised the unexpected-error sentinel.
            f.server.unexpected_error = False
            f.close()

    def test_http_finish_releases_both_files_after_flush_failure(self):
        from types import SimpleNamespace
        from voice_pcm_bridge import BridgeHandler, ConnectionFile
        events = []
        class File:
            def __init__(self, name):
                self.name = name
            def flush(self):
                events.append('flush')
                raise BrokenPipeError('SYNTHETIC_SECRET_FLUSH')
            def close(self):
                events.append(self.name)
                raise OSError('SYNTHETIC_SECRET_CLOSE')
        handler = object.__new__(BridgeHandler)
        handler._watchdog = SimpleNamespace(cancel=lambda: None, join=lambda timeout: None)
        handler.wfile, handler.rfile = ConnectionFile(File('writer')), ConnectionFile(File('reader'))
        handler.finish()
        self.assertEqual(events, ['flush', 'writer', 'reader'])


if __name__ == '__main__':
    unittest.main()
