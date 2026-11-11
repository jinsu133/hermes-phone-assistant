"""Offline admission regressions using real core hook emitters and plugin dispatch.

Run in a dedicated process with Hermes core on PYTHONPATH (see preflight report).
No AIAgent/Gateway is constructed; the CLI destination is an in-memory queue.
Derived from the isolated reproduce_voice_busy.py probe, but asserts the desired
behavior rather than accepting stale busy as success. Do not xfail the regression.
"""
import asyncio
import importlib.util
import logging
import os
from pathlib import Path
import queue
import socket
import sys
import tempfile
from contextlib import ExitStack
from types import SimpleNamespace
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
SID = "fixture-parent-session"


class VoiceAdmission(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.scope = ExitStack()
        cls.addClassCleanup(cls.scope.close)
        home = cls.scope.enter_context(tempfile.TemporaryDirectory(prefix="voice-admission-"))
        cls.home = Path(home)
        cls.scope.enter_context(patch.dict(os.environ, {"HERMES_HOME": home, "HOME": home}))
        # Audit guards run before Hermes imports, including imports that swallow errors.
        # Disable this process-local hook at cleanup (Python cannot remove audit hooks).
        core = Path(importlib.util.find_spec("hermes_cli").origin).resolve().parent.parent
        protected = core.parent
        scratch = Path(tempfile.gettempdir()).resolve()
        guard = {"active": True}
        cls.scope.callback(lambda: guard.update(active=False))

        def audit(event, args):
            if not guard["active"]:
                return
            if event in {"socket.connect", "socket.bind", "subprocess.Popen", "os.system"}:
                raise PermissionError("offline fixture forbids transport/process side effects")
            if event == "open" and isinstance(args[0], (str, bytes, os.PathLike)):
                path = Path(os.fsdecode(args[0])).absolute()
                in_core = path.is_relative_to(core)
                if ((path.is_relative_to(protected) and not in_core and not path.is_relative_to(scratch))
                        or (in_core and (path.name == ".env" or path.name.startswith("auth.json")))):
                    raise PermissionError("offline fixture forbids production profile reads/writes")

        sys.addaudithook(audit)
        # Fail closed even if a future registration/import starts networking.
        for operation in ("connect", "connect_ex", "bind"):
            cls.scope.enter_context(patch.object(socket.socket, operation,
                side_effect=AssertionError("offline fixture forbids socket I/O")))
        from hermes_cli.plugins import PluginContext, PluginManager, PluginManifest
        from agent.turn_context import _collect_pre_llm_call_context
        from agent.turn_api_request import _fire_pre_api_request_hook
        from agent.turn_finalizer import _apply_output_hooks
        cls.PluginContext, cls.PluginManager, cls.PluginManifest = PluginContext, PluginManager, PluginManifest
        cls.collect = staticmethod(_collect_pre_llm_call_context)
        cls.fire_api = staticmethod(_fire_pre_api_request_hook)
        cls.finalize = staticmethod(_apply_output_hooks)
        name = "voice_admission_source_plugin"
        spec = importlib.util.spec_from_file_location(name, ROOT / "__init__.py",
            submodule_search_locations=[str(ROOT)])
        cls.plugin = importlib.util.module_from_spec(spec)
        sys.modules[name] = cls.plugin
        cls.scope.callback(lambda: [sys.modules.pop(k, None) for k in list(sys.modules)
                                    if k == name or k.startswith(name + ".")])
        spec.loader.exec_module(cls.plugin)

    def setUp(self):
        self.scope = ExitStack()
        self.addCleanup(self.scope.close)
        # Only this manager is dispatched; discovery and production observers never run.
        self.manager = self.PluginManager(scope_key=str(self.home))
        self.cli = SimpleNamespace(session_id=SID, _agent_running=False,
            _pending_input=queue.Queue(), _interrupt_queue=queue.Queue())
        self.manager._cli_ref = self.cli
        self.ctx = self.PluginContext(self.PluginManifest(
            name="hermes-phone-control", path=str(ROOT)), self.manager)
        self.scope.enter_context(patch.object(self.ctx, "get_config", side_effect=lambda k, default=None: default))
        self.scope.enter_context(patch("hermes_cli.plugins._resolve_hook_callback_timeout", return_value=0))
        self.scope.enter_context(patch("hermes_cli.lifecycle.invoke_hook", side_effect=self.dispatch))
        self.scope.enter_context(patch("hermes_cli.lifecycle.has_hook",
            side_effect=lambda name: bool(self.manager._hooks.get(name))))
        self.scope.enter_context(patch("agent.turn_finalizer.apply_llm_output_transform",
            side_effect=lambda agent, response, **kw: (response, False, None)))
        # register() itself is real, including its actual hook-to-method bindings.
        self.addCleanup(self.cleanup_registration)
        self.plugin.register(self.ctx)
        self.link = self.manager._hooks["pre_api_request"][0].__self__
        self.link._home = self.home
        self.link._server = object()  # listening sentinel; no HTTP server/socket
        self.events = []

    def cleanup_registration(self):
        if hasattr(self, "link"):
            self.link._server = None  # never ask stop() to close the listening sentinel
        self.manager.unload()

    def dispatch(self, name, **kwargs):
        self.events.append(name)
        return self.manager.invoke_hook(name, **kwargs)

    def agent(self, sid=SID, detached=False):
        return SimpleNamespace(session_id=sid, _persist_disabled=detached,
            _turn_origin="background_review" if detached else "foreground",
            platform="telegram", model="fixture", provider="fixture", base_url="",
            api_mode="chat_completions", tools=[], max_tokens=100,
            _api_request_payload_for_hook=lambda kwargs: dict(kwargs))

    def begin(self, agent, turn):
        self.collect(agent, effective_task_id="fixture-task", turn_id=turn,
            original_user_message=turn, messages=[], conversation_history=[])

    def request(self, agent, turn):
        self.fire_api(agent, {}, [], [], messages=[], original_user_message=turn,
            approx_tokens=1, total_chars=1, retry_count=0, api_call_count=1,
            api_request_id="fixture-request", api_start_time=0,
            effective_task_id="fixture-task", turn_id=turn)

    def finish(self, agent, turn):
        self.finalize(agent, "fixture reply", logging.getLogger(__name__),
            platform="telegram", effective_task_id="fixture-task", turn_id=turn,
            original_user_message=turn, messages=[])

    def assert_blocked(self):
        self.assertEqual(self.link.say("조회 요청"),
            (409, {"ok": False, "error": "conversation_busy"}))
        self.assertTrue(self.cli._pending_input.empty())
        self.assertTrue(self.cli._interrupt_queue.empty())

    def assert_admitted(self):
        code, body = self.link.say("조회 요청")
        self.assertEqual(code, 200, body)
        self.assertTrue(body["accepted"])
        self.assertEqual(self.cli._pending_input.get_nowait(), self.link.format_voice_message("조회 요청"))
        self.assertTrue(self.cli._interrupt_queue.empty())

    def test_foreground_active_blocks_and_completion_unblocks(self):
        agent = self.agent()
        self.begin(agent, "foreground")
        self.assert_blocked()
        self.request(agent, "foreground")
        self.assert_blocked()
        self.finish(agent, "foreground")
        self.assertEqual(self.events, ["pre_llm_call", "pre_api_request", "post_llm_call"])
        self.assert_admitted()

    def test_completed_detached_review_does_not_leave_parent_busy(self):
        parent = self.agent()
        self.begin(parent, "foreground")
        self.request(parent, "foreground")
        self.finish(parent, "foreground")
        self.assertNotIn(SID, self.link._active_sessions)
        self.events.clear()
        review = self.agent(detached=True)  # same sid, as background_review.py configures
        self.begin(review, "review")
        self.request(review, "review")
        self.finish(review, "review")
        self.assertEqual(self.events, ["pre_api_request"])
        self.assertNotIn(SID, self.link._active_sessions,
            "completed detached background review must not leave parent session busy")
        self.assert_admitted()

    def test_repeated_api_callbacks_do_not_reactivate_completed_foreground(self):
        parent = self.agent()
        self.begin(parent, "foreground")
        for _ in range(3):
            self.request(parent, "foreground")
            self.assert_blocked()
        self.finish(parent, "foreground")
        self.assertNotIn(SID, self.link._active_sessions)
        self.events.clear()
        for _ in range(3):
            self.request(parent, "foreground")
            self.assertNotIn(SID, self.link._active_sessions)
        self.assertEqual(self.events, ["pre_api_request"] * 3)
        self.assert_admitted()

    def test_unrelated_active_session_does_not_block_target(self):
        other = self.agent(sid="fixture-unrelated-session")
        self.begin(other, "other")
        self.request(other, "other")
        self.assert_admitted()

    def test_unrelated_completion_does_not_clear_target_busy(self):
        parent, other = self.agent(), self.agent(sid="fixture-unrelated-session")
        self.begin(parent, "foreground")
        self.begin(other, "other")
        self.finish(other, "other")
        self.assert_blocked()
        self.finish(parent, "foreground")
        self.assert_admitted()

    def test_official_injection_ack_precedes_async_route_rejection(self):
        """Scheduler seam probe, not a claim of end-to-end Gateway admission."""
        from gateway.run_inbound import GatewayInboundMixin

        async def probe():
            entered, release = asyncio.Event(), asyncio.Event()

            async def reject_after_scheduling(**kwargs):
                entered.set()
                await release.wait()
                return False  # e.g. route removed or drain began after scheduling

            fixture = SimpleNamespace(_running=True, _gateway_loop=asyncio.get_running_loop(),
                _background_tasks=set(), _dispatch_plugin_message_injection=reject_after_scheduling)
            self.manager._cli_ref = None
            self.manager.set_gateway_message_injector(fixture,
                lambda **kw: GatewayInboundMixin._schedule_plugin_message_injection(fixture, **kw))
            with patch.object(self.ctx, "_gateway_injection_allowed", return_value=True):
                self.assertTrue(self.ctx.inject_message("fixture", role="user", session_key="fixture-route"))
            task = next(iter(fixture._background_tasks))
            try:
                await asyncio.wait_for(entered.wait(), timeout=2)
                self.assertFalse(task.done())
                release.set()
                with self.assertLogs("gateway.run", level="WARNING") as logs:
                    self.assertFalse(await task)
                    await asyncio.sleep(0)  # run the scheduler's done callback
                self.assertIn("was not routed", " ".join(logs.output))
            finally:
                release.set()
                if not task.done():
                    task.cancel()
                await asyncio.gather(task, return_exceptions=True)
                self.manager.clear_gateway_message_injector(fixture)

        asyncio.run(probe())

    def test_full_busy_queue_drops_without_delivery_ack(self):
        """Actual queue-cap branch with only in-memory adapter state."""
        from gateway.run_busy import GatewayBusySessionMixin
        from gateway.platforms.event import MessageEvent, MessageType
        adapter = SimpleNamespace(_pending_messages={})
        enqueued = []
        fixture = SimpleNamespace(_delivery_adapter_for=lambda source: adapter,
            _queue_depth=lambda key, **kw: 1, _BUSY_QUEUE_MAX_PENDING=1,
            _enqueue_fifo=lambda *args: enqueued.append(args))
        event = MessageEvent(text="fixture", message_type=MessageType.TEXT,
            source=SimpleNamespace(), internal=True, allow_gateway_control=False)
        with self.assertLogs("gateway.run", level="WARNING") as logs:
            result = GatewayBusySessionMixin._queue_or_replace_pending_event(fixture, "fixture-route", event)
        self.assertIsNone(result)
        self.assertEqual(enqueued, [])
        self.assertFalse(getattr(event, "_gateway_accepted", False))
        self.assertIn("pending queue at cap", " ".join(logs.output))


if __name__ == "__main__":
    unittest.main(verbosity=2)
