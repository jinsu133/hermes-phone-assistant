package com.mist.connect

import android.app.Activity
import android.app.KeyguardManager
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.util.UUID

/** One immutable, memory-only action. There is deliberately no RPC approve operation. */
internal object ActionConfirmation {
    private val main = Handler(Looper.getMainLooper())
    data class Pending(val id: String, val prompt: String, val gate: ActionApprovalGate,
                       val valid: () -> Boolean, val run: () -> JSONObject,
                       val voiceRequest: String = "", val phrase: String = "", val digest: String = "",
                       @Volatile var claimed: Boolean = false,
                       @Volatile var result: JSONObject? = null)
    @Volatile private var pending: Pending? = null
    @Volatile var visible: ActionConfirmationActivity? = null
    private var lockReceiver: android.content.BroadcastReceiver? = null
    fun watchLocks(context: android.content.Context) {
        if(lockReceiver != null) return
        val receiver=object: android.content.BroadcastReceiver() {
            override fun onReceive(c: android.content.Context?, i: Intent?) { cancelAll() }
        }
        context.registerReceiver(receiver,android.content.IntentFilter(Intent.ACTION_SCREEN_OFF),android.content.Context.RECEIVER_NOT_EXPORTED)
        lockReceiver=receiver
    }
    fun stopWatching(context: android.content.Context) {
        lockReceiver?.let { context.unregisterReceiver(it) }; lockReceiver=null; cancelAll()
    }
    @Synchronized fun busy(): Boolean = pending?.result == null && pending != null
    @Synchronized fun get(id: String): Pending? = pending?.takeIf { it.id == id }
    @Synchronized fun voicePending(): Pending? = pending?.takeIf { it.voiceRequest.isNotEmpty() && it.result == null && !it.claimed }
    @Synchronized fun status(id: String): JSONObject {
        val p = get(id) ?: return JSONObject().put("dispatched",false).put("state","unknown")
        return p.result ?: JSONObject().put("dispatched",false).put("state",if(p.voiceRequest.isEmpty()) "awaiting_user_confirmation" else "awaiting_voice_confirmation").put("request_id",p.id)
    }
    @Synchronized fun cancelAll() {
        pending?.let { if (it.result == null) { it.gate.cancel(it.id); it.result=JSONObject().put("dispatched",false).put("state","cancelled") } }
        main.post { visible?.finish() }
    }
    @Synchronized fun request(prompt: String, voiceRequest: String = "", phrase: String = "", digest: String = "",
                              valid: () -> Boolean, run: () -> JSONObject): JSONObject {
        check(!busy()) { "confirmation_pending" }
        val id=UUID.randomUUID().toString()
        val ttl = if(voiceRequest.isEmpty()) 120000L else 240000L
        val p=Pending(id,prompt,ActionApprovalGate(id,SystemClock.elapsedRealtime()+ttl),valid,run,voiceRequest,phrase,digest)
        pending=p
        if (voiceRequest.isEmpty()) main.post {
            if (p !== pending || p.result != null) return@post
            try {
                val access=checkNotNull(LCAccessibilityService.instance)
                access.startActivity(Intent(access,ActionConfirmationActivity::class.java).putExtra("request_id",id)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK))
            } catch (_: Exception) { cancelAll() }
        }
        main.postDelayed({ synchronized(this) { if (pending === p && p.result == null) {
            p.gate.cancel(id); p.result=JSONObject().put("dispatched",false).put("state","expired"); visible?.finish()
        } } },ttl)
        main.postDelayed({ synchronized(this) { if(pending === p) { cancelAll(); pending=null } } },ttl+300000)
        return status(id).put("confirmation_prompt",prompt.substringBefore("\n\n기기 화면 원문:"))
            .put("voice_phrase",phrase).put("action_digest",digest)
            .put("instruction",if(voiceRequest.isNotEmpty()) "앱이 명시적 음성 허용을 기다립니다. 도구 결과를 받은 뒤 다음 단계를 계속하세요."
                else "휴대폰 확인 화면에서 승인하거나 취소해 주세요. approval_status로 결과를 조회하세요.")
    }
    fun decideVoice(p: Pending, response: JSONObject) {
        if (p.voiceRequest != VoiceRequestService.activeRequestId || !VoiceRequestService.handsfreeActive) return
        val matches=response.optString("request_id")==p.voiceRequest && response.optString("action_id")==p.id && response.optString("digest")==p.digest
        if(matches && response.optBoolean("allowed")) VoiceDiagnostics.allowed.incrementAndGet() else VoiceDiagnostics.denied.incrementAndGet()
        decideInternal(p.id,matches && response.optBoolean("allowed"),true)
    }
    fun decide(id: String, allow: Boolean) {
        if(get(id)?.voiceRequest?.isNotEmpty()==true) return
        decideInternal(id,allow,false)
    }
    @Synchronized private fun decideInternal(id: String, allow: Boolean, voice: Boolean) {
        val p=get(id) ?: return
        if (p.result != null || p.claimed) return
        p.claimed=true
        p.gate.userDecision(allow,SystemClock.elapsedRealtime())
        if (!allow) { cancelAll(); return }
        // The Activity must disappear before comparing the original app's full screen.
        main.postDelayed({
            if (pending !== p || p.result != null) return@postDelayed
            val valid=runCatching { p.valid() }.getOrDefault(false)
            val claimed=synchronized(this) {
                if (pending !== p || p.result != null) false
                else p.gate.consume(id,SystemClock.elapsedRealtime(),valid)
            }
            val result=if(claimed) {
                runCatching { p.run().also { it.put("state",if(it.optBoolean("dispatched")) "completed" else "execution_failed") } }.getOrElse {
                    JSONObject().put("dispatched",false).put("state","context_changed_or_denied")
                }
            } else JSONObject().put("dispatched",false).put("state","context_changed_or_denied")
            synchronized(this) { if (pending === p && p.result == null) { p.result=result; if(voice && result.optBoolean("dispatched")) VoiceDiagnostics.resumed.incrementAndGet() } }
        },if(voice) 0 else 750)
    }
}

class ActionConfirmationActivity : Activity() {
    private var id=""
    private var decided=false
    private val main=Handler(Looper.getMainLooper())
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        id=intent.getStringExtra("request_id") ?: ""
        val p=ActionConfirmation.get(id)
        if (p == null || p.result != null || getSystemService(KeyguardManager::class.java).isDeviceLocked) { finish(); return }
        ActionConfirmation.visible=this
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.WHITE))
        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.WHITE)
            addView(LinearLayout(this@ActionConfirmationActivity).apply {
                orientation=LinearLayout.VERTICAL; setPadding(36,110,36,110)
                addView(TextView(this@ActionConfirmationActivity).apply {
                    text=p.prompt; setTextColor(Color.BLACK); textSize=18f
                })
                fun addDecision(label: String, allow: Boolean) {
                    addView(Button(this@ActionConfirmationActivity).apply {
                        text=label; filterTouchesWhenObscured=true
                        // Accessibility ACTION_CLICK cannot manufacture this physical touch event.
                        setOnTouchListener { _, event ->
                            if (event.flags and (MotionEvent.FLAG_WINDOW_IS_OBSCURED or MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED) != 0) return@setOnTouchListener true
                            if (event.action == MotionEvent.ACTION_UP) {
                                decided=true; ActionConfirmation.decide(this@ActionConfirmationActivity.id,allow); finish()
                            }
                            true
                        }
                    })
                }
                addDecision(getString(R.string.confirm_deny),false)
                addDecision(getString(R.string.confirm_allow),true)
            })
        })
        main.postDelayed({ if (!decided) { ActionConfirmation.cancelAll(); finish() } },120000)
    }
    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        if (ActionConfirmation.visible === this) ActionConfirmation.visible=null
        if (!decided) ActionConfirmation.cancelAll()
        super.onDestroy()
    }
}
