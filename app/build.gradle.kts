import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

val versionMajor = 1
val versionMinor = 0
val versionPatch = 0
val versionBuild = System.getenv("BUILD_NUMBER")?.toIntOrNull() ?: 0

// ---- Cors.Connect service configuration -------------------------------------
// Values are resolved in this order: environment variable -> local.properties
// (gitignored, per-developer) -> a safe placeholder default.
// See CONFIGURATION.md for how to set these.
val localProperties = Properties().apply {
    val localPropsFile = rootProject.file("local.properties")
    if (localPropsFile.exists()) {
        localPropsFile.inputStream().use { load(it) }
    }
}
fun configValue(envName: String, propName: String, default: String): String =
    System.getenv(envName) ?: localProperties.getProperty(propName) ?: default

// Base URL of the instance-creation service.
val corsBaseUrl: String =
    configValue("CORS_BASE_URL", "CORS_BASE_URL", "https://example.invalid/replace-with-your-endpoint")
        .removeSuffix("/")
// Shared static secret the server expects in the X-App-Token header (WB_APP_TOKEN).
// Must be set via the CORS_APP_TOKEN env var or local.properties; there is no
// working default. CorsClient.isConfigured checks for this exact placeholder.
val corsAppToken: String = configValue("CORS_APP_TOKEN", "CORS_APP_TOKEN", "REPLACE_WITH_WB_APP_TOKEN")
// Telegram bot username the app opens to obtain initData for the claim flow.
val corsTgBot: String = configValue("CORS_TG_BOT", "CORS_TG_BOT", "REPLACE_WITH_TELEGRAM_BOT_USERNAME")
// ----------------------------------------------------------------------------

android {
    namespace = "bypass.whitelist"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "cc.cors.connect"
        minSdk = 23
        targetSdk = 36
        versionCode = 1_000_000 * versionMajor + 1_000 * versionMinor + versionPatch + versionBuild
        versionName = "$versionMajor.$versionMinor.$versionPatch"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "CORS_BASE_URL", "\"$corsBaseUrl\"")
        buildConfigField("String", "CORS_APP_TOKEN", "\"$corsAppToken\"")
        buildConfigField("String", "CORS_TG_BOT", "\"$corsTgBot\"")
    }

    buildFeatures {
        buildConfig = true
    }

    // AndroidManifest sets android:extractNativeLibs="true", so the packaging
    // must use legacy (uncompressed-free) packaging for native libs.
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    // No custom debug signingConfig: AGP's built-in "debug" config already
    // points at the auto-generated ~/.android/debug.keystore (well-known
    // "android"/"android" credentials), so nothing needs to be committed here.
    // NOTE: release currently reuses the debug signing config as a placeholder.
    // Before shipping a real release build, create your own release keystore
    // and signingConfig — see CONFIGURATION.md "Release signing" section.
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar"))))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.viewpager2)
    implementation(libs.androidx.recyclerview)
    implementation(libs.zxing.android.embedded)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
