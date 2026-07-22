package org.fuchss.matrix.joinlink

import de.connect2x.lognity.api.backend.Backend
import de.connect2x.lognity.backend.DefaultBackend
import de.connect2x.trixnity.client.MatrixClient
import de.connect2x.trixnity.client.create
import de.connect2x.trixnity.client.createTrixnityDefaultModuleFactories
import de.connect2x.trixnity.clientserverapi.client.MatrixClientAuthProviderData
import de.connect2x.trixnity.clientserverapi.client.classicLogin
import de.connect2x.trixnity.clientserverapi.model.authentication.IdentifierType
import de.connect2x.trixnity.core.model.RoomId
import de.connect2x.trixnity.core.serialization.events.UnsupportedEventContentTypeException
import io.ktor.http.Url
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.fuchss.matrix.bots.helper.createCryptoDriverModule
import org.fuchss.matrix.bots.helper.createMediaStoreModule
import org.fuchss.matrix.bots.helper.createRepositoriesModule
import org.fuchss.matrix.joinlink.events.RoomToJoinEventContent
import org.fuchss.matrix.joinlink.events.joinLinkModule
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.io.path.createTempDirectory

/**
 * A [tuwunel](https://github.com/matrix-construct/tuwunel) Matrix homeserver started via Testcontainers.
 * A subclass is used so the recursive-generic `withXxx(..)` builder methods return the concrete type.
 */
private class TuwunelContainer : GenericContainer<TuwunelContainer>(DockerImageName.parse("ghcr.io/matrix-construct/tuwunel:latest"))

/**
 * Integration test that reproduces the production bug where the bot cannot handle its custom state events after a
 * restart:
 *
 * ```
 * de.connect2x.trixnity.core.serialization.events.UnsupportedEventContentTypeException:
 *   Event content type class org.fuchss.matrix.joinlink.events.RoomToJoinEventContent is not supported.
 *   If it is a custom type, you should register it!
 * ```
 *
 * The root cause lives in [org.fuchss.matrix.joinlink.MainKt] (`getMatrixClient`): the [joinLinkModule] – which
 * registers the serializers for [RoomToJoinEventContent] and friends – is only wired in on the very first login.
 * When an existing client is restored from the local database (every subsequent start), the module is dropped and the
 * custom event types are no longer known to Trixnity.
 *
 * These tests drive a real Matrix homeserver ([tuwunel]):
 *  1. [restoredClientCannotSerializeCustomEvent] reproduces the bug at the Trixnity level – a client restored
 *     *without* the [joinLinkModule] fails to send a [RoomToJoinEventContent] with [UnsupportedEventContentTypeException].
 *  2. [restartAppliesJoinLinkModuleAndCanSerializeCustomEvent] drives the *real* [getMatrixClient] across a restart
 *     and asserts that – with the module applied on the restore path – the restored client can send and read the
 *     custom event again.
 */
@Testcontainers
class RoomToJoinEventContainerTest {
    companion object {
        private const val TUWUNEL_PORT = 8008
        private const val SERVER_NAME = "localhost"

        private const val BOT_PASSWORD = "super-secret-password-1337"

        @Container
        @JvmStatic
        private val tuwunel: TuwunelContainer =
            TuwunelContainer()
                .withExposedPorts(TUWUNEL_PORT)
                .withEnv("TUWUNEL_SERVER_NAME", SERVER_NAME)
                .withEnv("TUWUNEL_ADDRESS", "0.0.0.0")
                .withEnv("TUWUNEL_PORT", TUWUNEL_PORT.toString())
                .withEnv("TUWUNEL_ALLOW_REGISTRATION", "true")
                .withEnv("TUWUNEL_YES_I_AM_VERY_VERY_SURE_I_WANT_AN_OPEN_REGISTRATION_SERVER_PRONE_TO_ABUSE", "true")
                .withEnv("TUWUNEL_ALLOW_FEDERATION", "false")
                .waitingFor(Wait.forHttp("/_matrix/client/versions").forPort(TUWUNEL_PORT).forStatusCode(200))
                .withStartupTimeout(Duration.ofMinutes(3))
    }

    private lateinit var dataDirectory: File

    // A unique user per test avoids collisions on the container that is shared across all tests.
    private lateinit var botUser: String

    private val baseUrl: String
        get() = "http://${tuwunel.host}:${tuwunel.getMappedPort(TUWUNEL_PORT)}"

    private fun config() =
        Config(
            prefix = "join",
            baseUrl = baseUrl,
            username = botUser,
            password = BOT_PASSWORD,
            dataDirectory = dataDirectory.absolutePath,
            admins = listOf("@$botUser:$SERVER_NAME"),
            users = emptyList(),
            encryptionKey = "test-encryption-key"
        )

    @BeforeEach
    fun setup() {
        Backend.set(DefaultBackend)
        dataDirectory = createTempDirectory("joinlink-it").toFile()
        botUser = "joinlinkbot-${System.nanoTime()}"
        registerBotUser()
    }

    @AfterEach
    fun cleanup() {
        dataDirectory.deleteRecursively()
    }

    /**
     * The initial login registers [joinLinkModule], so the custom [RoomToJoinEventContent] can be sent. After a
     * restart the client is restored from the database without that module – exactly what happens in production – and
     * sending the same event now fails with [UnsupportedEventContentTypeException].
     */
    @Test
    fun restoredClientCannotSerializeCustomEvent() =
        runBlocking {
            val config = config()

            // ---- 1. Initial login (mirrors the login path of Main.getMatrixClient) ----
            val roomId: RoomId
            loginClient(config).use { client ->
                roomId =
                    client.api.room
                        .createRoom(name = "join link room")
                        .getOrThrow()

                // With the joinLinkModule registered this succeeds.
                client.api.room
                    .sendStateEvent(roomId, RoomToJoinEventContent(roomToJoin = "encrypted-room-id"))
                    .getOrThrow()
            }

            // ---- 2. Restart: restore the existing client (mirrors the restore path of Main.getMatrixClient) ----
            val restored = restoreClient(config)
            assertNotNull(restored, "The existing client should be restored from the database after a restart")

            restored!!.use { client ->
                // Without the joinLinkModule the custom type is unknown -> UnsupportedEventContentTypeException.
                val result =
                    runCatching {
                        client.api.room
                            .sendStateEvent(roomId, RoomToJoinEventContent(roomToJoin = "encrypted-room-id"))
                            .getOrThrow()
                    }

                val error = result.exceptionOrNull()
                assertNotNull(error, "Sending a RoomToJoinEventContent without the joinLinkModule must fail")

                val unsupported =
                    generateSequence(error) { it.cause }
                        .filterIsInstance<UnsupportedEventContentTypeException>()
                        .firstOrNull()
                assertNotNull(
                    unsupported,
                    "Expected an UnsupportedEventContentTypeException in the cause chain but was: $error"
                )
                assertTrue(
                    unsupported!!.message?.contains(RoomToJoinEventContent::class.qualifiedName!!) == true,
                    "Exception should mention the unregistered RoomToJoinEventContent type but was: ${unsupported.message}"
                )
            }
        }

    /**
     * Companion to [restoredClientCannotSerializeCustomEvent], driving the *real* [getMatrixClient] of `Main.kt`:
     * the first call logs in (fresh database), the second call restores the existing client – exactly as a bot
     * restart does. Once the restore path also wires in the [joinLinkModule], the restored client can send and read
     * the custom [RoomToJoinEventContent] again.
     */
    @Test
    fun restartAppliesJoinLinkModuleAndCanSerializeCustomEvent() =
        runBlocking {
            val config = config()

            // First start: fresh login, creates the local database.
            val roomId: RoomId
            getMatrixClient(config).use { client ->
                roomId =
                    client.api.room
                        .createRoom(name = "join link room")
                        .getOrThrow()
                client.api.room
                    .sendStateEvent(roomId, RoomToJoinEventContent(roomToJoin = "encrypted-room-id"))
                    .getOrThrow()
            }

            // Second start: the client is restored from the database (the restart path of getMatrixClient).
            getMatrixClient(config).use { client ->
                // With the fix the joinLinkModule is applied on restore, so sending the custom event succeeds again.
                client.api.room
                    .sendStateEvent(roomId, RoomToJoinEventContent(roomToJoin = "another-encrypted-room-id"))
                    .getOrThrow()

                // And the state event is deserialized back into the typed content instead of an unknown type.
                val content =
                    client.api.room
                        .getStateEventContent(RoomToJoinEventContent.ID.name, roomId)
                        .getOrThrow()
                assertTrue(
                    content is RoomToJoinEventContent,
                    "Expected a typed RoomToJoinEventContent after restore but was ${content::class.qualifiedName}"
                )
            }
        }

    /** Login path of `Main.getMatrixClient`: a fresh client with the [joinLinkModule] wired into the module factories. */
    private suspend fun loginClient(config: Config): MatrixClient =
        MatrixClient
            .create(
                createRepositoriesModule(config),
                createMediaStoreModule(config),
                createCryptoDriverModule(),
                MatrixClientAuthProviderData
                    .classicLogin(
                        baseUrl = Url(config.baseUrl),
                        identifier = IdentifierType.User(config.username),
                        password = config.password,
                        initialDeviceDisplayName = "MatrixJoinLink-IntegrationTest"
                    ).getOrThrow()
            ) {
                modulesFactories = createTrixnityDefaultModuleFactories() + ::joinLinkModule
            }.getOrThrow()

    /** Restore path of `Main.getMatrixClient`: an existing client loaded from the database *without* the [joinLinkModule]. */
    private suspend fun restoreClient(config: Config): MatrixClient? =
        MatrixClient
            .create(
                createRepositoriesModule(config),
                createMediaStoreModule(config),
                createCryptoDriverModule()
            ).getOrThrow()

    /** Registers the bot user via the client-server API, completing the `m.login.dummy` UIA stage. */
    private fun registerBotUser() {
        val http = HttpClient.newHttpClient()
        val endpoint = URI.create("$baseUrl/_matrix/client/v3/register")

        fun post(body: String): HttpResponse<String> =
            http.send(
                HttpRequest
                    .newBuilder(endpoint)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            )

        // Step 1: initial request to obtain the UIA session id.
        val initial =
            post(
                buildJsonObject {
                    put("username", botUser)
                    put("password", BOT_PASSWORD)
                    put("inhibit_login", true)
                }.toString()
            )

        if (initial.statusCode() == 200) {
            return
        }
        check(initial.statusCode() == 401) { "Unexpected register response ${initial.statusCode()}: ${initial.body()}" }

        val session =
            Json
                .parseToJsonElement(initial.body())
                .jsonObject["session"]!!
                .jsonPrimitive.content

        // Step 2: complete the dummy auth stage with the obtained session.
        val completed =
            post(
                buildJsonObject {
                    put("username", botUser)
                    put("password", BOT_PASSWORD)
                    put("inhibit_login", true)
                    putJsonObject("auth") {
                        put("type", "m.login.dummy")
                        put("session", session)
                    }
                }.toString()
            )
        check(completed.statusCode() == 200) { "Registration failed ${completed.statusCode()}: ${completed.body()}" }
    }
}
