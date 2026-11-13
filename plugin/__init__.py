"""Native external plugin; registration never opens the pairing file or network.

Two scopes:
- read-only phone tools (status and approved UI observation)
- the explicitly paired, loopback-only voice link (`/voicelink import|on|off|status`) that carries
  the phone app's utterances into one fixed existing gateway session and observes only the matching reply.

The voice link binds nothing until the user turns it on. It never writes config, never
rewrites the agent's output, and deletes inbound audio after transcription.
"""
from pathlib import Path
import shlex
import asyncio

from .schemas import SCHEMAS
from .handlers import Handlers
from .voicelink import VoiceLink
from .confirmation import guard_phone_action
from . import confirmation as _confirmation
from . import messages
from . import voice_events as _voice_events


def register(ctx):
    from .android_stt import AndroidTranscriptionProvider
    _confirmation._CTX = ctx
    _voice_events._CTX = ctx
    messages.register(ctx)
    # Public provider registry (Hermes >= the release that added it); older hosts still get
    # the plugin's own direct use of this provider in the voice bridge.
    register_stt = getattr(ctx, "register_transcription_provider", None)
    if callable(register_stt):
        register_stt(AndroidTranscriptionProvider(ctx))
    T = lambda key, **values: messages.text(ctx, key, **values)
    link = VoiceLink(ctx)
    handlers = Handlers(ctx, link)
    for name, schema in SCHEMAS.items():
        ctx.register_tool(name=name, toolset="phone_control", schema=schema,
                          handler=getattr(handlers, name), is_async=True,
                          check_fn=(lambda: True) if name == "phone_speech" else handlers.configured,
                          description="Paired accessibility phone tools", emoji="📱")
    ctx.register_skill("phone-control", Path(__file__).parent / "skills/phone-control/SKILL.md")

    ctx.register_hook("post_llm_call", link.observe_reply)
    ctx.register_hook("pre_llm_call", link.prepare_voice_turn)
    ctx.register_hook("pre_tool_call", link.guard_voice_tool)
    ctx.register_hook("pre_tool_call", guard_phone_action)
    ctx.register_hook("pre_api_request", link.auto_start)
    ctx.register_hook("on_session_end", link.end_turn)
    ctx.register_middleware("tool_execution", link.bind_tool_voice)
    from .codex_request_compat import CodexRequestCompatibility
    ctx.register_middleware("llm_request", CodexRequestCompatibility(link))
    ctx.on_unload(link.stop)
    from .bridge_startup import start_bridge_after_load
    start_bridge_after_load(ctx, link)
    # The stock loader registers in a deadline worker without an asyncio loop, so the
    # bridge is opened from a plugin-owned thread after load; pre_api_request remains
    # an admitted-turn fallback.

    def _voicelink_command(args: str):
        # Stock gateway already offloads sync commands. Also support direct async hosts.
        epoch = link.startup_epoch()
        try:
            asyncio.get_running_loop()
        except RuntimeError:
            return _voicelink(args, epoch)
        return asyncio.to_thread(_voicelink, args, epoch)

    def _voicelink(args: str, epoch):
        raw = (args or "").strip()
        if raw == 'startup':
            if ctx.get_config('voice_enabled', False) is not True or epoch != 0:
                return {'ok': True, 'started': False, 'reason': 'disabled_or_off'}
            try:
                state = link.start(expected_epoch=epoch)
            except RuntimeError:
                return {'ok': False, 'error': 'PHONE_STARTUP_NOT_READY'}
            return {'ok': True, 'started': state['listening']}
        if not raw or raw == "status":
            state = link.status()
            return T("voicelink.status",
                     state=T("voicelink.on") if state['listening'] else T("voicelink.off_state"),
                     pairing=T("voicelink.paired") if state['paired'] else T("voicelink.unpaired"),
                     session=(T("voicelink.session_set") if state['session_configured']
                              else T("voicelink.session_unset")))
        if raw == "off":
            link.stop()
            return T("voicelink.stopped")
        if raw.startswith("import "):
            try:
                parts = shlex.split(raw)
                if len(parts) != 2:
                    return T("voicelink.import_usage")
                link.import_pairing(parts[1])
            except (ValueError, OSError) as exc:
                return T("voicelink.import_failed", error=exc)
            return T("voicelink.imported")
        if raw == "on":
            try:
                state = link.start(expected_epoch=epoch)
            except RuntimeError as exc:
                return T("voicelink.start_failed", error=exc)
            return T("voicelink.started", url=state['url'])
        return T("voicelink.usage")

    ctx.register_command("voicelink", _voicelink_command,
                         description="Pair and control the phone voice bridge",
                         args_hint="[import <file>|on|off|status]", argument_mode="text")

    def _phone_setup(args: str):
        import json
        import sys
        from .pairing import PairingError
        from .voice_route import capabilities
        try:
            parts = shlex.split(args or "doctor")
            if parts == ["doctor"]:
                return json.dumps({"schema_version": 1, "python": ".".join(map(str, sys.version_info[:3])),
                    "capabilities": link.execution_capabilities(), "pairing_stored": link.status()["paired"],
                    "voice_route": link.voice_admission(), "installation_performed": False}, ensure_ascii=False)
            if len(parts) == 2 and parts[0] == "import":
                return json.dumps(link.import_phone_pairing(parts[1]), ensure_ascii=False)
            return T("setup.usage")
        except PairingError as exc:
            return T("setup.import_failed", error=exc)
        except Exception:
            return T("setup.import_failed", error="PAIRING_IMPORT_FAILED")

    for command in ("phone_setup", "phone-setup"):
        ctx.register_command(command, _phone_setup,
                             description="Inspect release capabilities or import initial unified pairing",
                             args_hint="doctor | import <file>", argument_mode="text")

    async def _phone_pairing(args: str):
        import json
        from hermes_constants import get_hermes_home
        from .pairing_verification import verify
        if (args or "check").strip() != "check":
            return json.dumps({"ok": False, "error": "PAIRING_CHECK_USAGE"})
        return json.dumps(await verify(ctx, link, get_hermes_home()), ensure_ascii=False)

    for command in ("phone_pairing", "phone-pairing"):
        ctx.register_command(command, _phone_pairing,
                             description="Check both paired transports without audio or phone actions",
                             args_hint="check", argument_mode="text")

    def _phone_speech(args: str):
        from hermes_constants import get_hermes_home
        from .speech_control import command
        from .speech_settings import SettingsError
        try:
            return command(ctx, get_hermes_home(), args)
        except SettingsError as error:
            return T("speech.change_failed", error=error)
        except Exception:
            return T("speech.change_failed", error="SPEECH_SETTINGS_UNAVAILABLE")

    for command_name in ('phone-speech', 'phone_speech'):
        ctx.register_command(command_name, _phone_speech,
            description='Inspect and select phone TTS/STT independently; no credential collection',
            args_hint='status | catalog | <JSON selection>', argument_mode='text')

    def _phone_execution(args: str):
        import json
        from hermes_constants import get_hermes_home
        from .voice_execution_settings import ExecutionSettings, handle_execution_command, parse_execution_args
        try:
            parts = parse_execution_args(args or 'status')
        except (ValueError, TypeError):
            return json.dumps({'ok': False, 'error': 'EXECUTION_COMMAND_INVALID'})
        from .voice_model_picker import prepare_selection, binding_readback
        from .speech_settings import SettingsError
        from .voice_runs_transport import RunsTransportError
        settings = ExecutionSettings(get_hermes_home())
        writing = bool(parts and parts[0] in ('configure', 'select'))
        # Both direct configure and exact picker handoff use the SAME lifecycle gate.
        # A phone GET/gesture never calls this operator-only registered callback.
        with link._lifecycle_lock, link._lock:
            if writing and (link.listening or link._cancel_cleanup_blocked
                    or link._ingress_active or link._worker_cleanup_pending()):
                return json.dumps({'ok': False, 'error': 'EXECUTION_OFF_REQUIRED'})
        if parts and parts[0] == 'select':
            try:
                prepared = prepare_selection(settings, parts)  # HTTP outside BOTH locks.
                parts = ('configure', str(prepared['revision']), json.dumps(prepared['payload']))
            except SettingsError as error:
                return json.dumps({'ok': False, 'error': str(error)})
            except RunsTransportError:
                return json.dumps({'ok': False, 'error': 'EXECUTION_CATALOG_FAILED'})
            except (ValueError, TypeError, RecursionError, OverflowError):
                return json.dumps({'ok': False, 'error': 'EXECUTION_COMMAND_INVALID'})
        # Recheck OFF/settlement after the read-only network phase; no TOCTOU bypass.
        with link._lifecycle_lock, link._lock:
            if writing and (link.listening or link._cancel_cleanup_blocked
                    or link._ingress_active or link._worker_cleanup_pending()):
                return json.dumps({'ok': False, 'error': 'EXECUTION_OFF_REQUIRED'})
            if parts != ('catalog',):
                result = handle_execution_command(settings, parts)
                if result.get('ok') and 'settings' in result:
                    # Independently read the target back. Saved != live until normal
                    # OFF -> start creates a binding to this exact snapshot.
                    actual = settings.current()
                    if actual != result['settings']:
                        return json.dumps({'ok': False, 'error': 'SETTINGS_CONFLICT'})
                    result.update(binding_readback(actual, link))
                return json.dumps(result, ensure_ascii=False)
        return json.dumps(handle_execution_command(settings, parts), ensure_ascii=False)

    for command_name in ('phone-execution', 'phone_execution'):
        ctx.register_command(command_name, _phone_execution,
            description='Operator-only official runs settings; configure while voice link OFF',
            args_hint="status | catalog | select <revision> '<selection JSON>' | configure <revision> '<JSON>'", argument_mode='text')

    def _voicefix(args: str):
        from hermes_constants import get_hermes_home
        from . import voice_corrections as store
        home = get_hermes_home()
        raw = (args or "").strip()
        try:
            if not raw or raw == "list":
                entries = store.load(home)
                if not entries:
                    return T("voicefix.empty")
                lines = [f"- {e['wrong']} → {e['right']}" for e in entries]
                return T("voicefix.list", count=len(entries), lines="\n".join(lines))
            if raw.startswith("remove "):
                target = raw[len("remove "):].strip()
                if not target:
                    return T("voicefix.remove_usage")
                before = len(store.load(home))
                remaining = store.remove(home, target)
                return (T("voicefix.removed", count=before - len(remaining))
                        if before != len(remaining) else T("voicefix.no_match"))
            if "=>" not in raw:
                return T("voicefix.usage")
            wrong, right = (part.strip() for part in raw.split("=>", 1))
            store.put(home, wrong, right)
            return T("voicefix.saved", wrong=wrong, right=right)
        except store.CorrectionError as exc:
            key = "voicefix.err." + str(exc)
            reason = T(key) if key in messages.EN else T("voicefix.err.default")
            return T("voicefix.failed", reason=reason)

    ctx.register_command("voicefix", _voicefix,
                         description="Repair recurring speech-recognition mistakes",
                         args_hint="<wrong> => <right> | list | remove <wrong>",
                         argument_mode="text")
