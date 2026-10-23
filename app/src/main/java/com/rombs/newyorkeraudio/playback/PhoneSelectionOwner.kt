package com.rombs.newyorkeraudio.playback

/** Screen disposal may cancel its own phone request, but never a newer phone/car request. */
internal class PhoneSelectionOwner {
    private var request: Long? = null
    fun begin(number: Long) { request = number }
    fun clear() { request = null }
    fun matches(number: Long): Boolean = request == number
    fun finish(number: Long) { if (matches(number)) clear() }
}
