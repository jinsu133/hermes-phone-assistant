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


def register(ctx):
    from .android_stt import AndroidTranscriptionProvider
    ctx.register_transcription_provider(AndroidTranscriptionProvider(ctx))
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
    # The stock loader registers in a deadline worker without an asyncio loop.
    # The separately installed official gateway:startup hook invokes the public
    # command after API connection. pre_api_request remains an admitted-turn fallback.

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
            return (f"음성 연결: {'ON' if state['listening'] else 'OFF'} · "
                    f"페어링 {'완료' if state['paired'] else '필요'} · "
                    f"세션 {'설정됨' if state['session_configured'] else '미설정'}")
        if raw == "off":
            link.stop()
            return "음성 연결을 중단했습니다. 대기 중인 응답도 지웠습니다."
        if raw.startswith("import "):
            try:
                parts = shlex.split(raw)
                if len(parts) != 2:
                    return '사용법: /voicelink import "/storage/emulated/0/Download/hermes-phone-connection.json"'
                result = link.import_pairing(parts[1])
            except (ValueError, OSError) as exc:
                return f"음성 앱 페어링을 가져오지 못했습니다: {exc}"
            return "음성 앱 페어링을 저장했습니다. 비밀 키는 출력하거나 기록하지 않았습니다."
        if raw == "on":
            try:
                state = link.start(expected_epoch=epoch)
            except RuntimeError as exc:
                return f"음성 연결 시작 실패: {exc}"
            return f"음성 연결을 시작했습니다: {state['url']} (기기 내부 연결만 허용)"
        return '사용법: /voicelink import <파일> | on | off | status'

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
            return "사용법: /phone_setup doctor | import <통합 연결 파일>"
        except PairingError as exc:
            return "통합 연결 가져오기 실패: " + str(exc)
        except Exception:
            return "통합 연결 가져오기 실패: PAIRING_IMPORT_FAILED"

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
            return '음성 설정 변경 실패: ' + str(error)
        except Exception:
            return '음성 설정 변경 실패: SPEECH_SETTINGS_UNAVAILABLE'

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
                    return "등록된 발음 보정이 없습니다. `/voicefix 잘못들은말 => 바른말`로 추가합니다."
                lines = [f"- {e['wrong']} → {e['right']}" for e in entries]
                return "발음 보정 " + str(len(entries)) + "건\n" + "\n".join(lines)
            if raw.startswith("remove "):
                target = raw[len("remove "):].strip()
                if not target:
                    return "사용법: `/voicefix remove <잘못 들은 말>`"
                before = len(store.load(home))
                remaining = store.remove(home, target)
                return f"{before - len(remaining)}건을 삭제했습니다." if before != len(remaining) else "일치하는 항목이 없습니다."
            if "=>" not in raw:
                return "사용법: `/voicefix <잘못 들은 말> => <바른 말>` | `/voicefix list` | `/voicefix remove <말>`"
            wrong, right = (part.strip() for part in raw.split("=>", 1))
            store.put(home, wrong, right)
            return f"발음 보정을 저장했습니다: {wrong} → {right}. 다음 음성 요청부터 적용됩니다."
        except store.CorrectionError as exc:
            reason = {
                "correction_text_invalid": "문구가 비었거나 60자를 넘습니다",
                "correction_is_identity": "양쪽이 같아 보정이 아닙니다",
                "correction_store_full": "저장 한도(200건)를 넘었습니다",
                "correction_store_invalid": "저장 파일이 손상되어 거부했습니다",
                "correction_store_unreadable": "저장 파일을 읽을 수 없습니다",
                "correction_store_too_large": "저장 파일이 너무 큽니다",
            }.get(str(exc), "저장하지 못했습니다")
            return f"발음 보정 실패: {reason}."

    ctx.register_command("voicefix", _voicefix,
                         description="Repair recurring speech-recognition mistakes",
                         args_hint="<wrong> => <right> | list | remove <wrong>",
                         argument_mode="text")
