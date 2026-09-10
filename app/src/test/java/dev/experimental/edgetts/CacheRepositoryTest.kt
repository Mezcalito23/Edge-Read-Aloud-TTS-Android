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

    private val mp3 = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x10, 0x00)
    private val other = byteArrayOf(0x49, 0x44, 0x33, 0x04)

    @Before
    fun setUp() {
        root = File(System.getProperty("java.io.tmpdir") ?: ".", "edge-mp3-${System.nanoTime()}").also {
            it.mkdirs()
        }
        repo = CacheRepository(root)
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun mp3Dir(): File = File(root, CacheRepository.DIR_MP3)

    @Test
    fun writePublishesAtomicallyAndLeavesNoTmp() {
        assertTrue(repo.writeMp3("k1", mp3))
        val dir = mp3Dir()
        val files = dir.listFiles()?.map { it.name }?.sorted().orEmpty()
        assertEquals(listOf("k1.mp3"), files)
        assertArrayEquals(mp3, repo.readMp3("k1"))
        assertFalse(File(dir, "k1.mp3.tmp").exists())
    }

    @Test
    fun emptyPayloadDoesNotReplaceExistingEntry() {
        assertTrue(repo.writeMp3("k1", mp3))
        assertFalse(repo.writeMp3("k1", byteArrayOf()))
        assertArrayEquals(mp3, repo.readMp3("k1"))
        assertFalse(File(mp3Dir(), "k1.mp3.tmp").exists())
    }

    @Test
    fun writeReplacesPreviousValue() {
        assertTrue(repo.writeMp3("k1", mp3))
        assertTrue(repo.writeMp3("k1", other))
        assertArrayEquals(other, repo.readMp3("k1"))
        assertEquals(1, mp3Dir().listFiles { f -> f.extension == "mp3" }?.size)
    }

    @Test
    fun readDeletesEmptyFile() {
        val file = File(mp3Dir(), "bad.mp3")
        file.writeBytes(byteArrayOf())
        assertNull(repo.readMp3("bad"))
        assertFalse(file.exists())
    }

    @Test
    fun lruEvictsOldestWithoutTouchingIncomingKey() {
        val small = CacheRepository(root, maxBytes = 7)
        assertTrue(small.writeMp3("old", mp3))
        assertTrue(small.writeMp3("new", other))
        assertNull(small.readMp3("old"))
        assertArrayEquals(other, small.readMp3("new"))
        assertFalse(File(mp3Dir(), "new.mp3.tmp").exists())
    }

    @Test
    fun keyChangesWhenFormatChanges() {
        val a = repo.key("hola", "es-MX-DaliaNeural", "es-MX", "+0%", "+0Hz", "audio-mp3", "v1")
        val b = repo.key("hola", "es-MX-DaliaNeural", "es-MX", "+0%", "+0Hz", "audio-opus", "v1")
        assertEquals(64, a.length)
        assertTrue(a != b)
    }

    @Test
    fun constructorDeletesLegacyPcmDirectory() {
        val pcm = File(root, CacheRepository.DIR_PCM).also { it.mkdirs() }
        File(pcm, "old.pcm").writeBytes(mp3)
        CacheRepository(root)
        assertFalse(pcm.exists())
    }
}
