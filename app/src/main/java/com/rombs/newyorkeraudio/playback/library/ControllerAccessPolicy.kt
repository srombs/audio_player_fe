package com.rombs.newyorkeraudio.playback.library

const val ANDROID_AUTO_PACKAGE = "com.google.android.projection.gearhead"

// Production and rotated production certificate SHA-256 hashes published by Google's UAMP.
// https://github.com/android/uamp/blob/main/common/src/main/res/xml/allowed_media_browser_callers.xml
internal val ANDROID_AUTO_CERTIFICATES = setOf(
    "fdb00c43dbde8b51cb312aa81d3b5fa17713adb94b28f598d77f8eb89daceedf",
    "1ca8dcc0bed3cbd872d2cb791200c0292ca9975768a82d676b8b424fb65b5295",
)

internal fun controllerAllowed(callingUid: Int, ownUid: Int, trusted: Boolean,
    packageName: String, installedUid: Int?, certificates: Set<String>): Boolean =
    callingUid == ownUid || trusted ||
        (packageName == ANDROID_AUTO_PACKAGE && callingUid == installedUid &&
            certificates.any { it in ANDROID_AUTO_CERTIFICATES })
