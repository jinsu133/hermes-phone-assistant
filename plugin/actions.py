"""Narrow native action transport, operator-opt-in, no shell or endpoint arguments."""
import asyncio
from .backend import Denied, JsonMcpClient, load_connection, parse_json

DETAILS = {"kind", "recipient", "content", "merchant", "amount", "method"}
REQUIRED_DETAILS = {"general": set(), "call": {"recipient"}, "message": {"recipient", "content"}, "payment": {"merchant", "amount", "method"}}
FIELDS = {
    "approval_status": ({"action", "request_id"}, {"action", "request_id"}),
    "cancel_approval": ({"action"}, {"action"}),
    "list_apps": ({"action", "query"}, {"action"}),
    "package_version": ({"action", "package"}, {"action", "package"}),
    "open_app": ({"action", "package"}, {"action", "package"}),
    **{k: ({"action"}, {"action"}) for k in ("home", "back", "recents", "notifications")},
    **{k: ({"action", "snapshot", "ref"}, {"action", "snapshot", "ref"}) for k in ("click", "submit_search", "scroll_forward", "scroll_backward")},
    "read_node": ({"action", "snapshot", "ref", "offset"}, {"action", "snapshot", "ref"}),
    "input_text": ({"action", "snapshot", "ref", "text"}, {"action", "snapshot", "ref", "text"}),
}

def validate_action(args):
    if not isinstance(args, dict) or not isinstance(args.get("action"), str) or args["action"] not in FIELDS:
        raise Denied("INVALID_ARGUMENTS")
    allowed, required = FIELDS[args["action"]]
    if args["action"] not in {"list_apps", "open_app", "approval_status", "cancel_approval", "read_node", "package_version"}:
        allowed = allowed | {"confirmation"}
    if not required <= set(args) <= allowed:
        raise Denied("INVALID_ARGUMENTS")
    for key, value in args.items():
        if key == "confirmation":
            if not isinstance(value, dict) or not set(value) <= DETAILS:
                raise Denied("INVALID_ARGUMENTS")
            if any(not isinstance(v,str) or not v.strip() or len(v)>2000 for v in value.values()):
                raise Denied("INVALID_ARGUMENTS")
            kind=value.get("kind", "general")
            if kind not in REQUIRED_DETAILS or not REQUIRED_DETAILS[kind] <= set(value):
                raise Denied("INVALID_ARGUMENTS")
        elif key == "offset":
            if type(value) is not int or not 0 <= value <= 64000: raise Denied("INVALID_ARGUMENTS")
        elif not isinstance(value, str) or len(value) > (2000 if key == "text" else 200):
            raise Denied("INVALID_ARGUMENTS")

async def query_plan(args, filename):
    """Run a deterministic multi-step plan on the device (no per-step model round trip)."""
    if not isinstance(args, dict) or set(args) - {"plan", "_voice_request_id"} or "plan" not in args:
        raise Denied("INVALID_ARGUMENTS")
    plan = args["plan"]
    if not isinstance(plan, dict) or set(plan) - {"steps", "budget_ms", "task", "package", "version_code"}:
        raise Denied("INVALID_ARGUMENTS")
    if ("package" in plan) != ("version_code" in plan):
        raise Denied("INVALID_ARGUMENTS")
    if "package" in plan:
        pkg, version = plan["package"], plan["version_code"]
        if not isinstance(pkg, str) or len(pkg) > 200 or type(version) is not int or version < 0:
            raise Denied("INVALID_ARGUMENTS")
    steps = plan.get("steps")
    if not isinstance(steps, list) or not 1 <= len(steps) <= 24:
        raise Denied("INVALID_ARGUMENTS")
    budget = plan.get("budget_ms", 8000)
    if type(budget) is not int or not 500 <= budget <= 15000:
        raise Denied("INVALID_ARGUMENTS")
    allowed = {
        "launch": ({"op", "package"}, {"op", "package"}),
        "await": ({"op", "role", "id", "id_suffix", "text", "text_contains", "timeout_ms"}, {"op"}),
        "focus": ({"op", "role", "id", "id_suffix", "text", "text_contains", "index"}, {"op"}),
        "input": ({"op", "role", "id", "id_suffix", "text", "text_contains", "index", "value"}, {"op", "value"}),
        "submit": ({"op", "role", "id", "id_suffix", "text", "text_contains", "timeout_ms"}, {"op"}),
        "select": ({"op", "role", "id", "id_suffix", "text", "text_contains", "index"}, {"op"}),
        "click": ({"op", "role", "id", "id_suffix", "text", "text_contains", "index"}, {"op"}),
        "assert": ({"op", "role", "id", "id_suffix", "text", "text_contains"}, {"op"}),
        "back": ({"op"}, {"op"}),
        "home": ({"op"}, {"op"}),
        "label": ({"op", "name"}, {"op", "name"}),
        "route": ({"op", "branches", "to", "timeout_ms"}, {"op", "branches"}),
        "dismiss": ({"op", "labels"}, {"op", "labels"}),
    }
    for step in steps:
        if not isinstance(step, dict) or step.get("op") not in allowed:
            raise Denied("INVALID_ARGUMENTS")
        ok, required = allowed[step["op"]]
        if not required <= set(step) <= ok:
            raise Denied("INVALID_ARGUMENTS")
        for key, value in step.items():
            if key == "op":
                continue
            if key == "index":
                if type(value) is not int or not 0 <= value <= 20:
                    raise Denied("INVALID_ARGUMENTS")
            elif key == "timeout_ms":
                if type(value) is not int or not 100 <= value <= 5000:
                    raise Denied("INVALID_ARGUMENTS")
            elif key == "labels":
                if not isinstance(value, list) or not 1 <= len(value) <= 12:
                    raise Denied("INVALID_ARGUMENTS")
                if any(not isinstance(v, str) or not v.strip() or len(v) > 60 for v in value):
                    raise Denied("INVALID_ARGUMENTS")
            elif key == "branches":
                if not isinstance(value, list) or not 1 <= len(value) <= 8:
                    raise Denied("INVALID_ARGUMENTS")
                for branch in value:
                    if not isinstance(branch, dict) or "to" not in branch:
                        raise Denied("INVALID_ARGUMENTS")
                    if not set(branch) <= {"to", "role", "id", "id_suffix", "text", "text_contains"}:
                        raise Denied("INVALID_ARGUMENTS")
                    if any(not isinstance(v, str) or not v.strip() or len(v) > 200 for v in branch.values()):
                        raise Denied("INVALID_ARGUMENTS")
            elif not isinstance(value, str) or len(value) > (2000 if key in ("text", "value") else 200):
                raise Denied("INVALID_ARGUMENTS")
    pairing, port = load_connection(filename)
    client = JsonMcpClient(pairing, port)
    async with asyncio.timeout(20):
        await client.initialize()
        status = await client.rpc("tools/call", {"name": "phone_backend_status", "arguments": {}})
        if status.get("isError") is not False:
            raise Denied("BACKEND_DENIED")
        meta = parse_json(status["content"][0]["text"])
        if meta.get("instance_id") != pairing["instance_id"]:
            raise Denied("DEVICE_MISMATCH")
        if not all(meta.get(k) is True for k in ("ready", "unlocked", "accessibility")):
            raise Denied("PHONE_LOCKED_OR_UNAVAILABLE")
        if meta.get("plan_executor_version") != 1:
            raise Denied("PLAN_EXECUTOR_BACKEND_REQUIRED")
        result = await client.rpc("tools/call", {"name": "phone_run_plan", "arguments": args})
        if result.get("isError") is not False:
            error = parse_json(result["content"][0]["text"]).get("code")
            raise Denied(error if error in PLAN_ERRORS else "ACTION_DENIED_OR_STALE_SCREEN")
        return parse_json(result["content"][0]["text"])


PLAN_ERRORS = {"INVALID_ARGUMENTS", "VOICE_OWNER_REQUIRED", "CONTROL_STOPPED",
               "PHONE_LOCKED_OR_UNAVAILABLE", "CONFIRMATION_BACKEND_REQUIRED"}


async def query_control(operation, args, filename):
    pairing, port = load_connection(filename)
    client = JsonMcpClient(pairing, port)
    async with asyncio.timeout(8):
        await client.initialize()
        status = await client.rpc("tools/call", {"name":"phone_backend_status", "arguments":{}})
        if status.get("isError") is not False: raise Denied("BACKEND_DENIED")
        meta = parse_json(status["content"][0]["text"])
        if meta.get("instance_id") != pairing["instance_id"]: raise Denied("DEVICE_MISMATCH")
        if not all(meta.get(k) is True for k in ("ready", "unlocked", "accessibility")): raise Denied("PHONE_LOCKED_OR_UNAVAILABLE")
        if operation == "act" and args.get("action") not in {"list_apps","open_app","package_version"} and meta.get("action_confirmation_version") != 1:
            raise Denied("CONFIRMATION_BACKEND_REQUIRED")
        if args.get("_voice_request_id") and meta.get("voice_confirmation_version") != 1:
            raise Denied("VOICE_CONFIRMATION_BACKEND_REQUIRED")
        if operation == "status": return meta
        methods = {"observe":"phone_observe_screen", "act":"phone_action", "document_revision":"host_document_revision", "code_execution":"host_code_execution"}
        if operation not in methods: raise Denied("INVALID_ARGUMENTS")
        if operation == "document_revision" and meta.get("host_document_revision_version") != 1:
            raise Denied("DOCUMENT_REVISION_BACKEND_REQUIRED")
        if operation == "code_execution" and meta.get("host_code_execution_version") != 1:
            raise Denied("CODE_EXECUTION_BACKEND_REQUIRED")
        result = await client.rpc("tools/call", {"name":methods[operation], "arguments":args})
        if result.get("isError") is not False:
            body = parse_json(result["content"][0]["text"])
            error = body.get("code")
            # Name the one confirmation field that is not on screen, so the model fixes only that.
            if error == "CONFIRMATION_DETAILS_REQUIRED" and body.get("missing") in {"recipient","content","merchant","amount","method"}:
                raise Denied(f"CONFIRMATION_DETAILS_REQUIRED:{body['missing']}")
            allowed = {"SNAPSHOT_EXPIRED", "TARGET_CHANGED", "FOREGROUND_CHANGED", "PROTECTED_INPUT",
                       "SCREEN_LIMIT_EXCEEDED", "SCREEN_UNAVAILABLE", "APPROVAL_PENDING",
                       "CONFIRMATION_DETAILS_REQUIRED", "CONTROL_STOPPED", "UNSUPPORTED_UI",
                       "VOICE_OWNER_REQUIRED", "USER_DIRECT_ACTION_REQUIRED"}
            raise Denied(error if error in allowed else "ACTION_DENIED_OR_STALE_SCREEN")
        return parse_json(result["content"][0]["text"])
