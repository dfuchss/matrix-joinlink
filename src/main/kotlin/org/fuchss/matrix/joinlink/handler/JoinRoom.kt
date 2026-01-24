@file:OptIn(ExperimentalTime::class)

package org.fuchss.matrix.joinlink.handler

import de.connect2x.trixnity.client.room.message.text
import de.connect2x.trixnity.core.model.RoomId
import de.connect2x.trixnity.core.model.UserId
import de.connect2x.trixnity.core.model.events.ClientEvent
import de.connect2x.trixnity.core.model.events.idOrNull
import de.connect2x.trixnity.core.model.events.m.room.MemberEventContent
import de.connect2x.trixnity.core.model.events.m.room.Membership
import de.connect2x.trixnity.core.model.events.originTimestampOrNull
import de.connect2x.trixnity.core.model.events.roomIdOrNull
import de.connect2x.trixnity.core.model.events.senderOrNull
import org.fuchss.matrix.bots.MatrixBot
import org.fuchss.matrix.bots.helper.isAdminInRoom
import org.fuchss.matrix.bots.markdown
import org.fuchss.matrix.joinlink.Config
import org.fuchss.matrix.joinlink.events.JoinLinkEventContent
import org.fuchss.matrix.joinlink.events.RoomToJoinEventContent
import org.fuchss.matrix.joinlink.helper.decrypt
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

private val recentHandledJoinEventIdToTimestamp = ConcurrentHashMap<String, Long>()
private val userIdLocks = ConcurrentHashMap<UserId, Semaphore>()

private val logger: Logger = LoggerFactory.getLogger(MatrixBot::class.java)

/**
 * Handle a MemberEvent in a room where the bot is present.
 * If the bot has admin rights in the room, it will check if the user joined a JoinLinkRoom.
 * If so, it will send the user an invite to the room the JoinLinkRoom is linked to.
 * @param[event] The event to handle
 * @param[memberEventContent] The MemberEventContent of the MemberEvent.
 * @param[matrixBot] The bot to handle the MemberEvent.
 * @param[config] The config to use.
 */
internal suspend fun handleJoinsToMatrixJoinLinkRooms(
    event: ClientEvent<*>,
    memberEventContent: MemberEventContent,
    matrixBot: MatrixBot,
    config: Config
) {
    val roomId = event.roomIdOrNull
    val userId = event.senderOrNull

    if (roomId == null || userId == null) {
        return
    }

    // Only interested in Joins
    if (memberEventContent.membership != Membership.JOIN) {
        return
    }

    if (!matrixBot.self().isAdminInRoom(matrixBot, roomId)) {
        logger.debug("Skipping MemberEvent in {} because it's not a bot's room", roomId)
        return
    }

    // Cleanup recent list
    val now = Clock.System.now()
    recentHandledJoinEventIdToTimestamp
        .toMap()
        .filter {
            now - Instant.fromEpochMilliseconds(it.value) > 20.seconds
        }.forEach { recentHandledJoinEventIdToTimestamp.remove(it.key) }

    val eventId = event.idOrNull?.full ?: "$roomId-$userId"
    val originTimestamp = event.originTimestampOrNull ?: now.toEpochMilliseconds()

    try {
        acquireUserLock(userId)
        handleValidJoinEvent(eventId, originTimestamp, roomId, userId, matrixBot, config)
    } finally {
        releaseUserLock(userId)
    }
}

/**
 * Handle a MemberEvent in a room where the bot identifies a JoinLinkRoom.
 * @param[eventId] The EventId of the MemberEvent.
 * @param[originTimestamp] The originTimestamp of the MemberEvent.
 * @param[roomId] The roomId of the MemberEvent.
 * @param[userId] The userId of the MemberEvent.
 * @param[matrixBot] The bot to handle the MemberEvent.
 * @param[config] The config to use.
 */
private suspend fun handleValidJoinEvent(
    eventId: String,
    originTimestamp: Long,
    roomId: RoomId,
    userId: UserId,
    matrixBot: MatrixBot,
    config: Config
) {
    val roomToJoinState = matrixBot.getStateEvent<RoomToJoinEventContent>(roomId) ?: return
    if (roomToJoinState.roomToJoin.isNullOrEmpty()) {
        return
    }

    if (recentHandledJoinEventIdToTimestamp.putIfAbsent(eventId, originTimestamp) != null) {
        logger.debug("Skipping MemberEvent {} for user {} in {} because it was already handled", eventId, userId, roomId)
        return
    }

    logger.info("Inviting $userId to rooms because of JoinEvent to $roomId")

    val welcomeMessage =
        "" +
            "You've reached a MatrixJoinRoom. I'll invite you to the rooms ..\n" +
            "You can leave the room now :)"

    matrixBot.room().sendMessage(roomId) { text(welcomeMessage) }

    val roomToJoin = roomToJoinState.roomToJoin.decrypt(config)
    if (roomToJoin == null) {
        logger.debug("Skipping MemberEvent {} for user {} in {} because the link is broken", eventId, userId, roomId)
        return
    }

    // Validate RoomsToJoin ..
    val currentJoinLink =
        matrixBot
            .getStateEvent<JoinLinkEventContent>(roomToJoin)
            ?.joinlinkRoom
            .decrypt(config)
    if (currentJoinLink != roomId) {
        logger.error("I will not invite $userId to $roomToJoin because the link to $roomId is broken ($currentJoinLink <-> $roomId). Skipping ..")
        return
    }

    try {
        val joinedMembers = matrixBot.roomApi().getJoinedMembers(roomToJoin).getOrNull() ?: return
        if (joinedMembers.joined.containsKey(userId)) {
            logger.debug("Skipping MemberEvent {} for user {} in {} because the user is already in the room", eventId, userId, roomId)
            return
        }
        matrixBot.roomApi().inviteUser(roomToJoin, userId, reason = "Join via MatrixJoinLink").getOrThrow()
    } catch (e: Exception) {
        logger.error(e.message, e)
        matrixBot.room().sendMessage(roomToJoin) { markdown("**I could not invite $userId to the room. Please invite them manually.**") }
        matrixBot.room().sendMessage(roomId) {
            markdown("**I could not invite you to the room. Please ask the person who gave you the link that they can invite you manually.**")
        }
    }
}

private fun acquireUserLock(userId: UserId) {
    var lock = userIdLocks[userId]
    if (lock == null) {
        val newLock = Semaphore(1)
        val actualLock = userIdLocks.putIfAbsent(userId, newLock)
        lock = actualLock ?: newLock
    }
    lock.acquireUninterruptibly()
}

private fun releaseUserLock(userId: UserId) = userIdLocks[userId]?.release()
