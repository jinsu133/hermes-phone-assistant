package com.mist.connect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PermissionAssessmentTest {
    private fun snapshot(
        microphone: Boolean? = false,
        api33: Boolean = true,
        notificationGrant: Boolean? = false,
        notificationEnabled: Boolean? = false,
        accessibility: Boolean? = false,
        accessibilityInstance: Boolean? = false,
        overlay: Boolean? = false,
        overlayOptIn: Boolean? = false,
        handsfree: Boolean? = null,
        consent: Boolean? = null,
        mcp: Boolean? = false
    ) = PermissionSnapshot(microphone, api33, notificationGrant, notificationEnabled,
        accessibility, accessibilityInstance, overlay, overlayOptIn, handsfree, consent, mcp)

    @Test fun deniedAndDisabledAreNotPresentedAsReady() {
        val result = PermissionAssessment.from(snapshot())
        assertEquals(PermissionSignal.DENIED, result.microphone)
        assertEquals(PermissionSignal.DENIED, result.notificationRuntime)
        assertEquals(PermissionSignal.OFF, result.notificationDelivery)
        assertEquals(PermissionSignal.DENIED, result.accessibilityPermission)
        assertEquals(PermissionSignal.STOPPED, result.accessibilityConnection)
        assertEquals(PermissionSignal.DENIED, result.overlayPermission)
    }

    @Test fun allowedAccessibilityIsNotAConnectedService() {
        val result = PermissionAssessment.from(snapshot(accessibility = true))
        assertEquals(PermissionSignal.GRANTED, result.accessibilityPermission)
        assertEquals(PermissionSignal.ALLOWED_DISCONNECTED, result.accessibilityConnection)
    }

    @Test fun liveInstanceIsOnlyAnObservationNotAnEndToEndSuccess() {
        val result = PermissionAssessment.from(snapshot(microphone = true, notificationGrant = true,
            notificationEnabled = true, accessibility = true, accessibilityInstance = true,
            overlay = true, overlayOptIn = true, handsfree = true, consent = true, mcp = true))
        assertEquals(PermissionSignal.INSTANCE_OBSERVED, result.accessibilityConnection)
        assertEquals(PermissionSignal.INSTANCE_OBSERVED, result.mcpConnection)
        assertEquals(PermissionSignal.GRANTED, result.microphone)
        assertEquals(PermissionSignal.ON, result.controlConsent)
        assertFalse(PermissionSignal.values().any { it.name.contains("SUCCESS") || it.name.contains("WORKING") })
    }

    @Test fun stoppedMcpIsDistinctFromOptInAndPermission() {
        val result = PermissionAssessment.from(snapshot(handsfree = true, consent = true, mcp = false))
        assertEquals(PermissionSignal.ON, result.handsfreeOptIn)
        assertEquals(PermissionSignal.ON, result.controlConsent)
        assertEquals(PermissionSignal.STOPPED, result.mcpConnection)
    }

    @Test fun olderAndroidSkipsRuntimeNotificationPermissionButReadsEnablement() {
        val result = PermissionAssessment.from(snapshot(api33 = false, notificationGrant = false,
            notificationEnabled = false))
        assertEquals(PermissionSignal.NOT_APPLICABLE, result.notificationRuntime)
        assertEquals(PermissionSignal.OFF, result.notificationDelivery)
    }

    @Test fun notificationGrantIsNotDeliveryEnablement() {
        val result = PermissionAssessment.from(snapshot(notificationGrant = true, notificationEnabled = false))
        assertEquals(PermissionSignal.GRANTED, result.notificationRuntime)
        assertEquals(PermissionSignal.OFF, result.notificationDelivery)
    }

    @Test fun optionalOverlayOsGrantCannotEnablePrivacyOptIn() {
        val result = PermissionAssessment.from(snapshot(overlay = true, overlayOptIn = false))
        assertEquals(PermissionSignal.GRANTED, result.overlayPermission)
        assertEquals(PermissionSignal.OFF, result.overlayOptIn)
    }

    @Test fun disconnectedAndConsentOffRemainDistinctEvenWithMcpInstance() {
        val result = PermissionAssessment.from(snapshot(accessibility = true, accessibilityInstance = false,
            consent = false, mcp = true))
        assertEquals(PermissionSignal.ALLOWED_DISCONNECTED, result.accessibilityConnection)
        assertEquals(PermissionSignal.OFF, result.controlConsent)
        assertEquals(PermissionSignal.INSTANCE_OBSERVED, result.mcpConnection)
    }

    @Test fun staleServiceInstanceDoesNotFabricateOsAuthorization() {
        val result = PermissionAssessment.from(snapshot(accessibility = false, accessibilityInstance = true))
        assertEquals(PermissionSignal.DENIED, result.accessibilityPermission)
        assertEquals(PermissionSignal.INSTANCE_OBSERVED, result.accessibilityConnection)
    }

    @Test fun unreadConsentAndUnmeasuredOperationNeverBecomeGrants() {
        val result = PermissionAssessment.from(snapshot(microphone = null, accessibility = null,
            accessibilityInstance = null, overlayOptIn = null, handsfree = null, consent = null,
            mcp = null, notificationEnabled = null))
        assertEquals(PermissionSignal.NOT_OBSERVED, result.microphone)
        assertEquals(PermissionSignal.NOT_OBSERVED, result.accessibilityConnection)
        assertEquals(PermissionSignal.NOT_OBSERVED, result.overlayOptIn)
        assertEquals(PermissionSignal.NOT_OBSERVED, result.handsfreeOptIn)
        assertEquals(PermissionSignal.NOT_OBSERVED, result.controlConsent)
        assertEquals(PermissionSignal.NOT_OBSERVED, result.mcpConnection)
        assertEquals(PermissionSignal.NOT_OBSERVED, result.notificationDelivery)
    }
}
