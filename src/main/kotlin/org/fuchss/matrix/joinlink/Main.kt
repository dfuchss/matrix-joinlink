package org.fuchss.matrix.joinlink

import de.connect2x.lognity.api.backend.Backend
import de.connect2x.lognity.backend.DefaultBackend
import de.connect2x.trixnity.client.MatrixClient
import de.connect2x.trixnity.client.create
import de.connect2x.trixnity.client.createTrixnityDefaultModuleFactories
import de.connect2x.trixnity.clientserverapi.client.MatrixClientAuthProviderData
import de.connect2x.trixnity.clientserverapi.client.classicLogin
import de.connect2x.trixnity.clientserverapi.model.authentication.IdentifierType
import de.connect2x.trixnity.core.model.events.m.room.MemberEventContent
import io.ktor.http.Url
import kotlinx.coroutines.runBlocking
import org.fuchss.matrix.bots.MatrixBot
import org.fuchss.matrix.bots.command.ChangeUsernameCommand
import org.fuchss.matrix.bots.command.Command
import org.fuchss.matrix.bots.command.HelpCommand
import org.fuchss.matrix.bots.command.LogoutCommand
import org.fuchss.matrix.bots.command.QuitCommand
import org.fuchss.matrix.bots.helper.createCryptoDriverModule
import org.fuchss.matrix.bots.helper.createMediaStoreModule
import org.fuchss.matrix.bots.helper.createRepositoriesModule
import org.fuchss.matrix.bots.helper.handleCommand
import org.fuchss.matrix.bots.helper.handleEncryptedCommand
import org.fuchss.matrix.joinlink.events.joinLinkModule
import org.fuchss.matrix.joinlink.handler.command.LinkCommand
import org.fuchss.matrix.joinlink.handler.command.UnlinkCommand
import org.fuchss.matrix.joinlink.handler.handleJoinsToMatrixJoinLinkRooms
import java.io.File
import kotlin.random.Random

private lateinit var commands: List<Command>

/**
 * The main function to start the bot.
 */
fun main() {
    // System.setProperty("lognity.default.level", Level.DEBUG.name)
    Backend.set(DefaultBackend)

    runBlocking {
        val config = Config.load()
        commands =
            listOf(
                HelpCommand(config, "JoinLink") {
                    commands
                },
                QuitCommand(),
                LogoutCommand(),
                ChangeUsernameCommand(),
                LinkCommand(config),
                UnlinkCommand(config)
            )

        val matrixClient = getMatrixClient(config)

        val matrixBot = MatrixBot(matrixClient, config)
        matrixBot.subscribeContent { event -> handleCommand(commands, event, matrixBot, config) }
        matrixBot.subscribeContent { event -> handleEncryptedCommand(commands, event, matrixBot, config) }
        matrixBot.subscribeContent<MemberEventContent> { event -> handleJoinsToMatrixJoinLinkRooms(event, event.content, matrixBot, config) }

        val loggedOut = matrixBot.startBlocking()
        if (loggedOut) {
            // Cleanup database
            val databaseFiles = listOf(File(config.dataDirectory + "/database.mv.db"), File(config.dataDirectory + "/database.trace.db"))
            databaseFiles.filter { it.exists() }.forEach { it.delete() }
        }
    }
}

private suspend fun getMatrixClient(config: Config): MatrixClient {
    val existingMatrixClient = MatrixClient.create(createRepositoriesModule(config), createMediaStoreModule(config), createCryptoDriverModule()).getOrNull()
    if (existingMatrixClient != null) {
        return existingMatrixClient
    }

    val matrixClient =
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
                        initialDeviceDisplayName = "${MatrixBot::class.java.`package`.name}-${Random.Default.nextInt()}"
                    ).getOrThrow()
            ) {
                modulesFactories = createTrixnityDefaultModuleFactories() + ::joinLinkModule
            }.getOrThrow()

    return matrixClient
}
