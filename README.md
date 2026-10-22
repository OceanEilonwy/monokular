# Monokular

The companion app to [Monokulo](https://github.com/OceanEilonwy/monokulo) that
helps you take Monero payments at the till using your existing POS apps.

When the cashier takes a Monero sale in their point of sale (Square first),
Monokular shows the customer the payment code and confirms when the money
arrives: in the Dynamic Island on iPhone, in Slide Over on iPad, and in a
floating bubble or Live Update notification on Android.

Monokulo is self-hosted, so Monokular always connects from a store's own
Monokulo dashboard (Store settings › Connected apps), never to a central
server.

## Status

Design stage, with the Android project set up: it builds and has the connect
link parser, the sale event model and UnifiedPush registration. iOS hasn't
started.

- [Design document](docs/design.md): the sale flow with Square, connected
  apps, push (Web Push, UnifiedPush and a relay for APNs/FCM), the Live
  Activity, battery use, the Monokulo side and this repository's layout.
- [Design mockups](https://claude.ai/artifact/Kb9AtJFBF3ockyddomLc6t): iPhone,
  iPad and Android screens, onboarding, settings and the web connect pages.

## Building the Android app

You need JDK 21 and the Android SDK with platform 37.2. Android Studio
includes both: its bundled JDK is at `<android-studio>/jbr`.

```sh
cd android
echo "sdk.dir=$HOME/Android/Sdk" > local.properties   # once, not committed
sdkmanager "platforms;android-37.2"                   # once, if missing
./gradlew build                                       # compile, lint, test, assemble
./gradlew :app:installDebug                           # onto a connected device
```

| Module | What it is |
| --- | --- |
| `android/core` | Pure Kotlin, no Android: connect links, the `sale.*` event model. Tested on the JVM. |
| `android/push` | UnifiedPush registration and the push service. |
| `android/app` | The Compose app. |

Versions live in `android/gradle/libs.versions.toml`; each was released at
least two weeks before it was added.

## License

GPL-3.0. See [LICENSE](LICENSE).
