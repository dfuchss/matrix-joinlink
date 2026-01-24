package org.fuchss.matrix.joinlink.handler.command

import de.connect2x.trixnity.client.room.message.text
import de.connect2x.trixnity.core.model.EventId
import de.connect2x.trixnity.core.model.RoomId
import de.connect2x.trixnity.core.model.UserId
import de.connect2x.trixnity.core.model.events.m.room.Membership
import de.connect2x.trixnity.core.model.events.m.room.RoomMessageEventContent
import org.fuchss.matrix.bots.MatrixBot
import org.fuchss.matrix.bots.command.Command
import org.fuchss.matrix.bots.helper.canInvite
import org.fuchss.matrix.bots.matrixTo
import org.fuchss.matrix.bots.syntaxOfRoomId
import org.fuchss.matrix.bots.toInternalRoomIdOrNull
import org.fuchss.matrix.joinlink.Config
import org.fuchss.matrix.joinlink.events.JoinLinkEventContent
import org.fuchss.matrix.joinlink.events.RoomToJoinEventContent
import org.fuchss.matrix.joinlink.helper.decrypt

internal class UnlinkCommand(
    private val config: Config
) : Command() {
    override val name: String = "unlink"
    override val params: String = "[Link/ID to TargetRoom]"
    override val help: String = "remove all join links for the room"

    /**
     * Unlink a Matrix Join Link Room. If you provided an internal link to a room, the bot will use this room instead of the room the message was sent in.
     */
    override suspend fun execute(
        matrixBot: MatrixBot,
        sender: UserId,
        roomId: RoomId,
        parameters: String,
        textEventId: EventId,
        textEvent: RoomMessageEventContent.TextBased.Text
    ) {
        val possibleTargetRoomId = parameters.trim()
        val providedRoomId = possibleTargetRoomId.toInternalRoomIdOrNull(matrixBot)
        if (providedRoomId == null && possibleTargetRoomId.syntaxOfRoomId()) {
            logger.warn("Provided RoomId {} is not valid", possibleTargetRoomId)
            matrixBot.room().sendMessage(roomId) { text("Provided RoomId ($possibleTargetRoomId) is not valid") }
            return
        }

        val targetRoom = providedRoomId ?: roomId

        logger.info("Requested Unlink for $targetRoom")

        // Bot Admins are allowed to unlink rooms. Check permissions for non-bot-admins.
        if (!config.isBotAdmin(sender)) {
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
                return
            }

            // Check that the user is allowed to invite users to the room
            if (!matrixBot.canInvite(targetRoom, sender)) {
                logger.info("User ${sender.full} is not allowed to invite users to the room (${targetRoom.matrixTo()})")
                matrixBot.room().sendMessage(roomId) {
                    text("You are not allowed to invite users to this room (${targetRoom.matrixTo()}). Therefore, you cannot remove a join link.")
                }
                return
            }
        }

        val currentJoinLink =
            matrixBot
                .getStateEvent<JoinLinkEventContent>(targetRoom)
                ?.joinlinkRoom
                .decrypt(config)

        if (currentJoinLink == null) {
            matrixBot.room().sendMessage(roomId) { text("No Matrix Join Links available .. nothing to do ..") }
            return
        }

        matrixBot.sendStateEvent(targetRoom, JoinLinkEventContent())
        matrixBot.sendStateEvent(currentJoinLink, RoomToJoinEventContent())

        val usersOfRoom = matrixBot.roomApi().getMembers(currentJoinLink).getOrThrow()
        for (user in usersOfRoom) {
            if (user.sender.full == matrixBot.self().full || user.content.membership != Membership.JOIN) {
                continue
            }
            matrixBot.roomApi().banUser(currentJoinLink, user.sender, reason = "Matrix Join Link invalidated").getOrThrow()
        }

        matrixBot.roomApi().leaveRoom(currentJoinLink, reason = "Matrix Join Link invalidated")
        matrixBot.room().sendMessage(roomId) { text("Unlinked the Room") }
    }
}
