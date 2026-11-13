package com.mist.connect

import android.app.KeyguardManager
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Same-device authenticated callers only. No shell, ADB, Shizuku or arbitrary intents. */
internal class PhoneActions(private val service: McpService) {
    private data class Entry(val node: AccessibilityNodeInfo, val signature: String, val role: String = "")
    private val nodes = mutableMapOf<String, Entry>()
    private var snapshot = ""
    private var captured = 0L
    private var foreground = ""
    // A control label, not page content: long text is content and is never a routine target.
    private val GENERIC_LABEL_MAX = 60
    private val GENERIC_CLASSES = setOf("android.widget.TextView","android.widget.LinearLayout",
        "android.widget.FrameLayout","android.widget.RelativeLayout","android.view.ViewGroup",
        "android.view.View","android.widget.Button","android.widget.ImageButton","android.widget.ImageView")
    private fun access(): LCAccessibilityService {
        check(McpService.instance != null && !McpService.actionsPaused) { "control_stopped" }
        check(service.getSharedPreferences("handsfree", 0).getBoolean("control_consent", false)) { "control_consent_required" }
        check(!service.getSystemService(KeyguardManager::class.java).isDeviceLocked) { "device_locked" }
        return checkNotNull(LCAccessibilityService.instance) { "accessibility_unavailable" }
    }
    private fun signature(n: AccessibilityNodeInfo): String {
        val r = Rect(); n.getBoundsInScreen(r)
        return listOf(n.packageName, n.viewIdResourceName, n.className, n.text, n.contentDescription, r.toShortString()).joinToString("|") { it?.toString() ?: "" }
    }
    /**
     * Identity of an editable field by exactly the parts that survive typing: the keyboard
     * opening shifts bounds, so position cannot be part of a field's identity here. The
     * resource id plus package and class is what actually distinguishes one field from another.
     */
    private fun fieldIdentity(n: AccessibilityNodeInfo): String =
        listOf(n.packageName, n.viewIdResourceName, n.className).joinToString("|") { it?.toString() ?: "" }
    private fun sensitive(n: AccessibilityNodeInfo): Boolean = n.isPassword ||
        Regex("password|passwd|pin|otp|verification|card.number|cvc|cvv|비밀번호|인증번호|카드번호", RegexOption.IGNORE_CASE)
            .containsMatchIn("${n.viewIdResourceName} ${n.hintText} ${n.contentDescription}")
    private data class Meaning(val labels: String, val web: Boolean, val role: String, val complete: Boolean = true)
    private fun meaning(n: AccessibilityNodeInfo): Meaning {
        var web=n.className?.toString()?.contains("WebView")==true
        var listParent=false; var parentId=""; var depth=0
        var parent=n.parent
        while(parent != null && depth++ < 25) {
            val current=parent
            val clazz=current.className?.toString() ?: ""
            if(clazz.contains("WebView")) web=true
            if(!listParent && (clazz.endsWith("RecyclerView") || clazz.endsWith("ListView"))) {
                listParent=true; parentId=current.viewIdResourceName ?: ""
            }
            parent=current.parent; current.recycle()
        }
        if(parent != null) { web=true; parent.recycle() } // Unknown ancestry fails closed.
        val labels=linkedSetOf<String>(); val ids=linkedSetOf<String>(); var count=0; var truncated=false
        fun collect(node: AccessibilityNodeInfo, level: Int) {
            if(!node.isVisibleToUser) return
            if(level>0 && node.isClickable) return // Never attribute another button to this action.
            if(++count>32 || level>3) { truncated=true; return }
            if(sensitive(node) || node.isCheckable || (level>0 && node.isEditable)) { truncated=true; return }
            if(level>0) node.viewIdResourceName?.substringAfterLast('/')?.let { ids.add(it) }
            listOf(node.text,node.contentDescription,node.hintText).forEach { if(!it.isNullOrBlank()) labels.add(it.toString()) }
            for(i in 0 until node.childCount) {
                val child=node.getChild(i) ?: continue
                try { collect(child,level+1) } finally { child.recycle() }
            }
        }
        collect(n,0)
        val text=labels.joinToString(" ")
        val listRole=if(truncated) "" else ActionRiskPolicy.nativeListRole(n.viewIdResourceName ?: "",n.className?.toString() ?: "",
            n.isClickable,n.isCheckable,web,listParent,parentId,ids)
        val role=if(n.isEditable && ActionRiskPolicy.nativeSearch("input_text",n.viewIdResourceName ?: "",n.className?.toString() ?: "",text,n.isEditable,web,"")) "search_field" else listRole
        return Meaning(text,web,role,!truncated)
    }
    private fun observedUrl(n: AccessibilityNodeInfo): String {
        val extra=n.extras.getCharSequence("AccessibilityNodeInfo.targetUrl")?.toString()
        val spanned=n.text as? android.text.Spanned
        val spans=spanned?.getSpans(0,spanned.length,android.text.style.URLSpan::class.java)
        val raw=extra ?: spans?.singleOrNull()?.url ?: return ""
        // Expose data, not a safe-to-click assertion; never expose URI credentials.
        val uri=runCatching { java.net.URI(raw) }.getOrNull() ?: return ""
        return if(raw.length<=2048 && uri.scheme in setOf("https","http") && uri.host!=null && uri.userInfo==null) raw else ""
    }
    private fun supportsSearchSubmit(n: AccessibilityNodeInfo, m: Meaning): Boolean =
        android.os.Build.VERSION.SDK_INT>=30 && m.complete && m.role=="search_field" && n.isFocused &&
        ActionRiskPolicy.safeQuery(n.text?.toString() ?: "") &&
        n.actionList.any { it.id==AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id }
    private fun clear() { nodes.values.forEach { it.node.recycle() }; nodes.clear(); detailBindings.clear(); snapshot = "" }
    @Synchronized fun observe(): JSONObject {
        val a = access(); if (ActionConfirmation.busy()) return JSONObject().put("state","awaiting_user_confirmation"); clear()
        val root = checkNotNull(a.rootInActiveWindow) { "screen_unavailable" }
        foreground = root.packageName?.toString() ?: ""
        check(foreground.isNotEmpty() && foreground != service.packageName) { "approval_and_control_ui_protected" }
        snapshot = UUID.randomUUID().toString(); captured = SystemClock.elapsedRealtime()
        val list = JSONArray()
        var visited = 0
        var truncated = false
        fun walk(n: AccessibilityNodeInfo, depth: Int, clickableParent: String = "") {
            var nextParent=clickableParent
            if (++visited > 350 || depth > 18 || list.length() >= 100) { truncated=true; return }
            if (sensitive(n)) return
            if (n.isVisibleToUser && (n.isClickable || n.isEditable || n.isScrollable || !n.text.isNullOrEmpty() || !n.contentDescription.isNullOrEmpty())) {
                val id = "n${list.length()}"
                val r = Rect(); n.getBoundsInScreen(r)
                val m=if(n.isClickable || n.isEditable) meaning(n) else Meaning("",false,"")
                nodes[id] = Entry(AccessibilityNodeInfo.obtain(n), signature(n),m.role)
                list.put(JSONObject().put("ref", id).put("text", if (n.isEditable) "[input redacted]" else n.text?.toString()?.let { ReadableText.preview(it,180) } ?: "")
                    .put("label", n.contentDescription?.toString()?.let { ReadableText.preview(it,180) } ?: "").put("editable", n.isEditable)
                    .put("resource_id",n.viewIdResourceName ?: "").put("class",n.className?.toString() ?: "").put("clickable_parent",clickableParent)
                    .put("role",m.role).put("target_url",observedUrl(n)).put("supports_search_submit",supportsSearchSubmit(n,m))
                    .put("text_truncated",!n.isEditable && (n.text?.toString()?.let { it.codePointCount(0,it.length)>180 } ?: false)).put("label_truncated",n.contentDescription?.toString()?.let { it.codePointCount(0,it.length)>180 } ?: false)
                    .put("clickable", n.isClickable).put("scrollable", n.isScrollable).put("enabled", n.isEnabled).put("focused", n.isFocused)
                    .put("bounds", JSONArray(listOf(r.left, r.top, r.right, r.bottom))))
                if(n.isClickable) nextParent=id
            }
            for (i in 0 until n.childCount) { val child = n.getChild(i) ?: continue; try { walk(child, depth + 1,nextParent) } finally { child.recycle() } }
        }
        try { walk(root, 0) } finally { root.recycle() }
        // Capture only after complete observation; execution cannot create new evidence.
        if (!truncated) nodes.values.filter { it.node.isClickable }.forEach {
            tmapDetailDestination(it.node,meaning(it.node),capture=true)
        }
        // Read-only diagnosis stays available for explicit inspection but is NOT run on the
        // observation path: it walks the tree a second time and would corrupt latency numbers.
        return JSONObject().put("snapshot", snapshot).put("package", foreground).put("nodes", list)
            .put("truncated",truncated).put("detail_diagnosis",detailDiagnosis())
            .put("sensitive", true).put("untrusted_screen_data", true).put("expires_ms", 15000)
    }
    /** Predicate-by-predicate read of the TMAP detail adapter. Observation only, and it is
     *  deliberately NOT called from observe(): the extra tree walk would distort latency
     *  measurements. Exposed for explicit diagnosis via the debug action only. */
    internal fun detailDiagnosis(): JSONObject {
        val out=JSONObject()
        val pkg="com.skt.tmap.ku"; val prefix="$pkg:id/"
        out.put("foreground",foreground)
        out.put("detail_reason",detailReason)
        if(foreground!=pkg) return out.put("applicable",false)
        val root=access().rootInActiveWindow ?: return out.put("applicable",false)
        var count=0; var overBudget=""; var pages=0; var invalid=false
        var queryNodeClickable=false; var queryText=""; var queryCount=0
        var backCount=0; var exitCount=0; var nativeInWeb=false
        var targetFound=false
        val buttons=mutableListOf<String>(); val pageTexts=mutableListOf<String>()
        var buttonParentId="" ; var inconsistentButtonParents=false
        val ancestorChain=JSONArray(); var destinationAncestorsBlocked=""
        fun walk(node: AccessibilityNodeInfo, depth: Int, inWeb: Boolean, inPage: Boolean) {
            if(count++>=350) { if(overBudget.isEmpty()) overBudget="node_count"; return }
            if(depth>18) { if(overBudget.isEmpty()) overBudget="depth"; return }
            if(!node.isVisibleToUser) return
            if(node.packageName?.toString()!=pkg) { invalid=true; return }
            val web=inWeb || node.className?.toString()?.contains("WebView")==true
            val id=node.viewIdResourceName ?: ""
            val isPage=id=="root" && node.className?.toString()=="android.view.View" && web
            if(isPage) pages++
            val page=inPage || isPage
            if(id==prefix+"result_query_text") {
                queryCount++; queryText=node.text?.toString() ?: ""; queryNodeClickable=node.isClickable
                if(web || page) nativeInWeb=true
            }
            if(id==prefix+"result_back_button") { backCount++; if(web || page) nativeInWeb=true }
            if(id==prefix+"result_exit") { exitCount++; if(web || page) nativeInWeb=true }
            if(page) {
                if(node.isClickable) {
                    val m=meaning(node)
                    if(m.labels.isNotBlank()) {
                        buttons.add(m.labels)
                        val p=node.parent
                        val pid=try { p?.viewIdResourceName ?: "" } finally { p?.recycle() }
                        if(buttonParentId.isEmpty()) buttonParentId=pid else if(buttonParentId!=pid) inconsistentButtonParents=true
                        if(m.labels=="도착") {
                            targetFound=true
                            // The adapter rejects any clickable or linked node on the ancestor chain.
                            var a=node.parent; var d=0
                            while(a!=null && d++<18) {
                                val cur=a
                                val entry=JSONObject()
                                    .put("depth",d).put("class",cur.className?.toString() ?: "")
                                    .put("id",cur.viewIdResourceName ?: "")
                                    .put("clickable",cur.isClickable).put("linked",linked(cur))
                                ancestorChain.put(entry)
                                if(cur.isClickable || linked(cur)) {
                                    if(destinationAncestorsBlocked.isEmpty()) destinationAncestorsBlocked="${cur.className?.toString() ?: ""}|${cur.viewIdResourceName ?: ""}"
                                }
                                a=cur.parent; cur.recycle()
                            }
                        }
                    }
                } else if(!node.text.isNullOrBlank()) pageTexts.add(node.text.toString())
            }
            for(i in 0 until node.childCount) node.getChild(i)?.let { c -> try { walk(c,depth+1,web,page) } finally { c.recycle() } }
        }
        try { walk(root,0,false,false) } finally { root.recycle() }
        out.put("applicable",true)
            .put("pages",pages).put("scan_aborted",overBudget).put("foreign_node",invalid)
            .put("query_count",queryCount).put("query_text",ReadableText.preview(queryText,120))
            .put("query_node_clickable",queryNodeClickable)
            .put("query_text_in_page_count",pageTexts.count { it==queryText })
            .put("back_count",backCount).put("exit_count",exitCount).put("native_anchor_inside_web",nativeInWeb)
            .put("button_labels",JSONArray(buttons.distinct().sorted())).put("button_total",buttons.size)
            .put("destination_found",targetFound)
            .put("button_parent_id",buttonParentId).put("inconsistent_button_parents",inconsistentButtonParents)
            .put("destination_ancestor_blocked_by",destinationAncestorsBlocked)
            .put("destination_ancestors",ancestorChain)
        return out
    }
    private var screenPreview=""
    private var editableValues=emptyList<String>()
    private fun screenState(): String {
        val root=checkNotNull(access().rootInActiveWindow)
        val lines=mutableListOf<String>(); val labels=linkedSetOf<String>(); val edits=mutableListOf<String>()
        var count=0
        fun walk(n: AccessibilityNodeInfo, depth: Int) {
            check(++count <= 600 && depth <= 25) { "screen_too_large_for_confirmation" }
            if (!n.isVisibleToUser) return
            check(!sensitive(n)) { "protected_input_on_screen" }
            if(n.isEditable && !n.text.isNullOrEmpty()) edits.add(n.text.toString())
            lines.add(signature(n)); listOf(n.text,n.contentDescription).forEach { if(!it.isNullOrBlank()) labels.add(it.toString()) }
            for (i in 0 until n.childCount) { val child=n.getChild(i) ?: continue; try { walk(child,depth+1) } finally { child.recycle() } }
        }
        try { check(root.packageName?.toString() != service.packageName); walk(root,0) } finally { root.recycle() }
        screenPreview=labels.joinToString("\n"); editableValues=edits.toList()
        return lines.joinToString("\n").also { check(it.length <= 48000) { "screen_too_large_for_confirmation" } }
    }
    /** Some virtual list providers invalidate source IDs on refresh. Reacquire only
     * already-classified read/navigation rows, never approval-gated actions. Exact
     * full label/resource/class/bounds + native role and unique match are mandatory. */
    private fun refreshEverydayRow(ref: String): Boolean {
        val old=nodes[ref] ?: return false
        if(old.node.refresh() && signature(old.node)==old.signature && old.node.isVisibleToUser && old.node.isEnabled && !sensitive(old.node)) return true
        if(old.role !in setOf("conversation_row","search_suggestion","search_field")) return false
        // A search field's text is expected to change between observe and submit: the plan
        // types the query after the snapshot that located the field. Matching on a signature
        // that includes text would reject the very node we are about to submit, which then
        // falls through to a confirmation prompt no one asked for.
        val relaxed = if (old.role == "search_field") fieldIdentity(old.node) else null
        val root=access().rootInActiveWindow ?: return false
        var candidate: AccessibilityNodeInfo?=null; var count=0; var matches=0; var incomplete=false
        fun walk(n: AccessibilityNodeInfo,depth: Int) {
            if(++count>350 || depth>18) { incomplete=true; return }
            val same = signature(n)==old.signature ||
                (relaxed != null && n.isEditable && fieldIdentity(n)==relaxed)
            if(n.isVisibleToUser && n.isEnabled && (n.isClickable||n.isEditable) && !sensitive(n) && same && meaning(n).role==old.role) {
                matches++
                if(candidate==null) candidate=AccessibilityNodeInfo.obtain(n)
            }
            for(i in 0 until n.childCount) {
                val child=n.getChild(i) ?: continue
                try { walk(child,depth+1) } finally { child.recycle() }
            }
        }
        try {
            if(root.packageName?.toString()!=foreground) return false
            walk(root,0)
            if(incomplete || matches!=1) return false
            val fresh=candidate ?: return false
            nodes[ref]=Entry(fresh,old.signature,old.role);candidate=null;old.node.recycle()
            return true
        } finally { candidate?.recycle();root.recycle() }
    }
    // Per-observation selected-place evidence, never a model-supplied task/approval claim.
    // No immutable original task target reaches this actuator. This classifies routine
    // selection, not semantic compliance with the user's original destination request.
    private data class DetailBinding(val query: String, val pageData: List<List<String>>)
    private val detailBindings=mutableMapOf<String,DetailBinding>()
    /** Last measured reason the detail adapter declined; inspection only, never a gate. */
    @Volatile internal var detailReason=""
    private fun linked(node: AccessibilityNodeInfo): Boolean {
        if(node.extras.getCharSequence("AccessibilityNodeInfo.targetUrl")!=null) return true
        val text=node.text as? android.text.Spanned ?: return false
        return text.getSpans(0,text.length,android.text.style.URLSpan::class.java).isNotEmpty()
    }
    /** True when this node is the TMAP WebView page container itself. Its own clickability is
     * an artefact of the hybrid page (it is clickable on the real tree), not a navigation
     * affordance, so it is never a click target. Bound to the exact id and class. */
    private fun pageRoot(n: AccessibilityNodeInfo): Boolean =
        n.viewIdResourceName=="root" && n.className?.toString()=="android.view.View"
    /** Installed-app native query + unique matching page title + bounded exact structure.
     * All displayed non-action data (including any address) is frozen at observe and
     * compared again at dispatch. Screen strings remain data, never instructions. */
    private fun tmapDetailDestination(n: AccessibilityNodeInfo, m: Meaning, capture: Boolean = false): Boolean {
        val pkg="com.skt.tmap.ku"; val prefix="$pkg:id/"
        if(foreground!=pkg || n.packageName?.toString()!=pkg || !m.web || !m.complete ||
            m.labels!="도착" || n.className?.toString()!="android.widget.Button" ||
            !n.isClickable || !n.isEnabled || !n.isVisibleToUser || n.isEditable || n.isCheckable || sensitive(n)) return false
        // No executable link or nested independent action can borrow the button's label.
        if(n.extras.getCharSequence("AccessibilityNodeInfo.targetUrl")!=null) return false
        val spanned=n.text as? android.text.Spanned
        if(spanned!=null && spanned.getSpans(0,spanned.length,android.text.style.URLSpan::class.java).isNotEmpty()) return false
        // 전화하기 is observed on some detail pages and absent on others; the rest are invariant.
        val required=setOf("출발","경유","도착","도보 길안내","저장","공유","닫기")
        val optional=setOf("전화하기")
        val buttons=mutableMapOf<String,Int>(); val native=mutableMapOf<String,Int>()
        var count=0; var invalid=false; var pages=0; var targetMatches=0; var clickableData=0; var titleMatches=0
        var query=""; val pageText=mutableListOf<String>(); val pageData=mutableListOf<List<String>>()
        val rosterGroups=mutableListOf<List<String>>()
        var diagReason=""
        val root=access().rootInActiveWindow ?: return false
        val buttonParent=n.parent ?: run { root.recycle(); return false }
        var ancestor=n.parent; var ancestry=0; var clickableAncestors=0; var clickableAncestorIsPageRoot=true
        while(ancestor!=null) {
            val current=ancestor
            // The hybrid page wraps the callout in a container that carries a targetUrl extra
            // but has no execution ability of its own (not clickable/editable/checkable, no text).
            // Only that one exactly identified container is exempt from the link test; the
            // control being clicked is still verified link-free by node identity.
            val calloutContainer=current.viewIdResourceName==prefix+"web_callout" &&
                !current.isClickable && !current.isEditable && !current.isCheckable
            if(++ancestry>18 || (linked(current) && !calloutContainer) ||
                PhoneActionPolicy.riskyNavigationContext(listOf(current.viewIdResourceName,current.text,current.contentDescription,current.hintText).joinToString(" "))) {
                invalid=true
                if(diagReason.isEmpty()) diagReason="ancestor:"+ancestry+":"+(current.text?.toString()?.take(40) ?: "")+":"+(current.viewIdResourceName ?: "")
            }
            // The hybrid page container is itself clickable on the real tree (observed). That
            // single, exactly identified root is not an independent action; any other clickable
            // ancestor, or a second one, still invalidates the evidence.
            if(current.isClickable) {
                clickableAncestors++
                if(!pageRoot(current)) clickableAncestorIsPageRoot=false
            }
            ancestor=current.parent;current.recycle()
            if(ancestry>18) { ancestor?.recycle();break }
        }
        fun walk(node: AccessibilityNodeInfo, depth: Int, inWeb: Boolean, inPage: Boolean) {
            if(invalid) return
            if(++count>350 || depth>18) { invalid=true; return }
            if(!node.isVisibleToUser) return
            if(node.packageName?.toString()!=pkg || sensitive(node)) { invalid=true; return }
            val web=inWeb || node.className?.toString()?.contains("WebView")==true
            val id=node.viewIdResourceName ?: ""
            val isPage=id=="root" && node.className?.toString()=="android.view.View" && web
            if(isPage) pages++
            val page=inPage || isPage
            // The page container is exempt from the clickable-ancestor rule, not from content
            // vetting: its own announced labels must still be benign and link-free.
            if(isPage && (linked(node) || node.extras.getCharSequence("AccessibilityNodeInfo.targetUrl")!=null ||
                listOf(node.text?.toString() ?: "", node.contentDescription?.toString() ?: "",
                    node.hintText?.toString() ?: "").any { it.isNotBlank() &&
                    (!ActionRiskPolicy.safeQuery(it) || PhoneActionPolicy.riskyNavigationContext(it)) })) { invalid=true; return }
            if(id in setOf(prefix+"result_query_text",prefix+"result_back_button",prefix+"result_exit")) {
                if(web || page || !node.isEnabled || node.isEditable || node.isCheckable) { invalid=true; return }
                native[id]=(native[id] ?: 0)+1
                if(linked(node)) { invalid=true; return }
                if(id==prefix+"result_query_text") {
                    query=node.text?.toString() ?: ""
                    // The real detail header is itself tappable (observed), so clickability alone
                    // is not evidence of redirection here. The anchor must still be a bounded,
                    // safe, enabled, non-link native node; a link or unsafe text still fails closed.
                    if(query.isBlank() || query.length>256 || !ActionRiskPolicy.safeQuery(query)) { invalid=true; return }
                }
            }
            if(page && !isPage) {
                if(node.isEditable || node.isCheckable) { invalid=true; return }
                val text=node.text?.toString() ?: ""
                val cls=node.className?.toString() ?: ""
                val shown=listOf(text,node.contentDescription?.toString() ?: "")
                    .filter{it.isNotBlank()}.joinToString(" ")
                val explorerLink=linked(node) || node.extras.getCharSequence("AccessibilityNodeInfo.targetUrl")!=null
                // Only a Button whose complete label is exactly one roster entry is executable.
                // This check comes first because a roster label (e.g. 공유) is itself a risky word.
                val rosterSays=if(node.isClickable && cls=="android.widget.Button" && shown.isNotBlank())
                    meaning(node) else null
                val roster=rosterSays?.labels?.takeIf{ rosterSays.complete && it in (required + optional) }
                if(roster!=null && !explorerLink) {
                    if(!node.isEnabled) { invalid=true; return }
                    // The roster group is bound by a shared ancestry, not by one immediate parent.
                    // At most ONE extra wrapper is tolerated (observed on the real tree), and it
                    // must be an inert container: clickable/editable children would make it an
                    // independent action, which is exactly what a nested-button attack needs.
                    val chain=linkedSetOf<String>()
                    var up=node.parent; var hop=0
                    while(up!=null && hop++<3) {
                        val cur=up
                        // A wrapper that is an executable roster control INSIDE the target is an
                        // independent action borrowing this label: always reject.
                        val isOwnRoster=cur.isClickable && meaning(cur).labels in (required + optional)
                        if(isOwnRoster) { invalid=true; return }
                        if(cur.isClickable && !pageRoot(cur)) { invalid=true; return }
                        if(cur.isEditable || cur.isCheckable || linked(cur)) { invalid=true; return }
                        chain.add(signature(cur))
                        up=try { cur.parent } finally { cur.recycle() }
                    }
                    if(chain.size<2) { invalid=true; return }
                    rosterGroups.add(listOf(roster,chain.joinToString("\u0001")))
                    buttons[roster]=(buttons[roster] ?: 0)+1
                } else {
                    // Everything else on the page is inert data: recorded as evidence, never a
                    // permission. A link, or a risky label on anything clickable, fails closed.
                    if(explorerLink) { invalid=true; return }
                    if(node.isClickable && shown.isNotBlank() &&
                        (!ActionRiskPolicy.safeQuery(shown) || PhoneActionPolicy.riskyNavigationContext(shown))) {
                        invalid=true; return
                    }
                    if(node.isClickable && ++clickableData>8) { invalid=true; return }
                    if(text.isNotBlank()) pageText.add(text)
                    if(text.length>2048 || (node.contentDescription?.length ?: 0)>2048) { invalid=true; return }
                    val bounds=Rect();node.getBoundsInScreen(bounds)
                    pageData.add(listOf(node.viewIdResourceName ?: "",cls,text,
                        node.contentDescription?.toString() ?: "",node.hintText?.toString() ?: "",bounds.toShortString()))
                }
                // The page title lives on a clickable node on the real tree. Any non-clickable
                // duplicate of the query is ambiguous evidence and fails closed.
                if(text==query) {
                    if(node.isClickable) titleMatches++ else { invalid=true; return }
                }
                // Accessibility identity, not merely a matching label/signature elsewhere.
                if(node==n && signature(node)==signature(n)) targetMatches++
            }
            for(i in 0 until node.childCount) {
                val child=node.getChild(i)
                if(child==null) { invalid=true; return }
                try { walk(child,depth+1,web,page) } finally { child.recycle() }
            }
        }
        try { walk(root,0,false,false) } finally { buttonParent.recycle(); root.recycle() }
        // Exactly one clickable ancestor is tolerated, and only when it is the page container.
        if(clickableAncestors>1 || (clickableAncestors==1 && !clickableAncestorIsPageRoot)) invalid=true
        // Every roster control must share a common ancestor within a few hops. A single control
        // placed in a foreign subtree would leave the intersection empty.
        if(rosterGroups.size>1) {
            val common=rosterGroups.map{it[1].split("\u0001").toSet()}
                .reduce{a,b->a intersect b}
            if(common.isEmpty()) invalid=true
        }
        if(invalid || pages!=1 || query.isBlank() || titleMatches!=1 || targetMatches!=1 ||
            !required.all { buttons[it]==1 } ||
            optional.any { (buttons[it] ?: 0)>1 } ||
            !listOf("result_query_text","result_back_button","result_exit").all { native[prefix+it]==1 }) {
            detailReason="$diagReason pages=$pages title=$titleMatches target=$targetMatches buttons=$buttons native=$native"
            return false
        }
        val binding=DetailBinding(query,pageData.toList())
        val key=signature(n)
        if(capture) detailBindings[key]=binding
        return detailBindings[key]==binding
    }
    /** Observed native TMAP controls only; WebView details use the separate bounded adapter.
     * Evidence is reacquired from the active tree on every classification, including dispatch.
     * Resource IDs are an app adapter, not a sandbox against a malicious accessibility provider. */
    private fun nativeTmapNavigation(n: AccessibilityNodeInfo, m: Meaning): Boolean {
        val pkg="com.skt.tmap.ku"; val prefix="$pkg:id/"
        if(foreground!=pkg || n.packageName?.toString()!=pkg || m.web || !m.complete ||
            !n.isClickable || !n.isEnabled || !n.isVisibleToUser || n.isEditable || n.isCheckable) return false
        val id=n.viewIdResourceName ?: return false
        if(id==prefix+"suggest_text") return n.className?.toString() in setOf("android.widget.TextView","android.widget.Button") &&
            m.labels.isNotBlank() && ActionRiskPolicy.safeQuery(m.labels)
        if(id !in setOf(prefix+"route_preview_drive_button",prefix+"preview_summary_item")) return false
        // The class allowance is bound per id. The real start control is a ViewGroup; the summary
        // row keeps its original, narrower set so the unverified row path cannot widen with it.
        val allowed=if(id==prefix+"route_preview_drive_button")
            setOf("android.widget.Button","android.widget.LinearLayout","android.widget.FrameLayout",
                  "android.widget.RelativeLayout","android.view.ViewGroup")
        else setOf("android.widget.Button","android.widget.LinearLayout","android.widget.FrameLayout","android.widget.RelativeLayout")
        if(n.className?.toString() !in allowed) return false
        val root=access().rootInActiveWindow ?: return false
        var count=0; var invalid=false
        val counts=mutableMapOf<String,Int>()
        var headerText=false; var routeSummary=false; var driveText=false; var routeOption=false; var routeDistance=false
        fun walk(node: AccessibilityNodeInfo, depth: Int, ancestors: Set<String>) {
            if(++count>350 || depth>18) { invalid=true; return }
            if(!node.isVisibleToUser) return
            if(node.packageName?.toString()!=pkg || node.className?.toString()?.contains("WebView")==true || sensitive(node)) { invalid=true; return }
            val key=node.viewIdResourceName ?: ""
            if(key.startsWith(prefix)) {
                counts[key]=(counts[key] ?: 0)+1
            }
            if(prefix+"route_preview_header_points_list" in ancestors && !node.text.isNullOrBlank() && !node.isEditable) headerText=true
            if(key==prefix+"preview_summary_item" && prefix+"route_preview_list" in ancestors) routeSummary=true
            if(prefix+"route_preview_list" in ancestors && prefix+"preview_summary_item" in ancestors && !node.isClickable && !node.isEditable && !node.isCheckable) {
                if(key==prefix+"tmap_route_option_name_text" && node.text?.toString()=="티맵추천") routeOption=true
                if(key==prefix+"tmap_route_distance_text" && node.text?.toString()?.matches(Regex("[0-9.,]+ *(?:km|m)"))==true) routeDistance=true
            }
            if(key==prefix+"route_preview_drive_text" && prefix+"route_preview_drive_button" in ancestors && node.text?.toString()=="안내시작") driveText=true
            for(i in 0 until node.childCount) {
                val child=node.getChild(i)
                if(child==null) { invalid=true; continue }
                try { walk(child,depth+1,ancestors+key) } finally { child.recycle() }
            }
        }
        try { walk(root,0,emptySet()) } finally { root.recycle() }
        if(invalid || !headerText || !routeSummary || !driveText || !routeOption || !routeDistance ||
            counts[prefix+"route_preview_header_points_list"]!=1 || counts[prefix+"route_preview_list"]!=1 ||
            counts[prefix+"route_preview_drive_button"]!=1) return false
        if(id==prefix+"route_preview_drive_button") return driveButtonShape(n, m)
        val parent=n.parent ?: return false
        val inList=try { parent.viewIdResourceName==prefix+"route_preview_list" } finally { parent.recycle() }
        var option=false; var distance=false
        for(i in 0 until n.childCount) {
            val child=n.getChild(i) ?: return false
            try {
                if(child.packageName?.toString()!=pkg || child.isClickable || child.isEditable || child.isCheckable || sensitive(child)) return false
                if(child.viewIdResourceName==prefix+"tmap_route_option_name_text" && child.text?.toString()=="티맵추천") option=true
                if(child.viewIdResourceName==prefix+"tmap_route_distance_text" && child.text?.toString()?.matches(Regex("[0-9.,]+\\s*(?:km|m)"))==true) distance=true
            } finally { child.recycle() }
        }
        return inList && option && distance
    }
    /** Shape of the start-navigation control, bound to its exact resource id.
     * The real control carries a count child, so its combined label is not a single token;
     * the label alone therefore cannot prove the button means "start". This inspects the
     * children directly: exactly one start caption, any number of numeric count children,
     * nothing else, and no nested independent action. Observation is bounded. */
    private fun driveButtonShape(n: AccessibilityNodeInfo, m: Meaning): Boolean {
        val prefix="com.skt.tmap.ku:id/"
        if(n.contentDescription!=null && n.contentDescription.toString()!="안내시작") return false
        var startLabels=0; var countLabels=0; var other=0; var tokens=0
        for(i in 0 until n.childCount) {
            val child=n.getChild(i) ?: return false
            try {
                if(child.isClickable || child.isEditable || child.isCheckable || linked(child) || sensitive(child)) return false
                val id=child.viewIdResourceName
                val text=child.text?.toString() ?: ""
                when(id) {
                    prefix+"route_preview_drive_text" -> {
                        if(text!="안내시작") return false
                        startLabels++
                    }
                    prefix+"route_preview_count_text" -> {
                        if(!text.matches(Regex("[0-9]{1,3}"))) return false
                        countLabels++
                    }
                    else -> other++
                }
            } finally { child.recycle() }
        }
        if(startLabels!=1 || other!=0) return false
        // Second, independent check on the combined label: one start token, the rest numbers.
        for(token in m.labels.split(" ")) {
            if(token.isEmpty()) continue
            if(token=="안내시작") startLabels-- else if(token.matches(Regex("[0-9]{1,3}"))) tokens++ else return false
        }
        return startLabels==0 && tokens==countLabels
    }
    /** App-independent browsing control: labelled, clickable, not an input/toggle, not a web
     * page container, no risk wording, and any link it carries is a plain web address. */
    private fun genericControl(n: AccessibilityNodeInfo, m: Meaning): Boolean {
        if(n.isEditable || n.isCheckable || !n.isClickable || !m.complete || pageRoot(n)) return false
        val label=m.labels.trim()
        if(label.isEmpty() || label.length>GENERIC_LABEL_MAX) return false
        if(!ActionRiskPolicy.safeQuery(label) || PhoneActionPolicy.riskyNavigationContext(label)) return false
        if(linked(n) && observedUrl(n).isEmpty()) return false // an app or intent link is not browsing
        return n.className?.toString() in GENERIC_CLASSES
    }
    private fun everyday(args: JSONObject): Boolean {
        val op = args.getString("action")
        if (op !in setOf("click", "input_text", "submit_search", "scroll_forward", "scroll_backward")) return false
        if (args.optString("snapshot") != snapshot || SystemClock.elapsedRealtime() - captured > 15000) return false
        val ref=args.optString("ref")
        if(!refreshEverydayRow(ref)) return false
        val entry = nodes[ref] ?: return false
        val n = entry.node
        if (sensitive(n)) return false
        // Do not approve a label then execute an unchecked clickable ancestor.
        if (op == "click" && !n.isClickable) return false
        val m=meaning(n)
        if(!m.complete) return false
        val kind=args.optJSONObject("confirmation")?.optString("kind") ?: ""
        if(kind.isNotEmpty() && kind!="general") return false
        if(op== "submit_search") return supportsSearchSubmit(n,m)
        if(op=="click" && m.role=="conversation_row") return true
        if(op=="click" && m.role=="search_suggestion" && ActionRiskPolicy.safeQuery(m.labels)) return true
        if(ActionRiskPolicy.nativeSearch(op,n.viewIdResourceName ?: "",n.className?.toString() ?: "",m.labels,n.isEditable,m.web,args.optString("text"))) return true
        if(op=="input_text" && n.isEditable && !m.web && n.className?.toString()?.endsWith("EditText")==true &&
            foreground in setOf("com.kakao.talk","com.google.android.apps.messaging","com.samsung.android.messaging") &&
            n.viewIdResourceName in setOf("$foreground:id/message_edit_text","$foreground:id/compose_message_text","$foreground:id/embedded_text_editor")) return true
        if(op=="click" && (nativeTmapNavigation(n,m) || tmapDetailDestination(n,m))) return true
        // Ordinary navigation is judged by structure, in every app and in both native and web
        // content (many apps draw their screens in a WebView, which still exposes its buttons
        // as accessibility nodes): any labelled, non-effect control is browsing. Only the
        // classified final effects (message send, call, payment) and risk wording are approved
        // or refused, by PhoneActionPolicy.
        if(op=="click" && genericControl(n,m)) return true
        // Unknown destination/route labels are not navigation authority.
        return op in setOf("scroll_forward","scroll_backward") && n.isScrollable && !m.web
    }
    private fun decision(args: JSONObject): PhoneActionPolicy.Decision {
        val op=args.getString("action")
        // Routine classification may refresh/reacquire: inspect that exact refreshed node afterward.
        val structuralSafe=everyday(args)
        val n=nodes[args.optString("ref")]?.node
        if(op in setOf("click","input_text","submit_search","scroll_forward","scroll_backward") &&
            (n==null || n.packageName?.toString()!=foreground)) return PhoneActionPolicy.Decision.DENY
        val m=n?.let { meaning(it) } ?: Meaning("",false,"")
        // A node we cannot click is not a pending approval; it is an unsupported target.
        if(op=="click" && n!=null && (!n.isClickable || !n.isEnabled)) return PhoneActionPolicy.Decision.DENY
        // Text that an app could execute as a URI is never a routine input.
        if(op=="input_text" && !ActionRiskPolicy.safeQuery(args.optString("text"))) return PhoneActionPolicy.Decision.DENY
        // No app-specific gates here: every app gets the same structural classification.
        return PhoneActionPolicy.decide(op,foreground,n?.viewIdResourceName ?: "",n?.className?.toString() ?: "",m.labels,
            n?.isEditable ?: false,n?.isCheckable ?: false,m.web,(n?.let { sensitive(it) } ?: false) || !m.complete,
            structuralSafe,args.optJSONObject("confirmation")?.optString("kind") ?: "")
    }
    /**
     * Plan-only overlay close. Deliberately narrow: the caller has already matched an exact
     * allow-listed close label, and this re-verifies the node from the live tree before tapping.
     * Anything that is not visibly a close control (by resource id or by exact text) is refused,
     * as is any risk wording, so this can never become a generic click bypass.
     */
    @Synchronized fun closeOverlay(snapshotId: String, ref: String, label: String): JSONObject {
        check(ActionConfirmation.busy().not()) { "confirmation_pending" }
        access()
        if (snapshotId != snapshot || SystemClock.elapsedRealtime() - captured > 15000)
            return JSONObject().put("closed", false).put("code", "stale_snapshot")
        val entry = nodes[ref] ?: return JSONObject().put("closed", false).put("code", "unknown_ref")
        val n = entry.node
        if (!(n.refresh() && signature(n) == entry.signature)) return JSONObject().put("closed", false).put("code", "node_changed")
        if (n.packageName?.toString() != foreground) return JSONObject().put("closed", false).put("code", "foreground_changed")
        if (!n.isClickable || !n.isEnabled || !n.isVisibleToUser) return JSONObject().put("closed", false).put("code", "target_not_clickable")
        if (sensitive(n)) return JSONObject().put("closed", false).put("code", "protected_input_on_screen")
        val text = (n.text?.toString() ?: "").trim()
        val close = Regex("닫기|오늘은 그만보기|나중에|건너뛰기|다시 보지 않기|허용 안 함|cancel|close|skip|not now|later",
            RegexOption.IGNORE_CASE)
        val forbidden = Regex("허용|확인|동의|결제|전송|구매|송금|이체|삭제|allow|confirm|agree|pay|send|delete",
            RegexOption.IGNORE_CASE)
        val id = n.viewIdResourceName ?: ""
        val idSaysClose = Regex("(^|[/_])(close|dismiss|cancel|btn_close|dialog_close|notice_dialog_close|skip|later)(_|$)",
            RegexOption.IGNORE_CASE).containsMatchIn(id)
        if (text.isEmpty() || text != label) return JSONObject().put("closed", false).put("code", "node_changed")
        if (forbidden.containsMatchIn(text)) return JSONObject().put("closed", false).put("code", "unsupported_ui")
        if (!idSaysClose && !close.matches(text)) return JSONObject().put("closed", false).put("code", "unsupported_ui")
        val ok = n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        clear()
        return JSONObject().put("closed", ok).put("dispatched", ok).put("verify_with_observe", true)
    }
    /**
     * Drop any pending confirmation without deciding it. A plan that starts while an earlier
     * prompt is still open cannot observe the screen at all, so it would fail on its first
     * step; clearing the stale prompt is what makes a retry start from a known state.
     */
    @Synchronized fun cancelPending() { ActionConfirmation.cancelAll(); clear() }

    @Synchronized fun execute(args: JSONObject): JSONObject {
        val op=args.getString("action")
        if (op == "approval_status") { require(args.length()==2); return ActionConfirmation.status(args.getString("request_id")) }
        if (op == "cancel_approval") { require(args.length()==1); ActionConfirmation.cancelAll(); return JSONObject().put("dispatched",false).put("state","cancelled") }
        check(!ActionConfirmation.busy()) { "confirmation_pending" }
        val voiceRequest=args.optString("_voice_request_id")
        val clean=JSONObject(args.toString()).apply { remove("_voice_request_id") }
        if(VoiceRequestService.activeRequestId.isNotEmpty() || voiceRequest.isNotEmpty())
            check(voiceRequest.isNotEmpty() && VoiceRequestService.handsfreeActive && VoiceRequestService.activeRequestId==voiceRequest) { "voice_owner_required" }
        return dispatch(clean,false,voiceRequest)
    }
    @Synchronized private fun dispatch(args: JSONObject, approved: Boolean, voiceRequest: String = ""): JSONObject {
        val a = access()
        val op = args.getString("action")
        val required=when(op) {
            "list_apps" -> setOf("action")
            "open_app" -> setOf("action","package")
            "package_version" -> setOf("action","package")
            "home","back","recents","notifications" -> setOf("action")
            "click","read_node","submit_search","scroll_forward","scroll_backward" -> setOf("action","snapshot","ref")
            "input_text" -> setOf("action","snapshot","ref","text")
            else -> error("unsupported_action")
        }
        val optional=if(op=="list_apps") setOf("query") else if(op=="read_node") setOf("offset") else if(op !in setOf("open_app","list_apps","package_version")) setOf("confirmation") else emptySet()
        val keys=args.keys().asSequence().toSet()
        require(keys.containsAll(required) && keys.all { it in required || it in optional })
        if(op=="submit_search") check(decision(args)!=PhoneActionPolicy.Decision.DENY) { "unsupported_ui" }
        val classification=decision(args)
        check(classification!=PhoneActionPolicy.Decision.DENY) { "unsupported_ui" }
        if (!approved && classification!=PhoneActionPolicy.Decision.ROUTINE) {
            if(voiceRequest.isNotEmpty()) check(VoiceRequestService.handsfreeActive) { "voice_owner_required" }
            if (op in setOf("click","input_text","scroll_forward","scroll_backward")) {
                check(args.getString("snapshot")==snapshot && SystemClock.elapsedRealtime()-captured<=15000) { "stale_snapshot" }
                val old=checkNotNull(nodes[args.getString("ref")])
                check(old.node.refresh() && signature(old.node)==old.signature && !sensitive(old.node)) { "node_changed" }
            }
            // submit_search is exempt on purpose: the plan typed into the field after the
            // snapshot that located it, so its text (and, once the keyboard opens, its bounds)
            // legitimately differ from the recorded signature. The field is re-identified by
            // refreshEverydayRow, and the verdict below still re-checks the live node.
            val before=screenState()
            val details=JSONObject((args.optJSONObject("confirmation") ?: JSONObject()).toString())
            details.put("kind",classification.name.lowercase())
            val n=nodes[args.optString("ref")]?.node
            val target=if(n == null) op else "${n.text ?: ""} ${n.contentDescription ?: ""}"
            require(details.keys().asSequence().all { it in setOf("kind","recipient","content","merchant","amount","method") && details.get(it) is String })
            if (op=="click" && (details.optString("kind")=="message" || Regex("보내|전송|send",RegexOption.IGNORE_CASE).containsMatchIn(target))) {
                check(details.optString("content").isNotBlank() && editableValues.contains(details.getString("content"))) { "complete_message_body_required" }
            }
            if(voiceRequest.isNotEmpty() && Regex("송금|이체|주식.*(매수|매도)|권한.*허용|보안.*변경",RegexOption.IGNORE_CASE).containsMatchIn(target)) error("user_direct_action_required")
            if(op=="click") check(n?.isClickable==true) { "target_not_clickable" }
            val kind=classification.name.lowercase()
            val phrase=VoiceConfirmationPolicy.phrase(kind)
            val body=ConfirmationPolicy.body(op,target,before,details.keys().asSequence().associateWith { details.getString(it) },args.optString("text"))
            val prompt=if(voiceRequest.isNotEmpty()) body + "\n" + ConfirmationPolicy.sayPhrase(phrase)
                else body + "\n\n" + ConfirmationPolicy.onceNotice() + "\n\n" + ConfirmationPolicy.screenHeader() + "\n" + screenPreview
            val digest=java.security.MessageDigest.getInstance("SHA-256").digest((args.toString()+"\n"+before).toByteArray()).joinToString("") { "%02x".format(it) }
            val copy=JSONObject(args.toString())
            val originalPackage=a.rootInActiveWindow?.packageName?.toString()
            fun unchanged(): Boolean = if (op in setOf("home","recents","notifications"))
                access().rootInActiveWindow?.packageName?.toString()==originalPackage
                else screenState()==before
            fun ownerValid() = voiceRequest.isEmpty() || (VoiceRequestService.handsfreeActive && VoiceRequestService.activeRequestId==voiceRequest)
            return ActionConfirmation.request(prompt,voiceRequest,phrase,digest,
                { ownerValid() && McpService.instance === service && !McpService.actionsPaused && runCatching { unchanged() }.getOrDefault(false) },
                { check(ownerValid() && unchanged()); dispatch(copy,true,voiceRequest) })
        }
        if (op == "list_apps") {
            val query = args.optString("query").lowercase()
            val pm = service.packageManager
            val result = JSONArray()
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                .distinctBy { it.activityInfo.packageName }.sortedBy { it.loadLabel(pm).toString() }.forEach {
                    val label = it.loadLabel(pm).toString(); val pkg = it.activityInfo.packageName
                    if (result.length() < 80 && (query.isEmpty() || "$label $pkg".lowercase().contains(query)))
                        result.put(JSONObject().put("label", label).put("package", pkg))
                }
            return JSONObject().put("apps", result)
        }
        if (op == "open_app") {
            val pkg = args.getString("package")
            require(pkg.matches(Regex("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)+")))
            val launch = checkNotNull(service.packageManager.getLaunchIntentForPackage(pkg)) { "app_not_launchable" }
            clear(); a.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return JSONObject().put("dispatched", true).put("verify_with_observe", true)
        }
        if (op == "package_version") {
            // Read-only lookup of an installed app's versionCode. A profile pins the version it
            // was recorded against; comparing here means a stale profile is refused before any
            // UI is touched, instead of acting on anchors that no longer match the app.
            val pkg = args.getString("package")
            require(pkg.matches(Regex("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)+")))
            val info = try {
                service.packageManager.getPackageInfo(pkg, 0)
            } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
                return JSONObject().put("installed", false)
            }
            val code = if (android.os.Build.VERSION.SDK_INT >= 28)
                info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
            return JSONObject().put("installed", true).put("version_code", code)
        }
        if (op in setOf("home", "back", "recents", "notifications")) {
            clear(); return JSONObject().put("dispatched", a.pressKey(op)).put("verify_with_observe", true)
        }
        check(args.getString("snapshot") == snapshot && SystemClock.elapsedRealtime() - captured <= (if (approved) 240000 else 15000)) { "stale_snapshot" }
        check(a.rootInActiveWindow?.packageName?.toString() == foreground) { "foreground_changed" }
        val ref=args.getString("ref")
        val original=checkNotNull(nodes[ref]) { "unknown_ref" }
        val refreshed=if(!approved && op=="click" && original.role in setOf("conversation_row","search_suggestion")) refreshEverydayRow(ref)
            else original.node.refresh() && signature(original.node)==original.signature
        val entry=checkNotNull(nodes[ref]);val n=entry.node
        check(refreshed && n.isVisibleToUser && n.isEnabled && !sensitive(n)) { "node_changed" }
        if(op=="read_node") {
            check(!n.isEditable) { "protected_input_on_screen" }
            val offset=args.optInt("offset",0)
            val content=listOfNotNull(n.text?.toString(),n.contentDescription?.toString()).distinct().joinToString("\n")
            val page=ReadableText.page(content,offset)
            access()
            return JSONObject().put("content",page.content).put("offset",page.offset)
                .put("next_offset",page.nextOffset).put("has_more",page.hasMore()).put("total_chars",page.totalChars)
                .put("offset_unit","unicode_code_points")
                .put("untrusted_screen_data",true)
        }
        // Reclassify the exact refreshed node at the effect edge. No safe child/unsafe ancestor traversal.
        check(decision(args)==classification) { "unsupported_ui" }
        if(voiceRequest.isNotEmpty()) check(VoiceRequestService.handsfreeActive && VoiceRequestService.activeRequestId==voiceRequest) { "voice_owner_required" }
        val ok = when (op) {
            "click" -> { check(n.isClickable) { "target_not_clickable" }; n.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
            "input_text" -> { require(n.isEditable); val text = args.getString("text"); require(text.length <= 2000)
                n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }) }
            "submit_search" -> { check(supportsSearchSubmit(n,meaning(n))) { "unsupported_ui" }; n.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id) }
            "scroll_forward" -> n.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            "scroll_backward" -> n.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
            else -> error("unsupported_action")
        }
        clear()
        return JSONObject().put("dispatched", ok).put("verify_with_observe", true)
    }
}
