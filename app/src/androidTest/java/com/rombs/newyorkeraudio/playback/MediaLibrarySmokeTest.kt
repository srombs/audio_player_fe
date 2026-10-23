package com.rombs.newyorkeraudio.playback

import android.content.ComponentName
import android.net.Uri
import android.os.Looper
import android.os.Bundle
import android.media.browse.MediaBrowser as PlatformMediaBrowser
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaBrowser
import androidx.media3.session.SessionToken
import androidx.media3.session.LibraryResult
import androidx.media3.session.SessionCommand
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.ListenableFuture
import com.rombs.newyorkeraudio.playback.library.LIBRARY_ROOT_ID
import com.rombs.newyorkeraudio.playback.library.LIBRARY_ISSUES_ID
import com.rombs.newyorkeraudio.playback.library.LIBRARY_CONTINUE_ID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.FileNotFoundException
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

/** No activity or live backend is required for root discovery and provider checks. */
@RunWith(AndroidJUnit4::class)
class MediaLibrarySmokeTest {
    @Test fun invalidSearchFinishesAndControllerReconnectLeavesSessionUnchanged() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        fun connect(): ListenableFuture<MediaBrowser> {
            lateinit var future: ListenableFuture<MediaBrowser>
            instrumentation.runOnMainSync {
                future = MediaBrowser.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java)))
                    .setApplicationLooper(Looper.getMainLooper()).buildAsync()
            }
            return future
        }
        val firstConnection = connect()
        val first = firstConnection.get(5, TimeUnit.SECONDS)
        var secondConnection: ListenableFuture<MediaBrowser>? = null
        var thirdConnection: ListenableFuture<MediaBrowser>? = null
        try {
            var originalId: String? = null
            var originalPlay = false
            lateinit var search: ListenableFuture<LibraryResult<Void>>
            lateinit var results: ListenableFuture<LibraryResult<ImmutableList<MediaItem>>>
            instrumentation.runOnMainSync {
                originalId = first.currentMediaItem?.mediaId
                originalPlay = first.playWhenReady
                search = first.search("x".repeat(257), null)
                results = first.getSearchResult("x".repeat(257), 0, 10, null)
            }
            assertEquals(LibraryResult.RESULT_ERROR_BAD_VALUE, search.get(5, TimeUnit.SECONDS).resultCode)
            assertEquals(LibraryResult.RESULT_ERROR_BAD_VALUE, results.get(5, TimeUnit.SECONDS).resultCode)
            secondConnection = connect()
            val second = secondConnection.get(5, TimeUnit.SECONDS)
            instrumentation.runOnMainSync { MediaBrowser.releaseFuture(firstConnection) }
            lateinit var root: ListenableFuture<LibraryResult<ImmutableList<MediaItem>>>
            instrumentation.runOnMainSync { root = second.getChildren(LIBRARY_ROOT_ID, 0, 10, null) }
            assertEquals(2, root.get(5, TimeUnit.SECONDS).value!!.size)
            thirdConnection = connect()
            val third = thirdConnection.get(5, TimeUnit.SECONDS)
            instrumentation.runOnMainSync {
                assertEquals(originalId, third.currentMediaItem?.mediaId)
                assertEquals(originalPlay, third.playWhenReady)
            }
        } finally {
            instrumentation.runOnMainSync {
                MediaBrowser.releaseFuture(firstConnection)
                secondConnection?.let { MediaBrowser.releaseFuture(it) }
                thirdConnection?.let { MediaBrowser.releaseFuture(it) }
            }
        }
    }

    @Test fun recentContentIsMetadataOnlyAndDoesNotStartPlayback() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        lateinit var connection: ListenableFuture<MediaBrowser>
        instrumentation.runOnMainSync {
            connection = MediaBrowser.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java)))
                .setApplicationLooper(Looper.getMainLooper()).buildAsync()
        }
        val browser = connection.get(5, TimeUnit.SECONDS)
        try {
            lateinit var rootRequest: ListenableFuture<LibraryResult<MediaItem>>
            lateinit var childrenRequest: ListenableFuture<LibraryResult<ImmutableList<MediaItem>>>
            var playing = false
            var currentId: String? = null
            instrumentation.runOnMainSync {
                playing = browser.playWhenReady
                currentId = browser.currentMediaItem?.mediaId
                rootRequest = browser.getLibraryRoot(LibraryParams.Builder().setRecent(true).build())
            }
            assertEquals(RECENT_ROOT_ID, rootRequest.get(5, TimeUnit.SECONDS).value!!.mediaId)
            instrumentation.runOnMainSync { childrenRequest = browser.getChildren(RECENT_ROOT_ID, 0, 10, null) }
            val items = childrenRequest.get(5, TimeUnit.SECONDS).value!!
            assertTrue(items.size <= 1)
            items.forEach { assertNull(it.localConfiguration); assertNull(it.mediaMetadata.artworkData) }
            instrumentation.runOnMainSync {
                assertEquals(playing, browser.playWhenReady)
                assertEquals(currentId, browser.currentMediaItem?.mediaId)
                assertFalse(browser.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM))
                assertFalse(browser.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM))
            }
        } finally { instrumentation.runOnMainSync { MediaBrowser.releaseFuture(connection) } }
    }

    @Test fun legacyCarBrowserReceivesBothRootFoldersAndAdvertisesSearch() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val loaded = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        lateinit var browser: PlatformMediaBrowser
        instrumentation.runOnMainSync {
            browser = PlatformMediaBrowser(context, ComponentName(context, PlaybackService::class.java),
                object : PlatformMediaBrowser.ConnectionCallback() {
                    override fun onConnected() {
                        try {
                            assertEquals(LIBRARY_ROOT_ID, browser.root)
                            assertTrue(browser.extras?.getBoolean("android.media.browse.SEARCH_SUPPORTED") ?: false)
                            browser.subscribe(browser.root, object : PlatformMediaBrowser.SubscriptionCallback() {
                                override fun onChildrenLoaded(parentId: String, children: MutableList<PlatformMediaBrowser.MediaItem>) {
                                    try {
                                        assertEquals(LIBRARY_ROOT_ID, parentId)
                                        assertEquals(listOf(LIBRARY_ISSUES_ID, LIBRARY_CONTINUE_ID), children.map { it.mediaId })
                                        assertTrue(children.all { it.isBrowsable && !it.isPlayable })
                                        assertTrue(children.all { it.description.iconBitmap == null })
                                    } catch (error: Throwable) { failure.set(error) }
                                    finally { loaded.countDown() }
                                }
                                override fun onError(parentId: String) {
                                    failure.set(AssertionError("Legacy browse failed"))
                                    loaded.countDown()
                                }
                            })
                        } catch (error: Throwable) { failure.set(error); loaded.countDown() }
                    }
                    override fun onConnectionFailed() {
                        failure.set(AssertionError("Legacy browser connection failed"))
                        loaded.countDown()
                    }
                }, Bundle.EMPTY)
            browser.connect()
        }
        try {
            assertTrue("Legacy browse timed out", loaded.await(5, TimeUnit.SECONDS))
            failure.get()?.let { throw it }
        } finally { instrumentation.runOnMainSync { browser.disconnect() } }
    }

    @Test fun phoneControllerCanBrowseLocalRootWithoutStartingPlayback() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        lateinit var connection: ListenableFuture<MediaBrowser>
        instrumentation.runOnMainSync {
            connection = MediaBrowser.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java)))
                .setApplicationLooper(Looper.getMainLooper()).buildAsync()
        }
        val browser = connection.get(5, TimeUnit.SECONDS)
        try {
            lateinit var rootRequest: ListenableFuture<LibraryResult<MediaItem>>
            lateinit var childrenRequest: ListenableFuture<LibraryResult<ImmutableList<MediaItem>>>
            instrumentation.runOnMainSync {
                assertTrue(browser.isCommandAvailable(Player.COMMAND_PLAY_PAUSE))
                assertTrue(browser.availableSessionCommands.contains(SELECT_ARTICLE_COMMAND))
                assertTrue(browser.availableSessionCommands.contains(SessionCommand.COMMAND_CODE_LIBRARY_SEARCH))
                assertTrue(browser.availableSessionCommands.contains(SessionCommand.COMMAND_CODE_LIBRARY_GET_SEARCH_RESULT))
                rootRequest = browser.getLibraryRoot(null)
                childrenRequest = browser.getChildren(LIBRARY_ROOT_ID, 0, Int.MAX_VALUE, null)
                assertFalse(browser.isPlaying)
                assertNull(browser.currentMediaItem)
            }
            val root = rootRequest.get(5, TimeUnit.SECONDS)
            assertEquals(LibraryResult.RESULT_SUCCESS, root.resultCode)
            assertEquals(LIBRARY_ROOT_ID, root.value!!.mediaId)
            val nodes = childrenRequest.get(5, TimeUnit.SECONDS)
            assertEquals(listOf(LIBRARY_ISSUES_ID, LIBRARY_CONTINUE_ID), nodes.value!!.map { it.mediaId })
            val issues = nodes.value!!.first()
            assertEquals(LIBRARY_ISSUES_ID, issues.mediaId)
            assertEquals(true, issues.mediaMetadata.isBrowsable)
            assertEquals(false, issues.mediaMetadata.isPlayable)
            assertNull(issues.localConfiguration)
            assertNull(issues.mediaMetadata.artworkData)
            assertEquals("content", issues.mediaMetadata.artworkUri!!.scheme)
        } finally { instrumentation.runOnMainSync { MediaBrowser.releaseFuture(connection) } }
    }

    @Test fun providerServesPngFallbackAndRejectsWritesAndPathTraversal() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fallback = Uri.parse("content://${context.packageName}.artwork/image/fallback")
        context.contentResolver.openInputStream(fallback)!!.use { input ->
            assertEquals(0x89, input.read())
            assertEquals('P'.code, input.read())
        }
        assertEquals("image/png", context.contentResolver.getType(fallback))
        for ((uri, mode) in listOf(fallback to "rw",
            Uri.parse("content://${context.packageName}.artwork/image/..%2F..%2Fsecret") to "r")) {
            try {
                context.contentResolver.openFileDescriptor(uri, mode)?.close()
                fail("Expected read-only/path validation")
            } catch (_: FileNotFoundException) {}
        }
    }
}
