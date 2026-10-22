# Decisions: the Android app, first build

Made while building the Android app and its server side overnight on
2026-10-22, without the owner to ask. Each says what was chosen, why, and
what else was possible, so any of them can be reversed knowingly.

## Server (monokulo, OceanEilonwy/monokulo#140)

1. **Apps connect at `POST /apps/connect`, not `/connect/{slug}`.** A store
   has one shop plugin and finds it by the shop's host; a till has neither.
   A separate path also means no plugin slug can collide with it.
2. **App keys ride the existing `/pay/{pk}/…` routes.** `store_key::check`
   accepts an `ak_` key for its own store, so app orders go through the
   plugins' order API unchanged, pass the embed policy and get the store's
   rate-limit budget. Alternative: separate `/apps/…` routes duplicating the
   order API.
3. **Device name: the merchant's wins, else the app's.** The dashboard asks
   for an optional name before showing the code; the app sends its own
   (the phone's model) for when the merchant left it blank.
4. **The event stream has no history.** Announcements are in memory (a
   broadcast channel fed by the order API and the order-event subscriber).
   An app re-reads the status of the orders it shows after `ready` or
   `lagged`. A durable log would need a table and retention for little gain.
5. **No push targets yet.** They come with the `push` crate; until then the
   app keeps the stream open while a shift runs (a foreground service).
6. **Disconnecting an app is one click.** It only stops that device, and
   its orders can still be paid, unlike a plugin, whose disconnect rotates
   the store's key.
7. **The code page can't be shown again.** The token is only ever held
   hashed, so after leaving the page the merchant makes a new code. A
   "Check it connected" page reads by the code's hash.

## App

8. **The receipt codec is built at build time from Rust.** The `receipt`
   module runs `cargo ndk` (arm64-v8a, armeabi-v7a, x86_64) and UniFFI's
   bindgen from Gradle, so the Kotlin bindings can never drift from the
   crate. The cost: building the app needs Rust and the NDK. Committing the
   generated Kotlin was the alternative.
9. **NDK r27 (27.0.12077973), the current LTS**, over r30 which is also
   installed here.
