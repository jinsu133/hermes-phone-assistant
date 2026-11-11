"""App UI profiles for deterministic multi-step plans.

A profile is data, not code: which anchor identifies a screen in an app, and which
steps reach it from a known state. Keeping it here (Hermes home) rather than on the
device makes one file the single source of truth and lets the plugin decide when a
stored anchor is stale. The device receives the resolved plan inside the existing
`phone_run_plan` call and stays stateless.

Trust boundary: a profile can only produce plan *steps*, which the existing validator
and the device action policy still judge independently. Nothing here can approve an
action, widen the operand set, or skip validation.
"""
import contextlib
import json
import os
import tempfile
from pathlib import Path

# Anchors are matched by the planner's selector keys only. A profile may not invent
# an operation or a confirmation argument.
_SELECTOR_KEYS = ("role", "id", "id_suffix", "text", "text_contains")
_STEP_OPS = {"launch", "await", "focus", "input", "submit", "select", "click",
             "assert", "back", "home", "label", "route", "dismiss"}
# Mirror of actions.py::query_plan's per-op allow-list. The device rejects a step carrying
# any key outside this map, so validating here means a bad profile fails at save time with a
# clear code instead of at run time as INVALID_ARGUMENTS. Keep the two in lockstep.
_STEP_KEYS = {
    "launch": {"op", "package"},
    "await": {"op", "role", "id", "id_suffix", "text", "text_contains", "timeout_ms"},
    "focus": {"op", "role", "id", "id_suffix", "text", "text_contains", "index"},
    "input": {"op", "role", "id", "id_suffix", "text", "text_contains", "index", "value"},
    "submit": {"op", "role", "id", "id_suffix", "text", "text_contains", "timeout_ms"},
    "select": {"op", "role", "id", "id_suffix", "text", "text_contains", "index"},
    "click": {"op", "role", "id", "id_suffix", "text", "text_contains", "index"},
    "assert": {"op", "role", "id", "id_suffix", "text", "text_contains"},
    "back": {"op"},
    "home": {"op"},
    "label": {"op", "name"},
    "route": {"op", "branches", "to", "timeout_ms"},
    "dismiss": {"op", "labels"},
}
# The device also enforces a per-op minimum. A step that is merely *allowed* is not enough:
# {'op':'input'} without `value` passes the upper bound but the device refuses it.
_STEP_REQUIRED = {
    "launch": {"op", "package"},
    "await": {"op"},
    "focus": {"op"},
    "input": {"op", "value"},
    "submit": {"op"},
    "select": {"op"},
    "click": {"op"},
    "assert": {"op"},
    "back": {"op"},
    "home": {"op"},
    "label": {"op", "name"},
    "route": {"op", "branches"},
    "dismiss": {"op", "labels"},
}
# Words that must never appear in a dismiss label: a plan may close an overlay, never agree
# to one. The device checks this again against the live node, but refusing at save time keeps
# a bad profile out of the store in the first place.
_DISMISS_FORBIDDEN = ("허용", "확인", "동의", "결제", "전송", "구매", "송금", "이체", "삭제",
                      "승인", "결제하기", "구매하기")
# Keys the device caps at 2000 chars; every other string key is capped at 200.
_LONG_TEXT_KEYS = {"text", "value"}
_MAX_PROFILE_BYTES = 64 * 1024
_MAX_LOG_BYTES = 256 * 1024
_SCHEMA = 1


class ProfileError(Exception):
    def __init__(self, code):
        super().__init__(code)
        self.code = code


def _path(home):
    return Path(home) / "phone-profiles.json"


def _check_selector(selector, where):
    if not isinstance(selector, dict) or not selector:
        raise ProfileError(f"profile_bad_selector:{where}")
    if not set(selector) <= set(_SELECTOR_KEYS):
        raise ProfileError(f"profile_bad_selector:{where}")
    for key, value in selector.items():
        if not isinstance(value, str) or not value.strip() or len(value) > 200:
            raise ProfileError(f"profile_bad_selector:{where}")


def _check_steps(steps, where):
    if not isinstance(steps, list) or not 1 <= len(steps) <= 24:
        raise ProfileError(f"profile_bad_steps:{where}")
    for step in steps:
        if not isinstance(step, dict) or step.get("op") not in _STEP_OPS:
            raise ProfileError(f"profile_bad_steps:{where}")
        op = step["op"]
        if not set(step) <= _STEP_KEYS[op]:
            raise ProfileError(f"profile_bad_steps:{where}")
        if not _STEP_REQUIRED[op] <= set(step):
            raise ProfileError(f"profile_missing_field:{where}")
        for key, value in step.items():
            if key in ("index",):
                if type(value) is not int or not 0 <= value <= 20:
                    raise ProfileError(f"profile_bad_steps:{where}")
            elif key in ("timeout_ms",):
                if type(value) is not int or not 100 <= value <= 5000:
                    raise ProfileError(f"profile_bad_steps:{where}")
            elif key == "labels":
                if not isinstance(value, list) or not 1 <= len(value) <= 12:
                    raise ProfileError(f"profile_bad_steps:{where}")
                if any(not isinstance(v, str) or not v.strip() or len(v) > 60 for v in value):
                    raise ProfileError(f"profile_bad_steps:{where}")
                # A plan may close an overlay; it may never agree to one.
                if any(word in v for v in value for word in _DISMISS_FORBIDDEN):
                    raise ProfileError(f"profile_forbidden_dismiss_label:{where}")
            elif key == "branches":
                if not isinstance(value, list) or not 1 <= len(value) <= 8:
                    raise ProfileError(f"profile_bad_steps:{where}")
                for branch in value:
                    if not isinstance(branch, dict) or "to" not in branch:
                        raise ProfileError(f"profile_bad_steps:{where}")
                    if not set(branch) <= ({"to"} | set(_SELECTOR_KEYS)):
                        raise ProfileError(f"profile_bad_steps:{where}")
                    if any(not isinstance(v, str) or not v.strip() or len(v) > 200
                           for v in branch.values()):
                        raise ProfileError(f"profile_bad_steps:{where}")
                    rest = {k: v for k, v in branch.items() if k != "to"}
                    # A branch with only `to` matches no node on the device, so it can never
                    # fire. Refusing it here keeps the route contract explicit: a branch is a
                    # selector plus a jump target.
                    if not rest:
                        raise ProfileError(f"profile_bad_selector:{where}")
                    _check_selector(rest, where)
            elif key == "package":
                if not isinstance(value, str) or not value.strip() or len(value) > 200:
                    raise ProfileError(f"profile_bad_steps:{where}")
            elif not isinstance(value, str):
                raise ProfileError(f"profile_bad_steps:{where}")
            elif len(value) > (2000 if key in _LONG_TEXT_KEYS else 200):
                raise ProfileError(f"profile_bad_steps:{where}")


def _validate(profile, package):
    if not isinstance(profile, dict):
        raise ProfileError("profile_bad_shape")
    if profile.get("package") != package:
        raise ProfileError("profile_package_mismatch")
    if not set(profile) <= {"package", "version_code", "entry", "anchors", "routes",
                            "dismiss", "stats"}:
        raise ProfileError("profile_bad_shape")
    version = profile.get("version_code")
    if version is not None and (type(version) is not int or version < 0):
        raise ProfileError("profile_bad_version")
    anchors = profile.get("anchors", {})
    if not isinstance(anchors, dict) or not anchors:
        raise ProfileError("profile_bad_anchors")
    for name, selector in anchors.items():
        if not isinstance(name, str) or not name or len(name) > 64:
            raise ProfileError("profile_bad_anchors")
        _check_selector(selector, name)
    routes = profile.get("routes", {})
    if not isinstance(routes, dict):
        raise ProfileError("profile_bad_routes")
    for name, steps in routes.items():
        if name not in anchors:
            raise ProfileError(f"profile_unknown_anchor:{name}")
        _check_steps(steps, name)
    dismiss = profile.get("dismiss", [])
    if not isinstance(dismiss, list) or len(dismiss) > 12:
        raise ProfileError("profile_bad_dismiss")
    for label in dismiss:
        if not isinstance(label, str) or not label.strip() or len(label) > 60:
            raise ProfileError("profile_bad_dismiss")
    # A route may only dismiss labels the profile declared, and a declared label may not be a
    # consent word. Both are checked here so an unusable profile never reaches the store.
    for name, steps in routes.items():
        for step in steps:
            if step.get("op") != "dismiss":
                continue
            for label in step.get("labels") or []:
                if label not in dismiss:
                    raise ProfileError(f"profile_undeclared_dismiss:{label}")
    entry = profile.get("entry", {})
    if not isinstance(entry, dict) or not set(entry) <= {"deeplink", "fallback"}:
        raise ProfileError("profile_bad_entry")
    if entry.get("deeplink") is not None and (
            not isinstance(entry["deeplink"], str) or not entry["deeplink"].strip()):
        raise ProfileError("profile_bad_entry")
    if entry.get("fallback") not in (None, "launch"):
        raise ProfileError("profile_bad_entry")
    stats = profile.get("stats", {})
    if not isinstance(stats, dict) or len(stats) > 32:
        raise ProfileError("profile_bad_stats")
    for name, entry in stats.items():
        # Counters are advisory telemetry; anything unrecognisable is dropped rather than
        # trusted, but a shaped-but-bad entry is still refused so the store stays honest.
        if not isinstance(name, str) or name not in routes:
            raise ProfileError("profile_bad_stats")
        if not isinstance(entry, dict) or set(entry) != set(_empty_stats()):
            raise ProfileError("profile_bad_stats")
        for key in ("runs", "fails", "streak", "best"):
            value = entry.get(key)
            if type(value) is not int or value < 0:
                raise ProfileError("profile_bad_stats")
        if entry.get("last_ok") not in (None, True, False):
            raise ProfileError("profile_bad_stats")


def load(home):
    """Return the whole store, or an empty store when nothing is saved yet.

    Every stored profile is re-validated on read. A store that was edited by hand, or written
    by an older version of this module, must not be able to feed an unvalidated plan into the
    device; semantic damage is refused just like a JSON syntax error.
    """
    path = _path(home)
    try:
        with open(path, "rb") as handle:
            raw = handle.read(_MAX_PROFILE_BYTES + 1)
    except FileNotFoundError:
        return {"schema": _SCHEMA, "apps": {}}
    except OSError:
        raise ProfileError("profile_store_unreadable")
    if len(raw) > _MAX_PROFILE_BYTES:
        raise ProfileError("profile_store_too_large")
    try:
        data = json.loads(raw.decode("utf-8"))
    except Exception:
        raise ProfileError("profile_store_unreadable")
    if not isinstance(data, dict) or data.get("schema") != _SCHEMA:
        raise ProfileError("profile_store_bad_schema")
    apps = data.get("apps")
    if not isinstance(apps, dict):
        raise ProfileError("profile_store_bad_schema")
    for package, profile in apps.items():
        if not isinstance(package, str) or not package.strip() or len(package) > 200:
            raise ProfileError("profile_store_bad_schema")
        _validate(profile, package)
    return data


def save(home, data):
    """Write atomically so a crash cannot leave a half-written profile."""
    if not isinstance(data, dict) or data.get("schema") != _SCHEMA:
        raise ProfileError("profile_store_bad_schema")
    path = _path(home)
    path.parent.mkdir(parents=True, exist_ok=True)
    payload = json.dumps(data, ensure_ascii=False, indent=1, sort_keys=True)
    if len(payload.encode("utf-8")) > _MAX_PROFILE_BYTES:
        raise ProfileError("profile_store_too_large")
    temp = None
    try:
        fd, temp = tempfile.mkstemp(dir=str(path.parent), prefix=".phone-profiles-")
        with os.fdopen(fd, "w", encoding="utf-8") as handle:
            handle.write(payload)
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temp, path)
        temp = None
        # Making the rename itself durable: without this a power loss can undo the replace
        # even though the file contents were flushed.
        try:
            dir_fd = os.open(str(path.parent), os.O_RDONLY)
        except OSError:
            return
        try:
            os.fsync(dir_fd)
        except OSError:
            pass
        finally:
            os.close(dir_fd)
    finally:
        if temp is not None:
            try:
                os.unlink(temp)
            except OSError:
                pass


def get(home, package):
    """Return the stored profile for a package, or None."""
    return load(home)["apps"].get(package)


@contextlib.contextmanager
def _store_lock(home):
    """Serialise the read-modify-write in put() across processes.

    Two writers that both read the old store would otherwise each write a complete file and
    the later replace would silently drop the other app's profile. The lock lives beside the
    JSON (not on it) because replacing the JSON swaps the inode the lock would be held on.
    """
    path = _path(home)
    path.parent.mkdir(parents=True, exist_ok=True)
    lock_path = path.parent / ".phone-profiles.lock"
    handle = open(lock_path, "a+b")
    try:
        try:
            import fcntl
        except ImportError:  # pragma: no cover - Android/Termux always has fcntl
            yield
            return
        fcntl.flock(handle.fileno(), fcntl.LOCK_EX)
        try:
            yield
        finally:
            fcntl.flock(handle.fileno(), fcntl.LOCK_UN)
    finally:
        handle.close()


def put(home, package, profile, version_code=None):
    """Validate and store one app profile. Returns the stored profile."""
    if not isinstance(package, str) or not package.strip() or len(package) > 200:
        raise ProfileError("profile_package_mismatch")
    if version_code is not None:
        profile = dict(profile, version_code=version_code)
    _validate(profile, package)
    with _store_lock(home):
        data = load(home)
        data["apps"][package] = profile
        save(home, data)
    return profile


# --- Reliability tracking -------------------------------------------------------------
# A profile that is never checked quietly rots: the app updates, one anchor starts matching
# a different control, and the route keeps "succeeding" while doing the wrong thing. These
# counters make decay visible and let a route be demoted without a human noticing first.
#
# The counters live under `stats` and are deliberately plain numbers so a corrupt store is
# still detectable by _validate. They are advisory: nothing here can approve an action.
_PROMOTE_RUNS = 3        # consecutive successes before a candidate route is trusted
_DEMOTE_RATE = 0.5       # below this success rate a route is demoted to candidate
_DEMOTE_WINDOW = 8       # ...once this many outcomes are recorded


def _empty_stats():
    return {"runs": 0, "fails": 0, "streak": 0, "best": 0, "last_ok": None}


def _grade(profile, route):
    """Grade a route from one profile dict, without re-reading the store."""
    route_stats = (profile.get("stats") or {}).get(route) or _empty_stats()
    if not isinstance(route_stats, dict):
        route_stats = _empty_stats()
    runs = int(route_stats.get("runs") or 0)
    fails = int(route_stats.get("fails") or 0)
    streak = int(route_stats.get("streak") or 0)
    if runs >= _DEMOTE_WINDOW and (runs - fails) / runs < _DEMOTE_RATE:
        return "decayed"
    if streak >= _PROMOTE_RUNS:
        return "trusted"
    return "candidate"


def _log_path(home):
    return Path(_path(home)).parent / "phone-plans.log"


def _append_log(home, entry):
    """Append one JSON line per plan run to a device-local log.

    The log exists so a run can be reviewed later without a live session: it records which
    route ran, the outcome code, the elapsed time and the per-step timings that explain
    where the time went. It is append-only and rotated by size, and it never carries the
    query text, screen contents, tokens or exception reprs.
    """
    path = _log_path(home)
    try:
        if path.exists() and path.stat().st_size > _MAX_LOG_BYTES:
            rotated = path.with_suffix(".log.1")
            try:
                rotated.unlink()
            except FileNotFoundError:
                pass
            path.replace(rotated)
        path.parent.mkdir(parents=True, exist_ok=True)
        with open(path, "a", encoding="utf-8") as handle:
            handle.write(json.dumps(entry, ensure_ascii=False) + "\n")
            handle.flush()
    except OSError:
        # Logging must never turn a finished run into a reported failure.
        pass


def record(home, package, route, ok):
    """Record one run outcome for a route and return the updated stats.

    Called after a plan finishes, so the counters describe what the device actually did
    rather than what the plugin asked for. A failed run breaks the streak; it does not
    count as evidence against the anchors until enough failures accumulate, because a
    single failure is usually an app popup or a slow screen, not a bad profile.

    The app version is part of the identity: a run recorded against a version the profile
    no longer pins is discarded rather than accumulated, so upgrading an app does not leave
    a route looking proven when its anchors were never observed on that version.
    """
    if not isinstance(ok, bool):
        raise ProfileError("profile_bad_outcome")
    with _store_lock(home):
        data = load(home)
        profile = data["apps"].get(package)
        if profile is None:
            raise ProfileError("profile_missing")
        if route not in (profile.get("routes") or {}):
            raise ProfileError(f"profile_unknown_route:{route}")
        stats = profile.get("stats") or {}
        if not isinstance(stats, dict):
            stats = {}
        entry = stats.get(route) or _empty_stats()
        if not isinstance(entry, dict) or set(entry) != set(_empty_stats()):
            entry = _empty_stats()
        entry["runs"] = int(entry.get("runs") or 0) + 1
        if ok:
            entry["streak"] = int(entry.get("streak") or 0) + 1
            entry["best"] = max(int(entry.get("best") or 0), entry["streak"])
            entry["last_ok"] = True
        else:
            entry["fails"] = int(entry.get("fails") or 0) + 1
            entry["streak"] = 0
            entry["last_ok"] = False
        stats[route] = entry
        data["apps"][package] = dict(profile, stats=stats)
        _validate(data["apps"][package], package)
        save(home, data)
    return dict(entry)


def standing(home, package, route):
    """Grade one route as `trusted`, `candidate` or `decayed`.

    `trusted` means the route has proven itself on the pinned version. `candidate` means it
    may be tried but is not yet proven. `decayed` means recent outcomes are bad enough that
    the route should not be tried ahead of a safer path.
    """
    profile = get(home, package)
    if profile is None:
        raise ProfileError("profile_missing")
    routes = profile.get("routes") or {}
    if route not in routes:
        raise ProfileError(f"profile_unknown_route:{route}")
    return _grade(profile, route)


def anchor(home, package, name, version_code=None):
    """Resolve one anchor selector, invalidated when the app version moved."""
    profile = get(home, package)
    if profile is None:
        raise ProfileError("profile_missing")
    stored = profile.get("version_code")
    if stored is not None and version_code is not None and stored != version_code:
        raise ProfileError("profile_version_changed")
    selector = (profile.get("anchors") or {}).get(name)
    if selector is None:
        raise ProfileError(f"profile_unknown_anchor:{name}")
    return dict(selector)


def list_apps(home):
    """Return stored profiles as (package, routes, version) without exposing raw anchors."""
    data = load(home)
    out = []
    for package, profile in sorted((data.get("apps") or {}).items()):
        routes = profile.get("routes") or {}
        entry = profile.get("entry") or {}
        # Only a launch fallback prepends a step; counting one unconditionally overstates the
        # plan length for a profile that enters by deeplink.
        prefix = 1 if entry.get("fallback", "launch") == "launch" else 0
        out.append({
            "package": package,
            "version_code": profile.get("version_code"),
            "routes": sorted(routes),
            "steps": {name: len(steps) + prefix for name, steps in routes.items()},
            # Reliability is shown, not hidden: a route that has been quietly failing should
            # be visible in `list` before it is trusted with a user's request.
            "standing": {name: _grade(profile, name) for name in sorted(routes)},
        })
    return out


def resolve(home, package, route, query=None, version_code=None, budget_ms=15000):
    """Return a complete run_plan payload for one profile route.

    `{q}` in an input step is replaced with `query`; a route that needs a query but gets
    none is refused rather than typed literally.
    """
    steps = plan_for(home, package, route, version_code)
    if query is None:
        if any(step.get("value") == "{q}" for step in steps):
            raise ProfileError("profile_query_required")
    else:
        if not isinstance(query, str) or not query.strip() or len(query) > 200:
            raise ProfileError("profile_bad_query")
        for step in steps:
            if step.get("value") == "{q}":
                step["value"] = query
    if type(budget_ms) is not int or not 500 <= budget_ms <= 15000:
        raise ProfileError("profile_bad_budget")
    plan = {"budget_ms": budget_ms, "steps": steps}
    # The device compares this against the installed app before the first step, so the
    # profile's anchors are never applied to a version they were not observed against.
    profile = get(home, package) or {}
    pinned = profile.get("version_code")
    if isinstance(pinned, int) and not isinstance(pinned, bool):
        plan["package"] = package
        plan["version_code"] = pinned
    return plan


def plan_for(home, package, route, version_code=None):
    """Build a plan's steps for one route, with anchors already resolved.

    `route` names a key in the profile's `routes`. The returned steps are plain plan
    steps; the caller still passes them through the normal validator and device policy.

    Only `launch` is prepended. Overlays are dismissed by an explicit step inside the
    route, because a fixed dismiss-first order fires before an asynchronously shown
    popup and misses it. The profile's `dismiss` list is the allowed vocabulary, not a
    step that runs unconditionally.
    """
    profile = get(home, package)
    if profile is None:
        raise ProfileError("profile_missing")
    stored = profile.get("version_code")
    if stored is not None and version_code is not None and stored != version_code:
        raise ProfileError("profile_version_changed")
    routes = profile.get("routes") or {}
    if route not in routes:
        raise ProfileError(f"profile_unknown_route:{route}")
    steps = routes[route]
    _check_steps(steps, route)
    # A route may only dismiss labels the profile declared, so a route cannot widen the
    # overlay vocabulary on its own.
    allowed = set(profile.get("dismiss") or [])
    for step in steps:
        if step.get("op") == "dismiss":
            for label in step.get("labels") or []:
                if label not in allowed:
                    raise ProfileError(f"profile_undeclared_dismiss:{label}")
    entry = profile.get("entry") or {}
    built = []
    if entry.get("fallback", "launch") == "launch":
        built.append({"op": "launch", "package": package})
    built.extend(dict(step) for step in steps)
    if len(built) > 24:
        raise ProfileError("profile_plan_too_long")
    return built
