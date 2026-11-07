package dev.jyotiraditya.dmt.platform.secrets

import com.sun.jna.platform.win32.Crypt32Util
import dev.jyotiraditya.dmt.platform.AppDirs
import dev.jyotiraditya.dmt.platform.Platform
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.util.Base64

/** Where credentials (the jellyfin token) live. Never in dmt.properties. */
interface SecretStore {
    /** Short name shown in the ui, e.g. "keyring". */
    val name: String
    fun get(key: String): String?
    fun put(key: String, value: String): Boolean
    fun delete(key: String)

    companion object {
        /**
         * linux: the desktop keyring over the Secret Service api (gnome keyring, kwallet,
         * keepassxc), windows: DPAPI (encrypted for the logged-in user). If neither works, a
         * file only the user can read.
         */
        fun create(): SecretStore {
            val fallback = FileSecretStore(File(AppDirs.config, "secrets"))
            val preferred: SecretStore? = when {
                Platform.isWindows -> DpapiSecretStore(File(AppDirs.config, "secrets.dpapi"))
                Platform.isLinux -> runCatching { SecretServiceStore.connect() }.getOrNull()
                else -> null
            }
            return if (preferred == null) fallback else LayeredSecretStore(preferred, fallback)
        }
    }
}

/** Uses [primary]; falls back when it fails, and moves anything left in [fallback] over. */
class LayeredSecretStore(private val primary: SecretStore, private val fallback: SecretStore) : SecretStore {
    override val name: String get() = primary.name

    override fun get(key: String): String? {
        primary.runCatching { get(key) }.getOrNull()?.let { return it }
        val legacy = fallback.get(key) ?: return null
        if (primary.runCatching { put(key, legacy) }.getOrDefault(false)) fallback.delete(key)
        return legacy
    }

    override fun put(key: String, value: String): Boolean {
        if (primary.runCatching { put(key, value) }.getOrDefault(false)) {
            fallback.delete(key)
            return true
        }
        return fallback.put(key, value)
    }

    override fun delete(key: String) {
        runCatching { primary.delete(key) }
        fallback.delete(key)
    }
}

/** key=base64(value) lines in a file created with owner-only permissions (0600, dir 0700). */
open class FileSecretStore(private val file: File) : SecretStore {
    override val name: String = "file"

    protected open fun encode(plain: ByteArray): ByteArray = plain
    protected open fun decode(stored: ByteArray): ByteArray = stored

    @Synchronized
    private fun readAll(): MutableMap<String, String> = runCatching {
        file.readLines()
            .mapNotNull { line ->
                val i = line.indexOf('=')
                if (i <= 0) return@mapNotNull null
                val raw = Base64.getDecoder().decode(line.substring(i + 1))
                line.substring(0, i) to String(decode(raw), Charsets.UTF_8)
            }
            .toMap(LinkedHashMap())
    }.getOrDefault(LinkedHashMap())

    @Synchronized
    private fun writeAll(values: Map<String, String>): Boolean = runCatching {
        if (values.isEmpty()) {
            file.delete()
            return true
        }
        val dir = file.parentFile.apply { mkdirs() }
        restrictDir(dir)
        val tmp = File(dir, file.name + ".tmp")
        tmp.delete()
        createPrivate(tmp)
        tmp.writeText(
            values.entries.joinToString("\n", postfix = "\n") { (k, v) ->
                k + "=" + Base64.getEncoder().encodeToString(encode(v.toByteArray(Charsets.UTF_8)))
            },
        )
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        restrictFile(file)
        true
    }.getOrDefault(false)

    override fun get(key: String): String? = readAll()[key]

    override fun put(key: String, value: String): Boolean = writeAll(readAll().apply { put(key, value) })

    override fun delete(key: String) {
        val all = readAll()
        if (all.remove(key) != null) writeAll(all)
    }
}

/** Windows: the file holds DPAPI blobs, only decryptable by the same windows user. */
class DpapiSecretStore(file: File) : FileSecretStore(file) {
    override val name: String = "dpapi"
    override fun encode(plain: ByteArray): ByteArray = Crypt32Util.cryptProtectData(plain)
    override fun decode(stored: ByteArray): ByteArray = Crypt32Util.cryptUnprotectData(stored)
}

private val isPosix: Boolean = runCatching {
    java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix")
}.getOrDefault(false)

/** Creates [file] already 0600 so the secret is never briefly world-readable. */
private fun createPrivate(file: File) {
    if (isPosix) {
        Files.createFile(
            file.toPath(),
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")),
        )
    } else {
        file.createNewFile()
        restrictFile(file)
    }
}

fun restrictFile(file: File) {
    if (!file.exists()) return
    if (isPosix) {
        runCatching { Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString("rw-------")) }
    } else {
        file.setReadable(false, false)
        file.setReadable(true, true)
        file.setWritable(false, false)
        file.setWritable(true, true)
    }
}

fun restrictDir(dir: File) {
    if (!dir.isDirectory || Files.isSymbolicLink(dir.toPath())) return
    if (isPosix) {
        runCatching {
            if (Files.getOwner(dir.toPath(), LinkOption.NOFOLLOW_LINKS).name == System.getProperty("user.name")) {
                Files.setPosixFilePermissions(dir.toPath(), PosixFilePermissions.fromString("rwx------"))
            }
        }
    }
}
