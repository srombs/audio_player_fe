package com.rombs.newyorkeraudio.playback

import org.junit.Assert.*
import org.junit.Test

class PhoneLifecycleTest {
    @Test fun `backgrounding retains pending binding until selection settles without UI callbacks`() {
        val lifecycle = PhoneControllerLifecycle()
        lifecycle.attach()
        assertFalse(lifecycle.requestDetach(true))
        assertFalse(lifecycle.allowsUiCallbacks)
        assertFalse(lifecycle.shouldDetach(true))
        assertTrue(lifecycle.shouldDetach(false))
    }

    @Test fun `returning during a request prevents its completion from releasing the controller`() {
        val lifecycle = PhoneControllerLifecycle()
        lifecycle.requestDetach(true)
        lifecycle.attach()
        assertTrue(lifecycle.allowsUiCallbacks)
        assertFalse(lifecycle.shouldDetach(false))
        assertTrue(lifecycle.requestDetach(false))
    }

    @Test fun `stale phone completion cannot clear newer ownership and a car request clears phone ownership`() {
        val owner = PhoneSelectionOwner()
        owner.begin(1)
        owner.begin(2)
        owner.finish(1)
        assertFalse(owner.matches(1))
        assertTrue(owner.matches(2))
        owner.clear()
        assertFalse(owner.matches(2))
        owner.finish(2)
        owner.begin(3)
        owner.finish(3)
        assertFalse(owner.matches(3))
    }

    @Test fun `active phone page follows car selection but deliberate article browsing stays put`() {
        assertTrue(shouldFollowSessionArticle("old", "old", "car", false))
        assertFalse(shouldFollowSessionArticle("browsed", "old", "car", false))
        assertFalse(shouldFollowSessionArticle(null, "old", "car", false))
        assertFalse(shouldFollowSessionArticle("old", "old", null, false))
        assertFalse(shouldFollowSessionArticle("car", "old", "car", false))
    }

    @Test fun `restored player page follows first connected active article`() {
        assertTrue(shouldFollowSessionArticle("saved", null, "active", true))
        assertFalse(shouldFollowSessionArticle("saved", null, "active", false))
    }
}
