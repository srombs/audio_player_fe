package com.rombs.newyorkeraudio.ui.player

import com.rombs.newyorkeraudio.domain.model.Article
import com.rombs.newyorkeraudio.playback.PlaybackFailure
import com.rombs.newyorkeraudio.playback.PlaybackState

/** Resolver results can arrive before the screen's catalog metadata is refreshed. */
internal fun Article.hasNoNarration(playback: PlaybackState): Boolean =
    audioAvailable == false || audio == null ||
        (playback.mediaId == id &&
            (playback.error == PlaybackFailure.NO_NARRATION || playback.article?.audioAvailable == false))
