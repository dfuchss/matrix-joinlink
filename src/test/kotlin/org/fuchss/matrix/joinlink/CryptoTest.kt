package org.fuchss.matrix.joinlink

import de.connect2x.trixnity.core.model.RoomId
import org.fuchss.matrix.joinlink.helper.decrypt
import org.fuchss.matrix.joinlink.helper.encrypt
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CryptoTest {
    /**
     * Test the encryption and decryption of a room id.
     */
    @Test
    fun testEnDeCrypt() {
        val config = Config("prefix", "test", "user", "password", "", listOf(), listOf(), "test123")
        val roomId = RoomId("!test:localhost")

        val encryptedRoomId = roomId.encrypt(config)
        val decryptedRoomId = encryptedRoomId.decrypt(config)

        assertEquals(roomId, decryptedRoomId)
    }
}
