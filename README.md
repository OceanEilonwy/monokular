# Monokular

The companion app to [Monokulo](https://github.com/OceanEilonwy/monokulo) that
helps you take Monero payments at the till using your existing POS apps.

When the cashier takes a Monero sale in their point of sale (Square first),
Monokular shows the customer the payment code and confirms when the money
arrives: in the Dynamic Island on iPhone, in Slide Over on iPad, and in a
floating bubble or Live Update notification on Android.

Monokulo is self-hosted, so Monokular always connects from a store's own
Monokulo dashboard (Store settings › Connections › Apps), never to a central
server. The till holds the Square sign-in, and builds each receipt itself:
what someone bought never reaches Monokulo.

## Status

The Android app works end to end against Monokulo's connected-apps API
(OceanEilonwy/monokulo#140) and a stand-in Square, and follows the design
mockups: setup in six steps (connect from a store's code, sign in to Square,
alerts and the bubble), a floating bubble over Square that lights up with a
sale and opens its code over it, a Live Update notification with the
payment's progress, and the sale full screen in the app, with the receipt
in the code, until it's paid or refunded. It hasn't met real Square yet
(the Square spike, design doc §10), the app doesn't use push or the
Monokular Bell yet (the Bell itself is built: [below](#running-a-bell)), and
iOS hasn't started. Choices made along the way are in
[docs/decisions/android.md](docs/decisions/android.md); screenshots are in
[docs/screenshots/android](docs/screenshots/android).

- [Design document](docs/design.md): the sale flow with Square, connected
  apps, push (Web Push, UnifiedPush and a relay for APNs/FCM), the Live
  Activity, battery use, the Monokulo side and this repository's layout.
- [Design mockups](https://claude.ai/artifact/Kb9AtJFBF3ockyddomLc6t): iPhone,
  iPad and Android screens, onboarding, settings and the web connect pages.

## Building the Android app

You need JDK 21, the Android SDK with platform 37.2 and NDK r27, and Rust
with the Android targets and `cargo-ndk`: the receipt codec is the Rust crate
`orf-codec`, built into the app. Android Studio includes the JDK: it's at
`<android-studio>/jbr`.

```sh
cd android
echo "sdk.dir=$HOME/Android/Sdk" > local.properties    # once, not committed
sdkmanager "platforms;android-37.2" "ndk;27.0.12077973" # once, if missing
rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
cargo install cargo-ndk --version 4.1.2 --locked       # once
./gradlew build                                        # compile, lint, test, assemble
./gradlew :app:installDebug                            # onto a connected device
```

The Square application id is a build property:
`-Pmonokular.square.applicationId=sq0idp-…` (a placeholder otherwise). It's
public, so `~/.gradle/gradle.properties` is a good home for it; the
application's secret is never used (the till signs in with PKCE).

### Signing a debug build in to Square's sandbox

```properties
# ~/.gradle/gradle.properties
monokular.square.applicationId=sandbox-sq0idb-…
monokular.square.sandbox=true
```

In the Developer Console, on the application's **OAuth** page for
**Sandbox**, set the redirect URL to
`https://monokular.monokulo.com/square/debug/` (trailing slash and
all). Square only sends a sign-in back to https; that page
([site/square/debug/](site/square/debug/index.html)) hands it to the debug
build's `monokular-debug://square`. `-Pmonokular.square.redirectUri=…` names
another such page. Square's sandbox sign-in only works with the test
seller's **Sandbox Square Dashboard** already open in the same browser:
open it in Chrome on the phone (from the console's *Sandbox test accounts*)
before tapping "Sign in to Square". The sandbox has no Point of Sale app, so
it tests the sign-in and the API, not a real "Other: Monero" tender.

Ring up sandbox sales as the till would find them (an order paid by an
`EXTERNAL` "Monero" payment) on a local page,
[tools/square-sandbox](tools/square-sandbox/src/main.rs), using the
Developer Console's sandbox access token for the same test seller the till
signed in as. It lists the location's latest payments and refunds them too.

```sh
cargo run -p square-sandbox   # http://127.0.0.1:8739, then enter the token on the page
```

| Module | What it is |
| --- | --- |
| `android/core` | Pure Kotlin, no Android: connect codes, the Monokulo client, sale events. |
| `android/receipt` | ORF receipts in the payment code, through `crates/orf-codec-ffi` (UniFFI). |
| `android/sales` | Square on the till (PKCE sign-in, Monero tenders, orders, refunds) and the till's sales. |
| `android/app` | The Compose app, the shift's foreground service, its notifications and the bubble over other apps. |

### Running it end to end on an emulator

A debug build talks to a Monokulo on your machine and a stand-in Square:

```sh
# In the monokulo repository: a Monokulo with an in-process engine, on :8737
cargo run -p monokulo --example apps_dev_server
# Here: a stand-in Square on :8738
python3 -I tools/fake-square/fake_square.py
# The app, pointed at it
cd android && ./gradlew :app:installDebug -Pmonokular.square.baseUrl=http://10.0.2.2:8738

curl -X POST localhost:8737/__dev/code    # a connect code: paste it in, or share it to the app
curl -X POST localhost:8738/fake/sale -d '{"items":[["Flat white",2,450]]}'   # ring up a sale
curl -X POST localhost:8737/__dev/orders/<order id>/paid                       # and pay it
```

Versions live in `android/gradle/libs.versions.toml`; each was released at
least two weeks before it was added.

## Running a Bell

The Monokular Bell (`crates/monokular-bell`) rings the tills at a Square
location within a second or two of a payment there, instead of their waiting
for their next look at Square: Square's payment webhook comes to the Bell, and
the Bell sends each registered till a Web Push "ding". It needs one Square
application (the one the tills sign in through) with a webhook subscription
for `payment.created` and `payment.updated`, and an https address Square can
reach. Its API is in [docs/bell.md](docs/bell.md); the choices behind it in
[docs/decisions/bell.md](docs/decisions/bell.md).

```sh
cargo build --release -p monokular-bell
target/release/monokular-bell --init      # writes ~/.config/monokulo/bell.toml, every setting described
```

In `bell.toml` set at least the Bell's address and the application:

```toml
[bell]
public_uri = "https://bell.example.org"   # Square's webhook URL is this + /v1/square/webhook
square.application_id = "sq0idp-…"        # sandbox-sq0idb-… with square.environment = "sandbox"
```

The two secrets come from the environment only:

```sh
export MONOKULAR_BELL_SQUARE_WEBHOOK_KEY=…               # the subscription's signature key (Developer Console › Webhooks)
export MONOKULAR_BELL_VAPID_KEY=$(openssl rand -hex 32)  # once; keep it: the tills' subscriptions are made for its public key
target/release/monokular-bell
```

It listens on `127.0.0.1:8445` (`bell.listen`): put an https reverse proxy in
front, serving `bell.public_uri`; a proxy on the same machine is trusted for
`X-Forwarded-For` by default (`bell.trusted_proxies`). Registrations are kept
in `~/.local/share/monokulo/bell.db` (`bell.store_path`). Any setting can be
given on the command line too (`monokular-bell --help`); anything invalid or
missing stops it before it listens. Stopping it (SIGTERM) lets requests and
dings in flight finish.

An iPhone till, or an Android one with only Google's services, takes its
dings through a push relay (monokulo's `monokulo-push-relay`), and a relay
counts pushes per sender, which is the Bell's VAPID key: every merchant's
tills behind it are one sender. A completed payment dings each till
registered at its location once, so at its busiest a Bell sends about the
payments a minute across all its merchants times the tills at each
location; whoever runs the relay sizes its `limits.per_sender_per_minute`
(600 by default) above that, or the relay answers `429` and those dings
are lost: a ding is never sent again, as a minute late it is worth
nothing. Its `limits.per_handle_per_minute` (60) is per till and is never
what a Bell reaches. So that one busy merchant can't spend the relay's
budget for all of them, the Bell sends each merchant's tills at most
`bell.dings_per_merchant_per_minute` dings a minute (120 by default),
across its locations, and drops the rest: keep it well below the relay's
per-sender limit.

To try it on one machine against the stand-in Square, with any Web Push
endpoint on loopback as the till's:

```sh
FAKE_SQUARE_WEBHOOK_URL=http://127.0.0.1:8445/v1/square/webhook FAKE_SQUARE_WEBHOOK_KEY=dev \
  python3 -I tools/fake-square/fake_square.py
MONOKULAR_BELL_SQUARE_WEBHOOK_KEY=dev MONOKULAR_BELL_VAPID_KEY=$(openssl rand -hex 32) \
  cargo run -p monokular-bell -- --bell-public-uri http://127.0.0.1:8445 \
  --bell-square-environment sandbox --bell-square-application-id sandbox-sq0idb-dev \
  --bell-square-api-url http://127.0.0.1:8738 --bell-allow-private-endpoints true
# register a subscription for L-DEV with the token EAAA-dev-…, then ring up a sale:
curl -X POST localhost:8738/fake/sale -d '{"items":[["Flat white",2,450]]}'
```

## License

GPL-3.0. See [LICENSE](LICENSE).
