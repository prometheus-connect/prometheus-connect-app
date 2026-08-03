# Prometheus Connect

An Android client that gets you online on Russian mobile tariffs with a
**whitelist** — the ones where the operator passes traffic to a curated list of
domains and nothing else, so an ordinary VPN cannot even complete a handshake.

It works by tunnelling through a real Yandex Telemost video call. Telemost is on
the operators' whitelists and cannot be dropped without breaking Yandex's own
product, so the path stays open where protocol obfuscation does not help: since
early 2026 the filtering matches destination **IP and SNI together**, and no
amount of disguising a packet changes where it is addressed.

One button. It creates the call, brings up a device-wide VpnService, then opens
Telegram to sign you in and lifts the session out of its free window if your
subscription is active.

## What it is not

This is a lifeline, not a second VPN. The ceiling is inherent to the transport,
not to this app:

- roughly 4–6 Mbit/s;
- the call is re-established every 90–120 seconds, and requests in flight at
  that moment fail;
- Telegram, messengers, mail and banking apps are fine. Video and ordinary web
  browsing are not — a page load opens dozens of parallel connections and
  swamps a channel shaped like a video stream.

Use it when nothing else connects. Use the normal VPN the rest of the time.

## How it is built

The Kotlin is the shell — UI, `VpnService`, the WebView that joins the call, and
the client for our API. The engine is Go, shipped as prebuilt native libraries:
`librelay.so` is a [pion](https://github.com/pion/webrtc) WebRTC stack that talks
to the platform's SFU directly, and `mobile.aar` carries the proxy plumbing.

```
phone → Yandex Cloud Function (a whitelisted host, so the very first call works
        with no tunnel yet)
      → Prometheus Connect API
      → orchestrator fleet abroad, which mints a real Telemost call
      → the call itself carries the traffic
```

The Cloud Function exists only to bootstrap: on a whitelist tariff our own
domain does not resolve until a tunnel is already up. Off such a tariff the app
talks to the backend directly and never touches it.

Sign-in cannot use an App Link or a deep link back into the app, which is worth
knowing before anyone tries: Telegram renders the Mini App in a WebView, Android
does not consult App Links for in-WebView navigation, `Telegram.WebApp.openLink()`
opens Telegram's own in-app browser rather than the system one, and that browser
refuses `intent:` URLs outright. The Mini App therefore posts the signed
`initData` to the backend against a one-time code and the app collects it by
polling.

## Building

See [CONFIGURATION.md](CONFIGURATION.md). Nothing has a working default: the
backend URL, the shared app token, the bot username and the callback host all
have to be supplied, and a release build needs its own keystore or App Links
will not verify.

## Credit

This stands on [kulikov0/whitelist-bypass](https://github.com/kulikov0/whitelist-bypass),
which is where the method and the entire transport come from, by way of
[CorsacTheFox/cors.connect-app](https://github.com/CorsacTheFox/cors.connect-app),
which added the instance and Telegram-auth scaffolding. What is genuinely new
here is the backend, the provisioning and the sign-in flow.

[THIRD-PARTY.md](THIRD-PARTY.md) breaks down exactly which files came from
where, and flags the one component whose licence is still unresolved.
[LICENSE](LICENSE) carries the upstream MIT notice.
