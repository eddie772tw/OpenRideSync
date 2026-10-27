package org.openridesync.app.guides

import org.openridesync.honda.wire.HondaWire.GetModelSetupPageUrlRequest
import org.openridesync.honda.wire.HondaWire.GetModelTutorialPageUrlRequest
import java.net.URLDecoder

enum class GuideKind { SETUP, TUTORIAL }

data class VehicleGuide(
    val kind: GuideKind,
    val document: GuideDocument? = null,
    val operationId: String? = null,
)

internal fun setupRequest(label: String, languageTag: String): GetModelSetupPageUrlRequest =
    GetModelSetupPageUrlRequest.newBuilder().setModelLabel(label).setLanguageCode(languageTag).setVersion(2).build()

internal fun tutorialRequest(label: String, languageTag: String): GetModelTutorialPageUrlRequest =
    GetModelTutorialPageUrlRequest.newBuilder().setModelLabel(label).setLanguageCode(languageTag).setVersion(2).build()

internal fun setupTemplate(document: GuideDocument): GuideDocument {
    val query = GuideUrlPolicy.requireHttps(document.url).rawQuery.orEmpty().split('&')
    fun value(key: String): String {
        val raw = query.firstOrNull { it.substringBefore('=') == key }?.substringAfter('=', "").orEmpty()
        return buildString {
            for (character in URLDecoder.decode(raw, "UTF-8")) append(when (character) {
                '&' -> "&amp;"
                '<' -> "&lt;"
                '>' -> "&gt;"
                '\"' -> "&quot;"
                '\'' -> "&#39;"
                else -> character.toString()
            })
        }
    }
    return document.copy(html = document.html.replace("\$MODEL_NAME\$", value("model_name"))
        .replace("\$MODEL_IMAGE_SOURCE_NAME\$", value("model_image_source_name")))
}
