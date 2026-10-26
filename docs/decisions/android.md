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
22. **Connecting confirms the host first, then shows the store.** The design
    has the confirm screen show the store and wallet before connecting, but
    Monokulo only says which store a code is for when it's redeemed. So the
    app asks "Connect to pay.yourshop.com?", redeems, then shows store,
    server, wallet and network with "Wrong store: disconnect". A preview
    endpoint would need a code that can be read without being spent.
23. **The till runs in a `specialUse` foreground service** during a shift
    (Android 14+ needs a type; `dataSync` is capped at 6 hours a day on
    Android 15). Play needs the subtype explained; the manifest says it.
    Its notification is the current sale (`setRequestPromotedOngoing`, a
    Live Update on Android 16+).
24. **Secrets are AES-GCM encrypted with an Android Keystore key** and kept
    in app preferences, written with `commit` so a spent Square refresh
    token is never the one left on disk. No extra library (Tink,
    EncryptedSharedPreferences) is needed for this.
25. **The QR scanner is CameraX with ZXing**, not ML Kit, so the app has no
    Google Play services dependency and can go on F-Droid. Codes are drawn
    with ZXing's `QR_COMPACT`, which switches modes inside a code so a
    receipt's digits pack in numeric mode.
26. **Debug builds** (`app.monokular.debug`) take connect codes over plain
    http, return from Square on `monokular-debug://square`, and can point at
    a stand-in Square: for running against Monokulo on the developer's
    machine. Release builds take only https and the `monokular.app` App
    Link.
27. **The Square application id is a build property**
    (`-Pmonokular.square.applicationId=…`), defaulting to a placeholder,
    because the project's Square application doesn't exist yet.
28. **Push isn't wired up yet.** Monokulo has no push targets and there's no
    Bell, so the app relies on its foreground service and the event stream
    during a shift. The UnifiedPush module from the first PR, which nothing
    called, was removed (decision 56).
29. **A till without Square** ("Not now") still runs: it shows the sales
    other tills make, from the event stream, without receipts.

## App: matching the design (2026-10-23)

Made while bringing the app to the design mockups (the Android, onboarding,
settings and shared-pieces boards), again without the owner to ask. Where
the mockups' notes are older than `docs/design.md` (Square webhooks to
Monokulo, the `monokular.app/connect#host=` link), the design document
wins; nothing here changes what goes over the wire.

30. **Colours are the mockups' own, by role** (`ui/Theme.kt`): ground
    `#f4f1ea`, ink `#1a1917`, muted `#5a564c`, cards white, accent
    `#ff6600` with `#111111` on it, link `#b34700`, outline `#8c8577`,
    paid `#1a7f37`. The mockups are light only; dark mode keeps the same
    roles on a warm dark ground (`#1c1b19`), each text pair at least 4.5:1.
    The QR card and the bubble keep their light colours in both: they sit
    over another app, or face a customer's camera.
31. **Manrope is bundled** as a font resource (its variable TTF from
    google/fonts, 165 KB, SIL OFL 1.1; the licence ships in
    `assets/licenses/`), used for display type only, Roboto for the rest,
    as in the mockups. No downloadable fonts, so no Google Play services
    and nothing fetched at run time.
32. **The logo and the loading mark are drawn in Compose from the web's
    SVG paths** (`ui/Mark.kt`), not a vector drawable, so the ring's colour
    and the idle bubble's greyscale are parameters. The loading mark's
    glints follow the web's keyframes (4.2 s, the core 0.7 s) and stand
    still when Android's animations are off (animator scale 0), as
    `prefers-reduced-motion` does on the web. The bubble's pulse does too.
33. **"Sale #1042" is Square's receipt number** (the order's
    `merchant_order_id`), with a `#` added when Square's has none; with no
    number it's "Monero sale". "Square · Front Counter" is this till's
    Square location; a sale another till found says "From" and that
    device's name, from the event.
34. **Amounts are formatted for the till's locale** ("$24.50"), and the
    XMR amount is shown exactly, not rounded to 4 places as in the mockups:
    a customer typing it by hand must send exactly that.
35. **The bubble is an overlay window in the till's service**
    (`overlay/`), shown during a shift when Android allows "display over
    other apps" and the cashier didn't choose notifications only, and
    hidden while Monokular itself is on screen. It's a Compose view in a
    `TYPE_APPLICATION_OVERLAY` window; touches are handled in screen
    coordinates (the window moves under the finger), a drag snaps to the
    nearer side with the amount on the inside, and the side and height are
    remembered. TalkBack hears one button named as in the mockups' aria
    labels ("Monokular, new sale, tap to show the code"), announced when it
    changes. design.md lists `overlay/` and `notifications/` as Gradle
    modules; they're packages in `app` for now, because they share the
    theme and live in the app's service.
36. **The panel over Square**: on a phone it fills the screen with the
    mockup's scrim (a tap on it minimises, as does the bubble docked above
    it); on a window 600 dp or wider it floats top right with no scrim,
    like AndTabFloat, so Square stays usable beside it. It shows the sale
    the bubble is about: the newest still waiting, or the one just paid
    while it shows as paid (5 s), then the next one waiting. Android 12+
    lets overlays cover neither the status bar nor the navigation bar, so
    the scrim stops short of them.
37. **The notification follows the mockups**: a progress style with four
    segments (Waiting, Seen, Confirming, Paid), "Show code" and "Full
    screen" while waiting (AndSale), "Show code" and "Copy address" once
    seen (AndShade), the code as its large icon, the code's countdown in
    its header, and the amount as the Live Update chip's text. Android
    draws its own chip and can't put labels under the segments; the text
    says where the payment is instead. It's private on the lock screen,
    where a public version shows only the amount. A heads-up comes once
    per sale, never again when an older sale comes back to the top.
38. **"Show code" goes through a tiny activity** (`ShowCodeActivity`)
    that opens the panel or the app and finishes: a service action would
    leave the notification shade open over the panel, and starting the
    app from a service is a notification trampoline Android 12+ blocks.
39. **Paid and "No payment yet" are notifications of their own**
    (AndPaid: "The notification is replaced by a short 'Paid' one"): the
    shift's ongoing notification goes back to watching, a "Paid · Sale
    #1042" one appears for 5 minutes, and a "No payment yet" one stays
    until it's opened. Both are silent while Monokular is on screen
    saying the same thing.
40. **Setup follows the six onboarding steps**, adapted to Android:
    1 Welcome, 2 connect a store (the camera, or "Paste the link": the
    dashboard's code is a link on the store's own host, which Android
    can't open in the app), 3 the server then the store (decision 22), 4
    Square, 5 alerts (Android 13's notification permission) and then the
    bubble (AndPermission, with "Open settings" and "Use notifications
    only"), 6 Ready, whose "Start Monokular" starts the shift. The steps
    carry the mockups' "Step n of 6"; AndPermission has none, as drawn.
41. **The Square step watches for a test sale** as the mockup does: the
    till starts watching once a location is chosen, Continue waits for the
    test sale and "Skip the test" goes on without it. The test sale stays
    in the shift's list but isn't put full screen when setup ends. The
    mockup's path in Square ("Settings › Checkout › Payment types") is its
    own guess, still to check in Square POS.
42. **The till's screen is the sale full screen (AndFull), else the idle
    pane (AndTabSplit's Monokular side)** with this shift's sales under
    it. Close puts a sale back in that list; a paid sale shows "Paid" and
    returns to watching after 5 s (the iPad flow's note); an unpaid one
    (PhoneUnpaid) waits for "Refund the Square sale" or Done. Sales that
    ended while the cashier was elsewhere don't come back full screen,
    except an unpaid one that still needs refunding. "Back to Square"
    opens Square Point of Sale (`com.squareup`) when it's installed, else
    steps back to whatever was behind Monokular. The overflow menu has
    "Copy the XMR amount" and Settings; the mockup doesn't say what it
    holds.
43. **PhoneUnpaid's "Show a new code" isn't built.** A new code for the
    same Square sale needs a second order for one Square payment, which
    the idempotency key (decision 14) exists to prevent; the cashier
    rings the sale up again instead. Done takes its place.
44. **Settings follow PhoneSettings where Android has the same thing**:
    Store (with "Online"), Square (account, location, payment type, sign
    out), then Shift, Alerts and Bubble in place of the Dynamic Island,
    and Wake-ups. "New sale alerts" opens Android's notification settings
    (an app can't turn its own permission off). The wallet address was
    first shown without Copy, because Monokulo gave the app only its short
    form; decision 49 replaces that.
45. **Wide windows split the sale in two** (840 dp and up: the code on
    one side; amount, status, progress and buttons on the other); a
    narrow split-screen or free-form window gets the phone layout, with
    the code sized to the window and everything scrolling when it's short.
46. **"Back to Square" isn't shown in split screen**, where Square is
    already beside Monokular and stepping back would only hide it.
47. **AndPermission's label says "choose Monokular and turn this on"**:
    since Android 11 the "display over other apps" intent opens the list
    of apps, not Monokular's own page, so the cashier has to find it.
48. **A sale this till finds that another till already made keeps the
    phase the store's events gave it.** Found while testing two tills at
    one location: the second till's Square check gets the same order back
    (decision 14) and showed it as waiting again, though it was paid. Now
    it keeps the phase it had and only gains what a refund needs. A till
    that restarts and re-finds a sale from its 2-minute look-back
    (decision 15) still shows it as waiting until the event stream
    reconnects and resyncs it, because the order response carries no
    status; changing that would change the API, so it's left.
49. **The wallet address in Settings can be copied** (replaces the end of
    decision 44). Settings reads the store from Monokulo as it opens
    (`GET /api/v1/store`, decision 50), whose wallet carries its whole
    primary address. The row shows it shortened on the device, first 8
    characters, "…", last 8, as the mockup does, and the Copy icon after it
    copies the whole address: a 48 dp button, "Copy wallet address" to
    TalkBack, that reaches into the row's padding so the row stays as tall
    as the others. Until the first read of the run answers there's no
    wallet row, so nothing to copy; a store without a wallet has none
    either. The copy goes through the same helper as the sale
    notification's "Copy address": an "Address copied" toast before
    Android 13, Android's own clipboard preview from 13 on, and the clip
    isn't marked `EXTRA_IS_SENSITIVE`. A receiving address is meant to be
    shared and isn't a secret like a key, and hiding it from the preview
    would stop the cashier checking what was copied.

## App: Monokulo's HTTP API (2026-10-23)

Monokulo replaced the `/pay/{pk}/…` routes with its HTTP API (`/api/v1`,
HAL-FORMS, problem+json), and `POST /apps/connect` now answers only the
key and the API's entry. Checked against Monokulo
f7fb5c9bee755abc07dbac34dfec5f5043b6a66f.

50. **The store's details are read live, never saved.** The connection the
    app keeps is the `ak_` key and the API's entry, nothing else. The
    store's name, base currency and wallet (`GET /api/v1/store`) and this
    device's name (`GET /api/v1/app`) are read after connecting (the
    "Connected to …" screen is drawn from that read, with Continue waiting
    for it and Try again if it fails), each time Settings opens, and at app
    start (the till's and its notification's store name, and the base
    currency the notifications step shows). They're held in memory for the
    run (`LiveStore`), so Settings shows the last read, with "Reading…"
    where there's none yet, while the next is under way. Monokulo's docs
    ask for exactly this, since the merchant can rename the store or move
    it to another wallet at any time; a saved copy would show the old
    wallet as where payments go. The cost is a read per Settings visit and
    blank rows when the store can't be reached; keeping a "last seen" copy
    on the device was the alternative, and isn't worth showing a wallet
    that may no longer be the one.
51. **The app follows the API's links and forms.** It reads the entry
    (kept as decision 54 says) and finds the store, events and its own
    connection there,
    and makes orders and disconnects with the `create-order` and
    `disconnect` forms; a link or form the entry leaves out isn't tried.
    Links resolve against the URL the app actually called, so an onion
    connection stays on the onion host. An order's `self` link is kept
    with the sale and used to read it again after the event stream
    reopens; a sale another till made comes only as a sale event, and is
    read again at the event's `mk:order` link. The app builds no path. The
    order now
    carries its status, so a sale a restarted till re-finds no longer shows
    as waiting until the stream resyncs (the gap decision 48 left). One
    order's own stream (`mk:order-events`) isn't used: the store-wide
    stream already brings every sale's changes.
52. **A connection saved by the first app isn't read any more.** That
    form (`endpoint`, `public_key`, `store`, `wallet`, …) predates the API
    and was never released; reading it as the key at the endpoint's
    `/api/v1` was interim code, removed with the final API (2026-10-24).
    Such a till shows Welcome and connects again. A connection saved by
    the app on main before then (`app_key` and `api`) carries on unchanged
    (decision 55).
53. **The client is checked against Monokulo's own OpenAPI document,
    generated, not committed.** Monokulo generates it from its code and a
    test's real exchanges (`cargo xtask api openapi`) and never commits
    it, so this repo doesn't either: `protocol/openapi.yaml` is gone, and
    core's `monokuloOpenApi` Gradle task fetches Monokulo at the commit in
    `android/gradle.properties` (`monokulo.sha`) and generates it before
    core's tests (`tools/monokulo-openapi/generate.sh`). The contract test
    answers the client with Monokulo's recorded examples and checks what it
    sends against the schemas; `-Pmonokulo.openapi=FILE` uses a document
    already generated. Generating it needs only Rust (`cargo xtask api
    openapi` builds Monokulo's library and one example, no Node, no
    browser code; rustup installs the toolchain Monokulo names); CI caches
    the build per commit. Bumping `monokulo.sha` is how the app moves to a
    newer Monokulo. The document now has the app handshake with its
    problems, the entry as each key sees it, and the event stream's events
    (`x-events`) with a recorded stream, so the contract test answers all
    of them from Monokulo's own recordings and builds none of its own.

## App: the final API (2026-10-24)

Checked against Monokulo 3b0cc7c73160611bd8e1a4dd44624d0d9f59820f, which
has every fix the app was waiting for; the interim code is gone.

54. **The entry is kept as Monokulo says, per key.** `GET /api/v1`
    answers `Cache-Control: private, max-age=3600`, `Vary: Authorization`
    and an `ETag`. The client keeps it per key and entry address for
    `max-age`, so making an order is one request; once it has expired it
    asks again with `If-None-Match` and keeps its copy for another
    `max-age` on a `304`. Anything followed from it that answers `401`,
    `404` or `405` drops the copy, and the store, app, order and
    disconnect calls read the entry again and try once more. An unknown
    key is `401` at the entry, which is how the app hears it was
    disconnected. Errors are read only as problems (`type`, `detail`);
    `POST /apps/connect`'s are told apart by `type` (`invalid-connect-code`,
    `rate-limited` with how long to wait, and the rest by their `detail`),
    and a `challenge-required` is solved and sent again with
    `Monokulo-Proof`. The connect link is redeemed at its own address,
    path and all, so a Monokulo served under a path works end to end.
55. **Tor, through Orbot, preferred when it's there.** A Monokulo with an
    onion address gives `onion_api` in the handshake and `mk:onion-api` in
    the entry; the app keeps it with the connection, with a `tor` switch.
    Monokular has no Tor of its own: onion addresses go through Orbot's
    SOCKS proxy (`127.0.0.1:9050`, which resolves the name), everything
    else straight out, and plain http is allowed to `.onion` only. On
    connecting, the app goes over Tor when the store has an onion address
    and Orbot is running; Settings offers "Reach the store over Tor"
    whenever the store has one, and says when Orbot isn't running. Over
    Tor the app reads the onion entry and follows its relative links, so
    nothing goes to the web address. A till that connected at an onion
    address is always on Tor. A till connected before this keeps its
    `app_key` and `api`, carries on off Tor, and learns the onion entry
    from `mk:onion-api` the next time it reads the store; a store that no
    longer has one takes it off Tor. Embedding Tor (tor-android or Arti)
    was the alternative: tens of megabytes and a second Tor beside the
    Orbot a privacy-minded merchant already runs.
56. **The UnifiedPush module is gone.** Nothing called it: Monokulo has
    no push targets yet (decision 28). It comes back, wired up, with them.

## App: following Monokulo dc7f085 (2026-10-26)

57. **An expired sale is followed until Monokulo says it's final.**
    Monokulo now keeps watching an expired or cancelled order for a
    payment that arrives late, which turns it paid, and says so with
    `is_final: false` meanwhile. Each till sale keeps the `is_final` it
    last read (`settled`); after the stream reopens, the till reads again
    every sale that isn't paid or settled, so a late payment made while
    the stream was down still reaches the screen. A `sale.paid` for an
    expired sale moves it to paid as any other event does. A sale the
    cashier already refunded in Square that is then paid says "Paid after
    the refund" and stays on screen, asking the cashier to ring it up
    again or return the payment, instead of going back to watching.
    Nothing else in the 204 commits since 1d39b92 changes what the app
    sends or reads: the new `send-test-webhook` form is for a shop's
    server (`sk_`) only, and the rest are wording and recorded examples.
