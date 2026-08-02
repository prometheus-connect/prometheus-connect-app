import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

val versionMajor = 1
val versionMinor = 0
val versionPatch = 2
val versionBuild = System.getenv("BUILD_NUMBER")?.toIntOrNull() ?: 0

// ---- Prometheus Connect service configuration --------------------------------
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

// Base URL of the instance-creation service. This points at the Yandex Cloud
// Function proxy rather than the backend directly: on a strict RU mobile
// whitelist tariff, functions.yandexcloud.net resolves before any tunnel
// exists and auth.prometheus.info.gf does not.
val pcBaseUrl: String =
    configValue("PC_BASE_URL", "PC_BASE_URL", "https://example.invalid/replace-with-your-endpoint")
        .removeSuffix("/")
// Shared static secret the server expects in the X-App-Token header.
// Must be set via the PC_APP_TOKEN env var or local.properties; there is no
// working default. CorsClient.isConfigured checks for this exact placeholder.
val pcAppToken: String = configValue("PC_APP_TOKEN", "PC_APP_TOKEN", "REPLACE_WITH_APP_TOKEN")
// Telegram bot username the app opens to obtain initData for the claim flow.
val pcTgBot: String = configValue("PC_TG_BOT", "PC_TG_BOT", "REPLACE_WITH_TELEGRAM_BOT_USERNAME")
// Host serving the Mini App callback and the App Links assetlinks.json.
val pcCallbackHost: String = configValue("PC_CALLBACK_HOST", "PC_CALLBACK_HOST", "auth.prometheus.info.gf")

// ---- Release signing ---------------------------------------------------------
// The keystore lives outside the repo. Without it, a release build falls back
// to the debug key — which would silently break App Link verification, since
// assetlinks.json pins the release certificate's SHA-256 fingerprint.
val releaseStoreFile: String = configValue("PC_KEYSTORE_FILE", "PC_KEYSTORE_FILE", "")
val releaseStorePassword: String = configValue("PC_KEYSTORE_PASSWORD", "PC_KEYSTORE_PASSWORD", "")
val releaseKeyAlias: String = configValue("PC_KEY_ALIAS", "PC_KEY_ALIAS", "")
val releaseKeyPassword: String = configValue("PC_KEY_PASSWORD", "PC_KEY_PASSWORD", "")
val hasReleaseSigning: Boolean = releaseStoreFile.isNotBlank() && file(releaseStoreFile).exists()
// ----------------------------------------------------------------------------

android {
    namespace = "bypass.whitelist"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "gf.info.prometheus.connect"
        minSdk = 23
        targetSdk = 36
        versionCode = 1_000_000 * versionMajor + 1_000 * versionMinor + versionPatch + versionBuild
        versionName = "$versionMajor.$versionMinor.$versionPatch"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "PC_BASE_URL", "\"$pcBaseUrl\"")
        buildConfigField("String", "PC_APP_TOKEN", "\"$pcAppToken\"")
        buildConfigField("String", "PC_TG_BOT", "\"$pcTgBot\"")
        // Host that serves /.well-known/assetlinks.json and /tginit. Kept in
        // one place so the manifest's App Link filter and TelegramAuth cannot
        // drift apart — a mismatch breaks the local interception silently.
        buildConfigField("String", "PC_CALLBACK_HOST", "\"$pcCallbackHost\"")
        manifestPlaceholders["pcCallbackHost"] = pcCallbackHost
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
    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseStoreFile)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Falls back to the debug key when no keystore is configured, so a
            // plain `assembleRelease` still works for local smoke tests. Such a
            // build will NOT pass App Link verification.
            signingConfig = if (hasReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                logger.warn("PC_KEYSTORE_FILE is not set — signing the release build with the debug key. " +
                        "App Links will not verify against assetlinks.json.")
                signingConfigs.getByName("debug")
            }
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
