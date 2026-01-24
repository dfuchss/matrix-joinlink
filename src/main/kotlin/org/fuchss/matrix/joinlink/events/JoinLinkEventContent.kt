package org.fuchss.matrix.joinlink.events

import de.connect2x.trixnity.core.model.events.EventType
import de.connect2x.trixnity.core.model.events.StateEventContent
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The content of a joinlink event.
 * @param[joinlinkRoom] The encrypted room id of the public room (JoinLinkRoom).
 */
@Serializable
data class JoinLinkEventContent(
    @SerialName("joinlink_room") val joinlinkRoom: String? = null,
    @SerialName("external_url") override val externalUrl: String? = null
) : StateEventContent {
    companion object {
        val ID = EventType(JoinLinkEventContent::class, "org.fuchss.matrix.joinlink")
    }
}
