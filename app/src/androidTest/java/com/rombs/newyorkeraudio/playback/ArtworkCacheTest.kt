package com.rombs.newyorkeraudio.playback

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rombs.newyorkeraudio.playback.artwork.ArtworkCache
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ArtworkCacheTest {
    @Test fun downloadedArtworkIsDownsampledAndExposedAsLocalPng() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bitmap = Bitmap.createBitmap(1280, 960, Bitmap.Config.ARGB_8888)
        val bytes = ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it); it.toByteArray() }
        bitmap.recycle()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body(bytes.toResponseBody()).build()
        }.build()
        try {
            val cache = ArtworkCache(context, client)
            val url = "https://media.newyorker.com/${UUID.randomUUID()}.png"
            val uri = cache.uri(url)
            assertEquals("content", uri.scheme)
            val file = cache.fileFor(uri)!!
            val image = BitmapFactory.decodeFile(file.path)!!
            try { assertTrue(image.width <= 512 && image.height <= 512) } finally { image.recycle() }
            val downloadedUri = cache.uri(url)
            assertEquals("1", downloadedUri.getQueryParameter("v"))
            assertEquals("image/png", context.contentResolver.getType(downloadedUri))
            context.contentResolver.openInputStream(downloadedUri)!!.use { assertEquals(0x89, it.read()) }
            file.delete()
        } finally {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }

    @Test fun providerIdentifiersRejectForeignAuthoritiesAndUnrecognizedQueries() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val cache = ArtworkCache(context)
        assertNull(cache.key(Uri.parse("content://other.authority/image/fallback")))
        assertNull(cache.key(Uri.parse("content://${cache.authority}/image/fallback?url=https://evil.com")))
        assertNull(cache.key(Uri.parse("content://${cache.authority}/image/fallback#fragment")))
        assertNotNull(cache.key(cache.uri(null)))
    }
}
