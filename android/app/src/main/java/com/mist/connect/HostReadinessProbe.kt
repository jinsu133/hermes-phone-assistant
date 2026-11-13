package com.mist.connect

import android.content.Context
import java.io.IOException

/**
 * Checks Hermes the way a voice request does: the host's voice execution contract and the
 * voicelink bridge actually listening. Only both together show the host as ready.
 */
internal object HostReadinessProbe {
    @Volatile private var worker: Thread? = null
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    fun run(context: Context, quiet: Boolean = false) {
        if (worker?.isAlive == true) return
        val app = context.applicationContext
        val query = CompanionUiRepository.beginReadiness(app, quiet)
        val token = app.getSharedPreferences("lc_config", Context.MODE_PRIVATE).getString("voice_token", "") ?: ""
        if (!VoiceBridgeProtocol.validToken(token)) {
            CompanionUiRepository.completeReadiness(app, query, SetupReadiness.Phase.UNPAIRED)
            return
        }
        worker = Thread({
            val phase: SetupReadiness.Phase? = try {
                if (VoiceBridgeClient.ready(token)) SetupReadiness.Phase.AVAILABLE else SetupReadiness.Phase.UNREACHABLE
            } catch (error: VoiceBridgeClient.BridgeException) {
                if (error.category == "HOST_ROUTE_CONTRACT_UNAVAILABLE") SetupReadiness.Phase.HOST_UNSUPPORTED
                else SetupReadiness.Phase.UNREACHABLE
            } catch (_: IOException) {
                SetupReadiness.Phase.UNREACHABLE
            } catch (_: org.json.JSONException) {
                SetupReadiness.Phase.HOST_UNSUPPORTED
            } catch (_: RuntimeException) {
                null // e.g. a voice request owns bridge cleanup right now: no verdict
            }
            main.post {
                if (phase != null) CompanionUiRepository.completeReadiness(app, query, phase)
                else CompanionUiRepository.cancelReadiness(app, query)
            }
        }, "host-readiness").also { it.isDaemon = true; it.start() }
    }
}
