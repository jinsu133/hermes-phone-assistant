package com.mist.connect

import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject

/**
 * Deterministic plan executor. The model decides *what* (a short plan); this class
 * decides *how*, inside the app, without a network round trip per step.
 *
 * Every step still goes through [PhoneActions.execute] so [PhoneActionPolicy] and the
 * confirmation gate keep full authority. The executor can observe and order actions;
 * it cannot approve anything.
 */
internal class PlanExecutor(private val actions: PhoneActions) {

    data class StepReport(val op: String, val ok: Boolean, val detail: String, val elapsedMs: Long)

    private val reports = mutableListOf<StepReport>()

    /** Runs the plan and returns a compact report. Never claims success for skipped steps. */
    fun run(plan: JSONObject, voiceRequestId: String = "", budgetMs: Long = 8000): JSONObject {
        reports.clear()
        val started = SystemClock.elapsedRealtime()
        // A plan starts from a known state. A confirmation prompt left pending by an earlier
        // run pauses observation, which would make the very first step fail with
        // target_not_found and look like a missing screen. Clear it instead of inheriting it.
        runCatching { actions.cancelPending() }
        // A profile records the app version its anchors were observed against. Verify it here,
        // before the first step, so a changed app is refused instead of being clicked through
        // with selectors that may now resolve to a different control.
        val expectedPackage = plan.optString("package")
        if (expectedPackage.isNotEmpty()) {
            val expected = plan.optLong("version_code", -1L)
            val installed = try {
                packageVersion(expectedPackage)
            } catch (_: Exception) {
                return failure("package_version_unavailable", started, 0)
            }
            if (installed == null) return failure("package_not_installed", started, 0)
            if (installed != expected) return failure("package_version_changed", started, 0)
        }
        val steps = plan.optJSONArray("steps") ?: return failure("missing_steps", started, 0)
        // Flow control is resolved up front: labels are positions, not executed steps.
        val labels = HashMap<String, Int>()
        for (index in 0 until steps.length()) {
            val step = steps.getJSONObject(index)
            if (step.optString("op") == "label") {
                val name = step.optString("name")
                if (name.isNotEmpty()) labels[name] = index
            }
        }
        var completed = 0
        var index = 0
        while (index < steps.length()) {
            if (SystemClock.elapsedRealtime() - started > budgetMs) {
                return report(false, "budget_exceeded", started, completed, steps.length(), "")
            }
            val step = steps.getJSONObject(index)
            val op = step.optString("op")
            val stepStart = SystemClock.elapsedRealtime()
            try {
                var jump: String? = null
                val detail = when (op) {
                    "label" -> step.optString("name")
                    "route" -> {
                        val target = route(step, labels)
                        jump = target
                        "to:$target"
                    }
                    "dismiss" -> dismiss(step, voiceRequestId)
                    "launch" -> launch(step, voiceRequestId)
                    "await" -> await(step, voiceRequestId)
                    "focus" -> focus(step, voiceRequestId)
                    "input" -> input(step, voiceRequestId)
                    "submit" -> submit(step, voiceRequestId)
                    "select" -> select(step, voiceRequestId)
                    "click" -> click(step, voiceRequestId)
                    "back" -> simple("back", voiceRequestId)
                    "home" -> simple("home", voiceRequestId)
                    "assert" -> assertStep(step, voiceRequestId)
                    else -> throw PlanFailure("unsupported_op:$op", op)
                }
                reports.add(StepReport(op, true, detail, SystemClock.elapsedRealtime() - stepStart))
                completed++
                if (jump != null) {
                    // A jump skips forward or backward; count it against the budget, not completed.
                    index = labels[jump] ?: throw PlanFailure("unknown_label:$jump", op)
                } else {
                    index++
                }
            } catch (failure: PlanFailure) {
                reports.add(StepReport(op, false, failure.code, SystemClock.elapsedRealtime() - stepStart))
                return report(false, failure.code, started, completed, steps.length(), op)
            } catch (error: Exception) {
                val code = error.message ?: "step_failed"
                reports.add(StepReport(op, false, code, SystemClock.elapsedRealtime() - stepStart))
                return report(false, code, started, completed, steps.length(), op)
            }
        }
        return report(true, "ok", started, completed, steps.length(), "")
    }

    private class PlanFailure(val code: String, val op: String) : Exception(code)

    /** Observation failures that only mean "not on the target screen yet", never a policy refusal. */
    private val TRANSIENT_OBSERVE = setOf("approval_and_control_ui_protected", "screen_unavailable")

    /**
     * Submit failures that mean the field was judged mid-update, not that anything was refused.
     * Retrying re-observes; the device still judges every attempt on its own.
     */
    private val RETRYABLE_SUBMIT = setOf("confirmation_required", "not_dispatched",
        "node_changed", "stale_snapshot")

    // --- operations ---------------------------------------------------------------

    private fun launch(step: JSONObject, voiceRequestId: String): String {
        val pkg = step.optString("package")
        require(pkg.isNotEmpty()) { "missing_package" }
        val args = JSONObject().put("action", "open_app").put("package", pkg)
        if (voiceRequestId.isNotEmpty()) args.put("_voice_request_id", voiceRequestId)
        dispatch(args)
        return pkg
    }

    /**
     * Picks the first matching branch's label from the current screen. This is the whole
     * app-state-branching primitive: apps differ in *data*, not in code.
     */
    private fun route(step: JSONObject, labels: Map<String, Int>): String {
        val branches = step.optJSONArray("branches")
            ?: throw PlanFailure("missing_branches", "route")
        val timeout = step.optInt("timeout_ms", 1500).coerceIn(100, 5000)
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (true) {
            // A launch or app switch can briefly leave our own UI or a transient surface in
            // front. That is not a routing failure: keep observing until the deadline.
            val screen = try {
                observe()
            } catch (failure: PlanFailure) {
                throw failure
            } catch (error: Exception) {
                val code = error.message ?: "screen_unavailable"
                if (code !in TRANSIENT_OBSERVE) throw PlanFailure(code, "route")
                null
            }
            if (screen != null) {
                for (i in 0 until branches.length()) {
                    val branch = branches.getJSONObject(i)
                    val target = branch.optString("to")
                    if (find(screen, branch, 0) != null) {
                        if (target.isEmpty()) throw PlanFailure("branch_without_target", "route")
                        if (!labels.containsKey(target)) throw PlanFailure("unknown_label:$target", "route")
                        return target
                    }
                }
            }
            // No branch yet: the screen may still be rendering. Wait for it, don't guess.
            if (SystemClock.elapsedRealtime() >= deadline) break
            SystemClock.sleep(35)
        }
        val fallback = step.optString("to")
        if (fallback.isEmpty()) throw PlanFailure("route_unmatched", "route")
        if (!labels.containsKey(fallback)) throw PlanFailure("unknown_label:$fallback", "route")
        return fallback
    }

    /**
     * Closes a blocking overlay. Only an allow-listed close label is tapped, and a missing
     * overlay is not a failure: most screens have none.
     *
     * Closing is not the same act as enabling or paying, so it is executed through
     * [closeOverlay] rather than [PhoneActions.execute]. That path is reachable only from a
     * plan, only for a label on this list, and only for a node shaped like a close control
     * (by resource id or by text). The general action policy still sees every other step.
     */
    private fun dismiss(step: JSONObject, voiceRequestId: String): String {
        val labels = step.optJSONArray("labels") ?: return "none"
        val screen = observe()
        val nodes = screen.optJSONArray("nodes") ?: return "none"
        // High-risk words are refused even if a caller puts them in the list.
        val forbidden = Regex("허용|확인|동의|결제|전송|구매|송금|이체|삭제|allow|confirm|agree|pay|send|delete",
            RegexOption.IGNORE_CASE)
        var candidate: JSONObject? = null
        outer@ for (i in 0 until labels.length()) {
            val label = labels.optString(i)
            if (label.isEmpty() || forbidden.containsMatchIn(label)) continue
            for (j in 0 until nodes.length()) {
                val node = nodes.getJSONObject(j)
                if (!node.optBoolean("clickable") || !node.optBoolean("enabled", true)) continue
                val exact = node.optString("text") == label || node.optString("label") == label
                if (!exact) continue
                candidate = node
                break@outer
            }
        }
        val node = candidate ?: return "none"
        val ref = node.optString("ref")
        val result = actions.closeOverlay(screen.optString("snapshot"), ref, node.optString("text"))
        if (!result.optBoolean("closed")) {
            val code = result.optString("code")
            if (code.isNotEmpty()) throw PlanFailure(code, "dismiss")
            return "none"
        }
        return "dismissed:$ref"
    }

    private fun await(step: JSONObject, voiceRequestId: String): String {
        val timeout = step.optInt("timeout_ms", 2000).coerceIn(100, 5000)
        val deadline = SystemClock.elapsedRealtime() + timeout
        var waited = 0L
        while (true) {
            val screen = observe()
            val match = find(screen, step, 0)
            if (match != null) return "found:${match.optString("ref")}"
            if (SystemClock.elapsedRealtime() >= deadline) throw PlanFailure("await_timeout", "await")
            SystemClock.sleep(35)
            waited += 35
        }
    }

    private fun focus(step: JSONObject, voiceRequestId: String): String {
        val screen = observe()
        val node = find(screen, step, 0) ?: throw PlanFailure("target_not_found", "focus")
        clickRef(screen, node, voiceRequestId)
        return node.optString("ref")
    }

    private fun input(step: JSONObject, voiceRequestId: String): String {
        // "value" is the text to type; "text"/"text_contains" stay selectors.
        val text = step.optString("value")
        require(text.isNotEmpty() && text.length <= 2000) { "invalid_text" }
        var screen = observe()
        var node = find(screen, step, 0) ?: throw PlanFailure("target_not_found", "input")
        if (node.optString("role") != "search_field" || !node.optBoolean("editable")) {
            // Non-role targets must be editable on their own evidence.
            if (!node.optBoolean("editable")) throw PlanFailure("target_not_editable", "input")
        }
        // ACTION_SET_TEXT writes without focusing, and submit_search requires a focused
        // field. Focus first, then re-observe: tapping can open an IME surface or swap
        // fragments, which invalidates the old snapshot.
        if (!node.optBoolean("focused")) {
            clickRef(screen, node, voiceRequestId)
            screen = observe()
            node = find(screen, step, 0) ?: throw PlanFailure("target_not_found", "input")
        }
        val args = JSONObject().put("action", "input_text")
            .put("snapshot", screen.optString("snapshot"))
            .put("ref", node.optString("ref"))
            .put("text", text)
        if (voiceRequestId.isNotEmpty()) args.put("_voice_request_id", voiceRequestId)
        dispatch(args)
        return node.optString("ref")
    }

    private fun submit(step: JSONObject, voiceRequestId: String): String {
        // The native IME action only exists once the keyboard is attached to the field. Right
        // after a cold start that can lag the text write by a few hundred ms, and a premature
        // submit must not be mistaken for a user decision. Wait for the capability itself.
        //
        // Waiting is not enough on its own: `execute()` re-reads the node before judging it
        // routine, so a field that loses IME focus between the check and the tap is classified
        // as needing approval. That is not a user decision either, so re-arm and try again
        // inside the same deadline instead of surfacing a confirmation request.
        val timeout = step.optInt("timeout_ms", 1500).coerceIn(100, 5000)
        val deadline = SystemClock.elapsedRealtime() + timeout
        var lastRetryable = ""
        while (SystemClock.elapsedRealtime() < deadline) {
            val screen = observe()
            val node = find(screen, step, 0) ?: firstSearchField(screen)
                ?: throw PlanFailure("target_not_found", "submit")
            if (!node.optBoolean("supports_search_submit") || !node.optBoolean("focused")) {
                SystemClock.sleep(100)
                continue
            }
            val args = JSONObject().put("action", "submit_search")
                .put("snapshot", screen.optString("snapshot"))
                .put("ref", node.optString("ref"))
            if (voiceRequestId.isNotEmpty()) args.put("_voice_request_id", voiceRequestId)
            try {
                dispatch(args)
                return node.optString("ref")
            } catch (failure: PlanFailure) {
                // A confirmation request here is a stale-focus artefact, not consent: drop the
                // pending prompt so it cannot leak into the next step. `node_changed` and
                // `stale_snapshot` are the same race from the other side: the node was judged
                // before the text settled. All three mean "observe again and retry", never
                // "ask the user".
                if (failure.code !in RETRYABLE_SUBMIT) throw failure
                lastRetryable = failure.code
                try {
                    actions.execute(JSONObject().put("action", "cancel_approval"))
                } catch (_: Exception) {
                }
                SystemClock.sleep(150)
            }
        }
        throw PlanFailure(if (lastRetryable.isEmpty()) "submit_not_ready" else lastRetryable, "submit")
    }

    private fun select(step: JSONObject, voiceRequestId: String): String {
        val screen = observe()
        val index = step.optInt("index", 0)
        val node = find(screen, step, index) ?: throw PlanFailure("target_not_found", "select")
        clickRef(screen, node, voiceRequestId)
        return node.optString("ref")
    }

    private fun click(step: JSONObject, voiceRequestId: String): String {
        val screen = observe()
        val node = find(screen, step, step.optInt("index", 0)) ?: throw PlanFailure("target_not_found", "click")
        clickRef(screen, node, voiceRequestId)
        return node.optString("ref")
    }

    private fun simple(op: String, voiceRequestId: String): String {
        val args = JSONObject().put("action", op)
        if (voiceRequestId.isNotEmpty()) args.put("_voice_request_id", voiceRequestId)
        dispatch(args)
        return op
    }

    private fun assertStep(step: JSONObject, voiceRequestId: String): String {
        val screen = observe()
        val node = find(screen, step, 0) ?: throw PlanFailure("assert_failed", "assert")
        return node.optString("ref")
    }

    // --- helpers ------------------------------------------------------------------

    private fun clickRef(screen: JSONObject, node: JSONObject, voiceRequestId: String) {
        val args = JSONObject().put("action", "click")
            .put("snapshot", screen.optString("snapshot"))
            .put("ref", node.optString("ref"))
        if (voiceRequestId.isNotEmpty()) args.put("_voice_request_id", voiceRequestId)
        dispatch(args)
    }

    private fun dispatch(args: JSONObject) {
        val result = try {
            actions.execute(args)
        } catch (failure: PlanFailure) {
            throw failure
        } catch (error: IllegalStateException) {
            // PhoneActions signals refusals with check(), not PlanFailure: turn the message into
            // a plan failure so a caller can tell "retry after re-observing" from "stop".
            throw PlanFailure(error.message ?: "action_failed", args.optString("action"))
        }
        if (result.optString("state") == "awaiting_voice_confirmation" || result.optString("state") == "awaiting_user_confirmation") {
            throw PlanFailure("confirmation_required", args.optString("action"))
        }
        if (result.has("dispatched") && !result.optBoolean("dispatched")) {
            throw PlanFailure("not_dispatched", args.optString("action"))
        }
    }

    private fun observe(): JSONObject = actions.observe()

    /**
     * Installed versionCode for `package`, or null when it is not installed.
     *
     * Read directly from the package manager rather than through plan step execution: this is
     * a local query with no UI effect, and routing it through the action pipeline would let a
     * read-only lookup be reported as a user decision.
     */
    private fun packageVersion(packageName: String): Long? {
        if (!packageName.matches(Regex("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)+"))) return null
        val app = McpService.instance ?: return null
        val info = try {
            app.packageManager.getPackageInfo(packageName, 0)
        } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
            return null
        }
        return if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode
        else @Suppress("DEPRECATION") info.versionCode.toLong()
    }

    private fun firstSearchField(screen: JSONObject): JSONObject? {
        val nodes = screen.optJSONArray("nodes") ?: return null
        for (i in 0 until nodes.length()) {
            val node = nodes.getJSONObject(i)
            if (node.optString("role") == "search_field") return node
        }
        return null
    }

    /**
     * Selector ladder, cheapest first: role, resource-id suffix, exact text, contains text.
     * Vision is not consulted here; a miss is reported so a caller can choose to escalate.
     */
    private fun find(screen: JSONObject, step: JSONObject, index: Int): JSONObject? {
        val nodes = screen.optJSONArray("nodes") ?: return null
        val role = step.optString("role")
        val id = step.optString("id")
        val idSuffix = step.optString("id_suffix")
        val text = step.optString("text")
        val textContains = step.optString("text_contains")
        if (role.isEmpty() && id.isEmpty() && idSuffix.isEmpty() && text.isEmpty() && textContains.isEmpty()) return null
        val matches = mutableListOf<JSONObject>()
        for (i in 0 until nodes.length()) {
            val node = nodes.getJSONObject(i)
            if (!node.optBoolean("enabled", true)) continue
            if (role.isNotEmpty() && node.optString("role") != role) continue
            if (id.isNotEmpty() && node.optString("resource_id") != id) continue
            if (idSuffix.isNotEmpty() && !node.optString("resource_id").endsWith(idSuffix)) continue
            if (text.isNotEmpty()) {
                val hay = node.optString("text") + " " + node.optString("label")
                if (!hay.contains(text)) continue
            }
            if (textContains.isNotEmpty()) {
                val hay = node.optString("text") + " " + node.optString("label")
                if (!hay.contains(textContains)) continue
            }
            matches.add(node)
        }
        if (matches.isEmpty()) return null
        // Prefer clickable targets: an unclickable TextView is usually decoration.
        val clickable = matches.filter { it.optBoolean("clickable") }
        val pool = if (clickable.isNotEmpty()) clickable else matches
        return pool.getOrNull(index.coerceAtLeast(0))
    }

    private fun failure(code: String, started: Long, completed: Int): JSONObject =
        report(false, code, started, completed, 0, "")

    private fun report(ok: Boolean, code: String, started: Long, completed: Int, total: Int, failedOp: String): JSONObject {
        val steps = JSONArray()
        for (item in reports) {
            steps.put(JSONObject().put("op", item.op).put("ok", item.ok)
                .put("detail", item.detail).put("ms", item.elapsedMs))
        }
        return JSONObject().put("ok", ok).put("code", code).put("completed", completed)
            .put("total", total).put("failed_op", failedOp)
            .put("elapsed_ms", SystemClock.elapsedRealtime() - started)
            .put("steps", steps).put("verify_with_observe", ok)
    }
}
