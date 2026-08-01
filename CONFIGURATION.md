# Local configuration

This repo has been cleaned of hardcoded secrets before being made public. Nothing
in `app/build.gradle.kts` now has a working default for the values below — you
must supply your own before the app can talk to a real backend.

## What was removed and why

| Removed hardcode | Where it lived | Replaced with |
|---|---|---|
| `CORS_APP_TOKEN` static secret (the real `X-App-Token` / `WB_APP_TOKEN` value) | `app/build.gradle.kts` | Placeholder `"REPLACE_WITH_WB_APP_TOKEN"` — the app already detects this placeholder (`CorsClient.isConfigured`) and treats itself as unconfigured. |
| `CORS_BASE_URL` default (pointed at a specific Yandex Cloud Function) | `app/build.gradle.kts` | Placeholder `"https://example.invalid/replace-with-your-endpoint"`. |
| `CORS_TG_BOT` default (`wl_cors_bot`) | `app/build.gradle.kts` | Placeholder `"REPLACE_WITH_TELEGRAM_BOT_USERNAME"`. Low-sensitivity, but pulled out so a fork doesn't accidentally talk to the original bot. |
| `debug.keystore` (committed binary keystore) | repo root | Deleted. The debug build type now relies on Android Gradle Plugin's own auto-generated `~/.android/debug.keystore` (standard `android`/`android` credentials) — nothing to commit. |
| `local.properties` (machine-specific, contained `sdk.dir`) | repo root | Deleted from the copy; it's gitignored and regenerated automatically by Android Studio / Gradle on first sync. |
| `.claude/` local settings | repo root | Deleted; added to `.gitignore` so it won't come back. |

## How to configure your own values

Set each value either as an **environment variable** (good for CI) or as a key
in a local, gitignored **`local.properties`** file at the repo root (good for
day-to-day dev in Android Studio). Environment variables take priority.

```properties
# local.properties (create this file yourself — it's gitignored)
sdk.dir=/path/to/your/Android/sdk

CORS_BASE_URL=https://your-backend.example.com
CORS_APP_TOKEN=your-shared-secret-matching-server-WB_APP_TOKEN
CORS_TG_BOT=your_telegram_bot_username
```

or equivalently:

```bash
export CORS_BASE_URL="https://your-backend.example.com"
export CORS_APP_TOKEN="your-shared-secret-matching-server-WB_APP_TOKEN"
export CORS_TG_BOT="your_telegram_bot_username"
```

These are read in `app/build.gradle.kts` and exposed to the app via
`BuildConfig.CORS_BASE_URL`, `BuildConfig.CORS_APP_TOKEN`, `BuildConfig.CORS_TG_BOT`.

If `CORS_APP_TOKEN` is left unset, the app builds and runs, but `CorsClient.isConfigured`
is `false` and calls to the instance-creation API will fail — this is intentional,
so an unconfigured build doesn't silently share a real secret.

## Telegram App Link host

`TelegramAuth.CALLBACK_HOST` (`beta.cors-fox.cc`) and the matching
`AndroidManifest.xml` intent-filter host are **not** wired to `CORS_BASE_URL` —
they must match whatever host your Telegram bot's WebApp actually redirects to
(see the comment in `TelegramAuth.kt`). If you stand up your own bot/backend,
update both the manifest's `android:host` and `CALLBACK_HOST` to your own
verified HTTPS domain.

## Release signing

The `release` build type currently reuses the `debug` signing config as a
placeholder (see the comment in `app/build.gradle.kts`). Before shipping a real
release build, generate your own upload/release keystore
(`keytool -genkey -v -keystore release.keystore -alias release -keyalg RSA -keysize 2048 -validity 10000`),
keep it out of git, and add a proper `signingConfigs { create("release") { ... } }`
block sourcing its store/key passwords from environment variables or
`local.properties` the same way as above.
