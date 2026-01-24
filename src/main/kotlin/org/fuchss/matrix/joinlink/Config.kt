package org.fuchss.matrix.joinlink

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.fuchss.matrix.bots.IConfig
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.File

/**
 * This is the configuration template of the mensa bot.
 * @param[prefix] the command prefix the bot listens to. By default, "mensa"
 * @param[baseUrl] the base url of the matrix server the bot shall use
 * @param[username] the username of the bot's account
 * @param[password] the password of the bot's account
 * @param[dataDirectory] the path to the databases and media folder
 * @param[admins] the matrix ids of the admins. E.g. "@user:invalid.domain"
 * @param[users] the matrix ids of the authorized users or servers. E.g. "@user:invalid.domain" or ":invalid.domain"
 * @param[encryptionKey] a symmetric key that will be used to encrypt the event content for the bot
 */
data class Config(
    @param:JsonProperty override val prefix: String = "join",
    @param:JsonProperty override val baseUrl: String,
    @param:JsonProperty override val username: String,
    @param:JsonProperty override val password: String,
    @param:JsonProperty override val dataDirectory: String,
    @param:JsonProperty override val admins: List<String>,
    @param:JsonProperty override val users: List<String>,
    @param:JsonProperty val encryptionKey: String
) : IConfig {
    companion object {
        private val log: Logger = LoggerFactory.getLogger(Config::class.java)

        /**
         * Load the config from the file path. You can set "CONFIG_PATH" in the environment to override the default location ("./config.json").
         */
        fun load(): Config {
            val configPath = System.getenv("CONFIG_PATH") ?: "./config.json"
            val configFile = File(configPath)
            if (!configFile.exists()) {
                error("Config ${configFile.absolutePath} does not exist!")
            }

            val config: Config = ObjectMapper().registerKotlinModule().registerModule(JavaTimeModule()).readValue(configFile)
            log.info("Loaded config ${configFile.absolutePath}")
            config.validate()
            return config
        }
    }

    override fun validate() {
        super.validate()
        if (encryptionKey.isBlank()) {
            error("Please verify that encryptionKey is not null!")
        }
    }
}
