import java.util.Properties

/** Properties.load(InputStream) decodes ISO-8859-1 by spec — read as UTF-8
 *  instead so OWNER_NAME_HINT can contain Thai (and any non-Latin script). */
private fun loadLocalProperties(): Properties {
    val properties = Properties()
    val localPropertiesFile = project.rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        localPropertiesFile.inputStream().use { stream ->
            properties.load(stream.reader(Charsets.UTF_8))
        }
    }
    return properties
}

/** Escape a property value for embedding inside the generated Java string
 *  literal of BuildConfig (quotes/backslashes/newlines would otherwise break
 *  or corrupt the generated source). */
private fun escapeForBuildConfig(value: String): String =
    value.replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\r", "\\r")
        .replace("\n", "\\n")

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    id("org.jetbrains.kotlin.plugin.serialization") version "2.2.10"
}

android {
    namespace = "dev.nullphase.expense_ai"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "dev.nullphase.expense_ai"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            val properties = loadLocalProperties()
            buildConfigField("String", "GEMINI_API_KEY", "\"${escapeForBuildConfig(properties.getProperty("GEMINI_API_KEY") ?: "")}\"")
            buildConfigField("String", "OWNER_NAME_HINT", "\"${escapeForBuildConfig(properties.getProperty("OWNER_NAME_HINT") ?: "")}\"")
        }
        release {
            optimization {
                enable = false
            }
            buildConfigField("String", "GEMINI_API_KEY", "\"\"")
            buildConfigField("String", "OWNER_NAME_HINT", "\"\"")
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.google.ai.client)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}