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
116. **A phone's order grows out of the panel's card, a clip in its draw**
     (dev1, round 2; completes M5, replaces the crossfade of 102). The
     order is laid out once at the window's size and drawn inside a
     container that is a rounded rectangle from the code card's bounds
     (corners 32 dp) at 0 to the whole window (square) at 1, on one
     `motion.surface` progress of the page's `Transition`; the ground is
     drawn in the same clip, the code and its docked bubble fade out under
     `fadeQuick` and the order fades in with `fade`, and Back to the code
     is the same, reversed. The container is a clip, not a bounds
     animation, so no frame of it measures or lays anything out, and the
     path is made once per size and rewound each frame (built through
     `android.graphics.Path.addRoundRect` with four floats, which
     allocates nothing, where Compose's `RoundRect` would allocate one a
     frame). The container has no shadow: it starts exactly over the card,
     whose own shadow fades with the code under it. Under Still the
     progress snaps and the two crossfade (`fadeQuick`). The tablet keeps
     102's card whose height settles to the order's.
117. **A code whose time ran out is ended by the clock** (dev1, round 2
     review; both tracks' surfaces). Monokulo expires an order at its
     `expires_at`, but the till only heard so from the stream, so a stack
     that never sent it (the dev server's fake orders) or a stream that was
     down left a sale "Waiting · 0:00 left" for as long as the shift ran:
     the bubble pulsed for it, the panel's focus moved to it after every
     payment, and its dead code was offered as the next thing to scan. The
     runner now waits for the next live code's `expiresAt` and calls
     `Till.lapse()`, which moves every live sale past its time to Expired
     as Monokulo's own word would; the rule that only a payment moves an
     ended sale (87) means a server late to agree can't bring it back, and
     a late payment still pays it. One wait at a time (`collectLatest` on
     the next expiry), no polling. The dev server's fake orders still
     don't expire on their own; that is the other repository's, and the
     till no longer depends on it.
118. **A tablet's panel sits beside the bubble, and grows out of the
     bubble's side of it** (dev1, round 2; R1-O4, replaces the overlap QA
     logged under 90). With the bubble on the end edge the floating panel's
     window now stands off that edge by the bubble's window and an 8 dp gap
     (`Panel.floatingInsetDp`), so the card is next to the bubble and no
     frame has the bubble under it; with the bubble on the other edge the
     panel keeps to its own edge as before. The card grows from its side
     nearest the bubble at the bubble's height (held to the card), not from
     a corner, so the origin is literally beside the bubble. The plan said
     to slide the bubble to the panel's near corner on `settle` and back on
     lower; that would be a second window-attribute animation, which
     performance rule 9 allows only for the bubble's release, and it would
     move the thing the cashier placed. Moving the panel's window once, when
     it is made, costs nothing per frame. A bubble dragged to the other edge
     while the panel is up moves the panel's window once, on the snap's
     landing.
119. **A payment buzzes once, from the surface in front** (dev1, round 2,
     P2; §6). `PaidBuzz.bubble(appVisible)` is the overlay's half of
     the rule (the overlay reads it from the screens track's `PaidBuzz`,
     which names the one surface for either answer; the track's own
     `Haptics.bubbleConfirms` went in integration, two spellings of one
     rule): the free and the docked bubble buzz for a payment only while
     Monokular isn't on screen, and the answer reaches `BubbleFace` as a
     parameter from the overlay's state, so no composable under a window
     reads the app's graph. With the app in front its sale screen is
     the one the cashier is looking at and buzzes (S6, the screens track,
     reads the same rule from the other side), and a bubble still on its
     way out, or a tablet bubble the app came up beside, stays quiet. A
     device test counts the haptics a payment performs on the bubble with
     the app away (one) and in front (none); the test with the sale screen
     composed beside it waits for S6.
120. **The stage's progress springs, and every part of a screen change
    rides it on a straight line** (dev2, round 2; implements 112).
    `Motion.track` (a linear 450 ms, about as long as `surface` takes to
    settle) is what the shared surfaces' bounds, the rises and the slides
    are timed on, and `Motion.stage` (`surface.fraction`, null under
    Still) is what moves the stage's progress: `animateTo(screen,
    motion.stage)` for a change the till or a tap makes, `seekTo` under
    the finger, and the same `animateTo` for the release either way. Two
    presets were added to `Motion.kt` for this, the only way a seek and a
    spring can share one physics. The gesture first seeks to 0 and waits
    two frames, so the screen back goes to is composed and laid out
    before the finger moves anything: a shared surface's track starts on
    the frame its target bounds are known, and a first seek at 5 % left
    every later point 5 % short (the device test caught it). Where back
    goes is read from the model as the gesture starts, not in
    composition. A `BackScrub` is held from the gesture's start until it
    has committed or sprung back, and the enlarged code keeps full
    brightness while it is held, so a cancelled gesture no longer drops
    the brightness and the bar colours for the length of the preview.
121. **The code's white is a ground with nothing in it, and the code is
    the top layer** (dev2, round 2; implements 113). The card's white and
    the enlarged screen's white are one `sharedGround`: an empty rounded
    rectangle, remeasured to its moving bounds each frame (nothing inside
    to measure) rather than a scaled screen, its corner radius going from
    32 dp to 0 on the stage's progress (`Stage.shown`, on the same track
    as the bounds), so both sides draw the same shape and the corners
    don't snap square. The bitmap is `code/<id>` at `Layers.CODE`, over
    every ground; the enlarged code's own card has no white (it sits on
    the screen's), because a second white card drawn at rest under the
    moving one made the ground look like two rectangles. The heading and
    the foot render over the ground while the move runs
    (`overSurfaces`) and fade on the second half of the arrival, the first
    half of the leaving, read in `graphicsLayer` with `ModulateAlpha`.
    Because both bounds run linearly on one progress, a rectangle inside
    one at both ends is inside it on every frame: `MotionFramesTest`
    seeks the grow and the shrink to a quarter, a half and three
    quarters and checks the code is square, no smaller than at rest and
    has pure white on all four sides (its quiet zone, on its ground) on
    every one. The same test writes every screen move's seeked frames for
    review (the `additionalTestOutputDir` the runner pulls), so a cropped
    code is caught in CI rather than in a recording.
122. **The order page fades in over the sale while its card grows**
    (dev2, round 2; the designer's review of M7's OrderGrow). Only the
    list is the order's surface; the page's own ground, bar, heading and
    "Show the code" bar were drawn at full opacity on the first frame,
    covering the sale while the card was still its sale size, so the move
    read as a page appearing and then a card stretching. The page now
    fades in (`fade`) over the sale, which stays under it, and on the way
    back fades out first (`fadeQuick`) over the sale returning beneath. One
    full-screen alpha layer for 200 ms, which the host-GPU emulator draws
    within the frame; the RowGrow and CodeGrow pairs keep their
    no-fade rule (105). A device test checks the frame at progress 0 is
    the sale itself.
123. **A sale paid on screen closes its code into the page's hero, and a
    payment is one buzz from the surface the cashier is looking at**
    (dev2, round 2; M10, M9's app half, §6). `SaleScreen` is one
    `AnimatedContent` over `sale.live`: the page fades in over the live
    sale (which stays under it, no second alpha layer), and the code
    card is a `faceSurface` with the page's 72 dp hero: its bounds close
    on `Motion.surface`, scaled as one layer, the QR fading under
    `fadeQuick` while the tick fades in, the clip's corners going from
    the card's 32 dp to the circle's on the same spring (one reused
    path, nothing allocated a frame). "Show the code again" is the same
    move reversed, and a change mid-morph retargets. The hero is also
    `badge/<id>`, a surface during `RowGrow`, so the paid page stepping
    back after the dwell shrinks its hero into the row's badge, and a
    paid row opened from the shift grows its badge into the hero. The
    amount crossfades rather than moving as its own element: one more
    shared element for a number the cashier has already read. The buzz:
    `PaidBuzz.by(appVisible)` names one surface, the app when it is on
    screen, else the bubble; the view model emits when a payment lands
    (a sale live at the last look is paid now) and the activity buzzes
    only while started, and the bubble's `Confirm` buzzes only when the
    rule says bubble, so a bubble still leaving as the app comes up
    doesn't buzz a second time (in integration this became the
    `confirms` parameter of 119, not a read inside `Confirm`).
    `leave()` cancels the paid step-back, so Back during the dwell is
    the only step back.
124. **The window's background is the theme's ground** (dev2, round 2;
    review finding on the dark-mode recreation). The app ran on
    `Theme.Material.Light.NoActionBar`, so the frame between a light/dark
    change recreating the activity and Compose's first draw was the
    light window background: one white frame in the dark theme.
    `Theme.Monokular` (light and night) sets `windowBackground` to
    `@color/ground`, which a JVM test holds equal to `LightTokens.ground`
    and `DarkTokens.ground`, so the colour role stays defined once in
    `Theme.kt` and the resource follows it.
125. **A surface's two faces crossfade per draw, on the stage's progress,
    never through an offscreen layer each** (dev2, round 2; the review's
    native-heap blocker). On the host-GPU emulator the GPU's resource cache
    after ten shift-to-sale round trips held 86 MB (84 MB of it purgeable
    scratch textures) against 2.3 MB before: `sharedBounds`' `fadeIn`/
    `fadeOut` are `graphicsLayer` alphas with the default compositing, so
    each face of a full-screen surface was drawn into a screen-sized
    offscreen texture on every frame of the move, and the cache kept them.
    The faces now draw straight into the overlay with
    `CompositingStrategy.ModulateAlpha`, their alpha a function of
    `SeekableTransitionState.fraction` toward the screen they belong to
    (the arriving face in over the first `Motion.crossfadeOnTrack` of the
    move, the leaving one out over the last), read in `graphicsLayer {}`,
    so a cancelled gesture fades back the way it came. The leaving face's
    exit is a `fadeOut` that holds alpha at 1 for the move: the
    `sharedBounds` contract needs an exit to keep a leaving face drawn, and
    one at full opacity costs no layer. After: 5.1 MB and 0.2 MB of GPU
    cache in two runs. `Motion.crossfadeOnTrack` (fade's share of track)
    is the third preset this round adds to `Motion.kt`.
126. **The shared surfaces cost 6–14 MB of native heap once, and that is
    recorded rather than fixed this round** (dev2, round 2). Measured on a
    host-GPU emulator after decision 125 took the GPU cache back to the
    baseline's: 2.6 MB is Skia's glyph cache (text in a scaled surface
    rasterised at each size it passes through, bounded by Skia's own
    budget), the rest a step on the first transitions that holds flat
    afterwards and that `RUNNING_CRITICAL` doesn't return. Neither is a
    leak in the till's code (nothing grows with round trips), and the
    obvious levers would cost what the motion is for: not scaling text
    means re-laying out each frame, which principle 4 rules out. Settling
    it needs a device: the owner's Pixel was attached, but installing on
    it needs their say-so. Until then the 2 MB PSS rule is not met on the
    emulator and the after report says so.

127. **The integrated build is measured against live codes, on one
    emulator at a time** (lead, round 2 integration). The shared dev
    stack held nine "pending" orders 9 to 66 minutes past their
    `expires_at`: Monokulo would have expired them, the fake stack never
    does. The baseline build shows such a code live for as long as the
    shift runs ("Waiting · 0:00 left", 117's bug) and raises its sale by
    itself, the integrated build ends it by the clock, so the two would
    have measured different shifts and neither would have found a live
    row to open. The dead orders were marked expired on the stack
    (`__dev/orders/<id>/expired`, what Monokulo itself says of them), each
    pass of the A/B rings up two fresh sales first (the stack's codes
    live 15 minutes, a pass takes about five), and the phone and the
    tablet run one after the other, since a sale rung up for one till
    comes up by itself on the other and would land mid-flow. `to_shift`
    now leaves up to forty sales before giving up, not twelve: with other
    tills ringing up on the same store, twelve was not enough.

128. **The integrated build is not signed off on memory: the native heap's
    step is named, and it is text rasterised at every scale a surface
    passes through** (lead, round 2 integration; sharpens 126). On both
    emulators, before and after alternately, the integrated build holds
    12 to 24 MB more PSS at rest than the baseline after ten shift ↔ sale
    round trips (native heap +11 to +15 MB), and a `RUNNING_CRITICAL`
    trim returns 1 to 3 MB of it. `heapprofd` on the phone emulator,
    attached before the process started, puts the step in two places:
    1.6 MB at start (more JIT code, the first glyphs), then 7.5 MB over
    the ten trips, of which 5.1 MB is Skia's glyph strike cache and text
    atlas (`SkStrike::prepareImages` and `GrDrawOpAtlas::addToAtlas`
    under `AtlasTextOp`; `dumpsys gfxinfo` shows the glyph cache at
    3.0–4.3 MB against the baseline's 0.2). That is the shared surfaces
    scaling as layers (`ResizeMode.scaleToBounds`): the text inside a
    moving row or sale is drawn through a different matrix on each
    frame, every scale is a new strike, and each glyph is rasterised and
    uploaded again at that size. The cache is capped, so it is a step
    and not a leak, but a trim does not return it. One run also held a
    14.3 MB purgeable GPU scratch texture (16.45 MB of GPU caches against
    2.3), the size of one screen in RGBA: a whole-screen offscreen layer,
    which the order page's `fadeIn` on `OrderGrow` (122) would make the
    way the faces' fades did before 125; it did not recur in the second
    run and is not yet pinned to that. The screens track owns the fix:
    either the text inside a moving surface fades at its resting size
    while the ground and the glyph scale (so no glyph is ever drawn
    scaled), or the moving surface is one offscreen layer only for the
    move's frames with its texture released on landing, measured for
    both heaps; and the order page's parts take their alpha per draw as
    the code screen's heading does (`overSurfaces`). Measured again with
    `mem-r2.sh` and `heap-r2.sh` (the lead's scratch folder), the 2 MB
    rule is judged on PSS after a trim.

129. **A Back that lands mid-move tells the model the screen it was
    really on** (designer, round 2 review; blocker, screens track). On
    the integrated build, tapping the code and pressing Back before the
    grow has settled leaves the till unable to go Back or enlarge the code
    until an on-screen button is used (reproduced three times on
    emulator-5558: `input tap <code>; input keyevent 4` in one shell, then
    Back twice and a tap on the code, all ignored; the header's arrow
    recovers it). `StageBack` reads `from = state.currentState`, which
    during a running move is still the *previous* screen (the sale), so
    after the gesture lands on the sale it calls `back(from = Sale)`,
    `screenState` is `Code`, nothing matches and the model stays on
    `Code` while the stage shows the sale. The rule: what Back is "from" is
    the model's screen at the gesture's start (`state.targetState`, the
    screen the model is on), never `currentState`; a gesture that starts
    while a move is running retargets that move from its current fraction
    (the seekable state already allows it) rather than seeking a new pair;
    and the stage and the model are never allowed to disagree at rest: a
    device test taps the code, presses Back within 100 ms, and asserts the
    model is on the sale and a second Back reaches the shift.
130. **Every screen's frame at rest after a move is the frame it has
    under Still** (designer, round 2 review; blocker, screens track). With
    motion on, the order page's list card is not on screen once
    `OrderGrow` has landed: the page shows its heading and the "Show the
    code" bar over an empty ground, and the list's nodes are missing from
    the semantics tree; under `Motion.Still` the same page draws its lines
    (`review-r2/phone-order-page-motion-vs-still.webp`, sale #1257 with six
    lines, emulator-5558, light). The seeked frames in `frames/order-grow-
    *.webp` show the card drawn mid-move, so the card's face or the surface
    it rides is left at zero once the stage is at rest. The rule, and the
    test that keeps it: `MotionFramesTest` adds a rest frame after each
    move and compares the semantics tree after a move has landed with the
    tree composed under Still for the same screen; they must be equal.
131. **Two different texts are never drawn over each other above half
    opacity, on any surface** (designer, round 2 review; major, screens
    track; generalises 115 from the panel to the screens). Decision 125's
    crossfade brings the arriving face in over the first 44 % of the track
    and the leaving one out over the last 44 %, so from 44 % to 56 % both
    are opaque and either side of that one is above 0.8. Where the two
    faces hold different words in the same place the result is a smudge:
    the code screen's foot over the sale's "Copy address / Back to Square"
    (`frames/code-grow-50.webp`, `-75.webp`, and a recorded frame in
    `review-r2/phone-code-grow-recorded-frame.webp`, where the heading sits
    over "Sale #1249" too), the order page's heading over the sale's
    (`frames/order-grow-25.webp`) and its three-line card's words over the
    full list's (`order-grow-50`, `-75`), and the paid fold, where the
    whole page dissolves over the live sale and an eight-line order shows
    two receipts at once (`review-r2/phone-paid-fold-dissolve.webp`,
    `frames/paid-fold-120.webp`). The rule: words that differ between two
    faces fade through, the leaving under `fadeQuick` on the first part of
    the track and the arriving over the last (`Motion.fadeThrough`), as the
    panel's do; a card whose content differs between its faces (the order
    card's three lines against the whole list) shows one content during
    the move, the arriving one laid out at the target and scaled as one
    layer; and in the paid fold only the hero, the heading line and the
    buttons change: the order card is the same card on both sides and
    rides `order/<id>` to its place instead of dissolving.
132. **The code's ground keeps the code's rest margin on every frame of
    the enlarge** (designer, round 2 review; major, screens track;
    sharpens 113 and 121). `frames/code-grow-75.webp` and `code-shrink-25`
    show the white only two or three pixels wider than the code on either
    side; the code's bounds and the ground's are not interpolating between
    corresponding rectangles (the code reaches the ground's width before
    the ground reaches the screen's), so the quiet zone collapses for the
    last quarter of the grow and the first of the shrink. The rule: on
    every frame the white around the code is at least the smaller of the
    two rest margins (the card's and the enlarged screen's), which linear
    tracks between corresponding rectangles give by construction, and the
    device test measures that margin rather than "some white on each
    side".
133. **A ring's two colours are two strokes, faded through, never a blend**
    (dev1, round 3; review: the olive ring on the next sale). The bubble's
    ring went from paid green back to the lit orange through
    `animateColorAsState`, so for the whole fade the arc was a colour that
    means nothing (`frames/phone-next-sale-q1.png`), and the same blend ran
    the other way on every payment. `PhaseRing` now draws the arc twice, in
    the fill and in the paid colour, each with its own alpha read in the
    draw: the colour going fades out under `fadeQuick` and the colour coming
    fades in under `fadeThrough`, which starts when the other is gone. A
    plain alpha crossfade of two strokes in one place would still be the
    same olive (one over the other is the blend), so the arc passes over
    the bare track for a few frames instead; the track is the ring's
    neutral, and the sweep keeps moving under it. It is the one ring, so
    the bubble, the panel's status row, the sale's pill, the enlarged
    code's foot, a row's badge and the close sheet's glyph all get it.
    `PhaseRingOnDeviceTest` reads the arc on every frame of Confirming to
    Paid to Waiting and fails on any colour that isn't the fill or the
    paid colour over the track.
134. **A card's shadow fades with the card** (dev1, round 3; review: the
    grey box left by the lower). The panel's grow and collapse fade the
    card per draw (`CompositingStrategy.ModulateAlpha`, so there is no
    window-sized offscreen buffer), and a parent's alpha drawn that way
    never reaches a child's elevation shadow: the renderer draws shadows
    with the casting node's own alpha only. So the card faded out of a
    shadow that stayed whole (`frames/phone-lower-q2.png`), a grey square
    over Square for the last frames of every lower. The card's own layer
    now casts its shadow from `16 dp × Panel.shown(raise)` in
    `DefaultShadowColor` at that same alpha, `Panel.shown` being the one
    rule the grow's fade already used (opaque by a third of the way). An
    offscreen layer for the fade was the other fix and the one ruled out by
    decision 125's reasoning: a screen-sized texture per window for a
    shadow. Every surface with a shadow under a per-draw fade gets the
    same: the phone's card, the tablet's floating card and its order card,
    and the free bubble's circle, whose window fades it in and out on
    appear and go (`BubbleFace(presence)`). `OverlayFramesTest` lowers the
    panel over white and fails when the band under the card is darker than
    the card's opacity allows (`frames/phone-lower-light-q*.png`); on the
    old build it read 0.14 dark under a card 5 % opaque.
135. **A payment code is never faded in on the panel; its words fade
    through around it** (dev1, round 3; review: the next sale's code at
    40 % then 85 %). Performance rule 6 and principle 3 say the code is
    never faded in, and the panel broke it in three places: the next
    waiting sale's code grew out of the paid hero fading in with
    `motion.fade` (`frames/phone-next-sale-q1.png`, q2), a tablet's code
    came back from the order inside `AnimatedContent`'s `fadeIn`, and a
    phone's did the same under Still, where the order's ground snaps away
    and nothing covered the fade. A customer's camera may already be on the
    panel when the next code comes up. Now the code's layer is opaque
    whenever its face or page is the target: the next code grows from the
    hero's size at the same centre, opaque, over the hero, and only the
    words (the grabber, header, amount, order line, status and buttons)
    fade, through `SalePanel(words)`, one alpha read in each of their
    layers, arriving under `fadeThrough` so the words leaving are gone
    first (131). On a tablet the code's page comes in under the order
    (`targetContentZIndex`), whole, while the order fades off it; the
    order's own entrance now fades through too, where it used to fade in
    over the panel's words. Under Still a phone's order fades its ground
    with its words, a 120 ms crossfade off a whole code, instead of an
    empty ground covering the code for the length of the transition. The
    code still fades out into a paid or ended hero, which decision 115
    allows: a paid code scans nothing. The raise's fade over its first
    third (M1) is unchanged: the code there is a third of its size and
    growing out of the bubble, and the spec chose it. `OverlayFramesTest`
    reads the code's darkest module on each seeked frame of those three
    moves and fails on a grey one; the old build read 0.62 at q1.
136. **The panel's order line opens its room as it fades in** (dev1,
    round 3; N3, the panel's half: the sale screen's order card and header
    line are the screens track's). A sale's receipt reaches the till about
    a second after the sale, so the panel's order line popped in, and
    because the line sits above the code on a phone's panel, which hangs
    from the top, the code jumped down a line's height (about 62 dp) in
    one frame, under a camera that may already be on it. N3 asked for a
    fade alone, without a layout per frame; on the panel that would keep
    the jump, so the line's room opens with `expandVertically` on
    `motion.settle` while its words fade in on `motion.fade`
    (`AnimatedVisibility`, nothing on screen at all when there's no
    receipt). That is a layout per frame for about 250 ms of the card's
    few rows: the code inside is placed lower each frame, never measured
    or laid out again (its slot's constraints don't change), so principle 3
    holds and the code slides rather than jumps. The 14 dp above the line
    is inside its room, so nothing steps on the first frame. A panel that
    comes up with its receipt already there shows the line at once.
    `OverlayRecompositionsTest.theLateOrderLine` counts zero recompositions
    of the card, its code, rings, status row and bubble across the move;
    `OverlayFramesTest` saves `phone-late-order-q*.png` and holds the code
    square and opaque on each.
