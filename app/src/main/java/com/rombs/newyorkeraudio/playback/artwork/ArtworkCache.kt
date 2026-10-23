package com.rombs.newyorkeraudio.playback.artwork

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.net.Uri
import android.os.Looper
import com.rombs.newyorkeraudio.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit

/** Local URIs are immediate; bounded downloads and decoding happen only on IO threads. */
class ArtworkCache(context: Context, private val client: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(5, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS)
    .callTimeout(10, TimeUnit.SECONDS).followRedirects(false).build()) {
    private val context = context.applicationContext
    val authority = "${context.packageName}.artwork"
    private val directory = File(context.cacheDir, "car-artwork")
    private val disk = ArtworkDiskStore(directory)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gate = Semaphore(3)
    private val urls = linkedMapOf<String, String>()
    private val ready = directory.listFiles()?.mapNotNull { file ->
        file.name.removeSuffix(".png").takeIf { file.isFile && it.matches(Regex("[a-f0-9]{64}")) }
    }?.toMutableSet() ?: mutableSetOf()
    private val flights = mutableMapOf<String, Deferred<File?>>()
    private val mutableUpdates = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val updates = mutableUpdates.asSharedFlow()

    fun uri(url: String?): Uri {
        val key = url?.let { artworkKey(it) }
        if (key != null) synchronized(urls) {
            urls.remove(key)
            urls[key] = requireNotNull(url)
            while (urls.size > 512) {
                val oldest = urls.keys.first()
                urls.remove(oldest)
                ready.remove(oldest)
            }
        }
        return Uri.Builder().scheme("content").authority(authority).appendPath("image")
            .appendPath(key ?: "fallback").apply {
                if (key != null && synchronized(urls) { key in ready }) appendQueryParameter("v", "1")
            }.build()
    }

    fun prefetch(url: String?) {
        val key = url?.let { artworkKey(it) } ?: return
        uri(url)
        if (disk.read(key) == null) download(key, requireNotNull(url))
        else if (synchronized(urls) { ready.add(key) }) mutableUpdates.tryEmit(requireNotNull(url))
    }

    internal fun key(uri: Uri): String? {
        if (uri.scheme != "content" || uri.authority != authority || uri.encodedQuery !in listOf(null, "v=1") || uri.fragment != null ||
            uri.pathSegments.size != 2 || uri.pathSegments[0] != "image") return null
        val key = uri.pathSegments[1]
        return key.takeIf { it == "fallback" || it.matches(Regex("[a-f0-9]{64}")) }
    }

    internal fun cachedFile(uri: Uri): File? = key(uri)?.takeUnless { it == "fallback" }?.let { disk.read(it) }

    internal fun fileFor(uri: Uri): File? {
        val key = key(uri) ?: return null
        if (key == "fallback") return fallback()
        disk.read(key)?.let { return it }
        val url = synchronized(urls) { urls[key] }
        // Provider calls normally arrive on Binder threads; never block the player/UI thread.
        if (url != null && Looper.myLooper() != Looper.getMainLooper()) {
            val file = runBlocking { withTimeoutOrNull(12_000) { download(key, url).await() } }
            if (file != null) return file
        }
        return fallback()
    }

    private fun download(key: String, url: String): Deferred<File?> = synchronized(flights) {
        if (flights.size >= 96 && key !in flights) return@synchronized CompletableDeferred(null)
        flights[key] ?: scope.async(start = CoroutineStart.LAZY) {
            try {
                gate.withPermit {
                    disk.read(key) ?: fetch(url)?.let { bytes ->
                        disk.write(key, bytes).also {
                            synchronized(urls) { if (key in urls) ready.add(key) }
                            mutableUpdates.tryEmit(url)
                        }
                    }
                }
            } catch (_: Exception) { null }
            finally { synchronized(flights) { flights.remove(key) } }
        }.also { flights[key] = it; it.start() }
    }

    private fun fetch(url: String): ByteArray? {
        return client.newCall(Request.Builder().url(url).build()).execute().use { response ->
        if (!response.isSuccessful) return null
        val body = response.body ?: return null
        if (body.contentLength() > 5 * 1024 * 1024) return null
        val bytes = body.byteStream().use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (out.size() + count > 5 * 1024 * 1024) return null
                out.write(buffer, 0, count)
            }
            out.toByteArray()
        }
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        if (options.outWidth <= 0 || options.outHeight <= 0 || options.outWidth.toLong() * options.outHeight > 64_000_000) return null
        options.inSampleSize = 1
        while ((maxOf(options.outWidth, options.outHeight) + options.inSampleSize - 1) / options.inSampleSize > 512) options.inSampleSize *= 2
        options.inJustDecodeBounds = false
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        try { ByteArrayOutputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); out.toByteArray() } }
        finally { bitmap.recycle() }
        }
    }

    @Synchronized private fun fallback(): File {
        directory.mkdirs()
        val file = File(directory, "fallback.png")
        if (!file.isFile) {
            val drawable = requireNotNull(context.getDrawable(R.drawable.sample_artwork))
            val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
            try {
                drawable.setBounds(0, 0, 256, 256)
                drawable.draw(Canvas(bitmap))
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            } finally { bitmap.recycle() }
        }
        return file
    }
}
