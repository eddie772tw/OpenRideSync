package org.openridesync.app.guides

import org.junit.Assert.*
import org.junit.Test

class VehicleGuideTest {
    @Test fun fullLanguageTagAndVersionAreSentForBothDocumentKinds() {
        assertEquals("zh-Hant-TW", setupRequest("synthetic-model", "zh-Hant-TW").languageCode)
        assertEquals(2, setupRequest("synthetic-model", "zh-Hant-TW").version)
        assertEquals("synthetic-model", tutorialRequest("synthetic-model", "en-US").modelLabel)
        assertEquals("en-US", tutorialRequest("synthetic-model", "en-US").languageCode)
        assertEquals(2, tutorialRequest("synthetic-model", "en-US").version)
    }

    @Test fun setupUsesFirstQueryValueHtmlEscapingAndUnchangedBaseUri() {
        val document = GuideDocument("https://guides.example.invalid/setup?model_name=%3C%26%22%27%3E+A&model_name=ignored&model_image_source_name=CBR%2B500",
            "<h1>\$MODEL_NAME\$</h1><img alt='\$MODEL_IMAGE_SOURCE_NAME\$'><p>\$MODEL_NAME\$</p>")
        val rendered = setupTemplate(document)
        assertEquals(document.url, rendered.url)
        assertEquals("<h1>&lt;&amp;&quot;&#39;&gt; A</h1><img alt='CBR+500'><p>&lt;&amp;&quot;&#39;&gt; A</p>", rendered.html)
        assertEquals("<h1></h1>", setupTemplate(GuideDocument("https://guides.example.invalid/setup", "<h1>\$MODEL_NAME\$</h1>")).html)
    }
}
