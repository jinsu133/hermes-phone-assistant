package com.mist.connect

/** A read-only observation, not a test of audio capture, phone actions, or overlay rendering. */
data class PermissionSnapshot(
    val microphoneGranted: Boolean?,
    val notificationRuntimeRequired: Boolean,
    val notificationGranted: Boolean?,
    val notificationsEnabled: Boolean?,
    val accessibilityAllowed: Boolean?,
    val accessibilityInstancePresent: Boolean?,
    val overlayAllowed: Boolean?,
    val overlayOptIn: Boolean?,
    val handsfreeOptIn: Boolean?,
    val controlConsent: Boolean?,
    val mcpInstancePresent: Boolean?
)

enum class PermissionSignal {
    NOT_OBSERVED, NOT_APPLICABLE, DENIED, GRANTED, OFF, ON,
    ALLOWED_DISCONNECTED, INSTANCE_OBSERVED, STOPPED
}

/** Each field describes only the evidence named by that field. There is no aggregate success. */
data class PermissionAssessment(
    val microphone: PermissionSignal,
    val notificationRuntime: PermissionSignal,
    val notificationDelivery: PermissionSignal,
    val accessibilityPermission: PermissionSignal,
    val accessibilityConnection: PermissionSignal,
    val overlayPermission: PermissionSignal,
    val overlayOptIn: PermissionSignal,
    val handsfreeOptIn: PermissionSignal,
    val controlConsent: PermissionSignal,
    val mcpConnection: PermissionSignal
) {
    companion object {
        fun from(snapshot: PermissionSnapshot): PermissionAssessment = PermissionAssessment(
            microphone = grant(snapshot.microphoneGranted),
            notificationRuntime = if (snapshot.notificationRuntimeRequired) {
                grant(snapshot.notificationGranted)
            } else PermissionSignal.NOT_APPLICABLE,
            notificationDelivery = toggle(snapshot.notificationsEnabled),
            accessibilityPermission = grant(snapshot.accessibilityAllowed),
            accessibilityConnection = when {
                snapshot.accessibilityInstancePresent == true -> PermissionSignal.INSTANCE_OBSERVED
                snapshot.accessibilityInstancePresent == null -> PermissionSignal.NOT_OBSERVED
                snapshot.accessibilityAllowed == true -> PermissionSignal.ALLOWED_DISCONNECTED
                else -> PermissionSignal.STOPPED
            },
            overlayPermission = grant(snapshot.overlayAllowed),
            overlayOptIn = toggle(snapshot.overlayOptIn),
            handsfreeOptIn = toggle(snapshot.handsfreeOptIn),
            controlConsent = toggle(snapshot.controlConsent),
            mcpConnection = when (snapshot.mcpInstancePresent) {
                true -> PermissionSignal.INSTANCE_OBSERVED
                false -> PermissionSignal.STOPPED
                null -> PermissionSignal.NOT_OBSERVED
            }
        )

        private fun grant(value: Boolean?): PermissionSignal = when (value) {
            true -> PermissionSignal.GRANTED
            false -> PermissionSignal.DENIED
            null -> PermissionSignal.NOT_OBSERVED
        }

        private fun toggle(value: Boolean?): PermissionSignal = when (value) {
            true -> PermissionSignal.ON
            false -> PermissionSignal.OFF
            null -> PermissionSignal.NOT_OBSERVED
        }
    }
}
