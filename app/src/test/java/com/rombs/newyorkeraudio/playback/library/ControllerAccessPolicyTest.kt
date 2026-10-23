package com.rombs.newyorkeraudio.playback.library

import org.junit.Assert.*
import org.junit.Test

class ControllerAccessPolicyTest {
    @Test fun `anonymous legacy identity is not authorized as an actual controller`() {
        assertFalse(controllerAllowed(-1, 10, false, "android.media.session.MediaController", null, emptySet()))
        assertFalse(controllerAllowed(-1, 10, false, ANDROID_AUTO_PACKAGE, 20, ANDROID_AUTO_CERTIFICATES))
    }
    @Test fun `own uid and framework trusted controllers remain accepted`() {
        assertTrue(controllerAllowed(10, 10, false, "phone", null, emptySet()))
        assertTrue(controllerAllowed(20, 10, true, "system", null, emptySet()))
    }
    @Test fun `production and rotated auto certificates work without framework trust`() {
        ANDROID_AUTO_CERTIFICATES.forEach {
            assertTrue(controllerAllowed(20, 10, false, ANDROID_AUTO_PACKAGE, 20, setOf(it)))
        }
    }
    @Test fun `auto package name alone wrong uid and wrong signature are rejected`() {
        assertFalse(controllerAllowed(20, 10, false, ANDROID_AUTO_PACKAGE, 20, setOf("wrong")))
        assertFalse(controllerAllowed(21, 10, false, ANDROID_AUTO_PACKAGE, 20, ANDROID_AUTO_CERTIFICATES))
        assertFalse(controllerAllowed(20, 10, false, "other", 20, ANDROID_AUTO_CERTIFICATES))
        assertFalse(controllerAllowed(20, 10, false, ANDROID_AUTO_PACKAGE, null, ANDROID_AUTO_CERTIFICATES))
    }
}
