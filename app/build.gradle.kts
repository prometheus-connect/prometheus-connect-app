import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

val versionMajor = 1
val versionMinor = 3
val versionPatch = 7
val versionBuild = System.getenv("BUILD_NUMBER")?.toIntOrNull() ?: 0

// versionCode is deliberately NOT derived from the version name. Android
// refuses to install a package whose versionCode is lower than the installed
// one, and the pre-release test builds already shipped 1001005 as "1.1.5".
// Deriving it would have produced 1000000 for this release and forced everyone
// who tested to uninstall first. Keep it a plain monotonic counter: bump it by
// one for every build that leaves this machine, whatever the version name says.
val versionCodeCounter = 1001021

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
// Backend reached directly. Used only when the Cloud Function proxy itself is
// broken — it cannot be the default, because on a strict whitelist tariff this
// host does not resolve until a tunnel already exists.
val pcFallbackBaseUrl: String =
    configValue("PC_FALLBACK_BASE_URL", "PC_FALLBACK_BASE_URL", "https://auth.prometheus.info.gf")
        .removeSuffix("/")
// Published Yandex Disk folder advertising the pre-minted call pool. Read
// anonymously through cloud-api.yandex.net, which — unlike the minting API —
// passes a strict whitelist tariff. This is the only bootstrap that works when
// the operator is filtering; see PoolClient.
val pcPoolPublicKey: String =
    configValue("PC_POOL_PUBLIC_KEY", "PC_POOL_PUBLIC_KEY", "REPLACE_WITH_POOL_PUBLIC_KEY")
// Published rule blob for split routing, and which profile it holds. Served
// from the same whitelist-reachable Disk as the pool, compiled server-side to
// ~540 KB from upstream lists that would otherwise be 88 MB.
val pcRoutingPublicKey: String =
    configValue("PC_ROUTING_PUBLIC_KEY", "PC_ROUTING_PUBLIC_KEY", "REPLACE_WITH_ROUTING_PUBLIC_KEY")
val pcRoutingProfile: String =
    configValue("PC_ROUTING_PROFILE", "PC_ROUTING_PROFILE", "ru-blocked")
// A few hundred bytes carrying the upstream revision, so the app can tell
// whether the 5 MB blob is worth downloading. Upstream rebuilds daily.
val pcRoutingManifestKey: String =
    configValue("PC_ROUTING_MANIFEST_KEY", "PC_ROUTING_MANIFEST_KEY", "REPLACE_WITH_MANIFEST_KEY")
// Published folder holding the same upstream lists cut up one category per
// entry, so a user rule naming geosite:x or geoip:x can be honoured. Separate
// from the profile blob above: that is one compiled decision set, this is the
// raw catalogue the decisions were compiled from.
val pcRoutingCatalogueKey: String =
    configValue("PC_ROUTING_CATALOGUE_KEY", "PC_ROUTING_CATALOGUE_KEY", "REPLACE_WITH_CATALOGUE_KEY")

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
        versionCode = versionCodeCounter + versionBuild
        versionName = "$versionMajor.$versionMinor.$versionPatch"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "PC_BASE_URL", "\"$pcBaseUrl\"")
        buildConfigField("String", "PC_APP_TOKEN", "\"$pcAppToken\"")
        buildConfigField("String", "PC_TG_BOT", "\"$pcTgBot\"")
        // Host that serves /.well-known/assetlinks.json and /tginit. Kept in
        // one place so the manifest's App Link filter and TelegramAuth cannot
        // drift apart — a mismatch breaks the local interception silently.
        buildConfigField("String", "PC_CALLBACK_HOST", "\"$pcCallbackHost\"")
        buildConfigField("String", "PC_FALLBACK_BASE_URL", "\"$pcFallbackBaseUrl\"")
        buildConfigField("String", "PC_POOL_PUBLIC_KEY", "\"$pcPoolPublicKey\"")
        buildConfigField("String", "PC_ROUTING_PUBLIC_KEY", "\"$pcRoutingPublicKey\"")
        buildConfigField("String", "PC_ROUTING_PROFILE", "\"$pcRoutingProfile\"")
        buildConfigField("String", "PC_ROUTING_MANIFEST_KEY", "\"$pcRoutingManifestKey\"")
        buildConfigField("String", "PC_ROUTING_CATALOGUE_KEY", "\"$pcRoutingCatalogueKey\"")
        manifestPlaceholders["pcCallbackHost"] = pcCallbackHost
    }

    buildFeatures {
        buildConfig = true
    }

    // android.util.Log is a stub in unit tests and throws by default. The
    // routing server logs, and a throwing logger would fail tests for a reason
    // that has nothing to do with what they check.
    testOptions {
        unitTests.isReturnDefaultValues = true
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
    testImplementation(libs.json)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
