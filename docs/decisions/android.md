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
10. **`receipt` knows only ORF.** It turns a receipt into the code
    (`ReceiptCode.of`); turning a Square order into a receipt lives with the
    Square code in `sales`, so another POS adds its own mapping without
    touching the codec module.
11. **A `android` cargo profile** (LTO, one codegen unit, stripped) for the
    libraries the app ships: about 1.2 to 1.6 MB per ABI. Host release
    builds keep the defaults, because UniFFI's bindgen reads metadata from
    symbols LTO and stripping remove.
12. **`orf-codec-ffi`'s error field is `reason`**, not `message`, which
    clashes with Kotlin's `Throwable.message` in the generated bindings.
13. **A Square "Other: Monero" tender is read as an `EXTERNAL` payment whose
    `external_details.source` is the tender's name** (case-insensitive),
    status `COMPLETED`. Square's docs don't say how Point of Sale's custom
    Other tenders appear in the Payments API: the Square spike must check
    this first. If they don't appear as payments, `SquareSource` changes to
    searching orders' tenders; nothing else does.
14. **The idempotency key is `sq:` plus the first 40 hex digits of SHA-256
    of Square's payment id.** Every till finding the sale gets the same
    order, and Monokulo never learns Square's id.
15. **The payments cursor looks back 2 minutes** past the last handled sale
    (Square can list a payment late) and remembers the last 500 handled
    ids, so the overlap never makes a second order.
16. **A sale is handled once it has its Monokulo order**, not when it's
    found: a failure (Monokulo down) is retried on the next tick.
17. **Receipts from Square orders:** the time in the location's own time
    zone, cut to whole seconds (the codec refuses fractions); the unit price
    is the one that makes quantity × price the line's gross total when it
    exists in whole cents, else the list price; tips and discounts go in
    `extensions` (`tip`, `discount`); line breaks in names become spaces;
    a variation is shown unless it's Square's default "Regular".
18. **A till shows only its own sales when Square names the device**
    (`device_details.device_id` matching the till's chosen device);
    otherwise every Monokular at the location shows every sale.
19. **Square tokens are refreshed when less than a day is left.** PKCE
    refresh tokens are single-use, so the new one is saved before anything
    else; a refresh Square refuses (4xx) clears the sign-in and asks the
    cashier to sign in again.
20. **`Square-Version: 2026-09-16`**, the version Square's reference shows.
21. **Refunds use `monokular-refund-<payment id>` as their idempotency
    key**, so a retried refund refunds once.
