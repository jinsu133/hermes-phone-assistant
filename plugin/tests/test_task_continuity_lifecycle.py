"""Offline fixture lifetime regressions; faults affect fixture objects, never core."""
import json
import os
from pathlib import Path
import tempfile
import threading
import unittest
from unittest.mock import patch

import probe_voice_task_continuity as probe

_home = Path(os.environ['HOME']).resolve()
if (not _home.is_relative_to(probe.SCRATCH)
        or any(os.environ.get(key) != str(_home) for key in ('HERMES_HOME', 'TMPDIR'))):
    raise RuntimeError('run lifecycle tests in a fresh private scratch HOME/HERMES_HOME/TMPDIR')

import task_continuity_fixture as f


class Lifetime(unittest.TestCase):
    def setUp(self):
        # Preserve per-case artifacts in the caller's fresh private scratch HOME.
        self.home = Path(tempfile.mkdtemp(prefix='lifecycle-'+self._testMethodName+'-',
                                         dir=os.environ['HOME']))
        self.owner = {'profile': 'fixture', 'attempt': 'one'}

    def test_closed_owner_cannot_read_write_or_revive_after_new_owner(self):
        old = f.State(self.home, self.owner)
        deferred = old.connection()
        old.close()
        new = f.State(self.home, self.owner)
        self.addCleanup(new.close)
        before = new.snapshot()
        (self.home/'app.json').write_text(f.canonical({'screen': f.SCREEN}))
        def raw_connection():
            with deferred as db:
                db.execute("UPDATE task SET body='{}'")
        for name, call in [('snapshot', old.snapshot), ('begin', lambda: old.begin('open', 0)),
                           ('update', lambda: old.update(0, lambda s: None)),
                           ('verify', lambda: old.verify('open', 0)), ('recover', old.recover),
                           ('connection', raw_connection)]:
            with self.subTest(operation=name):
                with self.assertRaisesRegex(f.StateError, 'closed|owner'):
                    call()
                self.assertEqual(new.snapshot(), before)
        old.close()  # idempotence must not release the replacement owner's lease
        with self.assertRaises(BlockingIOError):
            f.State(self.home, self.owner)

    def test_close_waits_for_transaction_before_releasing_lease(self):
        state = f.State(self.home, self.owner)
        self.addCleanup(state.close)
        entered, release, closed = threading.Event(), threading.Event(), threading.Event()
        failures = []
        def transaction():
            try:
                with state.connection() as db:
                    db.execute('BEGIN IMMEDIATE')
                    entered.set()
                    if not release.wait(3):
                        raise AssertionError('test transaction release timed out')
            except BaseException as exc:
                failures.append(exc)
        worker = threading.Thread(target=transaction)
        closer = threading.Thread(target=lambda: (state.close(), closed.set()))
        worker.start()
        try:
            self.assertTrue(entered.wait(2))
            closer.start()
            self.assertFalse(closed.wait(.1))
            with self.assertRaises(BlockingIOError):
                f.State(self.home, self.owner)
        finally:
            release.set()
            worker.join(3)
            if closer.ident is not None:
                closer.join(3)
        self.assertFalse(worker.is_alive() or closer.is_alive())
        self.assertEqual(failures, [])
        self.assertTrue(closed.is_set())

    def test_reentrant_close_rejected_and_transaction_rolls_back(self):
        state = f.State(self.home, self.owner)
        self.addCleanup(state.close)
        before = state.snapshot()
        with self.assertRaisesRegex(f.StateError, 'transaction'):
            state.update(0, lambda s: state.close())
        self.assertEqual(state.snapshot(), before)

    def test_constructor_failure_releases_lease_and_preserves_primary(self):
        primary = ValueError('fixture initialization failed')
        with patch.object(f.State, 'snapshot', side_effect=primary):
            with self.assertRaises(ValueError) as got:
                f.State(self.home, self.owner)
        self.assertIs(got.exception, primary)
        state = f.State(self.home, self.owner)
        state.close()

    def test_constructor_cleanup_failure_does_not_replace_primary(self):
        primary = ValueError('fixture initialization failed')
        original_close = f.State.close
        def close_then_fail(state):
            original_close(state)
            raise OSError('SECRET-CLEANUP-PAYLOAD')
        with patch.object(f.State, 'snapshot', side_effect=primary), patch.object(f.State, 'close', close_then_fail):
            with self.assertRaises(ValueError) as got:
                f.State(self.home, self.owner)
        self.assertIs(got.exception, primary)
        self.assertNotIn('SECRET', str(getattr(primary, 'cleanup_errors', [])))
        self.assertEqual(primary.cleanup_errors[0]['type'], 'OSError')
        f.State(self.home, self.owner).close()

    def test_partial_registration_keeps_handles_for_cleanup(self):
        runtime = f.Runtime(self.home, 'success')
        self.addCleanup(runtime.state.close)
        handles = []
        class Context:
            def register_hook(self, *args):
                if len(handles) == 1:
                    raise ValueError('registration failure')
                handle = Handle()
                handles.append(handle)
                return handle
        with self.assertRaises(ValueError):
            runtime.register(Context())
        self.assertEqual(runtime.registrations, handles)
        for handle in runtime.registrations:
            handle.dispose()

    def test_owner_identity_mutation_refuses_connection(self):
        state = f.State(self.home, self.owner)
        self.addCleanup(state.close)
        state.owner['attempt'] = 'foreign'
        with self.assertRaisesRegex(f.StateError, 'owner'):
            with state.connection():
                self.fail('invalid owner entered a connection')

    def test_child_config_failure_uses_cleanup_boundary(self):
        resources = probe.OwnedResources(self.home)
        primary = ValueError('fixture config write failed')
        write_text = Path.write_text
        def write(path, *args, **kwargs):
            if path == self.home/'config.yaml':
                raise primary
            return write_text(path, *args, **kwargs)
        # Only fixture boundaries/files are patched, never a core method.
        with patch.object(probe, 'isolate', return_value=self.home), \
             patch.object(probe, 'OwnedResources', return_value=resources), \
             patch.object(Path, 'write_text', write):
            with self.assertRaises(ValueError) as got:
                probe.child('success')
        self.assertIs(got.exception, primary)
        self.assertFalse(resources.server_thread.is_alive())
        self.assertEqual(resources.server.fileno(), -1)
        self.assertIsNone(resources.runtime.state.fd)
        self.assertIsNone(f.ACTIVE)
        for name in ('requests.json', 'trace.json', 'engine-result.json', 'lifecycle.json'):
            self.assertTrue((self.home/name).exists(), name)
        self.assertFalse((self.home/'result.json').exists())

    def test_child_partial_registration_failure_disposes_real_handle(self):
        resources = probe.OwnedResources(self.home)
        primary = ValueError('fixture registration failed')
        def partial(runtime, ctx):
            runtime.registrations.append(ctx.register_hook('pre_llm_call', runtime.pre_llm))
            raise primary
        with patch.object(probe, 'isolate', return_value=self.home), \
             patch.object(probe, 'OwnedResources', return_value=resources), \
             patch.object(f.Runtime, 'register', partial):
            with self.assertRaises(ValueError) as got:
                probe.child('success')
        self.assertIs(got.exception, primary)
        self.assertFalse(resources.runtime.registrations[0].active)
        self.assertFalse(resources.server_thread.is_alive())
        self.assertIsNone(resources.runtime.state.fd)
        self.assertTrue((self.home/'lifecycle.json').exists())

    def test_server_close_faults_do_not_skip_join_or_evidence(self):
        from http.server import HTTPServer, BaseHTTPRequestHandler
        resources = self.resources()
        calls = []
        class Server(HTTPServer):
            def shutdown(self):
                super().shutdown()
                calls.append('shutdown')
                raise OSError('SECRET-CLEANUP-PAYLOAD')
            def server_close(self):
                super().server_close()
                calls.append('close')
                raise OSError('SECRET-CLEANUP-PAYLOAD')
        resources.server = Server(('127.0.0.1', 0), BaseHTTPRequestHandler)
        resources.server_thread = threading.Thread(target=lambda: resources.server.serve_forever(poll_interval=.01))
        resources.server_thread.start()
        with self.assertRaisesRegex(RuntimeError, 'cleanup'):
            resources.finish(None, [('trace', lambda: (self.home/'trace.json').write_text('[]'))])
        self.assertEqual(calls, ['shutdown', 'close'])
        self.assertFalse(resources.server_thread.is_alive())
        self.assertEqual(resources.server.fileno(), -1)
        report = json.loads((self.home/'lifecycle.json').read_text())
        self.assertTrue(report['owned_threads_joined'])
        self.assertFalse(report['resources_released'])
        self.assertEqual({x['stage'] for x in report['cleanup_errors']},
                         {'server_shutdown', 'server_close', 'state_close_deferred'})
        self.assertTrue((self.home/'trace.json').exists())

    def test_state_close_fault_still_writes_evidence(self):
        resources = self.resources()
        with patch.object(resources.runtime.state, 'close', side_effect=OSError('SECRET-CLEANUP-PAYLOAD')):
            with self.assertRaisesRegex(RuntimeError, 'cleanup'):
                resources.finish(None, [('trace', lambda: (self.home/'trace.json').write_text('[]'))])
        report = json.loads((self.home/'lifecycle.json').read_text())
        self.assertEqual([x['stage'] for x in report['cleanup_errors']], ['state_close'])
        self.assertFalse(report['resources_released'])
        self.assertNotIn('SECRET', json.dumps(report))
        self.assertTrue((self.home/'trace.json').exists())
        self.assertIsNotNone(resources.runtime.state.fd)

    def test_expected_operation_error_not_masked_by_cleanup(self):
        resources = self.resources()
        primary = LookupError('expected conversation failure')
        with patch.object(resources.runtime.state, 'close', side_effect=OSError('SECRET-CLEANUP-PAYLOAD')):
            with self.assertRaises(LookupError) as got:
                resources.finish(None, [], observed_primary=primary)
        self.assertIs(got.exception, primary)
        report = json.loads((self.home/'lifecycle.json').read_text())
        self.assertEqual(report['primary_error']['type'], 'LookupError')
        self.assertEqual(report['cleanup_errors'][0]['type'], 'OSError')

    def test_unstarted_threads_do_not_block_other_cleanup(self):
        from http.server import HTTPServer, BaseHTTPRequestHandler
        resources = self.resources()
        resources.server = HTTPServer(('127.0.0.1', 0), BaseHTTPRequestHandler)
        resources.server_thread = threading.Thread(target=resources.server.serve_forever)
        resources.ack_thread = threading.Thread(target=lambda: None)
        primary = RuntimeError('thread start failed')
        with self.assertRaises(RuntimeError) as got:
            resources.finish(primary, [])
        self.assertIs(got.exception, primary)
        self.assertEqual(resources.server.fileno(), -1)
        self.assertIsNone(resources.runtime.state.fd)
        self.assertEqual(resources.cleanup_errors, [])

    def test_last_evidence_failure_preserves_primary_and_cleanup_diagnostics(self):
        resources = self.resources()
        primary = ValueError('initialization failed')
        write_text = Path.write_text
        def write(path, *args, **kwargs):
            if path == self.home/'lifecycle.json':
                raise OSError('SECRET-CLEANUP-PAYLOAD')
            return write_text(path, *args, **kwargs)
        with patch.object(Path, 'write_text', write):
            with self.assertRaises(ValueError) as got:
                resources.finish(primary, [('trace', lambda: (self.home/'trace.json').write_text('[]'))])
        self.assertIs(got.exception, primary)
        self.assertEqual(primary.cleanup_errors[0]['stage'], 'lifecycle_evidence')
        self.assertNotIn('SECRET', json.dumps(primary.cleanup_errors))
        self.assertTrue((self.home/'trace.json').exists())
        self.assertFalse((self.home/'lifecycle.json').exists())
        self.assertIsNone(resources.runtime.state.fd)

    def test_constructor_cleanup_diagnostics_not_lost_by_outer_cleanup(self):
        resources = probe.OwnedResources(self.home)
        primary = ValueError('state constructor failed')
        primary.cleanup_errors = [{'stage': 'state_init_close', 'type': 'OSError'}]
        with self.assertRaises(ValueError) as got:
            resources.finish(primary, [])
        self.assertIs(got.exception, primary)
        report = json.loads((self.home/'lifecycle.json').read_text())
        self.assertEqual(report['cleanup_errors'], primary.cleanup_errors)
        self.assertFalse(report['resources_released'])

    def resources(self):
        runtime = f.Runtime(self.home, 'success')
        self.addCleanup(runtime.state.close)
        resources = probe.OwnedResources(self.home, join_timeout=.05)
        resources.runtime = runtime
        return resources

    def test_partial_initialization_stops_real_server_and_writes_evidence(self):
        from http.server import HTTPServer, BaseHTTPRequestHandler
        resources = self.resources()
        resources.server = HTTPServer(('127.0.0.1', 0), BaseHTTPRequestHandler)
        resources.server_thread = threading.Thread(target=lambda: resources.server.serve_forever(poll_interval=.01))
        resources.server_thread.start()
        primary = ValueError('config or import initialization failed')
        with self.assertRaises(ValueError) as got:
            resources.finish(primary, [('trace', lambda: (self.home/'trace.json').write_text('[]'))])
        self.assertIs(got.exception, primary)
        self.assertFalse(resources.server_thread.is_alive())
        self.assertEqual(resources.server.fileno(), -1)
        self.assertIsNone(resources.runtime.state.fd)
        self.assertTrue((self.home/'trace.json').exists())
        report = json.loads((self.home/'lifecycle.json').read_text())
        self.assertEqual(report['primary_error']['type'], 'ValueError')
        self.assertEqual(report['cleanup_errors'], [])

    def test_independent_close_faults_preserve_primary_and_evidence(self):
        resources = self.resources()
        calls = []
        class Agent:
            def close(self):
                calls.append('agent')
                raise ValueError('SECRET-CLEANUP-PAYLOAD')
        resources.agent = Agent()
        good, bad = Handle(), Handle(fail=True)
        resources.runtime.registrations = [good, bad]
        primary = LookupError('primary operation failed')
        def fail_evidence():
            raise OSError('SECRET-CLEANUP-PAYLOAD')
        with self.assertRaises(LookupError) as got:
            resources.finish(primary, [('failed_evidence', fail_evidence),
                                      ('trace', lambda: (self.home/'trace.json').write_text('[]'))])
        self.assertIs(got.exception, primary)
        self.assertEqual(calls, ['agent'])
        self.assertFalse(good.active)
        self.assertTrue(bad.active)
        self.assertIsNotNone(resources.runtime.state.fd)  # no early lease release
        with self.assertRaises(BlockingIOError):
            f.State(self.home, resources.runtime.identity)
        report = json.loads((self.home/'lifecycle.json').read_text())
        self.assertNotIn('SECRET', json.dumps(report))
        self.assertEqual({x['stage'] for x in report['cleanup_errors']},
                         {'agent_close', 'registration_1', 'state_close_deferred', 'failed_evidence'})
        self.assertTrue((self.home/'trace.json').exists())
        self.assertFalse(report['resources_released'])
        bad.fail = False
        bad.dispose()

    def test_live_worker_holds_state_lease_until_actual_join(self):
        resources = self.resources()
        release = threading.Event()
        resources.ack_thread = threading.Thread(target=release.wait)
        resources.ack_thread.start()
        try:
            with self.assertRaisesRegex(RuntimeError, 'cleanup'):
                resources.finish(None, [])
            report = json.loads((self.home/'lifecycle.json').read_text())
            self.assertFalse(report['owned_threads_joined'])
            self.assertIsNotNone(resources.runtime.state.fd)
            with self.assertRaises(BlockingIOError):
                f.State(self.home, resources.runtime.identity)
        finally:
            release.set()
            resources.ack_thread.join(2)
        self.assertFalse(resources.ack_thread.is_alive())
        resources.runtime.state.close()


class Handle:
    def __init__(self, fail=False):
        self.active = True
        self.fail = fail
    def dispose(self):
        if self.fail:
            raise OSError('SECRET-CLEANUP-PAYLOAD')
        self.active = False


if __name__ == '__main__':
    unittest.main()
