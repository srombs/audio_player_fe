package com.rombs.newyorkeraudio.data.repository

import com.rombs.newyorkeraudio.domain.model.Article
import com.rombs.newyorkeraudio.domain.model.AudioInfo

/** Synthetic metadata shared by JVM and device tests; never bundled in the app. */
object TestNarration {
    const val ISSUE_ID = "2026-10-12"
    val article = Article(
        id = "test-narration",
        title = "The City After Midnight",
        author = "Alex Morgan",
        articleUrl = "https://www.newyorker.com/magazine/2026/10/12/test-narration",
        imageUrl = "android.resource://com.rombs.newyorkeraudio/drawable/sample_cover_shapes",
        section = "Playback test",
        audio = AudioInfo(
            durationMs = 1_630_000,
            audioUrl = "https://audio.example/test-narration.mp3",
        ),
    )
}
