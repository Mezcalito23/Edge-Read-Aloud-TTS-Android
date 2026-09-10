package dev.experimental.edgetts

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class CacheRepositoryTest {

    private lateinit var root: File
    private lateinit var repo: CacheRepository

    private val pcm = byteArrayOf(0x01, 0x02, 0x03, 0x04)
    private val other = byteArrayOf(0x05, 0x06, 0x07, 0x08)

    @Before
    fun setUp() {
        root = File(System.getProperty("java.io.tmpdir") ?: ".", "edge-pcm-${System.nanoTime()}").also {
            it.mkdirs()
        }
        repo = CacheRepository(root)
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun pcmDir(): File = File(root, "edge_tts_pcm")

    @Test
    fun writePublishesAtomicallyAndLeavesNoTmp() {
        assertTrue(repo.write("k1", pcm))
        val dir = pcmDir()
        val files = dir.listFiles()?.map { it.name }?.sorted().orEmpty()
        assertEquals(listOf("k1.pcm"), files)
        assertArrayEquals(pcm, repo.read("k1"))
        assertFalse(File(dir, "k1.pcm.tmp").exists())
    }

    @Test
    fun invalidPayloadDoesNotReplaceExistingEntry() {
        assertTrue(repo.write("k1", pcm))
        assertFalse(repo.write("k1", byteArrayOf(0x01)))
        assertFalse(repo.write("k1", byteArrayOf()))
        assertArrayEquals(pcm, repo.read("k1"))
        assertFalse(File(pcmDir(), "k1.pcm.tmp").exists())
    }

    @Test
    fun writeReplacesPreviousValue() {
        assertTrue(repo.write("k1", pcm))
        assertTrue(repo.write("k1", other))
        assertArrayEquals(other, repo.read("k1"))
        assertEquals(1, pcmDir().listFiles { f -> f.extension == "pcm" }?.size)
    }

    @Test
    fun readDeletesCorruptOddLengthFile() {
        val file = File(pcmDir(), "bad.pcm")
        file.writeBytes(byteArrayOf(0x01, 0x02, 0x03))
        assertNull(repo.read("bad"))
        assertFalse(file.exists())
    }

    @Test
    fun lruEvictsOldestWithoutTouchingIncomingKey() {
        val small = CacheRepository(root, maxBytes = 8)
        assertTrue(small.write("old", pcm))
        Thread.sleep(5)
        File(pcmDir(), "old.pcm").setLastModified(1L)
        assertTrue(small.write("new", other))
        assertNull(small.read("old"))
        assertArrayEquals(other, small.read("new"))
    }
}
