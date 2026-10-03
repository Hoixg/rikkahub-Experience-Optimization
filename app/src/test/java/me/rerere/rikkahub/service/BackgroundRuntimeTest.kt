package me.rerere.rikkahub.service

import org.junit.Assert.*
import org.junit.Test

class BackgroundRuntimeTest {
    @Test fun finishingOneOwnerDoesNotClearAnotherServicesRuntime() {
        BackgroundRuntime.service("chat-test", true); BackgroundRuntime.service("scheduled-test", true)
        BackgroundRuntime.wakeLock("chat-test", true); BackgroundRuntime.wakeLock("scheduled-test", true)
        BackgroundRuntime.service("chat-test", false); BackgroundRuntime.wakeLock("chat-test", false)
        assertFalse("chat-test" in BackgroundRuntime.state.value.services)
        assertFalse("chat-test" in BackgroundRuntime.state.value.wakeLocks)
        assertTrue("scheduled-test" in BackgroundRuntime.state.value.services)
        assertTrue("scheduled-test" in BackgroundRuntime.state.value.wakeLocks)
        BackgroundRuntime.service("scheduled-test", false); BackgroundRuntime.wakeLock("scheduled-test", false)
    }
}
