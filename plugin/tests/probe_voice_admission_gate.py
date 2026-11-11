"""Bounded OFFLINE gate probe; each case runs sequentially in a fresh subprocess.

Uses the existing admission fixture unchanged (temporary HOME, audit/socket guards).
Real functions: plugin say/hooks/PluginContext injection, Gateway scheduling,
dispatch, ingress guard, running-session interrupt, strict session resolution,
and bounded FIFO. No GatewayRunner/AIAgent construction, live adapter or model.
Fixture adapter composes only those handlers; this is NOT a full adapter E2E test.

Run: PYTHONPATH=<core> <core>/venv/bin/python -B <this-file>
"""
import asyncio
import json
import os
from pathlib import Path
import sqlite3
import subprocess
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

from test_voice_admission import VoiceAdmission, SID

KEY = "agent:main:telegram:dm:fixture"
TEXT = "오프라인 조회 요청"


class AdmissionGate(VoiceAdmission):
    def route(self, sid):
        with sqlite3.connect(self.home / "state.db") as db:
            db.execute("CREATE TABLE IF NOT EXISTS gateway_routing (scope TEXT, session_key TEXT, entry_json TEXT)")
            db.execute("DELETE FROM gateway_routing")
            db.execute("INSERT INTO gateway_routing VALUES (?, ?, ?)",
                       (str((self.home / "sessions").resolve()), KEY, json.dumps({"session_id": sid})))

    def gateway(self, mode="interrupt"):
        from gateway.run_inbound import GatewayInboundMixin
        from gateway.run_busy import GatewayBusySessionMixin
        from gateway.run_turn import GatewayTurnMixin
        from gateway.run import GatewayRunner
        from gateway.platforms.base import BasePlatformAdapter
        from types import MethodType
        from gateway.session import SessionSource
        from gateway.config import Platform

        # Inherit handlers, not the production runner constructor or its service startup.
        class Fixture(GatewayInboundMixin, GatewayBusySessionMixin, GatewayTurnMixin):
            pass

        f = Fixture()
        f._running, f._draining = True, False
        f._gateway_loop = asyncio.get_running_loop()
        f._background_tasks = set()
        f._BUSY_QUEUE_MAX_PENDING = GatewayRunner._BUSY_QUEUE_MAX_PENDING
        f.source = SessionSource(platform=Platform.TELEGRAM, chat_id="fixture", user_id="fixture")
        f.entry = SimpleNamespace(session_id=SID, session_key=KEY, origin=f.source)
        f.state = SimpleNamespace(turn=SimpleNamespace(agent=None),
                                  conversation=SimpleNamespace(queued_events=[]))
        f.interrupts, f.received, f.resolved = [], [], []
        f.hook_calls = []
        f.busy, f.reject_strict, f.roll_after_lookup = False, False, False

        async def lookup(key):
            self.assertEqual(key, KEY)
            return f.entry

        async def slash(*args):
            return False, None

        async def no_compression(*args):
            return False

        async def should_not_hook(*args):
            f.hook_calls.append(args)
            raise AssertionError("Internal injection must bypass pre_gateway_dispatch")

        f.async_session_store = SimpleNamespace(lookup_by_session_key=lookup)
        f._restored_source = lambda entry: entry.origin
        f._is_user_authorized_for_source = lambda *a, **kw: True
        f._peek_session_state = lambda key: f.state
        f._session_state = lambda key: f.state
        f._effective_busy_input_mode = lambda source: mode  # explicit fixture policy, not live config
        f._hm_busy_slash_or_photo = slash
        f._hm_busy_telegram_grace_queue = lambda *args: False  # outside startup grace window
        f._agent_has_active_subagents = lambda agent: False
        f._session_has_compression_in_flight = no_compression
        f._pending_event_audio_paths = lambda event: []
        f._hm_pre_gateway_dispatch_hook = should_not_hook
        f._session_key_for_source = lambda source: KEY
        f._admit_bot_message_for_source = lambda source: True

        async def handle(event):
            f.received.append(event)
            if f.busy:
                # Real adapter ingress and active-session handler, not a fabricated
                # shortcut to the runner's distinct cold-path running-session branch.
                f.adapter._active_sessions[KEY] = object()
                return await BasePlatformAdapter.handle_message(f.adapter, event)
            admitted = await f._hm_admit_event(event)
            self.assertIsNotNone(admitted)
            if f.roll_after_lookup:
                f.entry = SimpleNamespace(session_id="fixture-rotated", session_key=KEY, origin=f.source)
                self.route(f.entry.session_id)
            if f.reject_strict:
                # Real strict-id gate exits before any turn/model or thread-pool work.
                f.resolved.append(await f._hmwa_resolve_session(event, event.source))
            return None  # adapter does not expose a receipt through injection API

        f.adapter = SimpleNamespace(_pending_messages={}, handle_message=handle,
            _message_handler=handle, _active_sessions={}, name="offline-fixture",
            _drop_unresolved=lambda event: False, _event_session_key=lambda event: KEY,
            _heal_stale_session_lock=lambda key: None, _canonicalize=lambda source: None,
            _busy_session_handler=f._handle_active_session_busy_message)
        f.adapter._handle_message_while_active = MethodType(
            BasePlatformAdapter._handle_message_while_active, f.adapter)
        f._delivery_adapter_for = lambda source: f.adapter
        self.manager._cli_ref = None
        self.ctx.get_config.side_effect = lambda k, default=None: KEY if k == "session_key" else default
        self.scope.enter_context(patch.object(self.ctx, "_gateway_injection_allowed", return_value=True))
        self.route(SID)
        self.manager.set_gateway_message_injector(f, f._schedule_plugin_message_injection)
        self.addCleanup(self.manager.clear_gateway_message_injector, f)
        return f

    async def drain(self, f):
        tasks = list(f._background_tasks)
        self.assertEqual(len(tasks), 1)
        results = await asyncio.wait_for(asyncio.gather(*tasks), timeout=3)
        await asyncio.sleep(0)  # deterministic done callbacks, not a timing guess
        self.assertFalse(f._background_tasks)
        return results[0]

    def accepted(self):
        code, body = self.link.say(TEXT)
        self.assertEqual(code, 200, body)
        self.assertTrue(body["accepted"])
        self.assertIsNotNone(self.link._pending)
        return body

    def test_cli_idle_check_then_foreground(self):
        # Deterministic seam: events.begin occurs AFTER idle check/reservation,
        # BEFORE the real PluginContext.inject_message call.
        original_begin = self.link.events.begin

        def foreground(request_id):
            original_begin(request_id)
            self.cli._agent_running = True
            self.begin(self.agent(), "concurrent-foreground")

        with patch.object(self.link.events, "begin", side_effect=foreground):
            self.accepted()
        self.assertTrue(self.cli._pending_input.empty())
        self.assertEqual(self.cli._interrupt_queue.get_nowait(), self.link.format_voice_message(TEXT))
        print("EVIDENCE cli: accepted=True; idle checked first; foreground starts; interrupt_queue=1")

    def test_gateway_idle_check_then_foreground(self):
        async def probe():
            f = self.gateway()
            self.assertNotIn(SID, self.link._active_sessions)
            self.accepted()  # scheduled but event loop cannot dispatch yet
            self.begin(self.agent(), "concurrent-foreground")
            f.busy = True
            f.state.turn.agent = SimpleNamespace(interrupt=f.interrupts.append,
                                                 _supports_active_turn_redirect=False)
            self.assertTrue(await self.drain(f))
            self.assertEqual(f.interrupts, [])
            self.assertEqual(f._queue_depth(KEY, adapter=f.adapter), 1)
            self.assertTrue(f.received[0]._gateway_accepted)
            self.assertEqual(f.hook_calls, [])
            self.assertFalse(f.received[0].allow_gateway_control)
            print("EVIDENCE gateway race: accepted=True; foreground starts; real active-adapter path queues=1; interrupt=0 even with interrupt policy")
        asyncio.run(probe())

    def test_runner_running_branch_is_distinct_interrupt_counterexample(self):
        async def probe():
            from gateway.platforms.event import MessageEvent, MessageType
            f = self.gateway()
            f.state.turn.agent = SimpleNamespace(interrupt=f.interrupts.append,
                                                 _supports_active_turn_redirect=False)
            event = MessageEvent(text=self.link.format_voice_message(TEXT), message_type=MessageType.TEXT,
                                 source=f.source, internal=True, allow_gateway_control=False)
            await f._hm_handle_running_session_message(event, f.source, KEY)
            self.assertEqual(f.interrupts, [event.text])
            print("EVIDENCE runner-only branch: interrupt=1; NOT the normal active-adapter path; live reachability not established")
        asyncio.run(probe())

    def test_reservation_then_rollover_before_lookup(self):
        async def probe():
            f = self.gateway()
            self.accepted()
            self.assertEqual(self.link._pending["session_id"], SID)
            f.entry = SimpleNamespace(session_id="fixture-rotated", session_key=KEY, origin=f.source)
            self.route(f.entry.session_id)
            self.assertTrue(await self.drain(f))
            event = f.received[0]
            self.assertEqual(event.metadata["gateway_session_id"], "fixture-rotated")
            # Actual plugin hook allows rebinding when the same route currently points at the new id.
            self.begin(self.agent(sid="fixture-rotated"), event.text)
            self.assertEqual(self.link._pending["session_id"], "fixture-rotated")
            self.assertEqual(self.link._pending["turn_id"], event.text)
            print("EVIDENCE rollover before lookup: pending old id -> dispatch pins NEW id -> real hook rebinds; no expected-id API")
        asyncio.run(probe())

    def test_rollover_after_lookup_strict_drop_reports_dispatch_true(self):
        async def probe():
            f = self.gateway()
            f.roll_after_lookup = f.reject_strict = True
            body = self.accepted()
            with self.assertLogs("gateway.run", level="WARNING") as logs:
                self.assertTrue(await self.drain(f))
            self.assertEqual(f.resolved, [None])
            self.assertIn("no longer current", " ".join(logs.output))
            self.assertEqual(self.link._pending["session_id"], SID)
            self.assertIsNone(self.link.outbox.get(body["request_id"]))
            print("EVIDENCE rollover after lookup: accepted=True; strict gate DROPS; dispatch=True; no outbox receipt")
        asyncio.run(probe())

    def test_scheduled_then_draining_rejects(self):
        async def probe():
            f = self.gateway()
            body = self.accepted()
            f._draining = True
            with self.assertLogs("gateway.run", level="WARNING") as logs:
                self.assertFalse(await self.drain(f))
            self.assertEqual(f.received, [])
            self.assertIn("was not routed", " ".join(logs.output))
            self.assertEqual(self.link._pending["request_id"], body["request_id"])
            self.assertNotIn("text", self.link._pending)  # digest is not recoverable original input
            self.assertIsNone(self.link.outbox.get(body["request_id"]))
            print("EVIDENCE drain: accepted=True -> real dispatch=False; warning only; pending remains digest-only; no receipt")
        asyncio.run(probe())

    def test_queue_capacity_and_scheduled_drop(self):
        async def probe():
            from gateway.platforms.event import MessageEvent, MessageType
            f = self.gateway(mode="queue")
            f.busy = True
            f.state.turn.agent = SimpleNamespace(interrupt=f.interrupts.append)
            events = []
            for i in range(f._BUSY_QUEUE_MAX_PENDING):
                event = MessageEvent(text=f"fixture-{i}", message_type=MessageType.TEXT,
                                     source=f.source, internal=True, allow_gateway_control=False)
                f._queue_or_replace_pending_event(KEY, event)
                self.assertTrue(event._gateway_accepted)
                events.append(event)
            self.assertEqual(f._queue_depth(KEY, adapter=f.adapter), 32)
            body = self.accepted()
            with self.assertLogs("gateway.run", level="WARNING") as logs:
                self.assertTrue(await self.drain(f))
            self.assertIn("pending queue at cap (32)", " ".join(logs.output))
            self.assertEqual(f._queue_depth(KEY, adapter=f.adapter), 32)
            self.assertFalse(getattr(f.received[0], "_gateway_accepted", False))
            self.assertEqual([f.adapter._pending_messages[KEY]] + f.state.conversation.queued_events, events)
            self.assertEqual(f.interrupts, [])
            self.assertIsNone(self.link.outbox.get(body["request_id"]))
            print("EVIDENCE queue: real FIFO accepts 32 in order; 33rd dropped; accepted=True; dispatch=True; interrupt=0; receipt=none")
        asyncio.run(probe())

    def test_queue_below_cap_is_noninterrupting_control(self):
        async def probe():
            f = self.gateway(mode="queue")
            self.accepted()
            f.busy = True
            f.state.turn.agent = SimpleNamespace(interrupt=f.interrupts.append)
            self.assertTrue(await self.drain(f))
            self.assertEqual(f.interrupts, [])
            self.assertEqual(f._queue_depth(KEY, adapter=f.adapter), 1)
            self.assertTrue(f.received[0]._gateway_accepted)
            print("EVIDENCE queue control: explicit queue fixture policy, capacity available -> queued=1; interrupt=0 (not durable receipt)")
        asyncio.run(probe())


CASES = [name for name in AdmissionGate.__dict__ if name.startswith("test_")]


def main():
    if len(sys.argv) == 3 and sys.argv[1] == "--case":
        name = sys.argv[2]
        if name not in CASES:
            raise SystemExit("unknown case")
        result = unittest.TextTestRunner(verbosity=2).run(unittest.TestSuite([AdmissionGate(name)]))
        return 0 if result.wasSuccessful() else 1
    # Clean child environment: do not pass credentials or production profile settings.
    core = Path(os.environ["PYTHONPATH"])
    scratch = Path(os.environ["TMPDIR"])
    failures = []
    for name in CASES:
        with tempfile.TemporaryDirectory(prefix="voice-gate-child-", dir=scratch) as home:
            env = {k: os.environ[k] for k in ("PATH", "LD_LIBRARY_PATH", "PREFIX") if k in os.environ}
            env.update(HOME=home, HERMES_HOME=home, TMPDIR=str(scratch), PYTHONPATH=str(core),
                       PYTHONDONTWRITEBYTECODE="1", PYTHONUNBUFFERED="1")
            run = subprocess.run([sys.executable, "-B", str(Path(__file__).resolve()), "--case", name],
                                 cwd=home, env=env, text=True, capture_output=True, timeout=90)
            print(run.stdout, end="")
            print(run.stderr, end="")
            if run.returncode:
                failures.append(name)
    print(json.dumps({"cases": len(CASES), "passed": len(CASES)-len(failures),
                      "failed": failures, "deployment_gate": "NO-GO" if not failures else "INCOMPLETE"}))
    return bool(failures)


if __name__ == "__main__":
    raise SystemExit(main())
