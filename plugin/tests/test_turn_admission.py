"""Plugin-owned turn admission, using only the public hook and middleware surfaces.

The plugin binds a turn when Hermes calls ``pre_llm_call`` with ``(session_id, turn_id)``,
publishes that key to its own tool handlers from the ``tool_execution`` middleware, and
forgets it on ``on_session_end``. No Hermes runtime internals are read. These tests use a
minimal stand-in context and need no Hermes installation.
"""
import asyncio
import sys
import time
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))

from plugin import voicelink as vl  # noqa: E402


class Ctx:
    def __init__(self, **config):
        self.config = config

    def get_config(self, key, default=None):
        return self.config.get(key, default)


def link(**config):
    return vl.VoiceLink(Ctx(**config))


def pend(target, text, request_id="req-1", session_id=None):
    """Publish a live phone request the way ``_inject`` does."""
    with target._lock:
        target._pending = {"request_id": request_id, "digest": vl._text_digest(text),
                           "session_id": session_id, "created": time.monotonic()}


class TextTurns(unittest.TestCase):
    def test_text_turn_is_admitted_without_a_voice_owner(self):
        target = link()
        target.prepare_voice_turn(session_id="s", turn_id="t", user_message="open the calendar")
        self.assertEqual(target._admission_owner(("s", "t")), ("record", None))
        self.assertIsNone(target.guard_voice_tool(tool_name="terminal", session_id="s", turn_id="t"))

    def test_tool_without_turn_ids_runs_when_no_phone_request_is_live(self):
        # For example a nested execute_code call: Hermes passes no turn IDs.
        target = link()
        self.assertIsNone(target.guard_voice_tool(tool_name="patch", session_id="", turn_id=""))

    def test_tool_without_turn_ids_is_blocked_while_a_phone_request_is_live(self):
        target = link()
        pend(target, "anything")
        blocked = target.guard_voice_tool(tool_name="patch", session_id="", turn_id="")
        self.assertEqual(blocked["action"], "block")
        self.assertIsNone(target.guard_voice_tool(tool_name="phone_status", session_id="", turn_id=""))

    def test_replayed_voice_envelope_without_a_live_request_is_revoked(self):
        target = link()
        stale = target.format_voice_message("send the message")
        target.prepare_voice_turn(session_id="s", turn_id="t", user_message=stale)
        self.assertEqual(target._admission_owner(("s", "t")), ("record", vl.REVOKED_OWNER))
        self.assertEqual(target.guard_voice_tool(tool_name="terminal", session_id="s",
                                                 turn_id="t")["action"], "block")

    def test_first_admission_wins_and_session_end_releases_it(self):
        target = link()
        target.prepare_voice_turn(session_id="s", turn_id="t", user_message="hello")
        stale = target.format_voice_message("again")
        target.prepare_voice_turn(session_id="s", turn_id="t", user_message=stale)
        self.assertEqual(target._admission_owner(("s", "t")), ("record", None))
        target.end_turn(session_id="s")
        self.assertEqual(target._admission_owner(("s", "t")), ("none", None))

    def test_missing_ids_are_never_admitted_and_table_is_bounded(self):
        target = link()
        target.prepare_voice_turn(session_id="", turn_id="t", user_message="x")
        self.assertEqual(target._admissions, {})
        for i in range(vl.MAX_ADMISSIONS + 10):
            target.prepare_voice_turn(session_id="s", turn_id=str(i), user_message="x")
        self.assertEqual(len(target._admissions), vl.MAX_ADMISSIONS)
        self.assertEqual(target._admission_owner(("s", "0")), ("none", None))


class VoiceTurns(unittest.TestCase):
    def test_matching_voice_turn_binds_the_request(self):
        target = link()
        text = target.format_voice_message("what's on today") + "\n[phone_request_id=req-1]"
        pend(target, text)
        context = target.prepare_voice_turn(session_id="s", turn_id="t", user_message=text)
        self.assertIsInstance(context, str)
        self.assertEqual(target._admission_owner(("s", "t")), ("record", "req-1"))
        self.assertEqual(target.voice_request("s", "t"), "req-1")

    def test_same_request_cannot_be_rebound_to_another_turn(self):
        target = link()
        text = target.format_voice_message("call mom") + "\n[phone_request_id=req-1]"
        pend(target, text)
        target.prepare_voice_turn(session_id="s", turn_id="t", user_message=text)
        target.prepare_voice_turn(session_id="s", turn_id="other", user_message=text)
        self.assertEqual(target._admission_owner(("s", "other")), ("record", vl.REVOKED_OWNER))
        self.assertIsNone(target.voice_request("s", "other"))

    def test_voice_turn_on_a_different_configured_route_is_revoked(self):
        target = link(session_key="agent:main:telegram:dm:1")
        text = target.format_voice_message("hi") + "\n[phone_request_id=req-1]"
        pend(target, text)
        original = vl._turn_session_key
        vl._turn_session_key = lambda: "agent:main:discord:dm:2"
        try:
            target.prepare_voice_turn(session_id="s", turn_id="t", user_message=text)
        finally:
            vl._turn_session_key = original
        self.assertEqual(target._admission_owner(("s", "t")), ("record", vl.REVOKED_OWNER))


class Middleware(unittest.TestCase):
    def test_handler_sees_its_own_turn_key(self):
        target = link()
        target.prepare_voice_turn(session_id="s", turn_id="t", user_message="hello")
        seen = target.bind_tool_voice({}, lambda args: target.tool_turn_key.get(),
                                      tool_name="phone_act", session_id="s", turn_id="t")
        self.assertEqual(seen, ("s", "t"))
        self.assertIsNone(target.tool_turn_key.get())

    def test_async_handler_loses_authority_when_its_turn_ended(self):
        target = link()
        target.prepare_voice_turn(session_id="s", turn_id="t", user_message="hello")

        async def handler(args):
            return target.tool_turn_key.get(), target.tool_voice_request.get()

        pending = target.bind_tool_voice({}, handler, tool_name="phone_act",
                                         session_id="s", turn_id="t")
        target.end_turn(session_id="s")
        key, owner = asyncio.run(pending)
        self.assertIsNone(key)
        self.assertEqual(owner, vl.REVOKED_OWNER)

    def test_async_handler_keeps_authority_while_turn_is_live(self):
        target = link()
        target.prepare_voice_turn(session_id="s", turn_id="t", user_message="hello")

        async def handler(args):
            return target.tool_turn_key.get(), target.tool_voice_request.get()

        key, owner = asyncio.run(target.bind_tool_voice({}, handler, tool_name="phone_act",
                                                        session_id="s", turn_id="t"))
        self.assertEqual(key, ("s", "t"))
        self.assertIsNone(owner)


if __name__ == "__main__":
    unittest.main(verbosity=2)
