package com.rombs.newyorkeraudio.playback

import android.os.Bundle
import androidx.media3.session.SessionCommand

internal const val SELECT_ARTICLE_ACTION = "com.rombs.newyorkeraudio.SELECT_ARTICLE"
internal const val CANCEL_SELECTION_ACTION = "com.rombs.newyorkeraudio.CANCEL_SELECTION"
internal const val VOICE_PLAY_ACTION = "com.rombs.newyorkeraudio.VOICE_PLAY"
internal const val SELECTION_REQUEST = "request_number"
internal const val VOICE_QUERY = "query"
internal const val SELECTION_MESSAGE = "message"
internal const val SELECTION_ID = "article_id"
internal const val SELECTION_REFRESH = "refresh"
internal const val SELECTION_FAILURE = "failure"
internal const val SELECTION_REASON = "reason"
internal val SELECT_ARTICLE_COMMAND = SessionCommand(SELECT_ARTICLE_ACTION, Bundle.EMPTY)
internal val CANCEL_SELECTION_COMMAND = SessionCommand(CANCEL_SELECTION_ACTION, Bundle.EMPTY)
internal val VOICE_PLAY_COMMAND = SessionCommand(VOICE_PLAY_ACTION, Bundle.EMPTY)
