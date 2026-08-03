# Local configuration

No value below has a working default: the app must be told which backend, which
shared secret and which Telegram bot to use before it can do anything real.

Set each value either as an **environment variable** (good for CI) or as a key
in a local, gitignored **`local.properties`** file at the repo root (good for
day-to-day dev in Android Studio). Environment variables take priority.

```properties
# local.properties (create this file yourself — it's gitignored)
sdk.dir=/path/to/your/Android/sdk

PC_BASE_URL=https://functions.yandexcloud.net/<function-id>
PC_APP_TOKEN=<shared secret matching the server's APP_TOKEN>
PC_TG_BOT=prometheus_connect_auth_bot
PC_CALLBACK_HOST=auth.prometheus.info.gf
```

or equivalently:

```bash
export PC_BASE_URL="https://functions.yandexcloud.net/<function-id>"
export PC_APP_TOKEN="<shared secret matching the server's APP_TOKEN>"
export PC_TG_BOT="prometheus_connect_auth_bot"
export PC_CALLBACK_HOST="auth.prometheus.info.gf"
```

These are read in `app/build.gradle.kts` and exposed to the app via
`BuildConfig.PC_BASE_URL`, `BuildConfig.PC_APP_TOKEN`, `BuildConfig.PC_TG_BOT`
and `BuildConfig.PC_CALLBACK_HOST`.

If `PC_APP_TOKEN` is left unset, the app builds and runs, but
`CorsClient.isConfigured` is `false` and calls to the instance-creation API will
fail — this is intentional, so an unconfigured build doesn't silently share a
real secret.

## Why the base URL is a Yandex Cloud Function

`PC_BASE_URL` points at a Cloud Function proxy, not at the backend directly. On
a strict RU mobile whitelist tariff nothing resolves except a curated domain
list, and `functions.yandexcloud.net` is on it while our own domain is not — so
the very first call, the one that creates the tunnel, has to go through the
function. `CorsClient` detects that host and switches to the proxy calling
convention automatically (path in the `__path` query parameter, session token in
`X-Prometheus-Session-Token`). Pointing `PC_BASE_URL` straight at
`https://auth.prometheus.info.gf` also works and uses normal REST conventions —
useful for testing over a connection that can already reach it.

## Telegram App Link host

`PC_CALLBACK_HOST` fills **both** `TelegramAuth.CALLBACK_HOST` and the
`AndroidManifest.xml` intent-filter host, so the two cannot drift apart. It must
be the host your Telegram bot's Mini App actually redirects to, and that host
must serve a `/.well-known/assetlinks.json` naming this app's package and its
release signing certificate — otherwise Android will not intercept the callback
locally and the sign-in flow silently falls back to opening a web page.

## Release signing

Generate a keystore and keep it out of git:

```bash
keytool -genkey -v -keystore prometheus-connect-release.jks \
        -alias prometheus -keyalg RSA -keysize 4096 -validity 10000
```

Then point the build at it (env vars or `local.properties`):

```properties
PC_KEYSTORE_FILE=/absolute/path/to/prometheus-connect-release.jks
PC_KEYSTORE_PASSWORD=...
PC_KEY_ALIAS=prometheus
PC_KEY_PASSWORD=...
```

With these set, `./gradlew assembleRelease` signs with the release key. Without
them the build still succeeds but falls back to the debug key and logs a
warning — such an APK will **not** pass App Link verification.

After the first release build, read the certificate fingerprint and put it in
`assetlinks.json` on the callback host:

```bash
keytool -list -v -keystore prometheus-connect-release.jks -alias prometheus \
  | grep 'SHA256:'
```

```json
[{
  "relation": ["delegate_permission/common.handle_all_urls"],
  "target": {
    "namespace": "android_app",
    "package_name": "gf.info.prometheus.connect",
    "sha256_cert_fingerprints": ["<the SHA256 from above>"]
  }
}]
```

## Upstream

Built on [kulikov0/whitelist-bypass](https://github.com/kulikov0/whitelist-bypass)
by way of [CorsacTheFox/cors.connect-app](https://github.com/CorsacTheFox/cors.connect-app).
Note that cors.connect-app is not a GitHub fork of whitelist-bypass — it is a
separate repository carrying a copy of that code — so the fork graph does not
show the real lineage. [THIRD-PARTY.md](THIRD-PARTY.md) does.
