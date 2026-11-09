package dev.jyotiraditya.dmt.platform.secrets

import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.Struct
import org.freedesktop.dbus.Tuple
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.annotations.Position
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.messages.DBusSignal
import org.freedesktop.dbus.types.Variant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

private const val BUS = "org.freedesktop.secrets"
private const val SERVICE_PATH = "/org/freedesktop/secrets"
private const val NO_PROMPT = "/"
private const val PROMPT_TIMEOUT_S = 120L
private const val APP = "tmpl"
private const val LEGACY_APP = "dmt"

class SecretValue(
    @field:Position(0) val session: DBusPath,
    @field:Position(1) val parameters: ByteArray,
    @field:Position(2) val value: ByteArray,
    @field:Position(3) val contentType: String,
) : Struct()

class OpenSessionResult(
    @field:Position(0) val output: Variant<*>,
    @field:Position(1) val result: DBusPath,
) : Tuple()

class SearchItemsResult(
    @field:Position(0) val unlocked: List<DBusPath>,
    @field:Position(1) val locked: List<DBusPath>,
) : Tuple()

class UnlockResult(
    @field:Position(0) val unlocked: List<DBusPath>,
    @field:Position(1) val prompt: DBusPath,
) : Tuple()

class CreateItemResult(
    @field:Position(0) val item: DBusPath,
    @field:Position(1) val prompt: DBusPath,
) : Tuple()

@DBusInterfaceName("org.freedesktop.Secret.Service")
interface SecretService : DBusInterface {
    fun OpenSession(algorithm: String, input: Variant<*>): OpenSessionResult
    fun SearchItems(attributes: Map<String, String>): SearchItemsResult
    fun Unlock(objects: List<DBusPath>): UnlockResult
    fun ReadAlias(name: String): DBusPath
}

@DBusInterfaceName("org.freedesktop.Secret.Collection")
interface SecretCollection : DBusInterface {
    fun CreateItem(properties: Map<String, Variant<*>>, secret: SecretValue, replace: Boolean): CreateItemResult
}

@DBusInterfaceName("org.freedesktop.Secret.Item")
interface SecretItem : DBusInterface {
    fun GetSecret(session: DBusPath): SecretValue
    fun Delete(): DBusPath
}

@DBusInterfaceName("org.freedesktop.Secret.Prompt")
interface SecretPrompt : DBusInterface {
    fun Prompt(windowId: String)

    class Completed(path: String, val dismissed: Boolean, val result: Variant<*>) :
        DBusSignal(path, dismissed, result)
}

/**
 * Freedesktop Secret Service client (gnome keyring, kwallet 5.97+, keepassxc). Items are
 * stored with attributes application=tmpl, key=<key>. A locked keyring triggers the desktop's
 * own unlock prompt.
 */
class SecretServiceStore private constructor(private val connection: DBusConnection) : SecretStore {

    override val name: String = "keyring"

    private val service: SecretService =
        connection.getRemoteObject(BUS, SERVICE_PATH, SecretService::class.java)

    private val session: DBusPath by lazy { service.OpenSession("plain", Variant("")).result }

    private fun attributes(key: String, app: String = APP) = mapOf("application" to app, "key" to key)

    @Synchronized
    override fun get(key: String): String? {
        read(attributes(key))?.let { return it }
        // saved before the rename (application=dmt): move it under tmpl
        val legacy = read(attributes(key, LEGACY_APP)) ?: return null
        if (put(key, legacy)) deleteMatching(attributes(key, LEGACY_APP))
        return legacy
    }

    private fun read(attributes: Map<String, String>): String? {
        val found = service.SearchItems(attributes)
        val items = found.unlocked + unlock(found.locked)
        val path = items.firstOrNull() ?: return null
        val item = connection.getRemoteObject(BUS, path.path, SecretItem::class.java)
        return String(item.GetSecret(session).value, Charsets.UTF_8)
    }

    @Synchronized
    override fun put(key: String, value: String): Boolean {
        val alias = service.ReadAlias("default")
        if (alias.path == NO_PROMPT) return false
        unlock(listOf(alias))
        val collection = connection.getRemoteObject(BUS, alias.path, SecretCollection::class.java)
        val props = mapOf<String, Variant<*>>(
            "org.freedesktop.Secret.Item.Label" to Variant("tmpl: $key"),
            "org.freedesktop.Secret.Item.Attributes" to Variant(attributes(key), "a{ss}"),
        )
        val secret = SecretValue(session, ByteArray(0), value.toByteArray(Charsets.UTF_8), "text/plain; charset=utf8")
        val result = collection.CreateItem(props, secret, true)
        if (result.prompt.path != NO_PROMPT && !prompt(result.prompt)) return false
        return read(attributes(key)) == value
    }

    @Synchronized
    override fun delete(key: String) {
        deleteMatching(attributes(key))
        deleteMatching(attributes(key, LEGACY_APP))
    }

    private fun deleteMatching(attributes: Map<String, String>) {
        val found = service.SearchItems(attributes)
        (found.unlocked + unlock(found.locked)).forEach { path ->
            val item = connection.getRemoteObject(BUS, path.path, SecretItem::class.java)
            val p = item.Delete()
            if (p.path != NO_PROMPT) prompt(p)
        }
    }

    private fun unlock(paths: List<DBusPath>): List<DBusPath> {
        if (paths.isEmpty()) return emptyList()
        val result = service.Unlock(paths)
        if (result.prompt.path == NO_PROMPT) return result.unlocked
        return if (prompt(result.prompt)) service.Unlock(paths).unlocked else emptyList()
    }

    /** Shows the keyring's own dialog and waits for the user; false if dismissed. */
    private fun prompt(path: DBusPath): Boolean {
        val done = CompletableFuture<Boolean>()
        val handler = connection.addSigHandler(SecretPrompt.Completed::class.java) { signal ->
            if (signal.path == path.path) done.complete(!signal.dismissed)
        }
        return try {
            connection.getRemoteObject(BUS, path.path, SecretPrompt::class.java).Prompt("")
            done.get(PROMPT_TIMEOUT_S, TimeUnit.SECONDS)
        } catch (_: Exception) {
            false
        } finally {
            runCatching { handler.close() }
        }
    }

    companion object {
        /** null when there is no session bus or no secret service on it. */
        fun connect(): SecretServiceStore? {
            if (System.getenv("DBUS_SESSION_BUS_ADDRESS").isNullOrBlank() &&
                System.getenv("XDG_RUNTIME_DIR").isNullOrBlank()
            ) {
                return null
            }
            val connection = DBusConnectionBuilder.forSessionBus().withShared(false).build()
            return try {
                SecretServiceStore(connection).also { store ->
                    store.service.ReadAlias("default") // fails fast when nothing provides the api
                    store.session
                }
            } catch (t: Throwable) {
                runCatching { connection.close() }
                null
            }
        }
    }
}
