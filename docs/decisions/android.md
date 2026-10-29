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
    shift". The lit bubble keeps the loading mark, its glints running while
    a sale is live (the owner asked for it back over the phase ring this
    decision first drew there), in its own window and docked above the
    panel; the phase shows in the panel's ring row. The panel's XMR line copies the amount when tapped, like
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
100. **The connector's service reaches the graph through the
     application, and a ding alone never makes Wake-ups.** `push` can't see
     `MonokularApp`, so `WakePushService` asks the application for a
     `WakePushService.Host` (`MonokularApp` is one) and hands each callback
     to its `UnifiedPushSubscriptions` (`Graph.push`), which turns them into
     `state` and `dings` and returns: no work, no service started, nothing
     logged. `Graph.wakeUps` is made on first use (the view model, Settings
     or a shift starting), and that first use is decision 95's "app start"
     (it registers again when a Bell is chosen); a process the distributor
     started for a ding outside a shift touches only `Graph.push`, so it
     costs the Bell no registration and Square no request. The connector's
     own manifest brings its distributor `<queries>`, `WAKE_LOCK`, its
     message receiver, its link activity (unused: decision 91) and an
     exported `RaiseToForegroundService` that a distributor binds to raise
     the app's priority while it delivers; the app declares only
     `WakePushService` (`exported="false"`, `PUSH_EVENT`), A12.1. The one
     thing in the app's logcat from Wake-ups is the connector's own debug
     line, "Found distributor with package name …", once per look at the
     installed distributors (each pass and each change of apps: QA counted
     125 in a 35-minute run); it names a package and nothing of the till's,
     so it stands (A9.5).
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
102. **The app sees a distributor come or go by itself.** The connector has
     no callback for a distributor uninstalled, and decision 91's "looks
     again at every Settings open" would have put a `recheck()` (a
     `POST /v1/register`, and a request to Square) on every visit to
     Settings. `UnifiedPushSubscriptions` registers, for the life of the
     process, a receiver for packages added and removed (a system
     broadcast, delivered for the distributors the connector's `<queries>`
     make visible), and `BellWakeUps` looks again only when it is waiting
     for a distributor (`NoDistributor`, `ChooseDistributor`) or when the
     one it subscribed through is no longer what `resolve()` gives:
     installing ntfy connects while Settings is open (A7.30), and
     uninstalling it takes the Bell registration back at once (A7.32); the
     emulator script's step 17 is where that is seen. The
     distributor is `Distributors.resolve()`: the saved one while it is
     installed, else the connector's `resolveDefaultDistributor` (the
     default or the only one, saved), else the list for the cashier.
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
104. **The Bell client is three suspending calls behind `BellClient`, and
     its errors are two exceptions.** `BellApi` takes the graph's client,
     with `Proxy.NO_PROXY` (decision 98: never Orbot, whatever the store
     does) and a 10 s `callTimeout` per call. A problem is `BellException
     (status, type, detail, retryAfter)`, `type` being the slug after the
     `#` of the Bell's problem address (`…/docs/bell.md#rate-limited`) and
     `retryAfter` from `Retry-After` or the body's `retry_after`; an answer
     that isn't the Bell's (a `/v1/info` that isn't its shape, an unknown
     `environment`, a `4xx` page without a problem) is
     `NotABellException`; no answer is the `IOException` OkHttp gives, a
     timeout an `InterruptedIOException`. `register` answers
     `BellRegistered(registrationId, squareApplicationId)`, and
     `BellWakeUps` makes the `BellRegistration` from it with what it sent.
     `BellApiTest` records the requests at a `MockWebServer`: the three
     routes only, no `Authorization` header on any, the token in
     `register`'s body and nowhere else, that body exactly the three fields
     and the subscription's public half (A9.1, A9.2).
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
    fails goes back to Settings with Square's words, shown above the
    Wake-ups cards, and leaves the old sign-in working. "Sign out of
    Square" now runs as the view model's other network work does:
    `unregister()` awaited, then the sign-out.
    "Disconnect this device" awaits `unregister(forget = true)` before
    Monokulo's disconnect (the Bell's `DELETE` comes first in its log, as
    the emulator script's step 22 expects), and "Connect again" after the
    store disconnected the till does too; redeeming a new connect code
    takes back any registration left before `forgetAll`. A location change
    calls `recheck()`, since the Bell rings the till for the location it
    registered.
106. **One pass at a time brings the Bell into line with the choice.**
     `BellWakeUps` doesn't keep a state machine beside the store: a pass
     reads the choice and the registration and does what decision 95
     orders, under one lock, and `status` is where it stopped. Choosing
     cancels a pass under way and starts one; `recheck()` joins one under
     way; a retry is a pass after a wait (`delay` on the graph's scope, so
     tests run it on virtual time). Once `POST /v1/register` is sent, it
     and saving its answer can't be cancelled, so a choice changed meanwhile
     always finds the registration to take back. `unregister` cancels the
     pass, then takes the lock: the `DELETE` (once, 10 s at most), the
     distributor's subscription ended, the registration forgotten. A pass
     that starts `Connected` (a shift starting, an app start) stays
     `Connected` while it registers again, so the pace doesn't drop to 2 s
     for a second; one that fails leaves it. The connector is asked for the
     subscription once per process (its docs: register at every start) and
     again only for another key or another distributor; an endpoint other
     than the registered one, at a start or from the distributor later, is
     a `DELETE` of the old registration and a `POST` of the new.
108. **A till that can't use its registration doesn't keep it.** A pass
     that ends at `NoSquare` (no sign-in, or Square ended it), at
     `SignInNeeded`, at the other environment's `Refused` or at
     `NoDistributor` takes back a registration it finds (`DELETE`): the
     Bell would otherwise ring a till that can't check Square for it, or
     keep a location for a till that left. Refusals from the `POST` itself
     leave it (the Bell says it left the registration as it was), and so do
     `Unreachable` and `MissingWakeups`, which retry. A missed wake-up
     (decision 94) also needs 60 s since the till connected, not only since
     the last ding: the first timer check after connecting can find a sale
     rung up just before, which no ding was ever going to announce.
110. **The distributor's failures have words too.** Decision 95's
     `Unreachable` reasons for the Bell are joined by the distributor's: "the
     UnifiedPush app refused" (`ACTION_REQUIRED`, `VAPID_REQUIRED`, an
     internal error, or a key the connector takes for no P-256 point), "the
     UnifiedPush app gave no keys" (an endpoint without them), "the
     UnifiedPush app has no connection" (`NETWORK`) and "the UnifiedPush app
     didn't answer" (no endpoint within 30 s: the distributor may have to
     reach its server first). All are retried as an unreachable Bell is.
     They fill A1.7's `reason` and nothing else changes on the screen.
112. **What the connector brings, checked.** `org.unifiedpush.android:connector`
     3.3.5's POM gives the Apache License 2.0; it depends on
     `com.google.crypto.tink:tink` 1.23.0 (the JVM Tink, with `protobuf-java`
     and `gson`, not `tink-android`, so there is nothing to force) and on
     nothing from Google's services: `./gradlew :app:dependencies` has no
     `com.google.firebase` or `com.google.android.gms` (A7.34). It keeps
     its registrations in a SQLite database and its keys in
     SharedPreferences, both under domains the backup rules already exclude
     with everything else (`allowBackup="false"` too), so the rules are
     unchanged (A12.3) and a restored or new phone subscribes and registers
     afresh at its first start. Its AAR carries no ProGuard rules; the
     release build's R8 pass keeps what the manifest names, but the emulator
     script runs a debug build, so a ding through a minified build is still
     to be seen before a release.
114. **The connector forgets the distributor with its last registration, so
     the app keeps it.** In 3.3.5, `UnifiedPush.unregister(context, instance)`
     removes the instance and then, with no registration left, the saved
     distributor too (`DistributorStore.remove()`; the distributor is still
     told to unregister, from the token it kept). The app has one instance,
     `bell`, so every Timer only, sign-out, Bell switch and rotated VAPID key
     would forget the distributor: the rotated-key path (unsubscribe, then
     subscribe for the new key) ended in "Needs a UnifiedPush app" with ntfy
     installed, and the cashier's pick among several (A7.31) was lost.
     `UnifiedPushSubscriptions.unsubscribe()` reads the saved distributor
     first and saves it again after unregistering; `subscribe()` with none
     saved resolves one (the default, or the only one installed) before it
     fails with `NoDistributor`. The build has no Robolectric, so this rests
     on the connector's bytecode, recorded here, and on two emulator checks
     in the script: pick a distributor among two, Timer only, the Bell again,
     and no second pick; unsubscribe inside ntfy, `NoDistributor`, and the
     next shift start connects again by itself. A distributor uninstalled
     with two others left is taken back as decision 108 says: the pass
     `DELETE`s the registration before showing the choice, so the Bell isn't
     ringing a dead endpoint while the cashier picks.
116. **A missed wake-up is judged at the timer's find, not later.** A
     timer-started check that finds a sale with no ding in the 60 s before
     is `MissingWakeups` at once (decision 94). The other way, waiting
     `MISSED_AFTER` past the find for a ding, would spare one case: a 30 s
     check landing on a sale whose webhook is still in flight. That case
     costs one idempotent `POST` and a few seconds at 2 s, since the ding on
     its way puts the till back to `Connected` when it lands. Waiting costs
     every real missed wake-up another minute at 30 s, which is what the
     cashier would notice, and a timer job of its own. So the verdict stays
     at the find. QA measured the cost on the emulator against fake-square,
     whose webhook follows the sale within milliseconds: twice in 35 minutes
     a 30 s check landed within 200 ms of a sale, before its ding, and the
     till showed "Wake-ups not arriving" and `POST`ed again, back to
     `Connected` within the second. Against Square, whose webhook takes
     seconds, the coincidence is likelier (a few per cent of sales) and
     costs the same: accepted as this decision says, and the first thing to
     revisit if the flicker is seen on a till.
118. **A distributor uninstalled ends the subscription twice over, so its
     end is judged by what is installed.** In 3.3.5,
     `UnifiedPush.getSavedDistributor()` finding the saved distributor gone
     broadcasts a local `UNREGISTERED` for each of the instance's tokens
     without removing them first, and the connector's receiver answers a
     plain `UNREGISTERED` with `UnifiedPush.unregister(context, instance)`,
     by instance. So `resolve()` in decision 102's look at a change of apps
     itself brings a `Lost`, after or before the pass that look started
     through the distributor left (the worse ordering also ends the `bell`
     registration that pass just made). `BellWakeUps` remembers the
     distributor a change of apps took away; at a `Lost` it takes the Bell
     registration back as decision 91 says and then asks `resolve()`: the
     same distributor still there, or none, is `NoDistributor` (the
     distributor itself ended it, decision 91); another, or several to
     choose from, is a pass that ends `Connected` or `ChooseDistributor`,
     subscribing afresh. An app updated (`PACKAGE_REMOVED` then
     `PACKAGE_ADDED`, both with `EXTRA_REPLACING`) is no change at all and
     no longer drives a look. Tested on virtual time in both orders of the
     change and the `Lost`; the emulator script's step 27 (two distributors,
     the used one uninstalled) is where it is seen on a device. The remembered
     departure lasts until a `Lost` uses it or the app ends the subscription
     itself, which is owed no `Lost`: the last distributor uninstalled has
     the pass unsubscribe before the connector's `Lost` lands, and
     `UnifiedPushSubscriptions` drops a `Lost` with nothing subscribed, so
     without that the departure outlived it and a later distributor's own
     end (the cashier ending the subscription in it) read as the old
     departure and subscribed again instead of showing `NoDistributor`.
     Tested: connected through ntfy, ntfy uninstalled (`NoDistributor`),
     NextPush installed (`Connected`), then NextPush's `Lost` is
     `NoDistributor` with no `POST` after it.
120. **A pass never takes the till down.** The graph's scope has a
     `SupervisorJob` and no exception handler, so anything a pass throws
     that isn't the Bell's or the network's (a store that can't be read or
     written, the Keystore under the encrypted preferences, the connector's
     SQLite or the package manager, Square's client failing some other way)
     would reach the thread's uncaught handler mid-shift, as `TillRunner`
     already guards against for Square. A pass catches every `Exception`
     but cancellation and settles `Unreachable` with decision 110's list
     joined by "something on this device failed", retried as an unreachable
     Bell is; the `Lost` handling does the same, a `DELETE` that fails any
     way is dropped locally as an unreachable Bell's is, and a failing look
     at installed apps becomes the next pass. Nothing about the failure is
     logged: its message can carry what the store held. Tested with a Bell
     client throwing `IllegalStateException`: the status settles, the scope
     lives, the retry and a later `choose()` connect.
122. **The registration remembers the distributor it was made through, and
     drops what nothing reads.** `BellRegistration` kept the location, the
     Square application the Bell answered with and the time it was made,
     and `BellInfo`/`BellRegistered` decoded the Bell's version and
     application id for them; nothing read any of it, so they are gone (the
     Bell's answers still decode: the client ignores unknown keys). In their
     place it keeps `distributor`, the package the subscription was made
     through, read as `subscribedThrough ?: registration.distributor` by
     decision 102's look and by the pass before it subscribes, which records
     a departure when `resolve()` gives another distributor. That covers a
     distributor uninstalled while the app was away: at the next start the
     connector's `getDistributor()` broadcasts the `UNREGISTERED` for the old
     token during the start's `resolve()` (3.3.5 broadcasts per token and
     removes nothing), the pass connects through the distributor left, and
     the `Lost` arrives after it with this process never having subscribed
     through the old one; without the stored package that `Lost` read as the
     new distributor's own end, `NoDistributor` with one installed until the
     next shift start. Now it is a pass that ends `Connected` through the new
     distributor with the registration posted again. Tested: a registration
     made through ntfy, a start with only NextPush, then the `Lost`.
     Registrations stored by earlier builds of this branch lack the field
     and read as none (the branch was never released).
123. **A distributor that answers late, or after being opened, connects
     the till at once** (lead, after QA round 1). QA's one major finding: a
     ntfy just installed sits in Android's stopped state until it is opened,
     so the connector's `REGISTER` to it goes unanswered, the pass settles
     "the UnifiedPush app didn't answer" after decision 110's 30 s, and the
     till waited out decision 95's first minute (83 s from the install to
     `Connected`) although the cashier had opened ntfy at once. Three
     things, none of them a change to the retry schedule: (1) an endpoint
     the connector delivers while no pass runs and no registration holds it
     starts a pass, so a distributor that answers after the 30 s connects
     the till the moment it does (`pushChanged` no longer needs a
     registration to act on `Subscribed`; a `Lost` still does; a
     subscription the last pass already took is nothing new, so a `POST`
     the Bell refused isn't tried again on the strength of the endpoint it
     was made with, which keeps decision 95's rule for refusals); (2) the
     activity coming to the foreground and Settings opening call
     `recheck()` when the status is `Unreachable`, and only then, so coming
     back from the distributor (or from the network settings) tries the Bell
     now rather than at the end of the wait, while `Connected` isn't
     `POST`ed again on every return and a `Refused` keeps decision 95's
     rules; (3) the reason reads "the UnifiedPush app didn't answer: open it
     once", which says what to do and stops the words from reading as a
     fault in the app just installed. Tested on virtual time: a late
     endpoint after `PUSH_SILENT` ends `Connected` with the subscription
     held registered and no second subscribe, and the wait that was set is
     cancelled with it; a `recheck()` 5 s into the wait connects. The
     foreground call is in the activity and view model, the one place the
     app knows it came forward; the push module stays without a lifecycle.
     Reading the status there makes the activity's first start decision
     100's first use of `Graph.wakeUps`, so the app-start registration now
     happens when the app is opened rather than at Settings or a shift; a
     process the distributor starts for a ding still touches `Graph.push`
     only.
124. **Settings › Wake-ups after QA round 1** (lead; refines 99, 103). Four
     findings on the screen, each taken: "Every 30 s" wrapped as "Every 30"
     and "s" at font scale 2.0, so the pace's space is a non-breaking one
     (` `); a debug build's http Bell showed the same detail as an
     https one of the same host ("10.0.2.2:8445" for both), so a Bell's row
     detail keeps its scheme when it isn't https, for the Monokular Bell row
     and My own Bell alike (`WakeUpsText.bell`), and the address field
     starts with the same text; the rate-limited refusal said "trying again
     in 58 seconds" for the whole minute, since the footnote doesn't count
     down, so it says "trying again in about a minute" (or "about N
     minutes" from `Retry-After`), which stays true while it shows; and
     tapping My own Bell with no address saved moved the tick and hid the
     status card while the saved Bell was still registered and `Connected`,
     so now the tap opens the address field and its footnote ("… Until then
     Wake-ups stay as they were.") above the saved choice's status card,
     and the tick stays with the saved choice until "Use this Bell", as
     A3.13 ties it to a saved mode. Decision 103's "ticked here" is
     superseded by this.
125. **What QA round 1 left, and why.** Recorded rather than fixed in this
     PR: a `DELETE` that the Bell was down for (decision 97's one try,
     then dropped locally) is not remembered, so the Bell keeps the old
     row until its failure limit trips, and ntfy.sh never fails a topic, so
     for a till that left while its Bell was down the row stays until the
     Bell's operator clears it; remembering the undelivered id and address
     in the store and sending it before the next `POST` to that Bell is the
     fix, and needs a store field, so it is a follow-up and not this
     round's change. Two findings are the till's, not Wake-ups': ten sales
     in one second stack ten code screens on the back stack (a new sale
     should replace an open code screen), and "Sign in to Square" from
     Settings › Square after a sign-out goes through onboarding's Connect
     Square step and lands on an already-seen sale's code (the Wake-ups
     card's own sign-in row returns to Settings correctly); both predate
     this branch, Wake-ups only makes the burst arrive faster, and both are
     follow-ups. Decision 116's coincidence and decision 100's log line are
     recorded there.

## App: Clover, the second point of sale (2026-10-28)

Made by the team's Kotlin lead and developers while adding Clover beside
Square (`docs/design.md` §1, §3; the Bell's side is `docs/decisions/bell.md`
B43 to B57), with the owner away. The research behind them, with its
sources, assumptions A1 to A6 and risks R1 to R10, is the team's
`clover-research.md`, summarised in the PR; the product owner's acceptance
criteria (D1 to D9 there) are taken as written except where an entry below
says otherwise.

126. **The point of sale is chosen in onboarding step 4 and in Settings, and
     kept as `pos.kind`; `PointOfSale` lives in `core`**, because `push` and
     `sales` both need it and `push` doesn't depend on `sales`. A till from
     before this build (Square tokens, no `pos.kind`) reads as Square, and
     its `square.skipped` reads as `pos.skipped`, so nobody is asked again.
127. **Clover signs in with its PKCE flow for low-trust apps, on the
     device, with no secret anywhere; the redirect is the App Link
     `https://monokular.monokulo.com/clover`** (a debug build:
     `/clover/debug/`, which [site/clover/debug/](../../site/clover/debug/index.html)
     forwards to `monokular-debug://clover`; with a stand-in, the scheme
     directly). Clover takes only https under the app's Site URL, so a
     custom scheme was never an option. Clover documents no `state`: the
     app sends one and checks it only when Clover echoes it; the binding is
     the one pending attempt (`PendingSignIn.pointOfSale`) plus the
     verifier, so a redirect with nothing pending answers "That Clover
     sign-in wasn't started here. Try again." The redirect's `merchant_id`
     is the merchant: there is no location to pick, one merchant per token.
128. **Clover's tokens keep Square's shape, now `PosTokens`, with the expiry
     converted from Clover's Unix seconds to RFC 3339; a 30-minute access
     token is refreshed under five minutes left, and the single-use refresh
     token is saved before anything else.** A `4xx` refresh signs the till
     out once with "Clover's sign-in has ended. Sign in again." and never
     loops (R1: Clover caps live refresh tokens per merchant per app, so two
     tills at one merchant may sign each other out); a `5xx` is transient;
     a `429` (refresh or API) holds the next look for its `retry-after` and
     keeps the sign-in.
129. **The Monero payment type is found by label (trimmed, case-insensitive,
     an enabled and visible one first), else created over REST with
     `labelKey` `com.clover.tender.external_payment`, and payments are known
     by its id**, remembered per merchant (`clover.tender.<merchant>`). A
     REST tender must use a system `labelKey`, so the id, not the key, tells
     Monero apart. Creating needs Merchant write; finding first means a
     merchant who added "Monero" in Clover needs no write (R9). A refused
     `POST` doesn't stop the sign-in: step 4's card and Settings ask the
     cashier to add a payment type called Monero in Clover and offer **Look
     again**, and nothing retries the `POST` on a timer. A remembered id
     deleted in Clover is forgotten and found again or made, only from that
     look.
130. **A Clover sale never paid is refunded by the cashier in Clover's Orders
     app, guided and then confirmed by the till.** Clover's REST API can't
     refund, void or delete an external-tender payment (R4), so the source
     offers `Refunds.InPos` (a type that can only look, rather than a
     `refund()` that does nothing: a departure from acceptance T4.1's
     method name), the sale gets `refundPendingAt` and stands as
     `RefundInPos` under Needs action, and the **Refund in Clover** sheet
     says which order, when and how much, with **I've refunded it** (asks
     Clover now; "Clover doesn't show a refund yet" if not) and **Not yet**.
     A refund is seen when the payment's non-voided refunds reach its
     amount, the order's `paymentState` is `REFUNDED`, or the payment is
     voided. The timer looks at pending refunds at most every 30 s, not on
     every pass as acceptance D9 says: without a Bell a pass is every 2 s,
     one request per pending sale, and the sheet's button asks at once. The
     alternative, recording the payment on an open order only once the
     Monero confirms (design B), is kept in the research for if a
     REST-made tender never shows in Clover Register (A1).
131. **`PosException` is a point of sale's common error; `SquareException`
     and `CloverException` extend it**, so `Till` and the view model catch
     one type.
132. **A Bell registration names its point of sale and its `location_id`
     (a Square location, or a Clover merchant: its only location), and
     `PosSide` replaces `SquareSide`.** The till reads the Bell's
     `points_of_sale` entry for its own point of sale, in this order:
     absent, refused ("it doesn't ring Clover tills"); another environment,
     refused in words naming it ("it's for Clover in Europe"); another
     application, `SignInNeeded(pos, applicationId)` and a sign-in through
     it, as decision 96 has for Square. `square-refused` and
     `clover-refused` match the till's own point of sale only, so a
     problem slug for the other is read by its status alone.
133. **A ding is exactly `{"wake":"<the till's current point of sale>"}`;
     anything else is dropped unlogged** (decision 92 widened): a Bell rings
     a till only for the point of sale it registered, so `{"wake":"square"}`
     on a Clover till is stale or forged.
134. **The Clover receipt groups the line items Clover repeats one per unit,
     reads `unitQty/1000` for measured ones, and shows one tax line, the
     order's total less its lines** (what the paper receipt shows; per-line
     `taxRates` would need another expand, acceptance D8). A total below the
     lines is shown as a discount, as Square's is, with no tax line. The
     merchant's name comes from `/v3/merchants`, its currency, time zone
     and country from `/properties` and its address; with no country the
     sale gets the plain code. `FoundSale.reference` is null (Clover prints
     no receipt number on the payment, acceptance D7), so the receipt reads
     "Clover order …". An order with 100 or more line items gets the plain
     code: Clover's `expand` returns at most 100, so the list may be cut,
     and total less lines would print the missing items as tax. Payments
     are looked at no further back than 89 days (Clover's 90-day filter
     cap), at most 20 pages of 100 a look.
135. **No "Back to Clover" button**: the Clover point of sale is its own
     device, not an app on the cashier's phone; the button stays Square's
     (`TillRunner`'s `onBackToPos` is Square's only), and the bubble beside
     a Clover device floats "over other apps".
136. **Every word on screen that names the point of sale comes from one
     `PosText` object, with an exhaustive `when` where the two differ**
     (acceptance D5; Wake-ups' reasons from `push`'s `PosWords`): "Refund it
     in Clover", "Checking Clover", "Merchant" where Square says "Location".
     Onboarding step 4 is "Connect your point of sale", the two as radio
     rows with the chosen one's card beneath, on the one screen that
     `Screen.PointOfSale` replaces `Screen.Square` with (acceptance T1.1
     describes one screen; there is no separate chooser).
137. **Build properties `monokular.clover.applicationId` (a placeholder
     otherwise), `monokular.clover.environment` (`north_america` by default;
     `europe`, `latin_america`, `sandbox`) and, for a debug build,
     `monokular.clover.baseUrl`** (a stand-in playing Clover's web and API
     hosts on one); `CLOVER_REDIRECT_URI` and `CLOVER_RETURN_URI` mirror
     Square's. A debug build's Settings field for the stand-in's address is
     kept as `debug.clover.base`.
138. **Changing the point of sale** (Settings › Point of sale › Change the
     point of sale, acceptance D6) first takes the Bell registration back,
     awaited, while the old sign-in is still there, then signs out of the
     old point of sale (tokens, location or merchant, cursor, remembered
     payment type), keeps the Wake-ups choice and the shift's saved sales,
     and goes to the new one's sign-in; choosing the one chosen changes
     nothing. The sign-out happens even when the old sign-in had already
     ended (a refused refresh, Square's sign in again), so no location,
     merchant, cursor or payment type id of a point of sale the till isn't
     signed in to is left behind; only the Bell step waits on a sign-in.
     It is `PosSwitch`, a pure helper tested on a recording fake,
     as `TillNav` is. Only a change forgets the old point of sale's cursor
     (where its search for sales had got to); Settings' "Sign out of …"
     (`PosSwitch.signOut`) keeps it, as Square's sign-out on Wake-ups did,
     so signing in to the same point of sale again reads on from there
     instead of reading the merchant's earlier payments again (Monokulo's
     idempotency key would make that re-read harmless, but it costs
     requests and Clover's rate limit for nothing).
     `Till` keeps the old sales safe from the new source: a sale another
     point of sale took is only ever marked to refund there
     (`refundPendingAt`), never sent to the till's point of sale, whose
     payment ids it doesn't know, and the timer never asks the new one
     about it; its "I've refunded it" takes the cashier's word, since
     nothing can ask the point of sale that took it, and the sheet says
     so ("Tap I've refunded it once it's done") instead of "Monokular will
     notice".
139. **A `401` from Clover before the token's expiry (Clover ended it early)
     is met by one refresh and the request asked once more**; a refresh
     another request already made is used instead, and a fresh token
     refused too signs the till out at once, so no later pass spends a
     refresh token (R1). `CloverAuth.refreshAfterRefusal`.
140. **A Clover order's title ("Table 4") is carried on the sale
     (`FoundSale.title`, defaulted so saved shifts still read)** from the
     payments already read with `expand=order`, no extra request; blank
     titles are none, Square leaves it null. The sale page shows it as its
     first fact, Order, wherever the page has facts: once the sale is
     closed, paid, expired, reversed or refunded (a sale waiting for
     payment shows its code and order card, with no facts, as before); and
     the Refund in Clover sheet names "order Table 4"
     rather than the order's id, which is what the cashier finds in Clover's
     Orders app.
141. **A sale found before the till's point of sale changed keeps the name
     of the one that took it, and is refunded there** (acceptance D6's
     "their source name shown as it was"). `FoundSale.pointOfSale` says
     which point of sale found it (a sale saved before Clover joined reads
     as Square's), so `PosText.of(sale, till)` is that, or the till's own
     for another till's sale (Monokulo doesn't say), and names every sale's
     words, receipt label and notification; the app keeps no list of its
     own. The till can't refund such a sale or see its refund (it is signed
     in elsewhere), so its page drops Refund and says to refund it there,
     and its close sheet offers what another till's does; one already told
     to refund it there keeps Refund, which opens the refund sheet named by
     the sale's point of sale, for the cashier's word (decision 138). The
     close sheet's "No" says the cashier refunds it when the sale's point
     of sale isn't the one the till's source watches or that one can't
     refund (`Closing.refundsThere`), `Till.refund`'s own rule, and any sale
     that then stands as `RefundInPos` opens the sheet.
     A sale screen's top is `SaleHeader`, from the sale and the till's
     point of sale: the receipt's name ("Clover order …"), the bar's second
     line, which names the old point of sale ("Clover · this till") rather
     than the location the till watches now, since that sale wasn't rung up
     there, and "Back to Square" only for a sale of the Square till it is
     (`PosText.elsewhere`, `PosText.placeOf`; the notification and the
     bubble's header say the same place). QA round 1 saw a Clover sale's
     order card turn "Square receipt" on a Square till: the bar and the
     button were this build's (fixed here); the receipt's name never reads
     anything but `FoundSale.pointOfSale`, which a saved shift keeps
     (`ShiftListTest` reads one back). The likeliest cause is the one QA's
     notes record: other streams' builds of `app.monokular.debug`, which
     name every receipt Square's and know no Clover, were being installed
     over this one on the same emulator during the run. On an emulator of
     its own against both stand-ins, a Clover sale left up through a change
     to Square reads "Clover · this till", "Clover order …" and no Back to
     Square, and still does after the app is stopped and opened again.
     A step 4 opened from Settings has Back and Done in place of the step
     number and the test sale, and the shift's top row says a Clover
     merchant's name once when it is the store's.
142. **Till's idempotency key keeps its `sq:` prefix for every point of
     sale**: changing it would change Square's keys for sales in flight,
     and two points of sale's payment ids never meet at one Monokulo
     connection.
143. **A till not signed in to a point of sale refuses a Square sale's
     refund rather than marking it refunded with nothing sent**:
     `TillRunner`'s `NoSource` (Square's, so a Clover sale is marked to
     refund in Clover as decision 141 has) throws `PosException` "Sign in to
     Square first", which the sale page shows as its refund problem. Before,
     its `Refunds.ByTill` did nothing and `Till` marked the sale refunded,
     on Wake-ups too.
144. **QA round 1 on the Clover till.** A registration taken back (a
     change of point of sale, a sign-out, another Bell) takes its Last
     wake-up with it, so Settings › Wake-ups starts again at "None yet"
     rather than showing the old point of sale's ding under "Checking
     Square". The screenshots are acceptance T6.2's set, from QA's run
     against tools/fake-clover (the dark Clover card from a run of the same
     build):
     [pos-choice](../screenshots/android/pos-choice.png)
     ([dark](../screenshots/android/pos-choice-dark.png)),
     [clover-signin](../screenshots/android/clover-signin.png)
     ([dark](../screenshots/android/clover-signin-dark.png)),
     [clover-connected](../screenshots/android/clover-connected.png)
     ([dark](../screenshots/android/clover-connected-dark.png)),
     [settings-pos-clover](../screenshots/android/settings-pos-clover.png)
     ([dark](../screenshots/android/settings-pos-clover-dark.png)),
     [refund-in-clover](../screenshots/android/refund-in-clover.png)
     ([dark](../screenshots/android/refund-in-clover-dark.png)) and
     [wakeups-clover](../screenshots/android/wakeups-clover.png)
     ([dark](../screenshots/android/wakeups-clover-dark.png)); beside them
     the sale with its Clover receipt
     ([clover-sale](../screenshots/android/clover-sale.png)), the refund
     not seen yet and then seen
     ([clover-refund-not-seen](../screenshots/android/clover-refund-not-seen.png),
     [clover-refunded](../screenshots/android/clover-refunded.png)) and
     Settings after changing to Square
     ([settings-pos-square](../screenshots/android/settings-pos-square.png)).
145. **An item sold by measure counts as one item.** The heads-up
     notification, the sale's order card and the order page count what was
     bought from the receipt (decision 63: "15 items"). The count added
     every line's quantity and rounded, which Square's whole quantities
     never showed; Clover's measured lines did, and "2 × Flat white,
     2.5 × Granola" read "5 items". A whole quantity now counts as that many
     items and a quantity that isn't whole as one, whatever it measures, so
     the same order reads "3 items": what the customer takes away. A
     measured quantity that happens to be whole (2.000 kg) counts as two,
     since a receipt line carries no unit; recorded rather than worth a
     field. `OrderText.lines` ("8 lines") is unchanged. (QA round 2.)
146. **A sign-out forgets the application the sign-in went through.**
     `Stores.squareApplicationId` and `cloverApplicationId` (decisions 96,
     132) exist for the tokens: a refresh goes to the application that
     issued them, so the id must live exactly as long as they do. Only
     `forgetAll` cleared them, so after signing in through another Bell's
     application, changing Wake-ups back to the project's Bell and signing
     out of the point of sale, the plain "Sign in to Clover" went through
     the other Bell's application again and the project's Bell asked for a
     sign-in a second time. `signOutHere` now forgets the id with the tokens
     (a change of point of sale and Settings' "Sign out of …" both go
     through it), so the next plain sign-in is through the build's own
     application, or the one the chosen Bell's row asks for. Changing the
     Bell choice alone leaves the id, as the tokens it refreshes are still
     in use; that Bell's status says "Sign in needed" until the sign-in
     through its row. (QA round 2.)
## App: motion (2026-10-29)

Made by the motion team (the lead designer, the tech lead and two
developers) while making the till's transitions fluid, with the owner
away. The owner's brief: no animation for its own sake, blazing fast and
memory efficient, no hacky animation code, the code always scannable, the
design unchanged.

90. **Motion is specified once, in `docs/motion.md`, and what it rejects
    stays rejected.** The spec names the principles, the presets (one
    `ui/Motion.kt`, springs and durations by purpose, no numbers at call
    sites), the spatial model (where each surface comes from and goes
    back to), each opportunity with its interruption and reduced-motion
    behaviour, and a list of motions considered and cut with the reason
    (window animation styles, a hand-animated brightness ramp, anything
    on the QR's modules, staggered lists, count-ups, chip colour fades, a
    paid celebration, new animation libraries). A cut motion is not
    brought back without a new argument written beside its old one; a
    new motion is added to the spec before it is built. The spec's
    "before" clips (`docs/reports/motion/before/`) were recorded on the
    base commit so the showcase can put the after beside them. Two
    things the designer saw while recording are findings, not motion
    work, and go to QA: the lit bubble's pulse ring is clipped by its
    own window by about 4 dp (the window's padding is 12 dp, the pulse
    reaches 16 dp past the circle), and on a tablet the floating panel
    can cover the free bubble when the bubble sits at the end edge at
    the panel's height.
91. **The motion presets are one `@Immutable` value under the theme, and
    reduced motion is its `Still` twin.** `ui/Motion.kt` (beside
    `Theme.kt`) holds `class Motion` with the specs `docs/motion.md` §2
    names (`surface`, `settle`, `flick`, `sweep`, `fade`, `fadeQuick`,
    `pulse`, `glints`, `Dwell.paid`), two instances, `Motion.Full` and
    `Motion.Still` (spatial specs `snap()`, fades `fadeQuick`, loops
    off), and `LocalMotion`, a `staticCompositionLocalOf` that
    `MonokularTheme` provides from `rememberReducedMotion()`: a
    `ContentObserver` on `Settings.Global.ANIMATOR_DURATION_SCALE`, so
    "Remove animations" takes effect without a restart and the three
    composables that each do a Binder read on first composition today
    (`LoadingMark`, `Circle`, `PanelCard`) read the local instead. Call
    sites write `motion.surface`, as they write `mk.ink`; a number at a
    call site is a review failure. Chosen over Material 3's
    `MotionScheme` (1.4.0 has it) because every moving thing here is a
    custom composable or an overlay window, not an M3 component, and over
    a `MotionDurationScale` in the coroutine context because Compose
    already applies the animator scale to its frame clock on API 33+
    (which makes every spring and tween snap at scale 0 by itself); what
    the local decides is the *shape* of a motion under reduced motion, a
    crossfade instead of a transform, and whether a loop runs, which a
    scale can't say. Springs for anything with a position (they retarget
    from the current value and velocity); tweens only for opacity.
92. **Overlay windows are made per open, and all their motion runs inside
    the window.** Measured on the base commit (`docs/reports/motion/
    baseline.md`): the panel window costs about 2 MB PSS and one
    `ViewRootImpl` while it's up and gives both back when lowered;
    keeping it alive hidden would hold that, plus a full-screen surface,
    for every minute of a shift with a sale up, and a hidden touch-modal
    window would need its flags flipped on every show. So `addPanel` and
    `removePanel` stay the window's lifetime, but the panel's content is
    added at progress 0 and the window is removed only when the collapse
    (`docs/motion.md` M1, M2) has landed, by `removeView`, not
    `removeViewImmediate`, which stays only in `stop()`. One `Animatable`
    in 0..1 per panel window drives `graphicsLayer {}` (scale, alpha,
    pivot at the docked bubble), seeded with the grabber's release
    velocity, so a second tap mid-grow reverses from where it is. The
    only window-attribute animation is the bubble's x on release (it is
    a window, and `updateViewLayout` already runs per move event): an
    `Animatable` on `AndroidUiDispatcher.Main` with `Motion.flick` and a
    `VelocityTracker`'s velocity, one relayout per frame for about
    300 ms, replacing `ValueAnimator` and its duration formula. Window
    animation styles were rejected with the spec (not interruptible, no
    origin). The tablet's floating window stays `WRAP_CONTENT` and grows
    from its corner nearest the bubble (spec §5): a strip window spanning
    the panel and the bubble's rect would give a true origin and keep
    Square's touches outside the strip, at the cost of owning the taps
    between them; left as the option to take if the corner pivot reads
    wrong on the device.
93. **Screens change inside one `SharedTransitionLayout` and one
    `AnimatedContent`, and predictive back seeks the same transition.**
    `MainActivity`'s `when (screen)` becomes `AnimatedContent(targetState
    = screen)` with a `contentKey` that makes `Sale(a)` to `Sale(b)` and
    `Sale(id)` to `Code(id)` transitions, and a `transitionSpec` by pair
    (`docs/motion.md` M7); the code is a `sharedElement` with
    `ScaleToBounds` (laid out once at the target size and scaled as one
    layer, which is what principle 3 needs: no re-layout, no re-encode,
    a scannable code every frame), the white card and the sale's row are
    `sharedBounds`. `SharedTransitionScope` is still
    `@ExperimentalSharedTransitionApi` in animation 1.12.1; the opt-in
    lives in the one file that uses it, and the fallback if the API moves
    at a BOM bump is a hand-rolled bounds animation on `LookaheadScope`
    + `approachLayout` (stable), which is more code for the same frames.
    Back: `PredictiveBackHandler` (activity-compose 1.13.0, over
    navigationevent 1.0.0; the manifest already has
    `enableOnBackInvokedCallback`) feeds a `SeekableTransitionState` so
    the gesture scrubs the shrink and a cancel springs back; the
    `BackHandler` rules of decision 77 are untouched, the gesture changes
    no state before commit. No BOM bump: compose-bom 2026.09.00 resolves
    to animation and foundation 1.12.1 (`animateItem`,
    `SeekableTransitionState`, `SharedTransitionScope`), material3 1.4.0
    and activity-compose 1.13.0, at minSdk 26. The enlarged code's
    brightness is set once, on the first frame, as today: the display's
    own controller ramps a window override, and a hand ramp is a relayout
    per step; the lead checks the ramp on a device and only a visible
    jump earns at most six steps over the transition (M8).
94. **One paid dwell, 5 s, everywhere.** `Bubble.PAID_MS` (4 s: the green
    bubble and the chip's tick), the panel's `delay(5_000)` before it
    lowers and `TillNav.PAID_STEP_BACK_MS` (the sale screen stepping back)
    become one `Motion.Dwell.paid` of 5 s, the acceptance criteria's
    number, and the bubble's fade back to lit or idle is driven by the
    panel's collapse landing, not by its own timer, so the docked bubble
    no longer turns white a second before the panel it is docked to
    moves on (`before-overlay2.mp4` at 0:24). Done and Back end the dwell
    early and run the same exit. The dwell is reading time, so reduced
    motion leaves it alone.
95. **Motion is tested on the JVM where it is a rule and on the emulator
    where it is a frame.** The rules (`Panel.lowers`, the collapse's
    velocity seeding, the ring's sweep target, which spec `Motion.Still`
    substitutes for which) are pure functions in `Motion`, `Panel` and
    `PhaseRing`'s companions, tested in `app/src/test` as `Panel.lowers`
    is today, with production values (no test-only specs). What happens
    over time (the code's node keeps its size through the enlarge, the
    ring's arc passes through a mid value and never a quarter mark, the
    panel's progress reverses on a second tap, nothing recomposes
    `QrCode` or `PhaseRing` per frame) gets Compose UI tests under
    `app/src/androidTest` with `composeTestRule.mainClock` (`autoAdvance
    = false`, `advanceTimeBy`), on `androidx.compose.ui:ui-test-junit4`
    and `ui-test-manifest`, androidx and test-only, run with
    `connectedDebugAndroidTest` against the round's emulators; no
    Robolectric (a new third-party dependency, and it stands in a fake
    `Choreographer` for exactly the thing being tested). CI stays
    `./gradlew build` on Linux this round; an emulator job for the
    instrumented tests is the lead's follow-up. Before and after numbers
    are taken with the same scripts on the same emulator instance in one
    session, compared as counts and ratios, never as milliseconds
    against a phone (`baseline.md`'s caveats), and the final numbers on
    a physical device.
96. **Six performance fixes ride with the motion work, each owned by the
    track that touches the file.** From `docs/reports/motion/baseline.md`:
    the lit bubble's pulse redraws its window at ~56 fps for as long as a
    sale is live, in two windows when the panel is up (dev1, with M4: the
    pulse runs only in the free bubble while a code waits and the panel
    is down); every countdown text starts its own one-second timer, six
    live rows meaning six recompositions a second (dev2, with M12: one
    clock per screen, tabular figures); `PanelActions` is rebuilt with
    seven lambdas on every state change and neither it nor `TillActions`
    is `@Immutable`, so the panel and the till's screens never skip
    (dev1 and dev2); the same code is encoded three times per sale and
    thrown away on every screen change, where a shared element wants one
    `ImageBitmap` (dev2 owns `Qr.kt`: a cache of the last few codes;
    dev1 uses it); the root of `MainActivity` recomposes on every runner
    update and would hand `AnimatedContent` new lambdas per poll (dev2:
    `derivedStateOf` on what each screen reads); the notification's
    large-icon code is encoded on the main thread in the service's
    collector (lead). Not a motion issue but measured on the way: JNA's
    `libjnidispatch.so` ships for four ABIs the app doesn't build for,
    about 0.5 MB of the release APK, an `abiFilters` line for another
    day.
97. **A preset is generic over what moves, and its thresholds are its
    own** (refines 91). A Compose spec is typed by its value
    (`FiniteAnimationSpec<Float>` for a progress, `<IntOffset>` for a
    row's placement, `<IntSize>` for a pill's size, `<Rect>` for a shared
    bounds transform, `<Color>` for a ground), and `SpringSpec` has no
    variance, so one `Motion.surface` value can't serve them all. Each
    preset is a `Preset` (`ui/Motion.kt`) with `fraction` (0..1, finished
    within a thousandth: a spring on a full-screen surface never jumps
    its last pixel, where Compose's default hundredth would be a visible
    28 px on a Pixel 9 Pro), `px` (within half a pixel) and
    `spec(visibilityThreshold)` for the rest, so a call site names the
    kind of value and never a number. `Motion.Still` is a second instance
    of the same class rather than a flag read at call sites: its spatial
    presets are `snap()`, its `fade` the 120 ms tween, and its loops are
    null, so `rememberInfiniteTransition` can't be started by mistake
    (it needs the spec). The dwell is `Motion.Dwell.PAID_MS`, the repo's
    style for a constant with a unit; `Bubble.PAID_MS` and
    `TillNav.PAID_STEP_BACK_MS` are gone. Tested on the JVM with
    production values: the presets' numbers, the order of their settling
    times (a fade quicker than a move, a row before a window, a window
    before the ring's sweep, the sweep under a second), that the sweep
    never passes a quarter mark on its way, and `Still`'s substitutions.
98. **The foundation carries what both tracks need, so neither waits.**
    `codeBitmap(text)` in `ui/Qr.kt`, the last four codes as one
    `ImageBitmap` each (96's cache, moved from dev2 to the foundation
    because dev1's panel container and dev2's enlarge both share the
    element); the instrumented test harness (`ui-test-junit4`,
    `ui-test-manifest`, `AndroidJUnitRunner`, with `androidx.test:runner`
    1.7.0 and `espresso-core` 3.7.0 pinned because the ones
    `ui-test-junit4` brings reflect on `InputManager.getInstance` and die
    on API 36 before a test runs; `app/src/androidTest/` with the
    foundation's two tests: the theme provides the motion the system asks
    for, and one code is one bitmap); explicit `compose-foundation`
    and `compose-animation` dependencies from the same BOM, which the
    tracks' `AnimatedContent`, `SharedTransitionLayout` and `animateItem`
    import; and the lead's own fix from 96, the notification's large-icon
    code encoded off the main thread.
99. **Shared files have one owner each, and the other track asks.** dev1
    (overlay): `overlay/*`, `ui/PhaseRing.kt` (M6 is used by every
    surface but lives in one file; the overlay track has less on its
    list), `ui/Mark.kt` (PR #18's file), `TillService.kt`. dev2 (screens):
    `MainActivity.kt`, `AppViewModel.kt`, `SaleText.kt`, `ui/Till.kt`,
    `ui/Parts.kt`, `ui/Qr.kt`, `ui/Theme.kt` and the rest of `ui/`. The
    lead: `ui/Motion.kt`, `notify/`, the docs, the measurements and the
    integration branch. A change wanted in the other track's file is a
    message to its owner; the integration branch takes each track's PR by
    rebase, dev1 first.
100. **The phone's panel grows once it has drawn, and the free bubble
    comes back only where the collapse landed** (refines 92 and amendment
    6; dev1). The panel window goes up at progress 0 with its docked
    bubble placed exactly over the free one (hidden until its slot is
    placed, so its first frame isn't a second bubble somewhere else); the
    free bubble window goes on the frame after one is drawn that way, and
    only then does the grow start, so the docked bubble always leaves from
    where the free one was. Started on add, the emulator showed two
    bubbles for a frame or two while the grow ran ahead of the handoff.
    Lowered, the order is the other way from §11's first wording: the
    panel collapses first, the docked bubble riding the same progress back
    to the free bubble's place, and the free bubble is added at rest there
    once the collapse lands; the panel window goes after the free bubble
    has drawn. Adding the free bubble before the collapse would show it at
    the edge while the docked one is still in its slot. The docked
    bubble's slide rides the panel's `raise` (one `Animatable`, one
    spring) rather than a second `settle` spring, so the card's origin and
    the bubble never part; it draws over the card (`zIndex`), which grows
    out of it. The order is a pure state machine (`overlay/Windows.kt`,
    `Handoff.next`) tested on the JVM; "drawn" is a pre-draw listener that
    asks for one more frame once the slot is placed, so reduced motion,
    which asks for no frames, still hands over.
101. **The overlay owns the paid moment, and the collapse ends it** (M9's
    overlay half; refines 94; dev1). One `Job` in `Overlay` holds the
    dwell (`Motion.Dwell.PAID_MS`); the panel's own `delay` in `SalePanel`
    is gone, so there is one timer in `overlay/`. A pure `PaidMoment`
    (`None`, `Held`, `Lowering`; `overlay/Windows.kt`, tested on the JVM)
    says what happens: at the dwell's end the panel collapses into the
    still-green bubble when nothing else waits, and `justPaid` turns false
    only when that collapse lands, so the bubble fades to lit or idle after
    it, never under a paid panel; with another sale waiting the moment
    ends at the dwell and the next code comes up in the panel, the bubble
    fading to lit as it does; with no panel up the bubble is green for the
    dwell. Done, the grabber or a tap beside the panel during the dwell
    end it with the same collapse. The chip's tick keeps its own timer in
    `TillService`, started at the same instant (a notification can't
    follow a collapse). One behaviour goes with the panel's timer: a panel
    the cashier raises on a sale that was paid earlier (nothing else
    live) no longer lowers itself after five seconds; it stays until Done,
    as any panel the cashier raised does. The dwell is for a payment that
    lands, not for looking back at one.
102. **The panel's code folds into its hero in place; the order crossfades
    in for now** (M5, dev1, round 1). Live to paid or ended, the code's
    slot stays: its height follows one `motion.surface` progress in a
    layout lambda, the white card scales down into the 88 dp circle at the
    same centre in its own layer (laid out once at 264 or 280 dp, never
    re-laid out, held to that size on every frame by a device test) and
    fades under `fadeQuick` while the hero fades in; the next sale's code
    grows back out of the same circle. The words under it and the buttons
    crossfade (`AnimatedContent`, the words' height settling). What the
    middle shows is `Panel.face(sale)`, tested on the JVM. The code and
    the order swap by crossfade (the docked bubble going with the code),
    and on a tablet the card's height settles to the order's; the phone's
    card-to-window container transform with the corner radius 32 to 0
    from M5 is not in this round: it needs the card and the order to share
    one container whose bounds are animated in layout, which reshapes
    `PhonePanel` more than a round allows next to M1's handoff, and a
    crossfade already says "the same panel, another face".
103. **A screen's clock is a `LongState`, and a countdown is its own
     composable** (dev2, M12, amendment 10). `rememberNowSeconds()` at a
     screen's root ticks one `mutableLongStateOf` on each second's
     boundary and is handed down as `LongState`, not `State<Long>`:
     `State<Long>.value` boxes a Unix time on every read. The read happens
     inside `Countdown` (a restartable `Text` wrapper) or a live shift
     row, never in an inline `Column`/`Row`, which would make the read the
     enclosing screen's. Each screen's clock is a default parameter, so
     tests drive it and the panel's `OrderContent` makes its own. Under
     Still the status words swap with the quick fade, not a snap: section
     7 makes every crossfade `fadeQuick`, and a snap between "Waiting" and
     "Seen" is the hard cut it exists to avoid.
104. **Recompositions are counted in the instrumented tests, by the
     compiler's trace markers** (dev2). `Composer.setTracer` in
     `androidTest/Recompositions.kt` counts each composable body that
     runs (a skipped one doesn't), so "the shift doesn't tick", "the code
     isn't recomposed by the grow" are assertions that fail in CI on the
     emulator, not a Layout Inspector session someone has to repeat. Test
     code only; it opts in to `InternalComposeTracingApi`.
105. **A shared surface is enabled only during its own move, and the
     grow moves don't fade whole screens** (dev2, M7, M8). `Nav.move` is
     a pure rule from the pair of screens (and whether the till, not the
     cashier, changed it); `Modifier.sharedSurface(key, during)` passes a
     `SharedContentConfig` whose `isEnabled` asks the running transition,
     at layout time, whether the move is `during`, so a sale that came up
     by itself rises instead of growing out of the row that appears for
     it in the same frame, and the sale root's surface stays off while
     its code or order card grows. Every surface is `scaleToBounds`
     (FillWidth, top): laid out once at its screen's size and scaled as a
     layer, never remeasured per frame. In `RowGrow`, `OrderGrow` and
     `CodeGrow` the screens themselves don't fade: the surface does the
     one crossfade inside its bounds and the screen leaving stays under
     it until it lands. On the software-GL emulator three full-screen
     alpha layers a frame (both screens fading plus the surface) cost
     more than the rest of the frame. The QR image's surface doesn't
     fade at all: the same bitmap on both sides, so one solid code shows
     on every frame. Settings' fade-through overlaps (exit `fadeQuick`,
     enter `fade` from 0.96) rather than waiting for the exit: a "then"
     would be a delay.
106. **Back is the activity's, so the gesture can preview it** (dev2,
     M7, decision 77 unchanged). The enlarged code's and the order page's
     own `BackHandler`s moved to the root, which calls the same `back()`.
     From a sale, its code, its order page and Settings, a
     `PredictiveBackHandler` seeks the stage's `SeekableTransitionState`
     toward `AppViewModel.backTarget()` (where `back()` would go, with no
     side effects), springs back from wherever it is on cancel, and calls
     `back(from)` only after the commit animation lands. Under Still it is
     the plain `BackHandler`.
107. **The emulator can't pass "no frame over twice the old p95" for a
     full-screen container transform, and that isn't a regression to fix
     there** (dev2, measured on emulator-5556, SwiftShader, host load
     20–50). A cut draws one or two frames a switch; a grow draws ten to
     fifteen, each rasterising both screens and the surface in software.
     Composition and layout per frame stay at the cut's level (anim p50
     2–19 ms against 5–12, layout p50 0.3–4.6 ms); the raster doubles.
     The code enlarge passes (p95 138–151 ms against 151, max 217 against
     a 302 limit); shift to sale doesn't (p95 434–630 against 209). Native
     heap steps up about 10 MB on the first transitions and then holds
     (+1.3 MB over ten more round trips): on SwiftShader the transition's
     layers are native memory. Both are to be measured on a device before
     anything is changed for them.
108. **Round 1 is integrated by rebase, dev1 first, and the screens track's
    decisions are renumbered 103–107** (lead). Both tracks wrote decisions
    from 100 in their own worktrees; the integration branch keeps dev1's
    100–102 as written (its code comments cite them) and carries dev2's
    five after them, so dev2's "100" is 103, its "104" is 107. Nothing
    else conflicted: the two tracks touched no file in common.
109. **The tablet's free bubble stops pulsing while the panel is up beside
    it** (lead, review of O3; refines 92's pulse rule). `Panel.pulses`
    already said "not with the panel up", but every free bubble was handed
    `panelUp = false`: on a phone that bubble is gone while the panel is up,
    on a tablet it stays beside the floating panel and redrew its window
    every frame for as long as the cashier read the panel. The bubble's
    window now collects the overlay's `panelOpen` and passes it to
    `BubbleFace(panelUp)`; the docked bubble's `pulse = false` is the same
    rule from the other side. A device test holds a Lit, Waiting bubble with
    the panel up to no halo.
110. **An overlay window's coroutines end with the window** (lead, review
    of O6). `BubbleFrame`'s edge snap runs in the overlay's scope (it moves
    the window's own layout params, which the window's composition can't
    outlive), so a bubble removed mid-snap kept animating a detached view,
    one caught exception a frame, until the spring landed. The frame
    cancels its snap and recycles its velocity tracker in
    `onDetachedFromWindow`. The rule for the rest of `overlay/`: a job that
    moves a window is cancelled where the window goes, and anything in a
    `Presence` already dies with its composition.
111. **After numbers come from before and after run alternately on one
    emulator in one session** (lead, measurement). The host's load ran
    from 20 to 170 during the round (three other emulators, Gradle, a Rust
    build), so a number taken at one hour against the baseline's taken at
    another says nothing. `ab.sh` installs the baseline's debug build and
    the integrated one in turn over the same data and runs the same flow
    twice each; the pair under the same load is what the verdict rests on,
    and the baseline's files are left as they were. A flow that draws more
    frames because it now animates (a grow where there was a cut) is read
    by its per-frame cost and its longest frame, not by its frame count.
112. **A back scrub is linear in distance; the spring belongs to the
    release** (designer, round 1 review of M7). The gesture seeks the
    stage's transition by a fraction of its duration, and the surface's
    bounds move on `Motion.surface`, a spring that covers most of its
    distance in its first third: on the phone a finger a third of the way
    across showed the shift complete with the sale already folded to its
    row, on the tablet a quarter of the way showed nothing moved. Neither
    is a preview. While the stage is being seeked, every spatial child
    (the shared surfaces' bounds, the slides) follows a linear track of one
    `Motion` duration, so progress 0.3 is 30 % of the way; the release,
    commit or cancel, animates the remaining fraction with
    `Motion.surface` seeded from the gesture (`SeekableTransitionState.
    animateTo(target, spec)`), which is where the physics was wanted. An
    uninterrupted move drives the same linear track with the same spring
    on its fraction, so the two paths cannot drift apart. Frames in
    `docs/reports/motion/review-r1/phone-back-scrub-hold.png`.
113. **The code's own surface is above every ground, and the ground
    carries no code** (designer, round 1 review of M8; sharpens
    principle 3). On both emulators, light and dark, the first frames of
    the enlarge show the enlarged screen's layout scaled into the card's
    width and clipped by it, a cropped code with no quiet zone, and then
    the whole-layer code jumps ahead of the white ground and hangs over
    its edge (`review-r1/phone-code-grow-frames.png`, `tablet-code-grow-
    frames.png`). "Scans on any frame" means the one bitmap, square, with
    its quiet zone, inside a ground that moves in step with it. The rule:
    `code/<id>` has a higher `zIndexInOverlay` than `code-card/<id>`; a
    ground's shared layer never includes the bitmap (what it scales is
    white, the heading and the foot); the two bounds run on the same
    track so they land together; the ground's corner radius follows the
    same progress from the card's to the screen's rather than snapping
    square on the first frame. A device test seeks the transition to a
    quarter, a half and three quarters and checks the code's node is
    square, un-clipped by its ground and no smaller than at rest.
114. **Overlay timing is read from framestats and screencaps, never from a
    recording on the software emulator** (designer, measurement). Every
    `screenrecord` of the phone and the tablet showed the panel appearing
    0.8–1.2 s after the tap, yet `dumpsys gfxinfo framestats` put the
    panel window's first frame 50–84 ms after the input command returned
    and its last at 404–443 ms, and a `screencap` 300 ms after a lower
    caught the collapse mid-way. The emulator's recorder lags overlay
    layers by up to a second and the in-app layers by less, so a
    recording can order events but not time them; it is used for what
    moves, and `framestats` for when.
115. **The panel's face is one `Transition`, and each side of it keeps
     the sale it showed** (dev1, round 2 review of M5). The fold, the
     code's and the hero's fades, the words and the buttons all follow one
     `updateTransition` of the face and its sale, keyed by the face, so
     they start on one frame; the side that is leaving keeps the sale it
     was shown for, so the code's status row fades out still saying
     "Confirming" with its ring in its own colour, never "Paid" (it said
     Paid for the length of its fade before: the old content read the new
     sale). The words fade through rather than across, with a new preset,
     `Motion.fadeThrough` (`fade`'s length, starting once `fadeQuick` is
     done): two different labels in one place ("Copy address" and "Done")
     drawn over each other at half alpha read as a smudge, and a
     crossfade is for two faces of the same thing. The words' size
     settles with `clip = false`, so the next sale's "Waiting for payment"
     is never cut to the width of the "Paid" before it. The panel ticks
     one clock (`rememberNowSeconds` at its card), so the last caller of
     the per-text `secondsLeft(expiresAt)` timer went, and the timer with
     it.
