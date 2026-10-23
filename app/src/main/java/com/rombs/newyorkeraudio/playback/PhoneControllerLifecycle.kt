package com.rombs.newyorkeraudio.playback

/** UI detachment must not destroy the last binding while an explicit playback request is resolving. */
internal class PhoneControllerLifecycle {
    private var detachWhenSettled = false
    val allowsUiCallbacks: Boolean get() = !detachWhenSettled
    fun attach() { detachWhenSettled = false }
    fun requestDetach(hasPendingWork: Boolean): Boolean { detachWhenSettled = true; return !hasPendingWork }
    fun shouldDetach(hasPendingWork: Boolean): Boolean = detachWhenSettled && !hasPendingWork
}

/** Follow an active player page, while preserving deliberate browsing of a different article. */
internal fun shouldFollowSessionArticle(displayedId: String?, previousId: String?, currentId: String?, firstConnection: Boolean): Boolean =
    displayedId != null && currentId != null && displayedId != currentId &&
        (displayedId == previousId || firstConnection)
