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
    machine. Release builds take only https and the `monokular.monokulo.com` App
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
    the scrim stops short of them. The till tweaks (65, 66, 74, 75) take
    the scrim away and give the panel its grabber and its order page.
37. **The notification follows the mockups**: a progress style with four
    segments (Waiting, Seen, Confirming, Paid), "Show code" and "Full
    screen" while waiting (AndSale), "Show code" and "Copy address" once
    seen (AndShade), the code as its large icon, the code's countdown in
    its header, and the amount as the Live Update chip's text. Android
    draws its own chip and can't put labels under the segments; the text
    says where the payment is instead. It's private on the lock screen,
    where a public version shows only the amount. A heads-up comes once
    per sale, never again when an older sale comes back to the top. The
    chip's text is gone since 68, and 78 has the notification's words now.
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
    holds. Replaced by the till tweaks: the shift (60, 64, 72), one sale
    screen by standing (70), the close sheet (62) and `TillNav` (80); the
    overflow menu is gone (a Settings cog, and the XMR amount copies when
    tapped).
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

## App: Square's sandbox (2026-10-26)

58. **A debug build's Square sign-in comes back through an https page.**
    Square sends a sign-in back only to an https redirect URL (http only to
    localhost, in the sandbox), so `monokular-debug://square` can't be
    registered. A debug build registers
    `https://monokular.monokulo.com/square/debug/` (this repo's
    `site/`, on GitHub Pages under that custom domain; the project doesn't
    own monokular.app) as its redirect URL, which opens the debug
    scheme with the same query; the token exchange sends the same https
    URL, as Square checks. The page loads nothing else and the code is no
    use without the PKCE verifier, which stays on the device. Pointed at
    the stand-in Square, a debug build still uses the scheme directly.
    `SQUARE_RETURN_URI` is what the app accepts the sign-in on; a release
    build's is its App Link, `https://monokular.monokulo.com/square`, the
    same as its redirect URL, which needs the release signing key's
    `assetlinks.json` on the site once that key exists. Monokulo's own
    pages were the alternative and are ruled out: Monokulo has no Square
    code (`docs/design.md` §1).
59. **The sandbox's sign-in leaves out `session=false`,** which Square's
    sandbox doesn't support; production keeps it, so each sign-in is the
    seller's own.

## App: the till redesign (2026-10-27)

Made by the team's tech lead and product owner while building the approved
designs (the "Monokular till tweaks" canvas: the panel over Square, the
order full screen, the sale and its close sheet, the shift and its detail
pages, the enlarged code, the status bar chip), with the owner away.

60. **Where a sale stands is one sealed type** (`sales/Standing.kt`):
    `Live(phase)`, `Paid(late, afterRefund)`, `Closed`, `Expired`,
    `Reversed`, `Refunded` and `OtherTill`, read off a `TillSale` with
    `standing`. It joins what Monokulo says about the payment (the phase)
    with what the cashier did on this till (closed it, refunded it), so a
    shift row's words, its icon and the page it opens are all decided in one
    place and tested there. Order of precedence: paid beats everything (a
    late payment after a close or a refund is `Paid` with the flags);
    another till's sale that isn't live or paid is `OtherTill` whatever was
    done here; then refunded, reversed, expired (or cancelled), closed. The
    code running out therefore outranks the cashier's close: a closed sale
    whose code then expires shows "Code expired", which has no "Show the
    code again". `live` (standing is `Live`) is what the bubble lights for,
    what the ongoing notification follows and what the shift opens as a
    code; `needsAction` is closed, expired or reversed on a sale this till
    found and hasn't refunded, and a sale paid after it was refunded (the
    cashier must ring it up again or return the payment), which is what
    the shift's "Needs action" chip counts. Another till's sale is never
    "closed on" that till in the app's words: Monokulo has no event for a
    till closing a sale, so this till can't know it; the row and page say
    what this till does know (its code ran out, its payment was reversed,
    it was closed here) and name the till that has its Square sale.
61. **The cashier's marks live on the sale, as times.** `TillSale` gains
    `closedAt` and `refundedAt` (`refunded` is now `refundedAt != null`),
    and `createdAt`, `seenAt`, `paidAt` and `endedAt` for the detail pages'
    timelines, all Unix seconds. The old `closed` set in the view model (a
    sale the cashier had closed, which only hid it) is gone: a close is a
    fact about the sale that the bubble, the notification and the shift
    all need, so it belongs in the model, not the screen. What remains in
    the view model is `left`: sales whose full screen the cashier stepped
    back from, which stay live and only don't come back up on their own.
    Times come from the source that knows them: the POS's `created_at` for
    a sale this till rang up, the event's `at` for a sale another till made
    and for each step the stream reports, and the till's clock (`now`,
    injectable for tests) for the cashier's own actions and for a resync,
    which says what happened but not when. A step already timed keeps its
    time.
62. **"Not sure: close it for now" is `Till.close`**: it stamps `closedAt`
    and nothing else. The code comes down (the sale isn't live), the
    bubble, panel and chip move on, and the sale goes to the shift under
    Needs action. Nothing is said to Monokulo, which keeps watching the
    order for a late payment exactly as before; `sale.paid` arriving later
    turns the row into "Paid after you closed it". "Show the code again" on
    the closed page is `Till.reopen`, which clears `closedAt`, so the code,
    bubble and chip come back. "No: refund it in Square" closes the sale
    and then refunds it (`close`, then `refund`): the sale's timeline shows
    both, and a refund the POS refuses leaves a Closed sale with its Refund
    button, and the till's problem text saying why. `Till.dismiss` and the
    sale screen's "Close sale" link, which took a sale off the till
    altogether, are gone: a sale Square has as Monero must stay in the shift
    until it's paid or refunded.
63. **What was bought reaches the screens from the sale itself.**
    `TillSale.receipt` keeps the `Receipt` this till built from Square's
    order (the one that went into the code), so the panel's order line,
    the order page, the sale's order card, the detail pages and the
    notification's item line all read it on the device. It is never sent to
    Monokulo (the till only ever sends amount, currency, reference and the
    idempotency key, as `TillTest` checks) and never logged: the app logs
    nothing, and `TillSale` must not be put through a logger or a crash
    report. A sale another till found has no receipt here, and its pages
    say so ("what was bought stays on that till"). Counts add quantities
    up ("15 items"); the first three lines are named, then "+N more".
64. **The shift list survives the process.** The till's sales were in
    memory only, so Android ending the service lost every sale, including
    closed and expired ones the cashier still had to refund: money Square
    had taken as Monero that nobody was reminded of. The till now saves its
    sales (JSON, through a `ShiftStore` the app backs with `SecureStore`,
    so the receipts inside are encrypted at rest like everything else the
    app keeps) on every change and loads them when it starts; the resync
    that follows the stream opening then brings their phases up to date.
    The list is this shift's: it starts when the cashier starts watching
    (`Stores.shiftStartedAt`, the "since 9:02 am" in the shift's summary)
    and is cleared when the next shift starts after a stop. It is capped at
    500 sales, newest first.
65. **The panel's grabber and the bubble lower it; nothing else is a
    collapse button.** The phone panel is still one `MATCH_PARENT` overlay
    window with the lit bubble docked above it. Dragging the grabber down
    moves the panel with the finger (`translationY`) and, past a quarter
    of its height or with a downward fling, lowers it (`panelOpen = false`);
    short of that it springs back. Tapping the docked bubble lowers it, as
    before; on a tablet, where the real bubble stays, its tap now toggles
    the panel instead of only opening it. A tap on the empty part of the
    window still lowers the panel: that window takes every touch, so the
    alternative is a dead zone over Square. The grabber is a 48 dp button
    to TalkBack ("Lower to the bubble"), which can't drag. On the tablet's
    floating panel, whose window is only as big as itself, the drag fades
    the panel instead of moving it.
66. **The order page is a page of the overlay, and a screen of the app.**
    From the panel, its order line swaps the panel's content for "The
    order, full screen" inside the same overlay window (a sealed
    `PanelPage`, Code or Order), and its back arrow or "Show the code" swaps
    it back. An overlay window can't take the system Back key (it isn't
    focusable), which is why the design draws both ways back on screen.
    Starting Monokular's activity for it would have taken Square off the
    screen, which the panel exists to avoid. The header row's "Open the
    shift" and the panel's "Full screen" do start the activity
    (`EXTRA_SHIFT`, `EXTRA_ORDER`), as "Full screen" already did. In the
    app, the sale screen's order card shows up to four lines (three and
    "+N more" when there are more) and then opens the same page as
    `Screen.Order`, so a long order can't push the sale's buttons off the
    screen; both places draw one `OrderPage` composable.
67. **The enlarged code is a screen of the app** (`Screen.Code(orderId)`),
    white in both themes, with the window's `screenBrightness` set to full
    and `FLAG_KEEP_SCREEN_ON` while it's up, both undone when it goes;
    its status bar icons are dark for the white ground. A tap anywhere or
    the shrink button goes back, as does the system's Back. The sale screen
    keeps the screen on too while a live code is showing; the panel over
    Square doesn't change brightness (an overlay window's brightness isn't
    honoured, and the design says the panel's code never changes size).
68. **The status bar chip is the small icon alone.** On Android 16+, a
    promoted ongoing notification's chip shows its small icon and, when
    set, `setShortCriticalText`; the amount that was there is removed, so
    the chip is the icon only, as designed. The icon is one of four
    monochrome vector drawables chosen by phase (`ic_mark_q1`, `_q2`,
    `_q3`: the mark's open ring with its pupil, the ring's arc filled a
    quarter, a half, three quarters, the rest at 30% alpha, which the
    status bar keeps; `ic_tick`: a tick), and the notification's
    `setColor` is the accent, or the paid green for the paid face. The
    system draws the chip and decides how it uses the colour; the app
    doesn't depend on it being orange. The ongoing notification shows the
    paid face, promoted, for a few seconds (`Bubble.PAID_MS`) and then goes
    back to watching or to the next live sale, so the chip goes; a sale
    that is closed, expires or is reversed isn't live, so the notification
    moves on and the chip goes at once. The separate "Paid" and unpaid
    notifications (decision 39) stay, unpromoted, in the shade. Before
    Android 16 there is no chip: the same per-phase icon is the ongoing
    notification's status bar icon, and the shade shows the same
    notification. The watching, paid and unpaid notifications use the plain
    ring-and-pupil mark (`ic_mark`); the launcher icon is unchanged.
69. **Three theme roles added, none renamed.** The designs are drawn in dark
    mode in the dark tokens' own colours (ground `#1C1B19`, card `#2A2825`,
    divider and chip `#3A3733`, edge `#4A4743`, muted `#A8A29A`, ink
    `#ECE8DF`, link `#FF8A3D`, paid `#4CC27A`, warn `#E8C267`, warnGround
    `#3D3320`, danger `#FF6B6B`), so they map onto the existing roles. Two
    had no role: `dangerGround` (`#3D2422` dark, `#F6DCD9` light) behind
    the tinted X, a reversed sale's icon and its note, `onDangerGround`
    (`#FFD2CE` dark, `#6E1A1A` light) for the note's text on it, and
    `paidGround` (`#1E3A28` dark, `#DCEFE2` light) behind the paid tick.
    The close sheet's "Yes" icon sits on `warnGround` rather than a fourth
    tint the design draws once (`#3A2A14`). The "Needs
    action" count sits on `warn` in `ground`-coloured text, which reads in
    both themes; the selected filter chip is `ink` on `ground` inverted.
    The enlarged code's screen is white in both themes, like the QR card.
70. **One sale screen, its content by standing.** `Screen.Sale(orderId)`
    shows the code (the design's SaleA) while the sale is live, and the
    matching detail page (Paid, Paid after closing, Closed, Code expired,
    Payment reversed, Refunded, Closed on another till) otherwise, so a
    sale that gets paid while on screen turns into its Paid page in place,
    and steps back to the shift after five seconds as before; opened from
    the shift, a detail page stays. The close sheet is a Material bottom
    sheet on the sale screen whose open state survives rotation. Ended and
    closed sales no longer come up full screen on their own (decision 42's
    "except an unpaid one that still needs refunding"): the shift's "Needs
    action" count and the unpaid notification, which opens the sale's
    page, do that job now. The sale screen's tap on the XMR amount to copy
    it stays. For a sale another till found, the close sheet has two rows,
    "No: close it" (refund it in Square on that till) and "Yes: keep the
    code up": there is nothing this till can refund, so "No" and "Not
    sure" would do the same thing.
71. **Times the app doesn't know aren't shown.** The design's "Monokulo
    watches for a late payment until 4:22 pm" and "Paid, 10 confirmations"
    name things the API doesn't give the app (only `is_final`, and no
    confirmation count). The pages say Monokulo is still watching for a
    late payment, or has stopped (`settled`), and "Paid", with the times
    the app does have.
72. **The shift's summary sums the sales paid since the shift started**
    per currency, and shows each currency's total joined with " · " (one,
    in practice); a sale the last shift carried over (decision 86), paid
    after its refund, is listed but not counted. Times of day are
    written as the locale does ("3:58 PM" in the US, "15:58" in Britain),
    with a plain space before am or pm; the design's lower case isn't
    forced on a locale that writes otherwise.
73. **The product owner's acceptance criteria** (the team's
    `acceptance.md`) are the test plan, and its own calls where the designs
    are silent (D1 to D12) stand except where a decision above says
    otherwise: the shift list is saved (64), so the marks on a sale survive
    the app being killed rather than being "simply absent"; and the shift
    starts when the cashier turns watching on, not each time the service
    starts (64).

Made while the two streams were built and merged (the overlay, order
page, enlarged code and chip; the shift, sale screen, close sheet and
detail pages), recorded by the developers and folded in by the lead.

74. **The panel's face follows the sale's standing.** Live: the code and
    the ring row. Paid, of any kind: the green tick, `SaleText.status` (so
    a late one says "Paid after you closed it"), "0.31684 XMR received",
    Done, and it lowers after five seconds. Anything else, where the code
    was: a clock on `warnGround` (expired, cancelled, closed here), a
    warning on `dangerGround` for a reversed payment ("Payment reversed ·
    don't hand it over"), the refund arrow on `chip` for a refunded one
    ("Refunded in Square", "Nothing more to do."), the line "Open the shift
    to refund it in Square." or "Refund it in Square on Back till." (or "on
    the till that took it" with no device name), and one button, "Open the
    shift". The lit bubble draws the sale's phase ring (track, the orange
    arc a quarter a step, the ink pupil) in place of the loading mark, in
    its own window and docked above the panel; setup's previews keep the
    Waiting look. The panel's XMR line copies the amount when tapped, like
    the sale screen's; the enlarged code's doesn't, since a tap anywhere
    there shrinks it.
75. **The grabber's rule and its size** (adds to 65). `Panel.lowers(drag,
    height, velocity, fling)`: the panel lowers once dragged past a quarter
    of its height, or flung down at 800 dp/s or more
    (`Panel.FLING_DP_PER_S`); short of that it springs back (a snap under
    "Remove animations"). The phone panel moves with `Modifier.offset`; the
    tablet's floating one fades. The grabber measures 80 × 48 dp itself,
    the pill centred in it, and the card's top padding and the header's gap
    are gone, so the header starts 48 dp from the card's top: 12 dp lower
    than drawn, the price of a target a finger can't miss into the header
    row, which opens the shift and takes the cashier out of Square. The
    tap beside the panel lowers it through a pointer input, not a
    `clickable`, so TalkBack hears exactly two "Lower to the bubble"
    buttons: the docked bubble and the grabber.
76. **The order page's words and when it resets.** The panel's order page
    goes back to the code whenever the panel lowers, the bubble's sale
    changes, the sale stops being live or it has no receipt; on a tablet it
    shows inside the 360 dp card, at most 640 dp tall. Its subtitle leaves
    out "Square receipt …" when the sale has no Square reference, and its
    time is the receipt's issue time. Tip and Discount rows come from the
    receipt's extensions (`tip.amount`, `discount.amount`, as
    `SquareReceipts` writes them), the discount with a real minus (U+2212);
    the subtotal is the sum of the line totals. Offered no receipt (which
    shouldn't happen: the panel and the sale don't show the order line
    without one) it says "What was bought stays on the till that rang it
    up." The detail pages list every line with no cap and no link to the
    order page, whose bottom bar is "Show the code" and only makes sense
    live; the sale screen caps at four (three and "+N more", decision 66).
77. **The enlarged code leaves by itself.** `CodeScreen` calls back once
    the sale stops being live (paid, expired, closed), so `Screen.Code`
    never shows a dead code and the sale screen shows the matching page;
    `AppViewModel.back(from)` steps back only if that screen is still up,
    since `TillNav` may already have moved on, and a second Back would
    leave the sale. Full brightness is `screenBrightness =
    BRIGHTNESS_OVERRIDE_FULL`, keep-on is `View.keepScreenOn`, the status
    and navigation bar icons go dark, all restored on dispose. Its colours
    are the light tokens' roles and `Fixed` ones on white, so the screen is
    the same in both themes.
78. **The notification's words and its paid face.** Title "New Monero sale
    · Front Counter"; text "$13.88 · 3 items · waiting for payment" (no
    XMR, as the shade is drawn); the items on a second line of the content
    text, every line listed and left to Android to ellipsise, because
    `ProgressStyle` takes the style and the sub-text is the sale's number.
    The public version is the title and "$13.88 · waiting for payment": no
    item ever leaves the private one. The paid face is posted on the
    ongoing notification itself (`SaleNotifications.justPaid`: silent,
    four green segments filled, the tick as its large icon) for
    `Bubble.PAID_MS`, after which `Ongoing.of` goes back to the next live
    sale or to watching; the separate "Paid · Sale #…" notification (39)
    still alerts, unpromoted, with `ic_mark`. One ongoing notification, as
    before: the foreground service's, which becomes the sale.
79. **A refund's problem belongs to its sale.** `TillState.refundProblems`
    maps an order to the POS's reason ("Couldn't refund it in Square: …"),
    shown above that sale's Refund button until the refund is tried again.
    It replaced `TillState.problem` for refunds because `check()` resets
    that every two seconds, so the notice vanished before the cashier
    could read it.
80. **Where the till goes is `TillNav`**, pure and tested, which replaced
    `TillFocus`, `focus` and `showing`. `TillNav.follow(screen, sales,
    left)`: on the shift, the newest live sale the cashier hasn't left
    comes up; a sale on screen stays whatever it becomes; its code or order
    page gives way to the sale once it isn't live. `TillNav.paidOnScreen`
    and the view model step back to the shift five seconds after a sale is
    paid while its code was up; opened from the shift once paid, the page
    stays. Leaving a sale brings up the next live one at once, as the old
    screen did. A sale id the till doesn't list yet (a notification tapped
    before the service loaded the shift) shows the shift until it appears;
    a disconnected till shows the shift's Disconnected state whatever was
    on screen.
81. **When a shift starts.** `setShift(true)` after watching was off clears
    the saved sales but for those that still need action (decision 86)
    and stamps `shiftStartedAt`; `TillRunner.start` stamps
    it when it's null (setup's first run, or an install updated from before
    the shift was saved), so setup's test sale stays in the first shift.
    With watching off the summary reads "Not watching" over Start watching,
    and the rows still list what the till has.
82. **The pages' notes, in the app's own knowledge.** Closed: "The code is
    still live until 4:22 PM, and Monokulo keeps watching for a late
    payment after that: if one arrives, this sale turns paid." while
    `expiresAt` is ahead (the code's own expiry is known), else "Monokulo
    keeps watching for a late payment for a while: …", and "Monokulo has
    stopped watching it." once `settled`; Expired the same way with "The
    code ran out at 4:22 PM". A refunded sale's timeline says "Closed:
    customer not paying" whenever it was closed before the refund (the
    sheet's No, or the Closed page's Refund: both mean the customer wasn't
    paying), else "Code expired", "Cancelled" or "Reversed". The close
    sheet's "Yes" line is "14:52 left to pay." while waiting or part paid
    and "The payment is being confirmed." once seen (the design draws only
    waiting). Another till's live row has no "· on Back till" suffix; its
    ended rows do.
83. **The X only while live, and a paid sale is never refunded.**
    `Closing.choices(sale)` is empty once a sale isn't live, so the tinted
    X never shows on a paid or refunded sale. Three layers keep "No: refund
    it in Square" from refunding a payment that landed as it was tapped:
    `Till.refund` returns before the POS call when the phase is Paid or
    `refundedAt` is set; `AppViewModel.closeSale` applies the sheet's
    answer only while `Closing.applies(sale)` (the sale is still live in
    the runner's state); and `CloseSheet` hands the choice up only when its
    hide animation finished normally, so a sale that turned paid and
    swapped the screen out mid-animation drops the answer. `TillTest` and
    `ShiftListTest` cover it.
84. **Small things outside the plan's file lists.** `@Serializable` on
    `PaymentCode` (receipt) and `FoundSale` (sales) for the saved shift;
    the pure `ShiftList`, `SalePage`, `Closing` and `CloseChoice` in the
    app module beside `SaleText`; `Fixed.White` (the QR card, the enlarged
    code's screen, a glyph on ink) and `Fixed.Scrim` (the sheet's 62%
    near-black) in the theme, so the app has no colour literal outside it;
    `CircleButton` in Parts; `ProgressSegments` removed with the old sale
    screen; `MainActivity`'s own clipboard code replaced by
    `SaleNotifications.copyXmr`, which the panel uses too. New tests:
    `PanelTest` (the grabber rule, the header's place, the ended faces),
    `OrderPageTest` (counts, rates, totals, tip, discount, JPY, the
    subtitle, the countdown line), `NotificationFaceTest` (`Face.of`,
    `Ongoing.of`), `ShiftListTest` (filters, the Needs action count, the
    summary, which row opens what, the pages' words, the sheet's
    outcomes, `TillNav`) and `TillTest`'s saved shift, cap, close, reopen
    and refund guard.
85. **QA's first pass over the panel and the chip** (refines 65, 68, 74).
    The chip: Android 16 prints a chronometer, or a `when` in the future,
    as a promoted notification's chip text, so the ongoing notification
    sets no time at all (`setShowWhen(false)`, no chronometer) and the
    chip is the icon alone; the code's countdown is on the panel, the
    sale screen and the enlarged code. Android 16 draws the promoted
    chip itself, a system-themed pill with a white icon whatever
    `setColor` says, and refuses promotion to a colorized notification,
    so the chip can't be the board's orange pill or green tick: the
    shape of the icon (the ring filling, then the tick) carries the
    phase, and `setColor` still tints the icon in the shade. The panel's
    paid face: the overlay sees the sale it shows turn paid itself
    (`Panel.landed`) rather than waiting for the service's `paid()`, so a
    second sale waiting behind it comes up only after `Bubble.PAID_MS`
    and the cashier sees the payment land; `PanelTest` covers it. The
    panel's window on a phone draws edge to edge (`fitInsetsTypes = 0`,
    the cutout always), under the status and navigation bars, and pads
    itself with the safe drawing insets, so The order's ground runs under
    the bars. With the phone on its side the bubble docks beside the
    panel and the panel puts the code and its status to the right of the
    header, amount, order line and buttons, the code at its one size; the
    grabber heads that left column alone (still 48 dp, centred over the
    header) so the code starts 18 dp from the panel's top, the column's
    gaps close to 10 dp and the order line reads "15 items · Flat white,
    Oat latte, +3 more" on one line, which at the default font fits a
    phone's height with a few dp to spare; the card still scrolls when a
    large font needs more. The panel's paid hero and the bubble's paid
    look draw the detail pages' hero, the paid tick on the paid ground:
    the panel in the theme's colours, the bubble in the light theme's
    whatever the theme, as the rest of the bubble is; the solid green
    `PaidTick` stays only in onboarding's previews. The "Paid" and "No
    payment yet" notifications share a group, and once two are up a
    silent summary with the mark (`ic_mark`) heads it, opening the shift,
    gone when the Paid ones would be: Android's own bundle used the
    launcher icon, a solid square in the status bar.
86. **The end of a shift loses nothing to do.** Turning watching off
    closes every live sale (`Till.closeLive`, as the sheet's "Not sure"
    does), so no code stays up with nothing watching it; the shift's rows
    still list under Start watching, from the saved shift when the app
    starts with watching off (`TillRunner.showSaved`, a till that isn't
    running), and their pages still refund. Turning watching on again
    starts a new shift that keeps the last one's Needs action sales
    (`ShiftList.carried`) and drops the rest, so nothing needs a warning
    or a question first. A restart or a settings change that restarts the
    service closes nothing.
87. **Only a payment moves a sale that ended unpaid.** A double spend
    voids the payment, and the order's status then reads `pending` again
    with nothing saying it was reversed, so a resync once put a reversed
    sale back to Waiting with its code, bubble and chip. `Till` now keeps
    a reversed sale reversed (expired doesn't replace it either: it still
    mustn't be handed over) and never moves an expired or cancelled one
    back to a live phase; a paid status, late, still pays either.
88. **The launch intent acts once.** `MainActivity.onCreate` ran
    `handle(intent)` on every recreate, so a rotation or a theme change
    replayed the intent the activity was born with: a shared connect link
    reopened onboarding over the enlarged code, a notification's
    `EXTRA_ORDER` or `EXTRA_SHIFT` reopened that sale or the shift over
    whatever the cashier was doing. The intent is now handled only on a
    fresh create (`savedInstanceState == null`) and never when the system
    relaunches the activity from Recents (`FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY`),
    where Android hands back the original intent too; `onNewIntent` calls
    `setIntent` first so a later recreate sees the newest one. The view
    model keeps the screen across a recreate; after the process is killed
    the intent isn't replayed either, so the app comes back on its default
    screen rather than on a sale the cashier may have left, which is the
    plainer outcome. In the shift's top row the store and location are
    their own text and shorten first, so " · Watching", " · Not watching"
    or " · Disconnected" is never cut at large font sizes; the TalkBack
    label is unchanged.
89. **A phone is a phone whichever way it's held** (QA's third pass; refines
    36 and 65). `Overlay.wide()` read the display's current width, so a
    phone on its side (952 dp across) counted as a tablet: raising the panel
    from the bubble in landscape put up the floating card, a 360 dp window
    at the top right that holds only the header, the amount and the order
    line, clipped at the screen's bottom with no code and no buttons, the
    free bubble still lit beside it. The answer was also captured in the
    state flow, so it went stale until the next sale event: back upright
    the next raise was still the floating card, and a sale event in
    landscape with the phone panel up added the free bubble beside the
    docked one. Now `Panel.wide(smallestScreenWidthDp)` (600 dp and up,
    tested) decides, the same whichever way a phone is turned; `show`
    judges it on every pass rather than carrying it in the flow, the
    service forwards `onConfigurationChanged` so a fold opening or split
    screen is judged again, and a panel of the other kind is taken down
    and put up again rather than left clipped. Beside it, QA saw once a
    failed refund's Closed page with "Rung up" but no "Closed" row:
    `Till.add` read the known sale, built the new one and replaced it in
    two steps on `Dispatchers.Default`, so a close or refund stamped
    meanwhile on another thread could be overwritten by the resync; it is
    one `change` now, and a re-found sale keeps `refundedAt` as it kept
    `closedAt`. Not reproduced, so the race is closed on reasoning, not
    on a failing test.

## App: Wake-ups, the Monokular Bell (2026-10-28)

Made by the team's tech lead and product owner while wiring the app to the
Bell (`docs/design.md` §3, §5, §7; `docs/bell.md`), with the owner away.
The product owner's acceptance criteria (D1–D16 there) are taken as
written except where an entry below says otherwise.

90. **UnifiedPush's connector, `org.unifiedpush.android:connector` 3.3.5**
    (Apache-2.0, released 2026-08-22 on Maven Central), in a `push` module
    that depends on `core` alone (decision 56's module, back as the design's
    `push/`). `UnifiedPush.register(context, "bell", null, vapid)` hands the
    Bell's `vapid_public_key` (base64url, as `/v1/info` gives it) to the
    distributor, which then takes pushes signed by that key only; the
    connector throws `VapidNotValidException` for a key that isn't a P-256
    point. One instance for the Bell in use; another Bell, or the same
    Bell with a rotated key (D16: the key is kept in `BellRegistration`),
    unregisters the instance and registers again for the new key. FCM is
    not a path this release (D14): the embedded FCM distributor needs
    Firebase keys and a deployed relay, and neither exists; the app ships
    no Firebase dependency. The connector is wrapped behind
    `PushSubscriptions`, so the registration logic and its tests never see
    Android.
91. **Without a distributor, Wake-ups says so and stays on the timer.** The
    connector's dialogs and its `unifiedpush://link` deep link are not
    used: `UnifiedPush.resolveDefaultDistributor(context)` decides without
    an Activity. None installed: status `NoDistributor`, the card names
    ntfy, the choice is kept, and the app looks again at every app start
    and Settings open, so installing one and coming back connects without
    choosing again. One, or a default: saved (`saveDistributor`) and used.
    Several and no default: status `ChooseDistributor(packages)`, and the
    status card lists them as radio rows in the app's own style (D8); the
    pick is `saveDistributor`, and registration goes on. A saved one that
    is gone, or that unregisters the app (`onUnregistered`), takes the Bell
    registration back (`DELETE`) and is `NoDistributor`.
92. **The subscription's keys are the connector's, on this device.** The
    connector's `DefaultKeyManager` makes the P-256 pair and auth secret per
    instance, keeps the private key in the app's SharedPreferences encrypted
    AES-GCM under a key in the Android Keystore, and decrypts each push
    (RFC 8291) before `PushService.onMessage`: the app sees
    `PushMessage(content, decrypted)`. The private key never leaves the
    device and is never ours to hold; `allowBackup=false` and the backup
    rules keep it out of backups, and a restored till re-registers at its
    first start anyway (A12.3). A ding is taken only when `decrypted` is
    true and the content is the JSON object `{"wake":"square"}` and
    nothing else; anything else is dropped, never logged. Holding the
    private key in the Keystore itself (EC key agreement there needs
    API 31) is a follow-up, not this.
93. **A ding reaches the shift through the app's graph, and is dropped
    outside one.** `WakePushService` (the connector's `PushService`, in the
    app's process) gives each ding to `Graph.push.dings`, a `SharedFlow<Unit>`
    without replay; `TillRunner` collects it while a shift runs and calls
    `Shift.wake(WakeCause.Ding)`, and `BellWakeUps` collects it for "Last
    wake-up" and to end `MissingWakeups`. No shift (watching off, or the
    process was started by the ding alone): nothing checks Square, by the
    design's "outside a shift nothing runs" (D9), and no foreground service
    is started from a push (Android 14's rules make that a fight not worth
    having for a sale the cashier isn't watching for). The registration
    lives with the choice, not the shift (D4): registering costs a Square
    request and one of the Bell's 32 places per location, a ding outside a
    shift costs nothing, and the Bell never learns when shifts start.
94. **The pace follows the status, and a missed wake-up is detected by the
    timer.** `WakeUpStatus.interval` is 30 s for `Connected` and 2 s for
    every other status: one function, one test over all of them. `Shift.run`
    reads it afresh on every pass, so a change needs no restart, and tells
    the runner why it checked (`WakeCause`: `Timer`, `Ding`, `Opened`) and
    how many sales it found (`Till.check` answers the count). A *timer*
    check that finds a sale while `Connected`, with no ding in the 60 s
    before it, is a wake-up the Bell missed (D12): status
    `MissingWakeups`, pace 2 s, and one `POST /v1/register` again (the Bell
    may have deleted the registration after failed dings; it keeps the id
    for the same subscription). The next ding returns it to `Connected`.
    Replaces the lead's first cut, a ten-minute "doubt" that went back to
    30 s on its own: an unproven Bell should not slow the till. The app
    opening wakes the shift as before, in every status.
95. **Registering, in order, and registering again.** Choosing a Bell: the
    choice is saved, the old registration (another Bell's) is taken back,
    then `GET /v1/info` (10 s timeout; failure → `Unreachable(reason)`,
    with "no connection", "it didn't answer", "it answered 503" or "it
    isn't a Bell" for an answer that isn't `/v1/info`'s). Its `environment`
    must match the build's (`sandbox` or `production`), or it is
    `Refused("it's for Square's sandbox" | "it's for Square's production
    accounts")`, final. Its `square_application_id` must be the one the
    till signed in through, or `SignInNeeded(id)` (decision 96). No Square
    sign-in at all (or the till runs without Square): `NoSquare`. Then the
    distributor (decision 91), `PushSubscriptions.subscribe(vapid)`, and on
    `Subscribed`, `POST /v1/register` with `SquareAuth.accessToken()`, the
    location and the subscription: `201` → `Connected`, kept as
    `BellRegistration` in the encrypted store. Problems, read as RFC 9457
    `type`s with the Bell's `detail` as the fallback: `square-refused` →
    `Refused("Square didn't confirm this location. Sign in to Square
    again.")`; `too-many-registrations`, `endpoint-registered`,
    `invalid-request` → `Refused` with the acceptance criteria's words, no
    retry on their own; `rate-limited` → `Refused(…, retryAt)`, retried
    after `Retry-After`; `square-unavailable`, `internal`, other 5xx and no
    answer → `Unreachable`, retried with a growing wait, 1 min doubling to
    15 min (D5). Every app start and shift start with a Bell chosen
    registers again (`recheck()`: the Bell answers the same id); so do a
    new Square sign-in, a changed location, and a new endpoint from the
    distributor (`DELETE` of the old id first, since the Bell keys
    registrations by endpoint). The retries live in the graph's coroutine
    scope: no WorkManager and no alarm (D13); a process that dies retries
    at its next start. The connector's `NETWORK` failure is `Unreachable`;
    `ACTION_REQUIRED` and `VAPID_REQUIRED` are `Unreachable("the
    UnifiedPush app refused")`, an endpoint without keys `Unreachable("the
    UnifiedPush app gave no keys")`.
96. **A Bell with another Square application means signing in through it.**
    The till's Square application is `Stores.squareApplicationId ?:
    BuildConfig.SQUARE_APPLICATION_ID`, read by `Graph.squareConfig()`
    (D7: the application in use is saved with the sign-in). For
    `SignInNeeded`, the status card says "This Bell uses a different Square
    application. Sign in to Square through it to use the Bell" with a
    "Sign in to Square" row; the sign-in is started with that application's
    id (kept with the pending PKCE state), and only once it finishes is the
    id saved as the till's and the old sign-in replaced, so an abandoned
    sign-in leaves the working one alone (D6). The location is chosen again
    if the new account has several; the sales cursor is the location's and
    carries over. Back to the project's Bell is the same flow the other way;
    Timer only keeps whatever sign-in there is.
97. **Unregistering is awaited.** `DELETE /v1/register/{id}` (a `404` is as
    good as a `204`; 10 s at most), then `PushSubscriptions.unsubscribe()`,
    then the registration is forgotten, on: Timer only chosen (the choice
    is saved first; the "My own Bell" address is kept for next time, D11);
    another Bell chosen (before the new one's `POST`); signing out of Square
    (the `DELETE` before the sign-out, since it is awaited and needs only
    the id and the address; the choice stays, status `NoSquare`, and the
    next sign-in registers again); disconnecting the device (before
    `forgetAll`, which puts the choice back to Timer only); the distributor
    gone (decision 91). A Bell that can't be reached for the `DELETE` is
    tried once and the registration dropped locally; the Bell deletes a
    dead endpoint by itself after its push service answers 404/410, or
    after its failure limit.
98. **A Bell's address is `BellAddress`**: `https`, a path kept (a Bell
    under one), a trailing slash dropped, a missing scheme meaning https,
    shown as its host and path without the scheme; a user name, a query or
    a fragment is refused, and plain `http` is taken in a debug build only
    (a Bell on the developer's machine) (D2). An onion address is refused,
    and a Bell is reached directly, never through Orbot (D10): Square's
    webhooks force every Bell onto a public https address anyway, the ding
    goes through a public push service the till talks to regardless, and
    the Bell is told what it learns in the card's footnote; the lead's
    first cut, onion Bells through Orbot with an "Orbot isn't running"
    state, bought nothing for its two more states. The project's Bell is
    `BuildConfig.BELL_URL` from `-Pmonokular.bell.url` (default
    `https://bell.monokulo.com`, a placeholder until one is deployed), which
    takes whatever the field takes (a host, or `http://10.0.2.2:8445` for a
    debug build): a whole address rather than D1's host, so a Bell under a
    path works too. The subscription's endpoint is the distributor's and
    must be public https for the Bell to take it.
99. **Settings › Wake-ups, in the app's own parts, as the acceptance
    criteria's A1 lays it out.** Not a setup step; a fresh install and every
    updated one is Timer only (D3). Under the section label, one `Group` of
    three `ChoiceRow`s (new in `Parts.kt`: label, muted detail, a tick in
    the ink colour at the end of the chosen one, `selectableGroup` on the
    group and `Role.RadioButton` with `selected` on each row, the whole row
    tappable): "Timer only" / "Checks Square every 2 seconds", "Monokular
    Bell" / its host, "My own Bell" / its saved address or "Enter its
    address". With "My own Bell" chosen, a fourth row holds an
    `OutlinedTextField` "Bell address" (`KeyboardType.Uri`) and an
    `OutlineButton` "Use this Bell", enabled only when the field differs
    from the saved address and parses, with `BellAddress.problem` as a
    `Muted` line under the field. With a Bell chosen, a second `Group`:
    `FactRow("Checking Square", "Every 30 s" | "Every 2 s")`, `FactRow("Bell",
    …)` with the status word ("Connected" in `mk.paid`, "Connecting…", "Not
    connected", "Needs a UnifiedPush app", "Sign in needed", "Wake-ups not
    arriving"), "Last wake-up" while `Connected` or `MissingWakeups`, the
    distributor rows for `ChooseDistributor`, and an `ActionRow("Sign in to
    Square")` for `SignInNeeded`. The footnotes are A1.7's words, status by
    status, plus the privacy footnote on every Bell choice, and the Shift
    footnote changes with the pace (A1.8). All of it from one exhaustive
    `when` over `WakeUpStatus` in a pure `WakeUpsText`, tested string by
    string (D15). `Theme.kt` tokens only; both themes; 200% font scale wraps.
101. **The shift says why it checked, counts what is new, and re-reads its
    pace every 2 s** (dev-2; refines 94). `WakeCause` gains `Started`, the
    shift's first check: sales rung while nothing watched (a ding outside a
    shift is dropped, decision 93) are found by it, and a `Timer` find there
    would read as a wake-up the Bell missed. `Till.check()` answers how many
    of the POS's sales it found that the previous check hadn't, another
    device's included (the Bell rings for every sale at the location, not
    for one till's): a sale whose order failed and is found again isn't
    counted twice, and a POS that can't be read found 0. The timer waits in
    ticks of 2 s at most and reads `interval()` at each, so the pace going
    from 30 s to 2 s mid-wait checks within a tick rather than at the end of
    the 30 s (A5.24); the ticks cost a coroutine resuming, no request. The
    wait is a `select` over the conflated wake channel and the tick's
    timeout, not `withTimeoutOrNull { receive() }`, which can take a wake-up
    as it times out and drop it. The pace Settings shows is read off the
    status, the same `WakeUpStatus.interval` the shift reads, so the plan's
    `RunnerState.interval` would only have copied it and isn't added.
103. **Settings › Wake-ups' choices, where A1 is silent** (dev-2; refines 99).
    "My own Bell" tapped with no address saved yet ticks the row and opens
    the address field, but nothing is chosen until "Use this Bell": the
    saved choice, its registration and its status card stand meanwhile
    (hidden while the row waits, with the footnote "Enter the address of
    the Bell you run, then press Use this Bell. Until then Wake-ups stay as
    they were."); with an address saved, the tap chooses it at once. The
    field starts with the saved address as the row shows it, with its
    scheme only when that isn't https (a debug build's http Bell), so it
    reads back as the same address and "Use this Bell" stays off. The Bell
    row says "Sign in needed" for `NoSquare` as well as `SignInNeeded`
    (the emulator script's step 16 expects it; the footnotes tell them
    apart), and "Choose a UnifiedPush app" for `ChooseDistributor`, whose
    footnote is "Deliver wake-ups through one of the UnifiedPush apps
    above. Until you choose, Monokular checks every 2 seconds."; its rows
    are `ChoiceRow`s in the status card named by Android's label for each
    package, none ticked. `NoDistributor` adds a "Get ntfy" row in the link
    colour that opens `https://ntfy.sh/` in a Custom Tab, as Square's
    sign-in opens. Settings opening calls nothing: `UnifiedPushSubscriptions`
    hears a package installed or removed and `BellWakeUps` looks again by
    itself (decision 91), and the card follows its status live.
105. **The view model's side of signing in through a Bell, and of taking
    the registration back** (dev-2; refines 96, 97). `Stores.pendingSignIn`
    is a `PendingSignIn(pkce, applicationId)`: the application rides with
    the PKCE state (kept across the app being ended), null for the till's
    own. The attempt's `SquareAuth` is built with that application, so
    `authorizeUrl` and `finish` use its `client_id`; only after `finish`
    succeeds is `Stores.squareApplicationId` set (and the old token
    replaced by `finish` itself), the running till restarted as the new
    application, the location chosen again when the account has several,
    and the cashier taken back to Settings, where `chooseLocation`'s
    `recheck()` carries the registration on to `Connected`. A sign-in that
    fails goes back to Settings with Square's words and leaves the old
    sign-in working. "Sign out of Square" now runs as the view model's
    other network work does: `unregister()` awaited, then the sign-out.
    "Disconnect this device" awaits `unregister(forget = true)` before
    Monokulo's disconnect (the Bell's `DELETE` comes first in its log, as
    the emulator script's step 22 expects), and "Connect again" after the
    store disconnected the till does too; redeeming a new connect code
    takes back any registration left before `forgetAll`. A location change
    calls `recheck()`, since the Bell rings the till for the location it
    registered.
