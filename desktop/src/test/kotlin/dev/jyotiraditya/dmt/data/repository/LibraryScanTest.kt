package dev.jyotiraditya.dmt.data.repository

import dev.jyotiraditya.dmt.data.ffmpeg.Ffmpeg
import dev.jyotiraditya.dmt.data.source.local.DmtStore
import dev.jyotiraditya.dmt.domain.model.DmtSettings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LibraryScanTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun fixture(name: String): File =
        File(javaClass.getResource("/audio/$name")!!.toURI())

    @Test
    fun stableIdsFitCueStride() {
        val id = stableId("/home/me/Music/a.flac")
        assertEquals(id, stableId("/home/me/Music/a.flac"))
        assertNotEquals(id, stableId("/home/me/Music/b.flac"))
        assertTrue(id > 0 && id * 1000 + 999 > 0)
    }

    @Test
    fun probeReadsTagsAndStreamInfo() {
        val probe = Ffmpeg.probe(fixture("tone.flac").absolutePath)!!
        assertTrue(probe.hasAudio)
        assertEquals("flac", probe.codec)
        assertEquals("Test Tone", probe.tags["title"])
        assertTrue(probe.durationMs in 900..1100)
    }

    @Test
    fun scanFindsEveryFormatAndUsesTheCache() = runBlocking {
        val music = tmp.newFolder("music")
        listOf("tone.flac", "tone.mp3", "tone.m4a", "tone.opus", "tone-vorbis.ogg", "tone-alac.m4a")
            .forEach { fixture(it).copyTo(File(music, it)) }
        val prefs = PreferencesRepository(DmtStore(File(tmp.root, "dmt.properties")))
        prefs.save(DmtSettings(musicFolders = listOf(music.absolutePath.replace('\\', '/'))))
        val cache = File(tmp.root, "library.json")
        val repo = MediaRepositoryImpl(prefs, cache)

        val first = repo.scan()
        assertEquals(6, first.size)
        assertTrue(first.all { it.title == "Test Tone" && it.artist == "DMT" && it.album == "Fixtures" })
        assertTrue(cache.isFile)

        val second = repo.scan()
        assertEquals(first.map { it.id }.toSet(), second.map { it.id }.toSet())
    }
}
