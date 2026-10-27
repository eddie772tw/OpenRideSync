package org.openridesync.app.auth

import android.content.Context
import org.json.JSONObject

/** Only public client identifiers belong here; never credentials or account data. */
internal data class AuthClientConfig(
    val projectId: String, val apiKey: String, val applicationId: String,
    val webClientId: String, val authDomain: String, val webApiKey: String,
    val hondaApiKey: String?
) {
    fun browserJson(): String = JSONObject().put("apiKey", webApiKey)
        .put("authDomain", authDomain).put("projectId", projectId).toString()

    companion object {
        fun load(context: Context): AuthClientConfig {
            val bytes = context.assets.open("auth-client.json").use { input ->
                val buffer = ByteArray(16385)
                var size = 0
                while (size < buffer.size) {
                    val count = input.read(buffer, size, buffer.size - size)
                    if (count == -1) break
                    size += count
                }
                require(size <= 16384)
                buffer.copyOf(size)
            }
            val json = JSONObject(bytes.toString(Charsets.UTF_8))
            val required = setOf("projectId", "apiKey", "applicationId", "webClientId", "authDomain", "webApiKey")
            require(json.keys().asSequence().all { it in required || it == "hondaApiKey" })
            required.forEach { require(json.get(it) is String) }
            val config = AuthClientConfig(json.getString("projectId"), json.getString("apiKey"),
                json.getString("applicationId"), json.getString("webClientId"), json.getString("authDomain"), json.getString("webApiKey"),
                if (json.has("hondaApiKey")) json.getString("hondaApiKey").also { require(it.matches(Regex("[A-Za-z0-9_-]{1,256}"))) } else null)
            require(config.projectId.matches(Regex("[a-z][a-z0-9-]{4,28}[a-z0-9]")))
            require(config.authDomain == "${config.projectId}.firebaseapp.com")
            require(config.apiKey.matches(Regex("AIza[0-9A-Za-z_-]{35}")))
            require(config.webApiKey.matches(Regex("AIza[0-9A-Za-z_-]{35}")))
            val number = Regex("1:([0-9]+):android:[0-9a-f]+").matchEntire(config.applicationId)?.groupValues?.get(1)
            require(number != null && config.webClientId.matches(Regex("${number}-[a-z0-9]+\\.apps\\.googleusercontent\\.com")))
            return config
        }
    }
}
