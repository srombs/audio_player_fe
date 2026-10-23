package com.rombs.newyorkeraudio.playback.artwork

import java.io.File
import java.security.MessageDigest
import java.net.URI

internal fun artworkKey(url: String): String? = try {
    val uri = URI(url)
    if (uri.scheme != "https" || uri.host != "media.newyorker.com" || uri.userInfo != null ||
        uri.port !in listOf(-1, 443) || url.length > 4096) null
    else MessageDigest.getInstance("SHA-256").digest(url.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
} catch (_: Exception) { null }

/** Only generated image keys can access files. No URL paths or user filenames become disk paths. */
internal class ArtworkDiskStore(private val directory: File, private val maxFiles: Int = 128,
    private val maxBytes: Long = 32L * 1024 * 1024) {
    init { require(maxFiles > 0 && maxBytes > 0); directory.mkdirs() }
    private fun file(key: String): File? {
        if (!key.matches(Regex("[a-f0-9]{64}"))) return null
        val candidate = File(directory, "$key.png")
        return candidate.takeIf { it.canonicalFile.parentFile == directory.canonicalFile }
    }

    @Synchronized fun read(key: String): File? = file(key)?.takeIf { it.isFile && it.length() in 1..maxBytes }
        ?.also { it.setLastModified(System.currentTimeMillis()) }

    @Synchronized fun write(key: String, bytes: ByteArray): File {
        directory.mkdirs()
        require(bytes.isNotEmpty() && bytes.size <= minOf(maxBytes, 2L * 1024 * 1024))
        val target = requireNotNull(file(key))
        val temporary = File.createTempFile("artwork-", ".tmp", directory)
        try {
            temporary.writeBytes(bytes)
            check(temporary.renameTo(target))
        } finally { temporary.delete() }
        var size = 0L
        var count = 0
        val images = directory.listFiles()?.filter { it.name.matches(Regex("[a-f0-9]{64}\\.png")) }
            ?.sortedByDescending { if (it == target) Long.MAX_VALUE else it.lastModified() }.orEmpty()
        images.forEach {
            if (++count > maxFiles || size + it.length() > maxBytes) it.delete() else size += it.length()
        }
        return target
    }
}
