package com.rombs.newyorkeraudio.playback.artwork

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ArtworkDiskStoreTest {
    @get:Rule val folder = TemporaryFolder()
    @Test fun `only validated new yorker https image urls create keys`() {
        assertEquals(64, artworkKey("https://media.newyorker.com/cover.jpg")!!.length)
        for (url in listOf("http://media.newyorker.com/cover.jpg", "https://evil.com/cover.jpg",
            "https://media.newyorker.com:444/image", "https://user@media.newyorker.com/image", "bad")) assertNull(artworkKey(url))
    }
    @Test fun `cache paths cannot escape image directory`() {
        val store = ArtworkDiskStore(folder.newFolder())
        for (key in listOf("../../secret", "fallback", "https://example.com/image", "a".repeat(65))) assertNull(store.read(key))
    }
    @Test fun `file count is bounded and completed files survive recreation`() {
        val directory = folder.newFolder()
        val store = ArtworkDiskStore(directory, maxFiles = 2)
        for (key in listOf("a", "b", "c")) store.write(key.repeat(64), byteArrayOf(1, 2, 3))
        assertEquals(2, directory.listFiles()!!.size)
        assertNotNull(ArtworkDiskStore(directory).read("c".repeat(64)))
    }
    @Test fun `byte budget prunes old images and rejects oversized writes`() {
        val directory = folder.newFolder()
        val store = ArtworkDiskStore(directory, maxBytes = 5)
        store.write("a".repeat(64), byteArrayOf(1, 2, 3))
        store.write("b".repeat(64), byteArrayOf(1, 2, 3))
        assertNull(store.read("a".repeat(64)))
        assertNotNull(store.read("b".repeat(64)))
        try { store.write("c".repeat(64), ByteArray(6)); fail("Expected oversized write rejection") } catch (_: IllegalArgumentException) {}
    }
}
