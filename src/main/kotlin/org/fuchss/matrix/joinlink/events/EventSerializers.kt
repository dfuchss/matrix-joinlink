package org.fuchss.matrix.joinlink.events

import de.connect2x.trixnity.core.serialization.events.EventContentSerializerMappings
import de.connect2x.trixnity.core.serialization.events.default
import de.connect2x.trixnity.core.serialization.events.invoke
import de.connect2x.trixnity.core.serialization.events.stateOf
import org.koin.dsl.module

private val joinLinkSerializationMapping =
    EventContentSerializerMappings {
        setOf(
            stateOf<JoinLinkEventContent>(JoinLinkEventContent.ID.name),
            stateOf<RoomToJoinEventContent>(RoomToJoinEventContent.ID.name)
        )
    }

/**
 * Koin module for the joinlink events.
 */
val joinLinkModule =
    module {
        single<EventContentSerializerMappings> {
            EventContentSerializerMappings.default + joinLinkSerializationMapping
        }
    }
