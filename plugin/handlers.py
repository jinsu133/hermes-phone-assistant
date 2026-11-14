"""Safe JSON boundary; kwargs carry attribution, not authorization/ownership."""
import asyncio
import json
import math
from .backend import BackendDenied, Denied, query
from .actions import query_control, query_plan, validate_action
from .observe_compact import compact_observation, observe_settled
from .voice_drafts import DraftWorkspace
from .voice_documents import VoiceDocuments
from .voice_revision import prepare_revision, save_revision
from .voice_python import run_python
import hashlib
import threading
import time
import unicodedata

MESSAGES = {
    'INVALID_ARGUMENTS': 'The read-only phone tool received invalid arguments.',
    'NOT_CONFIGURED': 'A private connection file must be configured by the operator.',
    'INVALID_CONNECTION': 'Connection settings are invalid or unavailable.',
    'AUTH_DENIED': 'Phone authentication was rejected.',
    'DEVICE_MISMATCH': 'The phone identity does not match the paired device.',
    'FOREGROUND_DENIED': 'The foreground is not the approved test app.',
    'TIMEOUT': 'The bounded phone read timed out; no result was released.',
    'CANCELLED': 'The read was interrupted; no result was released.',
    'BACKEND_DENIED': 'Phone observation was denied or unavailable.',
    'BACKEND_UNAVAILABLE': 'The phone backend is unavailable or returned an unsupported response.',
    'INVALID_RESPONSE': 'The phone response did not satisfy the read-only contract.',
    'RESPONSE_LIMIT': 'The phone response exceeded transport limits.',
    'OBSERVATION_LIMIT': 'The observation exceeded allowed limits.',
    'OBSERVATION_UNAVAILABLE': 'No approved non-input text was available.',
}


def identity(value):
    # Host-supplied IDs only; never use caller tool arguments as ownership evidence.
    return value if isinstance(value,str) and 0 < len(value) <= 128 else None


_PROFILE_CODES = {
    "profile_bad_selector", "profile_bad_steps", "profile_missing_field",
    "profile_forbidden_dismiss_label", "profile_plan_too_long", "profile_bad_shape",
    "profile_package_mismatch", "profile_bad_version", "profile_bad_anchors",
    "profile_bad_routes", "profile_unknown_anchor", "profile_bad_dismiss", "profile_bad_entry",
    "profile_store_unreadable", "profile_store_bad_schema", "profile_store_too_large",
    "profile_missing", "profile_version_changed", "profile_unknown_route",
    "profile_undeclared_dismiss", "profile_query_required", "profile_bad_query",
    "profile_bad_budget", "profile_bad_stats", "profile_bad_outcome",
}


def _profile_code(code):
    """Drop the ':detail' suffix and refuse codes that are not on the fixed list."""
    base = str(code).split(":", 1)[0]
    return base if base in _PROFILE_CODES else "profile_invalid"


class Handlers:
    def __init__(self, ctx, link=None):
        self.ctx = ctx
        self.link = link
        self.drafts = None

    def configured(self):
        # Discovery gate: no credential read, no connection test and no remote I/O.
        return isinstance(self.ctx.get_config('connection_file'),str)

    async def phone_speech(self, args, **kwargs):
        from hermes_constants import get_hermes_home
        from .speech_control import perform
        from .speech_settings import SettingsError
        try:
            if type(args) is dict and args.get('action') == 'select':
                if self.link is None:
                    return json.dumps({'ok': False, 'error': 'TURN_ADMISSION_REQUIRED'})
                key = (str(kwargs.get('session_id') or ''), str(kwargs.get('turn_id') or ''))
                with self.link._lock:
                    state, owner = self.link._admission_owner(key)
                    if state != 'record' or owner == '__revoked_voice_owner__':
                        return json.dumps({'ok': False, 'error': 'TURN_ADMISSION_REQUIRED'})
                    if owner is not None and self.link.tool_voice_request.get() != owner:
                        return json.dumps({'ok': False, 'error': 'VOICE_OWNER_REQUIRED'})
            return json.dumps(perform(self.ctx, get_hermes_home(), args), ensure_ascii=False)
        except SettingsError as error:
            return json.dumps({'ok': False, 'error': str(error)})
        except Exception:
            return json.dumps({'ok': False, 'error': 'SPEECH_SETTINGS_UNAVAILABLE'})

    async def phone_status(self, args, **kwargs):
        return await self._handle('status', args, kwargs)

    async def phone_observe(self, args, **kwargs):
        if self.ctx.get_config('control_enabled', False) is True:
            return await self._control('observe', args, kwargs)
        return await self._handle('observe', args, kwargs)

    async def voice_progress(self, args, **kwargs):
        try:
            if self.link is None:
                raise ValueError("progress_owner_required")
            with self.link._lock:
                request = self.link.tool_voice_request.get()
                pending = self.link._pending or {}
                if not request or self.link.voice_request(pending.get("session_id"), pending.get("turn_id")) != request:
                    raise ValueError("progress_owner_required")
                if self.link.confirmation.pending is not None:
                    return json.dumps({"ok": True, "queued": False, "reason": "confirmation_pending"})
                return json.dumps({"ok": True, **self.link.events.explain(request, args)})
        except ValueError as exc:
            known = {"progress_owner_required", "invalid_progress_arguments", "progress_evidence_required", "progress_redaction_unavailable"}
            return json.dumps({"ok": False, "error": str(exc).upper() if str(exc) in known else "PROGRESS_UNAVAILABLE"})

    async def voice_draft(self, args, **kwargs):
        if isinstance(args, dict) and args.get("action") == "run_python":
            return await self._run_python(args)
        if isinstance(args, dict) and args.get("action") == "revise_document":
            return await self._revise_document(args)
        try:
            if self.link is None: raise ValueError("voice_owner_required")
            with self.link._lock:
                request=self.link.tool_voice_request.get()
                pending=self.link._pending or {}
                if not request or self.link.voice_request(pending.get("session_id"),pending.get("turn_id"))!=request or self.link._home is None:
                    raise ValueError("voice_owner_required")
                if isinstance(args, dict) and args.get("action") in ("list_documents", "read_document"):
                    try:
                        documents = VoiceDocuments(self.ctx.get_config("voice_documents", {}))
                        return json.dumps({"ok": True, "result": documents.perform(args)}, ensure_ascii=False)
                    except ValueError as exc:
                        known = {"document_catalog_invalid", "invalid_document_arguments", "document_not_allowed",
                                 "document_digest_required", "document_changed", "document_file_denied",
                                 "document_redaction_unavailable"}
                        code = str(exc).upper() if str(exc) in known else "DOCUMENT_UNAVAILABLE"
                        return json.dumps({"ok": False, "error": code})
                    except (OSError, TypeError, UnicodeError):
                        return json.dumps({"ok": False, "error": "DOCUMENT_UNAVAILABLE"})
                if self.drafts is None: self.drafts=DraftWorkspace(self.link._home)
                return json.dumps({"ok":True,"result":self.drafts.perform(request,args)},ensure_ascii=False)
        except ValueError as exc:
            known={"voice_owner_required","invalid_draft_arguments","invalid_draft_name","draft_too_large","draft_not_owned","draft_count_limit","draft_changed","draft_digest_required","private_directory_required","draft_file_denied"}
            return json.dumps({"ok":False,"error":str(exc).upper() if str(exc) in known else "DRAFT_DENIED_OR_CHANGED"})
        except (OSError,TypeError,UnicodeError):
            return json.dumps({"ok":False,"error":"DRAFT_DENIED_OR_CHANGED"})

    async def _run_python(self,args):
        cancel=threading.Event()
        try:
            if (not isinstance(args,dict) or set(args)!={"action","name","expected_sha256"}
                    or not isinstance(args.get("name"),str) or not args["name"].endswith(".py")):
                raise ValueError("invalid_python_arguments")
            if self.link is None or self.ctx.get_config("control_enabled",False) is not True:
                raise ValueError("python_owner_required")
            with self.link._lock:
                request=self.link.tool_voice_request.get();pending=self.link._pending or {}
                if not request or self.link.voice_request(pending.get("session_id"),pending.get("turn_id"))!=request:
                    raise ValueError("python_owner_required")
                if self.drafts is None: raise ValueError("draft_not_owned")
                stage=self.drafts.perform(request,{"action":"read","name":args["name"]})
                source=stage["content"];sha=stage["sha256"]
                if args["expected_sha256"]!=sha: raise ValueError("draft_changed")
                if not source or len(source)>2000 or len(source.encode("utf-8"))>8192:
                    raise ValueError("python_source_limit")
                from agent.redact import redact_sensitive_text
                if (any(unicodedata.category(c).startswith("C") and c not in "\n\r\t" for c in source)
                        or redact_sensitive_text(source,force=True,file_read=True,secret_file=True,redact_url_credentials=True)!=source):
                    raise ValueError("python_sensitive_source")
                digest=hashlib.sha256("\0".join((request,args["name"],sha)).encode("utf-8")).hexdigest()
            filename=self.ctx.get_config("connection_file")
            approval=await query_control("code_execution",dict(voice_request_id=request,name=args["name"],
                source=source,source_sha256=sha,action_digest=digest),filename)
            if approval.get("state")!="awaiting_voice_confirmation":raise ValueError("python_not_authorized")
            receipt=await self._wait_voice(approval,request,filename)
            if (receipt.get("state")!="completed" or receipt.get("dispatched") is not True
                    or receipt.get("permission_only") is not True or receipt.get("voice_request_id")!=request
                    or receipt.get("authorized_digest")!=digest):raise ValueError("python_not_authorized")
            meta=await query_control("status",{},filename)
            if meta.get("voice_request_id")!=request or meta.get("handsfree_active") is not True or meta.get("control_allowed") is not True:
                raise ValueError("python_owner_required")
            with self.link._lock:
                pending=self.link._pending or {}
                if self.link.voice_request(pending.get("session_id"),pending.get("turn_id"))!=request:
                    raise ValueError("python_owner_required")
                if self.drafts.perform(request,{"action":"read","name":args["name"]})["sha256"]!=sha:
                    raise ValueError("draft_changed")
            task=asyncio.create_task(asyncio.to_thread(run_python,source,cancel))
            while not task.done():
                await asyncio.wait({task},timeout=.05)
                with self.link._lock:
                    pending=self.link._pending or {}
                    if self.link.voice_request(pending.get("session_id"),pending.get("turn_id"))!=request:cancel.set()
            result=await task
            for field in ("stdout","stderr"):
                result[field]=redact_sensitive_text(result[field],force=True,file_read=True,secret_file=True,redact_url_credentials=True)
            result.update(source_sha256=sha,name=args["name"])
            return json.dumps({"ok":True,"result":result},ensure_ascii=False)
        except asyncio.CancelledError:
            return json.dumps({"ok":False,"error":"CANCELLED"})
        except Denied as exc:
            return json.dumps({"ok":False,"error":exc.code})
        except ValueError as exc:
            known={"invalid_python_arguments","python_owner_required","draft_not_owned","draft_changed",
                   "python_source_limit","python_sensitive_source","python_not_authorized"}
            return json.dumps({"ok":False,"error":str(exc).upper() if str(exc) in known else "PYTHON_UNAVAILABLE"})
        except Exception:
            return json.dumps({"ok":False,"error":"PYTHON_UNAVAILABLE"})
        finally:
            cancel.set()

    async def _revise_document(self, args):
        try:
            if self.link is None or self.ctx.get_config("control_enabled", False) is not True:
                raise ValueError("revision_owner_required")
            with self.link._lock:
                request = self.link.tool_voice_request.get()
                pending = self.link._pending or {}
                if (not request or self.link._home is None or
                        self.link.voice_request(pending.get("session_id"),pending.get("turn_id")) != request):
                    raise ValueError("revision_owner_required")
                documents = VoiceDocuments(self.ctx.get_config("voice_documents", {}))
                stage = prepare_revision(documents,request,args)
                if self.drafts is None: self.drafts=DraftWorkspace(self.link._home)
            filename=self.ctx.get_config("connection_file")
            approval=await query_control("document_revision",stage.native_args(),filename)
            if approval.get("state") != "awaiting_voice_confirmation":
                raise ValueError("revision_not_authorized")
            receipt=await self._wait_voice(approval,request,filename)
            # Native status requires an unlocked, available device after the decision.
            meta=await query_control("status",{},filename)
            if meta.get("voice_request_id")!=request or meta.get("handsfree_active") is not True or meta.get("control_allowed") is not True:
                raise ValueError("revision_owner_required")
            with self.link._lock:
                pending=self.link._pending or {}
                if self.link.voice_request(pending.get("session_id"),pending.get("turn_id")) != request:
                    raise ValueError("revision_owner_required")
                result=save_revision(documents,self.drafts,stage,receipt)
                return json.dumps({"ok":True,"result":result},ensure_ascii=False)
        except Denied as exc:
            return json.dumps({"ok":False,"error":exc.code})
        except asyncio.CancelledError:
            return json.dumps({"ok":False,"error":"CANCELLED"})
        except ValueError as exc:
            known={"revision_owner_required","invalid_revision_arguments","document_not_allowed",
                   "document_digest_required","document_changed","revision_match_not_unique",
                   "revision_too_large","revision_sensitive_content","revision_not_authorized",
                   "revision_verification_failed"}
            return json.dumps({"ok":False,"error":str(exc).upper() if str(exc) in known else "REVISION_UNAVAILABLE"})
        except Exception:
            return json.dumps({"ok":False,"error":"REVISION_UNAVAILABLE"})

    async def phone_act(self, args, **kwargs):
        return await self._control('act', args, kwargs)

    async def phone_run_plan(self, args, **kwargs):
        """Deterministic in-app plan execution; one call instead of one per UI step."""
        try:
            if self.ctx.get_config('control_enabled', False) is not True:
                raise Denied('CONTROL_DISABLED')
            voice_request = self.link.tool_voice_request.get() if self.link else None
            self._require_admission(voice_request)
            transport_args = dict(args)
            if voice_request:
                transport_args['_voice_request_id'] = voice_request
            from . import phone_learning as learning
            from .backend import load_connection
            from hermes_constants import get_hermes_home
            filename = self.ctx.get_config('connection_file')
            candidate = learning.template(args.get('plan')) if self.ctx.get_config('learning_enabled', True) is True else None
            device = None
            if candidate is not None:
                try: device = load_connection(filename)[0]['instance_id']
                except Exception: candidate = None
            started = time.monotonic()
            value = await query_plan(transport_args, filename)
            learned = {'state': 'not_eligible'}
            if candidate is not None and device is not None:
                try:
                    screen = await query_control('observe', {}, filename) if value.get('ok') is True else None
                    self._require_admission(voice_request)
                    if load_connection(filename)[0]['instance_id'] != device:
                        raise learning.LearningError('LEARNING_DEVICE_CHANGED')
                    learned = learning.record(get_hermes_home(), device, candidate,
                        learning.verified(candidate, value, screen),
                        min(120000, max(0, int((time.monotonic()-started)*1000))))
                except asyncio.CancelledError:
                    raise
                except Exception:
                    # Learning is advisory and cannot turn a completed device action
                    # into a claimed failure. Never persist raw exceptions or screens.
                    learned = {'state': 'unverified'}
                    try:
                        learned = learning.record(get_hermes_home(), device, candidate, False, 0)
                    except Exception:
                        pass
            return json.dumps({'ok':True, 'operation':'plan', 'result':value, 'learning':learned}, ensure_ascii=False)
        except asyncio.CancelledError:
            return json.dumps({'ok':False,'error':'CANCELLED'})
        except Denied as exc:
            return json.dumps({'ok':False,'error':exc.code})
        except Exception:
            return json.dumps({'ok':False,'error':'CONTROL_UNAVAILABLE'})

    def _require_admission(self, voice_request):
        """Enforce turn admission for any mutating call, text or voice.

        A text turn the hook admitted carries a record whose owner is None, so it passes. A
        turn with no record at all cannot prove admission and is refused: guarding this with
        `if voice_request` would let an unadmitted direct call reach the device. The live
        request is only consulted when this really is a voice turn.
        """
        if not self.link:
            return
        with self.link._lock:
            pending = self.link._pending or {}
            live = self.link.voice_request(pending.get('session_id'), pending.get('turn_id'))
            key = self.link.tool_turn_key.get()
            state, admitted = self.link._admission_owner(key) if key else ('none', None)
            if (state != 'record' or admitted != voice_request
                    or (voice_request and live != voice_request)):
                raise Denied('VOICE_OWNER_REQUIRED')

    async def _learned_profiles(self, args, home, **kwargs):
        from . import phone_learning as learning
        from .backend import load_connection
        try:
            action = args['action']
            allowed = {'learned': {'action'}, 'forget': {'action','route_id'},
                       'reuse': {'action','route_id','query','run'}}[action]
            if set(args)-allowed:
                raise Denied('INVALID_ARGUMENTS')
            if action == 'learned':
                device = load_connection(self.ctx.get_config('connection_file'))[0]['instance_id']
                return json.dumps({'ok':True,'entries':learning.list_routes(home,device)})
            if action == 'forget':
                self._require_admission(self.link.tool_voice_request.get() if self.link else None)
                return json.dumps({'ok':True,'removed':learning.forget(home,args.get('route_id'))})
            if self.ctx.get_config('learning_enabled', True) is not True:
                raise learning.LearningError('LEARNING_DISABLED')
            if 'run' in args and type(args['run']) is not bool:
                raise Denied('INVALID_ARGUMENTS')
            device = load_connection(self.ctx.get_config('connection_file'))[0]['instance_id']
            plan = learning.resolve(home,args.get('route_id'),device,args.get('query'))
            if args.get('run') is not True:
                return json.dumps({'ok':True,'plan':plan,'verification':'screen_arrival_only'})
            result = json.loads(await self.phone_run_plan({'plan':plan}, **kwargs))
            if result.get('ok') is not True or result.get('learning',{}).get('state') == 'unverified':
                # A transport failure or unverified result is not permission to replay.
                try: result['learning']=learning.record(home,device,plan,False,0)
                except Exception: pass
            return json.dumps(result,ensure_ascii=False)
        except learning.LearningError as exc:
            return json.dumps({'ok':False,'error':str(exc),'next_step':'reobserve_and_replan_no_automatic_retry'})
        except Denied as exc:
            return json.dumps({'ok':False,'error':exc.code})

    async def phone_profiles(self, args, **kwargs):
        """List stored app profiles, or resolve one route into a ready-to-run plan."""
        try:
            from hermes_constants import get_hermes_home
            from . import phone_profiles as store
            if not isinstance(args, dict):
                raise Denied('INVALID_ARGUMENTS')
            action = args.get('action')
            home = get_hermes_home()
            if action in {'learned', 'reuse', 'forget'}:
                return await self._learned_profiles(args, home, **kwargs)
            if action == 'list':
                if set(args) - {'action'}:
                    raise Denied('INVALID_ARGUMENTS')
                return json.dumps({'ok': True, 'profiles': store.list_apps(home)}, ensure_ascii=False)
            if action != 'resolve':
                raise Denied('INVALID_ARGUMENTS')
            if set(args) - {'action', 'package', 'route', 'query', 'version_code', 'run'}:
                raise Denied('INVALID_ARGUMENTS')
            plan = store.resolve(home, args.get('package'), args.get('route'),
                                 query=args.get('query'), version_code=args.get('version_code'))
            if args.get('run') is not True:
                return json.dumps({'ok': True, 'operation': 'profile', 'plan': plan},
                                  ensure_ascii=False)
            if self.ctx.get_config('control_enabled', False) is not True:
                raise Denied('CONTROL_DISABLED')
            voice_request = self.link.tool_voice_request.get() if self.link else None
            self._require_admission(voice_request)
            stored = (store.get(home, args.get('package')) or {}).get('version_code')
            # Now that resolve() pins the recorded version into the plan, the device always
            # compares it before acting. It is unchecked only when the profile records none,
            # and the device then skips the comparison rather than guessing.
            version_checked = isinstance(stored, int) and not isinstance(stored, bool)
            transport_args = {'plan': plan}
            if voice_request:
                transport_args['_voice_request_id'] = voice_request
            value = await query_plan(transport_args, self.ctx.get_config('connection_file'))
            # Record only outcomes that describe the anchors: a plan refused before its first
            # step (version drift, budget, bad arguments) says nothing about whether the
            # route works, so it must not drag a healthy route's success rate down.
            outcome = None
            if isinstance(value, dict) and isinstance(value.get('completed'), int):
                if value.get('ok') is True:
                    outcome = True
                elif value.get('completed', 0) > 0:
                    outcome = False
            if outcome is not None:
                try:
                    store.record(home, args.get('package'), args.get('route'), outcome)
                except Exception:
                    # Telemetry must never turn a successful run into a reported failure.
                    pass
            if isinstance(value, dict) and isinstance(value.get('completed'), int):
                # One line per run so the outcome can be reviewed later without a live
                # session. Query text and screen contents are deliberately excluded.
                steps = []
                for step in value.get('steps') or []:
                    if not isinstance(step, dict):
                        continue
                    steps.append({'op': step.get('op'), 'ok': step.get('ok'),
                                  'detail': step.get('detail'), 'ms': step.get('ms')})
                store._append_log(home, {
                    'ts': time.strftime('%Y-%m-%dT%H:%M:%S%z'),
                    'package': args.get('package'), 'route': args.get('route'),
                    'version_checked': version_checked,
                    'ok': value.get('ok'), 'code': value.get('code'),
                    'completed': value.get('completed'), 'total': value.get('total'),
                    'failed_op': value.get('failed_op'),
                    'elapsed_ms': value.get('elapsed_ms'), 'steps': steps})
            return json.dumps({'ok': True, 'operation': 'plan', 'profile': {
                'package': args.get('package'), 'route': args.get('route'),
                'version_checked': version_checked}, 'result': value},
                ensure_ascii=False)
        except asyncio.CancelledError:
            return json.dumps({'ok': False, 'error': 'CANCELLED'})
        except Denied as exc:
            return json.dumps({'ok': False, 'error': exc.code})
        except Exception as exc:
            # Only the store's own error type is trusted, and only a code from a fixed set is
            # returned: the suffix after the colon can carry a route or label name, which is
            # profile data, not something to echo to the model.
            from .phone_profiles import ProfileError
            if isinstance(exc, ProfileError):
                return json.dumps({'ok': False, 'error': _profile_code(exc.code)})
            return json.dumps({'ok': False, 'error': 'CONTROL_UNAVAILABLE'})

    async def _control(self, operation, args, kwargs):
        try:
            if self.ctx.get_config('control_enabled', False) is not True:
                raise Denied('CONTROL_DISABLED')
            if operation == 'observe':
                if args != {}: raise Denied('INVALID_ARGUMENTS')
            else: validate_action(args)
            filename = self.ctx.get_config('connection_file')
            voice_request = self.link.tool_voice_request.get() if self.link else None
            # A text turn the hook admitted carries an admission record with a None owner, so
            # it passes. A turn with no record at all cannot prove text admission and must be
            # denied: skipping the check whenever no voice request exists would let an
            # unadmitted runtime mutate the phone.
            if operation == 'act' and args.get('action') not in {'approval_status','cancel_approval'} and self.link:
                with self.link._lock:
                    pending = self.link._pending or {}
                    live = self.link.voice_request(pending.get('session_id'), pending.get('turn_id'))
                    key = self.link.tool_turn_key.get()
                    state, admitted = self.link._admission_owner(key) if key else ('none', None)
                    if (state != 'record' or admitted != voice_request
                            or (voice_request and live != voice_request) or (pending and not voice_request)):
                        raise Denied('VOICE_OWNER_REQUIRED')
            transport_args = dict(args)
            if operation == 'act' and voice_request and args.get('action') not in {'approval_status','cancel_approval'}:
                transport_args['_voice_request_id'] = voice_request
            if operation == 'observe':
                value = await observe_settled(transport_args, filename)
            else:
                value = await query_control(operation, transport_args, filename)
            if value.get('state') == 'awaiting_voice_confirmation':
                if not voice_request:
                    await query_control('act', {'action':'cancel_approval'}, filename)
                    raise Denied('VOICE_OWNER_REQUIRED')
                value = await self._wait_voice(value, voice_request, filename)

            if operation == 'observe':
                value = compact_observation(value)
            return json.dumps({'ok':True, 'operation':operation, 'result':value}, ensure_ascii=False,
                              separators=(',', ':') if operation == 'observe' else None)
        except asyncio.CancelledError:
            return json.dumps({'ok':False,'error':'CANCELLED'})
        except Denied as exc:
            return json.dumps({'ok':False,'error':exc.code})
        except Exception:
            return json.dumps({'ok':False,'error':'CONTROL_UNAVAILABLE'})

    async def _wait_voice(self, value, request_id, filename):
        action_id = value['request_id']
        finished = False
        try:
            self.link.begin_confirmation(request_id, value)
            async with asyncio.timeout(245):
                while True:
                    event = self.link.confirmation.get(request_id, action_id)
                    result = await query_control('act', {'action':'approval_status', 'request_id':action_id}, filename)
                    if result.get('state') != 'awaiting_voice_confirmation':
                        finished = True
                        return result
                    await asyncio.sleep(0.4)
        finally:
            self.link.confirmation.clear(action_id)
            if not finished:
                try:
                    await query_control('act', {'action':'cancel_approval'}, filename)
                except Exception:
                    pass  # App expiry/OFF/lock remain independent fail-closed guards.

    async def _handle(self, operation, args, kwargs):
        envelope = {'schema_version':1,'operation':operation,
                    'task_id':identity(kwargs.get('task_id')),
                    'session_id':identity(kwargs.get('session_id')),
                    'ownership':'not_enforced_stage_a','read_only':True}
        try:
            if not isinstance(args,dict) or args:
                raise Denied('INVALID_ARGUMENTS')
            timeout = self.ctx.get_config('timeout_seconds',5.0)
            if (type(timeout) not in (int,float) or not math.isfinite(timeout)
                    or not 0.1 <= timeout <= 10):
                raise Denied('INVALID_CONNECTION')
            data = await query(operation,self.ctx.get_config('connection_file'),timeout)
            return json.dumps(dict(envelope,ok=True,**data),ensure_ascii=False)
        except asyncio.CancelledError:
            # No persistent work to cancel. This is NOT an Android action-cancel acknowledgment.
            code = 'CANCELLED'
        except TimeoutError:
            code = 'TIMEOUT'
        except Denied as exc:
            code = exc.code if exc.code in MESSAGES else 'BACKEND_DENIED'
        except BackendDenied:
            code = 'BACKEND_DENIED'
        except (OSError, ValueError, TypeError, KeyError, AttributeError):
            code = 'INVALID_CONNECTION'
        except Exception:
            # Keep input, HTTP bodies, tokens, paths and exception repr out of host logs.
            code = 'BACKEND_UNAVAILABLE'
        return json.dumps(dict(envelope,ok=False,error={'code':code,'message':MESSAGES[code]}))

