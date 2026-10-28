import groovy.json.JsonSlurper
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

abstract class PrepareHondaClientConfig : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val configFile: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun prepare() {
        val config = configFile.get().asFile
        val bytes = config.inputStream().use { it.readNBytes(16385) }
        check(bytes.size <= 16384) { "Private auth-client.json exceeds the supported size" }
        val contents = try { JsonSlurper().parseText(bytes.toString(Charsets.UTF_8)) as? Map<*, *> }
            catch (_: Exception) { error("Invalid private auth-client.json") }
        val required = setOf("projectId", "apiKey", "applicationId", "webClientId", "authDomain", "webApiKey")
        check(contents != null && required.all { contents[it] is String && (contents[it] as String).isNotBlank() } &&
            contents.keys.all { it is String && (it in required || it == "hondaApiKey") } &&
            (!contents.containsKey("hondaApiKey") || contents["hondaApiKey"] is String)) {
            "Private auth-client.json must contain only the reviewed client-configuration fields"
        }
        val output = outputDirectory.file("auth-client.json").get().asFile
        output.parentFile.mkdirs()
        output.writeBytes(bytes)
    }
}

val sourceCommit = providers.gradleProperty("sourceCommit").getOrElse("unknown")
require(sourceCommit.matches(Regex("[0-9a-f]{7,40}|unknown"))) { "sourceCommit must be a Git SHA or unknown" }

android {
    namespace = "org.openridesync.app"
    compileSdk = 36
    defaultConfig {
        applicationId = "org.openridesync.app"
        minSdk = 30
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        versionCode = 2
        versionName = "0.1.0-alpha.1"
        buildConfigField("String", "SOURCE_COMMIT", "\"$sourceCommit\"")
        buildConfigField("boolean", "HONDA_CONFIGURED", providers.gradleProperty("withHondaConfig").getOrElse("false").toBooleanStrict().toString())
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true; buildConfig = true }
    testOptions.unitTests.isIncludeAndroidResources = true
    val releaseStoreFile = providers.gradleProperty("releaseStoreFile")
        .orElse(providers.environmentVariable("RELEASE_STORE_FILE")).orNull
    val releaseStorePassword = providers.gradleProperty("releaseStorePassword")
        .orElse(providers.environmentVariable("RELEASE_STORE_PASSWORD")).orNull
    val releaseKeyAlias = providers.gradleProperty("releaseKeyAlias")
        .orElse(providers.environmentVariable("RELEASE_KEY_ALIAS")).orNull
    val releaseKeyPassword = providers.gradleProperty("releaseKeyPassword")
        .orElse(providers.environmentVariable("RELEASE_KEY_PASSWORD")).orNull
    signingConfigs {
        if (!releaseStoreFile.isNullOrBlank()) {
            create("release") {
                val keystore = file(releaseStoreFile)
                check(keystore.isFile) { "Release keystore file does not exist" }
                storeFile = keystore
                storePassword = checkNotNull(releaseStorePassword) { "Missing releaseStorePassword" }
                keyAlias = checkNotNull(releaseKeyAlias) { "Missing releaseKeyAlias" }
                keyPassword = checkNotNull(releaseKeyPassword) { "Missing releaseKeyPassword" }
            }
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            isShrinkResources = false
            if (signingConfigs.findByName("release") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    if (providers.gradleProperty("withHondaConfig").getOrElse("false") == "true") {
        val config = providers.gradleProperty("hondaConfigFile").orNull?.let { file(it) }
        check(config != null && config.isFile) {
            "Prepare private auth-client.json before enabling withHondaConfig; pass -PhondaConfigFile=<private-file>"
        }
        val prepareConfig = tasks.register<PrepareHondaClientConfig>("prepareHondaClientConfig") {
            configFile.set(config)
            outputDirectory.set(layout.buildDirectory.dir("generated/privateAuthAssets"))
        }
        androidComponents.onVariants { variant ->
            variant.sources.assets?.addGeneratedSourceDirectory(prepareConfig) { it.outputDirectory }
        }
    }
    check(providers.gradleProperty("withOemUiAssets").getOrElse("false") != "true") {
        "OEM assets are excluded from this public repository"
    }
}
kotlin { jvmToolchain(17) }
dependencies {
    implementation(project(":diagnostics"))
    implementation(project(":honda"))
    implementation(project(":auth-bridge"))
    implementation(libs.okhttp)
    implementation("com.caverock:androidsvg-aar:1.4")
    implementation(platform("com.google.firebase:firebase-bom:34.19.0"))
    implementation("com.google.firebase:firebase-auth")
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    testImplementation(libs.junit)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test:core:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
}
