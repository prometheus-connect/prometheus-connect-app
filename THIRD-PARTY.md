# Provenance

This app is three layers of other people's work with a thin layer of our own on
top. Written out honestly, because two of those layers arrived here without a
licence file and that needed fixing rather than ignoring.

## What this repository is made of

| Layer | Where it lives | Size | Origin | Licence |
|---|---|---|---|---|
| Tunnel engine | `app/src/main/jniLibs/*/librelay.so` | 13 MB per ABI | [kulikov0/whitelist-bypass](https://github.com/kulikov0/whitelist-bypass) | MIT |
| Proxy plumbing | `app/libs/mobile.aar` | 18 MB | gomobile build over [go-gost](https://github.com/go-gost/gost) and friends | MIT |
| Android app | `app/src/main/java/bypass/whitelist/**` | 46 files, ~6300 lines | [kulikov0/whitelist-bypass](https://github.com/kulikov0/whitelist-bypass) | MIT |
| Instance + Telegram auth | `app/src/main/java/cc/cors/connect/**` | 5 files, ~1100 lines | [CorsacTheFox/cors.connect-app](https://github.com/CorsacTheFox/cors.connect-app) | **none stated — see below** |
| This fork's changes | across the above | ~800 lines changed | Prometheus Connect | MIT |

Note the shape of that table: the Kotlin you see on the language bar is the
shell. The part that actually moves bytes is Go, shipped here as prebuilt
native libraries — `librelay.so` is a [pion](https://github.com/pion/webrtc)
WebRTC stack, and `mobile.aar` carries a gomobile build of the proxy side. The
Go sources are not in this repository; they live upstream.

## The unresolved part

`CorsacTheFox/cors.connect-app` publishes **no licence file**. Under default
copyright that leaves its original contributions — the `cc/cors/connect/**`
package — all rights reserved, so this repository cannot honestly call itself
MIT as a whole. Everything else can, and does.

Two things follow:

- The MIT notice for kulikov0's work was missing from the chain before this
  commit. MIT permits redistribution, including of binaries and inside closed
  products, on the single condition that the notice travels with the code. It
  now does, in `LICENSE`.
- Permission for the `cc/cors/connect/**` layer is still outstanding. Until it
  is granted, treat that package as used by courtesy rather than by right.

If you are reading this because you maintain one of the upstreams and something
here is wrong — attribution, licence, anything — please open an issue. It will
be fixed, not argued about.

## Not affiliated

Yandex, Telemost, VK, Wildberries and Telegram are trademarks of their
respective owners. This project is not connected with, endorsed by, or
supported by any of them.
