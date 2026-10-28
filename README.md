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
in the code, until it's paid or refunded. With a Monokular Bell chosen in
Settings › Wake-ups, Square wakes the till through the Bell within a second
or two of a sale and the till checks Square every 30 seconds instead of
every 2; the dings come over UnifiedPush, so the phone needs a distributor
such as [ntfy](https://ntfy.sh/) (FCM through the push relay isn't built).
It hasn't met real Square yet (the Square spike, design doc §10), and iOS
hasn't started. Choices made along the way are in
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
| `android/push` | Wake-ups: the Bell's API, the till's registration with it, and its dings over UnifiedPush. |
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

#### Wake-ups

To try a Bell too, ring the sales up through it, give the emulator a
UnifiedPush distributor, and point the build at the Bell
([Running a Bell](#running-a-bell) has the rest):

```sh
# The stand-in Square, sending each sale to the Bell as a signed webhook
FAKE_SQUARE_WEBHOOK_URL=http://127.0.0.1:8445/v1/square/webhook FAKE_SQUARE_WEBHOOK_KEY=dev \
  python3 -I tools/fake-square/fake_square.py
# The Bell on :8445; keep the VAPID key between runs, or every subscription is for a dead key
[ -f /tmp/bell-vapid.key ] || openssl rand -hex 32 > /tmp/bell-vapid.key
MONOKULAR_BELL_SQUARE_WEBHOOK_KEY=dev MONOKULAR_BELL_VAPID_KEY=$(cat /tmp/bell-vapid.key) \
  cargo run -p monokular-bell -- --bell-public-uri http://127.0.0.1:8445 \
  --bell-square-environment sandbox --bell-square-application-id sandbox-sq0idb-dev \
  --bell-square-api-url http://127.0.0.1:8738
# ntfy's F-Droid build (no Google services) as the distributor, on the public ntfy.sh, from
# github.com/binwiederhier/ntfy-android/releases; its certificate's SHA-256 must be the one ntfy publishes
apksigner verify --print-certs ntfy-<version>-fdroid.apk | grep SHA-256
adb -s emulator-5554 install ntfy-<version>-fdroid.apk      # then open it once
# The app, for the Bell's application and the Bell
cd android && ./gradlew :app:installDebug -Pmonokular.square.applicationId=sandbox-sq0idb-dev \
  -Pmonokular.square.sandbox=true -Pmonokular.square.baseUrl=http://10.0.2.2:8738 \
  -Pmonokular.bell.url=http://10.0.2.2:8445
```

Then Settings › Wake-ups › Monokular Bell. The ding goes Bell → ntfy.sh →
ntfy on the emulator, so the emulator needs the internet; fake-square's log
shows the till's pace (`GET /v2/payments` every 30 s once connected, and one
within a second of each sale). `-Pmonokular.bell.url` takes what the
Settings field takes: a release build only an https address.

Versions live in `android/gradle/libs.versions.toml`; each was released at
least two weeks before it was added.

## License

GPL-3.0. See [LICENSE](LICENSE).
