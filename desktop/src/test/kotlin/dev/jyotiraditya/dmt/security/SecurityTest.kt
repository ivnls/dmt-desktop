package dev.jyotiraditya.dmt.security

import dev.jyotiraditya.dmt.data.ffmpeg.Ffmpeg
import dev.jyotiraditya.dmt.data.remote.jellyfin.JellyfinSession
import dev.jyotiraditya.dmt.data.remote.jellyfin.ServerUrlProblem
import dev.jyotiraditya.dmt.data.remote.jellyfin.ServerUrls
import dev.jyotiraditya.dmt.data.repository.PreferencesRepository
import dev.jyotiraditya.dmt.data.source.local.DmtStore
import dev.jyotiraditya.dmt.domain.model.DmtSettings
import dev.jyotiraditya.dmt.platform.secrets.FileSecretStore
import dev.jyotiraditya.dmt.presentation.player.nextUiScale
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

class SecurityTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val posix = java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix")

    @Test
    fun serverUrls() {
        assertEquals("https://jelly.example.com", ServerUrls.normalize(" jelly.example.com/ "))
        assertNull(ServerUrls.problem("https://jelly.example.com"))
        assertEquals(ServerUrlProblem.INSECURE_PUBLIC, ServerUrls.problem("http://jelly.example.com"))
        assertEquals(ServerUrlProblem.INSECURE_PUBLIC, ServerUrls.problem("http://8.8.8.8:8096"))
        assertNull(ServerUrls.problem("http://192.168.1.10:8096"))
        assertNull(ServerUrls.problem("http://10.0.0.2"))
        assertNull(ServerUrls.problem("http://100.101.102.103"))
        assertNull(ServerUrls.problem("http://localhost:8096"))
        assertNull(ServerUrls.problem("http://nas.local:8096"))
        assertNull(ServerUrls.problem("http://nas:8096"))
        assertEquals(ServerUrlProblem.INVALID, ServerUrls.problem("ftp://nas"))
        assertEquals(ServerUrlProblem.INVALID, ServerUrls.problem("https://"))
    }

    @Test
    fun tokenOnlyGoesToItsServer() {
        JellyfinSession.set("https://jelly.example.com/", "secret")
        assertEquals("X-Emby-Token" to "secret", JellyfinSession.headerFor("https://jelly.example.com/Audio/1/stream"))
        assertNull(JellyfinSession.headerFor("https://jelly.example.com.evil.net/Audio/1"))
        assertNull(JellyfinSession.headerFor("https://other.example.com/x"))
        JellyfinSession.set(null, null)
    }

    @Test
    fun ffmpegProtocolWhitelist() {
        assertEquals("file", Ffmpeg.protocolsFor("/home/me/Music/a.flac"))
        assertEquals("file", Ffmpeg.protocolsFor("C:/Users/me/Music/a.flac"))
        assertEquals("https,tls,tcp", Ffmpeg.protocolsFor("https://jelly/Audio/1/stream"))
        assertEquals("http,tcp", Ffmpeg.protocolsFor("http://nas:8096/Audio/1/stream"))
        assertNull(Ffmpeg.probe("http://example.com/a.mp3"))
    }

    @Test
    fun ffmpegRefusesFilesThatReachOtherFiles() {
        // a concat list disguised as audio must not be able to pull in another file
        val target = File(javaClass.getResource("/audio/tone.flac")!!.toURI())
        val evil = tmp.newFile("evil.ffconcat")
        evil.writeText("ffconcat version 1.0\nfile 'subfile,,start,0,end,0,,:${target.absolutePath}'\n")
        assertNull(Ffmpeg.probe(evil.absolutePath)?.takeIf { it.hasAudio })
    }

    @Test
    fun fileSecretsArePrivate() {
        assumeTrue(posix)
        val file = File(tmp.root, "conf/secrets")
        val store = FileSecretStore(file)
        assertTrue(store.put("k", "v1"))
        assertEquals("v1", store.get("k"))
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file.toPath())))
        assertFalse(file.readText().contains("v1"))
        store.delete("k")
        assertNull(store.get("k"))
    }

    @Test
    fun tokenNeverLandsInPropertiesAndLegacyTokensMove() = runBlocking {
        val props = File(tmp.root, "dmt.properties")
        props.writeText("jellyfin_url=https\\://jelly.example.com\njellyfin_token=legacy-token\n")
        val secrets = FileSecretStore(File(tmp.root, "secrets"))
        val repo = PreferencesRepository(DmtStore(props)) { secrets }
        assertEquals("legacy-token", repo.settings.first().jellyfinToken)
        assertFalse(props.readText().contains("legacy-token"))
        assertEquals("legacy-token", secrets.get("jellyfin_token"))

        repo.save(DmtSettings(jellyfinUrl = "https://jelly.example.com", jellyfinToken = "new-token"))
        assertFalse(props.readText().contains("token"))
        assertEquals("new-token", secrets.get("jellyfin_token"))
        assertEquals("new-token", repo.settings.first().jellyfinToken)
        if (posix) {
            assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(props.toPath())))
        }

        repo.save(DmtSettings())
        assertNull(secrets.get("jellyfin_token"))
    }

    @Test
    fun zoomSteps() {
        assertEquals(110, nextUiScale(0, 1))
        assertEquals(90, nextUiScale(0, -1))
        assertEquals(150, nextUiScale(125, 1))
        assertEquals(200, nextUiScale(200, 1))
        assertEquals(0, nextUiScale(150, 0))
    }
}
