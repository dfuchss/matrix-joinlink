package org.fuchss.matrix.joinlink.events

import de.connect2x.trixnity.core.model.events.EventType
import de.connect2x.trixnity.core.model.events.StateEventContent
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The content of a room to join event (present in a JoinLinkRoom).
 * @param[roomToJoin] The encrypted room id of the room to join.
 */
@Serializable
data class RoomToJoinEventContent(
    @SerialName("room_to_join") val roomToJoin: String? = null,
    @SerialName("external_url") override val externalUrl: String? = null
) : StateEventContent {
    companion object {
        val ID = EventType(RoomToJoinEventContent::class, "org.fuchss.matrix.room_to_join")
    }
}
