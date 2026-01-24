package org.fuchss.matrix.joinlink.handler.command

import de.connect2x.trixnity.client.room.message.text
import de.connect2x.trixnity.clientserverapi.model.room.CreateRoom
import de.connect2x.trixnity.clientserverapi.model.room.DirectoryVisibility
import de.connect2x.trixnity.core.model.EventId
import de.connect2x.trixnity.core.model.RoomId
import de.connect2x.trixnity.core.model.UserId
import de.connect2x.trixnity.core.model.events.m.room.HistoryVisibilityEventContent
import de.connect2x.trixnity.core.model.events.m.room.PowerLevelsEventContent
import de.connect2x.trixnity.core.model.events.m.room.RoomMessageEventContent
import org.fuchss.matrix.bots.MatrixBot
import org.fuchss.matrix.bots.command.Command
import org.fuchss.matrix.bots.helper.ADMIN_POWER_LEVEL
import org.fuchss.matrix.bots.helper.canInvite
import org.fuchss.matrix.bots.helper.canSendStateEvents
import org.fuchss.matrix.bots.matrixTo
import org.fuchss.matrix.bots.syntaxOfRoomId
import org.fuchss.matrix.bots.toInternalRoomIdOrNull
import org.fuchss.matrix.joinlink.Config
import org.fuchss.matrix.joinlink.events.JoinLinkEventContent
import org.fuchss.matrix.joinlink.events.RoomToJoinEventContent
import org.fuchss.matrix.joinlink.helper.decrypt
import org.fuchss.matrix.joinlink.helper.encrypt

internal class LinkCommand(
    private val config: Config
) : Command() {
    override val name: String = "link"
    override val params: String = "[Link/ID to TargetRoom] {Readable Name of Link}"
    override val help: String = "create a join link for the room (if none provided, the room the message was sent in is used)"

    /**
     * Handle a link request. If the message originates from a user that is authorized, the bot tries to create a JoinLinkRoom (or uses an existing one).
     * If you provided an internal link to a room, the bot will use this room instead of the room the message was sent in.
     */
    override suspend fun execute(
        matrixBot: MatrixBot,
        sender: UserId,
        roomId: RoomId,
        parameters: String,
        textEventId: EventId,
        textEvent: RoomMessageEventContent.TextBased.Text
    ) {
        val possibleTargetRoomId = parameters.split(" ").first()
        val providedRoomId = possibleTargetRoomId.toInternalRoomIdOrNull(matrixBot)

        if (providedRoomId == null && possibleTargetRoomId.syntaxOfRoomId()) {
            logger.warn("Provided RoomId {} is not valid", possibleTargetRoomId)
            matrixBot.room().sendMessage(roomId) { text("Provided RoomId ($possibleTargetRoomId) is not valid") }
            return
        }

        val targetRoom = providedRoomId ?: roomId

        logger.info("Requested Link for $targetRoom")

        if (!hasPermissions(matrixBot, sender, roomId, targetRoom)) {
            return
        }

        val currentJoinLink =
            matrixBot
                .getStateEvent<JoinLinkEventContent>(targetRoom)
                ?.joinlinkRoom
                .decrypt(config)

        if (currentJoinLink != null) {
            matrixBot.room().sendMessage(roomId) { text("Link to share the Room (${targetRoom.matrixTo()}): ${currentJoinLink.matrixTo()}") }
            return
        }

        val nameOfLink = if (providedRoomId != null) parameters.substringAfter(" ").trim() else parameters.trim()

        if (nameOfLink.isBlank()) {
            matrixBot.room().sendMessage(roomId) { text("Please provide a name for the link") }
            return
        }

        // Create Join Room
        val joinLink =
            matrixBot
                .roomApi()
                .createRoom(
                    visibility = DirectoryVisibility.PRIVATE,
                    name = "Matrix Join Link '$nameOfLink'",
                    topic = "This is the Matrix Join Link Room called '$nameOfLink'. You can leave the room :)",
                    preset = CreateRoom.Request.Preset.PUBLIC,
                    powerLevelContentOverride =
                        PowerLevelsEventContent(
                            users = mapOf(matrixBot.self() to ADMIN_POWER_LEVEL),
                            events =
                                mapOf(
                                    RoomToJoinEventContent.ID to ADMIN_POWER_LEVEL,
                                    JoinLinkEventContent.ID to ADMIN_POWER_LEVEL
                                ),
                            eventsDefault = ADMIN_POWER_LEVEL,
                            stateDefault = ADMIN_POWER_LEVEL
                        )
                ).getOrThrow()

        // Set History Visibility to Joined to prevent others from seeing too much history
        matrixBot.roomApi().sendStateEvent(joinLink, HistoryVisibilityEventContent(HistoryVisibilityEventContent.HistoryVisibility.JOINED)).getOrThrow()

        logger.info("Create Room $joinLink for $targetRoom")
        val joinLinkEvent = JoinLinkEventContent(joinLink.encrypt(config))
        matrixBot.sendStateEvent(targetRoom, joinLinkEvent)

        val roomsToJoinEvent = RoomToJoinEventContent(targetRoom.encrypt(config))
        matrixBot.sendStateEvent(joinLink, roomsToJoinEvent)

        matrixBot.room().sendMessage(roomId) { text("Link to share the Room: ${joinLink.matrixTo()}") }
    }

    private suspend fun hasPermissions(
        matrixBot: MatrixBot,
        sender: UserId,
        roomId: RoomId,
        targetRoom: RoomId
    ): Boolean {
        // Check that the user is in the room
        if (!matrixBot
                .roomApi()
                .getJoinedMembers(targetRoom)
                .getOrThrow()
                .joined
                .containsKey(sender)
        ) {
            logger.info("User ${sender.full} is not in the room (${targetRoom.matrixTo()})")
            matrixBot.room().sendMessage(roomId) { text("You are not in the room (${targetRoom.matrixTo()})") }
            return false
        }

        // Check that user can invite
        if (!matrixBot.canInvite(targetRoom, sender)) {
            logger.info("User ${sender.full} is not allowed to invite users to this room (${targetRoom.matrixTo()})")
            matrixBot.room().sendMessage(roomId) { text("You are not allowed to invite users to this room (${targetRoom.matrixTo()})") }
            return false
        }

        // Check that bot can invite
        if (!matrixBot.canInvite(targetRoom)) {
            logger.debug("I am not allowed to invite users to this room (${targetRoom.matrixTo()})")
            matrixBot.room().sendMessage(roomId) { text("I am not allowed to invite users to this room (${targetRoom.matrixTo()})") }
            return false
        }

        // Check that bot can send state events
        if (!matrixBot.canSendStateEvents(targetRoom)) {
            logger.debug("I am not allowed to send state events to this room (${targetRoom.matrixTo()})")
            matrixBot.room().sendMessage(roomId) { text("I am not allowed to send state events to this room (${targetRoom.matrixTo()})") }
            return false
        }

        return true
    }
}
