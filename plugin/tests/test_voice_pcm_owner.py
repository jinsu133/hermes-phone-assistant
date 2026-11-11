"""Offline host receipt ownership races; no live endpoint or provider."""
import pathlib
import sys
import threading
import unittest
from concurrent.futures import ThreadPoolExecutor
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))
from voice_pcm_stream import Identity
from voice_pcm_owner import HostPlaybackOwner


def receipt(i, frames=4):
    return dict(request_id=i.request_id, audio_id=i.audio_id,
                generation=i.generation, frame_count=frames, status='played')


class HostOwnerTests(unittest.TestCase):
    def prepared(self, kind='final'):
        o = HostPlaybackOwner()
        i = Identity('r', 'a', 1)
        self.assertTrue(o.begin('r', 1))
        self.assertTrue(o.admit(i, 0, kind))
        self.assertTrue(o.eos(i, 4))
        return o, i

    def test_complete_then_cancel_revokes_event_and_receipt(self):
        o, i = self.prepared()
        accepted = threading.Event()
        def complete():
            self.assertTrue(o.accept(receipt(i)))
            accepted.set()
        with ThreadPoolExecutor(2) as pool:
            a = pool.submit(complete)
            def cancel():
                self.assertTrue(accepted.wait(2))
                o.cancel()
            b = pool.submit(cancel)
            a.result(3); b.result(3)
        self.assertIsNone(o.take_eligibility())
        self.assertIsNone(o.accepted_receipt())

    def test_cancel_then_complete_rejected(self):
        o, i = self.prepared()
        cancelled = threading.Event()
        with ThreadPoolExecutor(2) as pool:
            def complete():
                self.assertTrue(cancelled.wait(2))
                return o.accept(receipt(i))
            result = pool.submit(complete)
            o.cancel(); cancelled.set()
            self.assertFalse(result.result(3))
        self.assertIsNone(o.take_eligibility())

    def test_concurrent_duplicate_begin_and_admit(self):
        o = HostPlaybackOwner()
        barrier = threading.Barrier(2)
        def begin():
            barrier.wait(2)
            return o.begin('r', 1)
        with ThreadPoolExecutor(2) as pool:
            a, b = pool.submit(begin), pool.submit(begin)
            self.assertEqual(sorted([a.result(3), b.result(3)]), [False, True])
            i = Identity('r', 'a', 1)
            def admit():
                barrier.wait(2)
                return o.admit(i, 1, 'final')
            a, b = pool.submit(admit), pool.submit(admit)
            self.assertEqual(sorted([a.result(3), b.result(3)]), [False, True])

    def test_old_generation_and_stale_audio_do_not_accept(self):
        o, i = self.prepared('progress')
        j = Identity('r', 'f', 1)
        self.assertTrue(o.admit(j, 1, 'final'))
        self.assertFalse(o.accept(receipt(i)))
        self.assertFalse(o.eos(i, 4))
        self.assertTrue(o.eos(j, 4))
        self.assertTrue(o.begin('r', 2))
        self.assertFalse(o.accept(receipt(j)))
        self.assertFalse(o.admit(j, 2, 'final'))
        self.assertIsNone(o.take_eligibility())

    def test_only_matching_terminal_played_receipt_emits(self):
        for kind in ['progress', 'final', 'approval_notice']:
            with self.subTest(kind=kind):
                o, i = self.prepared(kind)
                self.assertFalse(o.accept(receipt(i, 3)))
                wrong = receipt(i); wrong['status'] = 'downloaded'
                self.assertFalse(o.accept(wrong))
                self.assertTrue(o.accept(receipt(i)))
                self.assertFalse(o.accept(receipt(i)))
                e = o.take_eligibility()
                if kind == 'progress':
                    self.assertIsNone(e)
                else:
                    self.assertEqual(e.kind, kind)
                    self.assertEqual(e.identity, i)
                self.assertIsNone(o.take_eligibility())

    def test_no_eos_no_receipt_and_off_requires_new_epoch(self):
        o = HostPlaybackOwner(); i = Identity('r', 'a', 1)
        o.begin('r', 1); o.admit(i, 0, 'final')
        self.assertFalse(o.accept(receipt(i)))
        o.off(); self.assertFalse(o.eos(i, 4)); self.assertFalse(o.begin('r', 1))
        self.assertTrue(o.begin('r', 2))

    def test_identity_reuse_and_order_and_bounded_ledger(self):
        o = HostPlaybackOwner(); o.begin('r', 1)
        for n in range(1023):
            self.assertTrue(o.admit(Identity('r', 'a'+str(n), 1), n, 'progress'))
        self.assertFalse(o.admit(Identity('r', 'extra', 1), 1023, 'progress'))
        self.assertFalse(o.admit(Identity('r', 'a0', 1), 1023, 'final'))
        self.assertFalse(o.admit(Identity('r', 'f', 1), 1, 'final'))
        self.assertTrue(o.admit(Identity('r', 'f', 1), 1023, 'final'))
        self.assertFalse(o.admit(Identity('r', 'late', 1), 1024, 'progress'))

    def test_accept_holds_owner_lock_while_cancel_contends_at_gate(self):
        o, i = self.prepared()
        at_gate, release, contended, cancelled = (threading.Event() for _ in range(4))
        real_lock = o._lock
        errors, accepted = [], []
        # Test instrumentation preserves the real mutex and its lock scope.
        class ObservedLock:
            def __enter__(self):
                if not real_lock.acquire(blocking=False):
                    contended.set()  # witnessed a failed acquire, not merely thread start
                    if not real_lock.acquire(timeout=3):
                        raise AssertionError('owner lock timeout')
            def __exit__(self, *args):
                real_lock.release()
        o._lock = ObservedLock()
        gate_accept = o._gate.accept
        def held_gate(value, identity):
            self.assertTrue(real_lock.locked())
            at_gate.set()
            self.assertTrue(release.wait(3))
            return gate_accept(value, identity)
        o._gate.accept = held_gate
        def run(call):
            try:
                call()
            except BaseException as exc:
                errors.append(exc)
        def cancel():
            o.cancel()
            cancelled.set()
        a = threading.Thread(target=run, args=(lambda: accepted.append(o.accept(receipt(i))),), daemon=True)
        b = threading.Thread(target=run, args=(cancel,), daemon=True)
        a.start()
        try:
            self.assertTrue(at_gate.wait(2))
            b.start()
            self.assertTrue(contended.wait(2))
            self.assertFalse(cancelled.is_set())
        finally:
            release.set()
            a.join(4)
            if b.ident is not None:
                b.join(4)
        self.assertFalse(a.is_alive() or b.is_alive(), 'worker join timeout')
        if errors:
            raise errors[0]
        self.assertEqual(accepted, [True])  # historical acceptance linearized first
        self.assertTrue(cancelled.is_set())
        self.assertIsNone(o.accepted_receipt())
        self.assertIsNone(o.take_eligibility())

    def test_off_clears_unconsumed_eligibility(self):
        o, i = self.prepared()
        self.assertTrue(o.accept(receipt(i)))
        # Inspect only: do not consume the slot before OFF.
        self.assertIsNotNone(o._eligibility)
        o.off()
        self.assertIsNone(o.take_eligibility())
        self.assertIsNone(o.accepted_receipt())

    def test_off_does_not_revoke_detached_historical_snapshot(self):
        o, i = self.prepared()
        self.assertTrue(o.accept(receipt(i)))
        historical = o.take_eligibility()
        o.off()
        self.assertEqual(historical.identity, i)  # still a value, never authorization
        self.assertIsNone(o.take_eligibility())
        self.assertIsNone(o.accepted_receipt())

    def test_receipt_snapshot_is_detached(self):
        o, i = self.prepared(); r = receipt(i)
        self.assertTrue(o.accept(r)); r['status'] = 'changed'
        snap = o.accepted_receipt(); self.assertEqual(snap['status'], 'played')
        snap['status'] = 'changed'
        self.assertEqual(o.accepted_receipt()['status'], 'played')


if __name__ == '__main__':
    unittest.main()
