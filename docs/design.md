# Monokular: companion app, POS integration and push

Monokular is the iPhone, iPad and Android app that sits next to a point of
sale (Square first) and shows the customer a Monero payment code when the
cashier takes a Monero sale. This document covers everything it needs from
[monokulo](https://github.com/OceanEilonwy/monokulo) and the design of the app
itself. Paths under `crates/` refer to the monokulo repository; §7 is the
layout of this one.

1. [The sale flow](#1-the-sale-flow) end to end, with Square.
2. [Connected apps](#2-connected-apps): how a device connects to one store on
   a self-hosted monokulo.
3. [Push](#3-push): a reusable `push` crate, Web Push everywhere, UnifiedPush,
   and a `push-relay` crate for APNs and FCM that runs embedded in monokulo
   or as its own binary.
4. [The Live Activity](#4-the-live-activity) and what drives it.
5. [Battery and latency](#5-battery-and-latency).
6. [Monokulo code layout](#6-monokulo-code-layout), settings and migrations.
7. [The Monokular repository](#7-the-monokular-repository).
8. [Delivery plan](#8-delivery-plan) and [open questions](#9-open-questions).

Design mockups (iPhone, iPad, Android, web connect pages, onboarding and
settings): <https://claude.ai/artifact/Kb9AtJFBF3ockyddomLc6t>.

---

## 1. The sale flow

Square doesn't expose a sale rung up in Square Point of Sale until it has been
tendered, and a third party can't add a payment to a POS-created order or
complete it (`UpdateOrder` can't touch POS orders; the "open orders" alpha is
closed to new apps). So the cashier tenders with a custom payment type and
monokulo reconciles:

```text
cashier: rings up in Square POS ─▶ taps "Other: Monero" ─▶ Square closes the sale
Square ──payment.created webhook──▶ monokulo (integrations::square)
monokulo: GET /v2/payments/{id} (amount, order_id, location_id)
          GET /v2/orders/{order_id} (line items, optional)
          creates the Monokulo order (engine) for that amount ─▶ an invoice
          ─▶ push "sale.waiting" to every Monokular device at that location
Monokular: shows amount + code (Live Activity / Slide Over / bubble / full screen)
customer pays ─▶ engine: order.unconfirmed ─▶ order.confirming ─▶ order.paid
monokulo ─▶ push "sale.seen" / "sale.confirming" / "sale.paid"
never paid ─▶ order.expired ─▶ push "sale.expired" ─▶ Monokular offers
          "Refund the Square sale" (RefundPayment, or the cashier refunds in POS)
```

Nothing is written back to Square when the payment arrives: the Square sale
was recorded as "Monero" when the cashier tapped it. The only write is the
refund of a sale that never pays.

Square specifics:

| Item | Value |
| --- | --- |
| OAuth scopes | `PAYMENTS_READ`, `ORDERS_READ`, `MERCHANT_PROFILE_READ`; `PAYMENTS_WRITE` for refunds |
| Tokens | Access token 30 days; refresh at least weekly (code-flow refresh tokens don't expire) |
| Webhooks | `payment.created` (and `payment.updated`), per application; verify `x-square-hmacsha256-signature`; dedupe by `event_id`; Square retries for 24 h |
| Filter | Payments at a location the store has linked whose Other tender name is the configured one (default "Monero") |
| Routing | Location (and, if Square gives it, `device_details`) → the Monokular devices of that store at that location |
| Sandbox | Square's sandbox can't run Square POS: verify with $0 Other tenders on a real account |

To verify in production before building the app (§9): how an Other tender
appears in the Payments API, whether `payment.created` fires for it, and
whether `RefundPayment` accepts it.

POS integrations are a module boundary of their own (`integrations::square`
first). A later POS adds a sibling module that turns its own events into the
same "sale" shape; nothing below it changes.

---

## 2. Connected apps

Every monokulo is self-hosted, so the app can't know a server. A connection
always starts from a store's dashboard, and Monokular is one kind of
connected app among others (the WooCommerce plugin, a merchant's own code),
never a first-class special case.

### Web

- **Store settings › Connected apps** (a card beside Webhooks): every app or
  plugin holding a key for the store, its name ("Front Counter iPad"), last
  seen, and Disconnect. The WooCommerce plugin's connection moves into this
  list.
- **Connect an app › Monokular**: a device name, a single-use connect code
  (QR) and an **Open in Monokular** button. Without JavaScript the "waiting"
  line is a Refresh button.

### The connect link

```text
https://monokular.app/connect#host=pay.yourshop.com&token=ct_…
```

- One project-owned domain is an iOS universal link and an Android App Link;
  a self-hosted host can't be (its domain isn't in the app's entitlements).
- The fragment never reaches `monokular.app`. Without the app installed the
  page there links to the App Store / Play Store / F-Droid.
- `token` is single-use and short-lived, the same model as the existing
  `/connect/{platform}` tokens (`http::connect`, `Db::consume_connect_token`).

### Redeeming

The app shows the store, host and wallet first (the confirm screen), then:

```http
POST https://pay.yourshop.com/api/v1/apps/connect
{ "token": "ct_…", "app": "monokular", "device_name": "Front Counter iPad",
  "platform": "ios" }
→ 201 { "app_connection_id": "ac_…", "app_key": "ak_…" (secret, shown once),
        "store": { "id", "name", "base_currency" },
        "wallet": { "name", "address_short" },
        "vapid_public_key": "BJ…" (this instance's Web Push key, §3) }
```

The app keeps `app_key` in the Keychain / Android Keystore. Every later call
uses it. Disconnecting (either side) deletes the connection and its push
targets.

### What a connected app can do

Scopes, per connection: `orders:read`, `invoices:create` (Monokular needs
both), `push:subscribe`. Never wallet changes or spending. The scope list is
shown on both the web connect page and the app's confirm screen.

### Live data while the app is open

```http
GET /api/v1/apps/events    (SSE, Authorization: Bearer ak_…)
```

The same "sale" events as the pushes (§3), plus a heartbeat comment every
25 s. Used whenever the app is in the foreground, so on-screen updates never
wait for a push.

---

## 3. Push

### Goals

- A **reusable** piece of monokulo: any connected app can ask for pushes;
  Monokular is only the first user.
- **One transport** in monokulo: Web Push (RFC 8030), encrypted (RFC 8291,
  `aes128gcm`) and signed (VAPID, RFC 8292).
- UnifiedPush works with no relay at all; APNs and FCM go through a relay
  (`push-relay`), embedded in monokulo or standalone.
- The relay learns as little as possible: no store, no device-to-store link,
  ciphertext for every ordinary notification.

### Why Web Push for everything

A Web Push subscription is `(endpoint URL, p256dh key, auth secret)`. Three
kinds of endpoint all accept the same request:

| Endpoint | Who runs it | Delivers to |
| --- | --- | --- |
| UnifiedPush distributor (ntfy, NextPush, …) | The user, or a public instance | Android app via its distributor |
| `https://relay.monokular.app/v1/push/{handle}` | The project (standalone `monokulo-push-relay`) | APNs (iOS) or FCM (Android with Google services) |
| A browser's push service | Browser vendor | A future web app or admin page |

So monokulo only ever does "encrypt, sign, POST to endpoint". Whether that
lands at a UnifiedPush distributor or the relay is the endpoint's business.

### VAPID ties a subscription to one monokulo

Each monokulo has one VAPID key pair (P-256), generated on first start and
stored encrypted like other secrets in the database. The public key goes to
the app when it connects (§2). The app passes it to whoever issues its
endpoint:

- UnifiedPush: the app gives it to the distributor at registration (the
  AND_3 spec's VAPID field), which then refuses pushes not signed by it.
- The relay: the app registers its APNs/FCM token **with the VAPID key**, and
  the relay accepts pushes to that handle only when the VAPID JWT verifies
  against it.

This needs no per-instance relay account: a monokulo is identified by its
VAPID key, rate limits are per key, and a stolen endpoint URL is useless
without the instance's private key.

### Two kinds of subscription

| Kind | Payload | Used for |
| --- | --- | --- |
| `encrypted` | RFC 8291 ciphertext | Every notification (new sale, paid, expired) and every Android message |
| `live-activity` | Plain JSON (iOS applies Live Activity pushes itself, with no extension to decrypt) | Live Activity updates only: `phase`, `sale`, `amount`, `xmr`, `expiresAt`, `waitingCount`, and `paymentURI` only when the device turned on "Show code in island" (§4) |

For `encrypted`, the iOS Notification Service Extension and the Android
receiver decrypt with the subscription's private key, which never leaves the
device.

### Two crates: `push` and `push-relay`

Push is split like the engine: a library monokulo always uses, and a relay
that runs embedded in monokulo or on its own, the way the engine runs inside
monokulo by default and as `monokulo-engine` when separate.

| Crate | Kind | Used by |
| --- | --- | --- |
| `push` | library | Every monokulo (sending), `push-relay` (VAPID checks), tests |
| `push-relay` | library + binary `monokulo-push-relay` | Monokulo (embedded, cargo feature `relay`, on by default), or standalone |

Keeping them apart means a monokulo built with `--no-default-features`
drops the relay's server code, APNs/FCM clients and OAuth entirely, and
another Rust sender only takes the small crate.

### The `push` crate (`crates/push`)

A library with no monokulo types and no server code.

```text
crates/push/src/
  lib.rs            Subscription, Message, Urgency, Ttl, Topic; re-exports
  subscription.rs   endpoint + keys + kind; validation (https, length, key sizes)
  message.rs        payload builders, the 4 KB budget check, Topic for collapsing
  vapid.rs          VapidKey: generate, load, sign the JWT (aud = endpoint origin, exp ≤ 12 h)
  encrypt.rs        RFC 8291 encrypt/decrypt (decrypt is used by tests and the relay's own tests)
  send.rs           Sender: build the request, POST with a shared reqwest::Client,
                    map the response (201 sent, 404/410 gone, 413 too big,
                    429/5xx retry with Retry-After)
```

### The `push-relay` crate (`crates/push-relay`)

```text
crates/push-relay/src/
  lib.rs            RelayConfig, Relay::new(config, store), Relay::router() -> axum::Router,
                    run(config) for the standalone binary; the settings declarations
  routes.rs         /v1/register, /v1/unregister, /v1/push/{handle}
  handles.rs        HandleStore trait; SqliteHandleStore (rusqlite); an in-memory one for tests
  apns.rs           APNs provider: HTTP/2, token (.p8) auth, push types alert/background/liveactivity
  fcm.rs            FCM HTTP v1 provider: service-account OAuth, data messages
  limits.rs         per-VAPID-key and per-handle token buckets
  main.rs           `monokulo-push-relay`: read settings, open the store, serve
```

The library interface is small on purpose: a config, a store, a router.
Monokulo and the standalone binary both build a `Relay` the same way and
differ only in who owns the listener.

### Crates

Chosen to fit the workspace: rustls only (there's no OpenSSL in `Cargo.lock`),
RustCrypto, reqwest, axum, tokio.

| Need | Crate | Why |
| --- | --- | --- |
| Web Push request building, RFC 8291, VAPID | [`web-push-native`](https://docs.rs/web-push-native) | RustCrypto (`p256`, `aes-gcm`, `hkdf`), no HTTP client of its own (we use reqwest), has `decrypt` for tests. Pin to a version at least two weeks old and review it (0.5.0 is from October 2026). |
| JWT (VAPID ES256, APNs ES256, FCM RS256) | `jwt-simple` | Already `web-push-native`'s optional JWT dependency. Build it with its pure-Rust feature so no C crypto library enters the tree; check the feature names when adding it. |
| HTTP | `reqwest` (already used) | Add the `http2` feature for APNs, which requires HTTP/2. |
| FCM OAuth | `gcp_auth` | Service-account tokens for FCM HTTP v1, rustls. |
| Server | `axum`, `tokio` (already used) | Relay routes. |
| Rate limits | `governor` | Token buckets keyed by VAPID key and handle. |
| Storage | `rusqlite` (already used) | Monokulo's tables and the relay's handle table. |

For APNs, `a2` was considered and not used: it hasn't been released since
2024 and has no Live Activity push type. APNs is a single HTTP/2 POST
(`/3/device/{token}`, headers `apns-push-type`, `apns-topic`,
`apns-priority`, `apns-expiration`, `apns-collapse-id`, bearer JWT refreshed
every 50 minutes), so a thin client on reqwest is smaller and easier to keep
current.

### The relay API

Registration, from the app:

```http
POST /v1/register
{ "platform": "apns" | "apns-liveactivity" | "apns-pushtostart" | "fcm",
  "token": "<APNs/FCM device or activity token>",
  "app": "monokular",                   // picks the bundle ID / FCM project
  "vapid_public_key": "BJ…" }           // the store's monokulo
→ 201 { "endpoint": "https://relay.monokular.app/v1/push/ph_7f3k…",
        "expires_at": … }
```

- One registration per (token, VAPID key): a device connected to two stores
  gets two handles, which the relay can't link to each other.
- Tokens change; the app re-registers whenever iOS or Android hands it a new
  one and sends the new endpoint to monokulo.

Delivery, from monokulo (plain Web Push):

```http
POST /v1/push/ph_7f3k…
Authorization: vapid t=<JWT>, k=<public key>
Content-Encoding: aes128gcm          (encrypted kind)  |  application/json (live-activity kind)
TTL: 1800
Urgency: high | normal | low
Topic: sale-1042                     (collapses superseded updates)
```

The relay checks the JWT against the handle's key, applies limits, and
translates:

| Handle platform | APNs/FCM request |
| --- | --- |
| `apns` | `apns-push-type: alert`, `mutable-content: 1`, the ciphertext in a custom key; the Notification Service Extension decrypts and fills in title and body. Fallback text: "New sale". |
| `apns-liveactivity` | `apns-push-type: liveactivity`, topic `<bundle>.push-type.liveactivity`, `aps.content-state` from the plain JSON, `event: update` / `end`, `alert` only for waiting / paid / expired. |
| `apns-pushtostart` | `event: start` (iOS 17.2+), only if the app opted in (it then sends attributes through the relay). |
| `fcm` | Data message, priority from `Urgency`, the ciphertext as data; the app decrypts. |

Answers back to monokulo: `201`; `404`/`410` when APNs/FCM say the token is
dead (the relay deletes the handle; monokulo deletes the target); `429`
with `Retry-After`.

### Running the relay

**Embedded** (in every release build of monokulo, off unless turned on):

```toml
[relay]
enabled = true                 # default false: only useful with APNs/FCM keys
listen = "0.0.0.0:8443"        # its own listener by default, so its limits and logs
                               # stay apart from the store pages
public_url = "https://relay.example.org"
```

**Standalone:**

```sh
monokulo-push-relay --options relay.toml
```

Both read the same settings, declared once in `push-relay` and registered
by both binaries (the same pattern as the engine's `--engine-…` settings in
monokulo, so they appear on monokulo's admin page when embedded):
`relay.enabled` (embedded only), `relay.listen`, `relay.public_url`,
`relay.apps` (bundle ID / package name / team ID / key ID per app),
`relay.limits.*`, `relay.handle_ttl_days`, `relay.store_path`. Invalid
values stop the process, as everywhere else in monokulo. Secrets come only
from the environment, in both cases:

```text
MONOKULO_RELAY_APNS_KEY         (.p8 contents)
MONOKULO_RELAY_FCM_CREDENTIALS  (service-account JSON)
```

**Storage:** the relay always keeps its own SQLite file (`relay.store_path`),
even when embedded, never tables in monokulo's database. It never touches
store data, and moving from embedded to standalone is a matter of copying
that file. It holds only `handles(id, platform, app, token,
vapid_public_key, created_at, last_used_at)`. No payloads are stored and no
request bodies are logged.

**Who runs what:** the project runs the official relay **standalone**, so it
can be deployed and scaled on its own schedule. Embedding suits a small
operator shipping their own app with their own APNs/FCM keys: one process,
one upgrade. Either way their app registers with that relay and hands
monokulo the endpoint, with no change to monokulo.

**Release artifacts:** `monokulo` (relay included), `monokulo-engine` and
`monokulo-push-relay`, all from the workspace version as today.

### UnifiedPush

On Android the app uses the UnifiedPush connector. With a distributor
installed (ntfy, NextPush, …) the endpoint is the distributor's and pushes
go **straight from the store's monokulo to the distributor**, with no relay.
Without one, the app falls back to FCM through the relay (or the UnifiedPush
embedded FCM distributor pointed at the relay; check that library's gateway
contract before choosing). On a phone without Google services and without a
distributor, Monokular keeps a foreground service connected to the store's
SSE stream during a shift (§5).

### Push targets in monokulo (`push_targets`)

```text
push_targets(id, app_connection_id, kind encrypted|live-activity,
             endpoint, p256dh, auth, events, created_at, last_ok_at, failures)
```

- Registered by the app over its connected-app key:
  `PUT /api/v1/apps/push-targets/{client_id}` (idempotent; the app picks
  `client_id` per subscription).
- Removed when the connection is disconnected, when the endpoint answers
  404/410, or after a run of failures (the same give-up rule as webhooks).
- `events` filters by event name; Monokular subscribes to the `sale.*`
  events of its location.

### Events

| Event | From | Urgency | Notification | Live Activity |
| --- | --- | --- | --- | --- |
| `sale.waiting` | Square payment → invoice created | high | "New Monero sale · $24.50" | phase waiting, alert |
| `sale.seen` | `order.unconfirmed` | low | none | phase seen |
| `sale.confirming` | `order.confirming` | low | none | phase confirming |
| `sale.paid` | `order.paid` / `order.overpaid` | high | "Paid · $24.50" | phase paid, alert, then idle |
| `sale.partial` | `order.partial` | high | "Part paid" | phase waiting, amount left |
| `sale.expired` | `order.expired` | high | "No payment yet" | phase expired, alert |
| `sale.double_spend` | `order.double_spend_detected` | high | "Payment reversed" | phase expired |

Payloads use the same body versioning as webhooks (`api_version`) so a
connected app can rely on them.

### Delivery path

Pushes ride the existing webhooks pipeline rather than a parallel one:

```text
engine order_events ──SSE──▶ webhooks::subscriber
                               │ same transaction
                               ├─▶ webhook_deliveries   (unchanged)
                               └─▶ push_deliveries      (new: one row per target)
push::delivery worker ──Web Push──▶ endpoint (distributor or relay)
```

`push_deliveries` reuses the webhook worker's shape (claim, send, back off,
give up), with two differences: a newer delivery with the same `Topic`
supersedes an unsent older one, and deliveries past their TTL are dropped,
not retried (a "Seen" update an hour late is worse than none).

---

## 4. The Live Activity

A status display for the current sale. It creates nothing and fetches
nothing (Live Activity code has no network); each push changes its content
state and iOS redraws it.

**Static attributes** (set by the app when it starts the activity, so never
through the relay): location label, store name.

**Content state** (changed by pushes):

| Field | Example | Use |
| --- | --- | --- |
| `phase` | idle / waiting / seen / confirming / paid / expired | Layout and colour |
| `sale` | "#1042" | Label |
| `amount` | "$24.50" | Large amount |
| `xmr` | "0.1421" | Second line |
| `expiresAt` | timestamp | A countdown iOS draws itself (`Text(timerInterval:)`), and `stale-date` |
| `waitingCount` | 2 | "+1 more" when sales overlap |
| `paymentURI` | `monero:…` | **Only** when the device has "Show code in island" on |

**Show code in island** is a per-device setting, off by default. Turning it
on explains that the payment address of each sale then passes through the
Monokular push relay. Off: the expanded island shows amount, status and "Tap
to show code"; the QR appears only in the app.

| Phase | Compact | Expanded / lock screen | Push |
| --- | --- | --- | --- |
| idle | Logo + green dot | "Watching for sales · Front Counter" | none |
| waiting | Logo + "$24.50" | Amount, XMR, countdown, QR if enabled | alert, high |
| seen | Logo + "Seen" | "Seen on the network" | silent, low |
| confirming | Logo + "1/2" | "Confirming" | silent, low |
| paid | Tick + "$24.50" | "Paid · 0.1421 XMR received" | alert, high, then idle |
| expired | Clock | "No payment yet · refund in Square" | alert, high |

Lifecycle: one activity per shift, started by the app (iOS ends it after 8 h;
the next app launch starts a new one; push-to-start is opt-in because it
sends the attributes through the relay). Overlapping sales show the newest
plus "+N more". Tapping opens `monokular://sale/{id}`.

---

## 5. Battery and latency

| State | Mechanism | Cost |
| --- | --- | --- |
| App in the foreground | SSE to the store's own monokulo | Normal app use |
| iPhone/iPad in the background | APNs via the relay | Close to nothing (the system's own push connection) |
| Android with a UnifiedPush distributor | Direct Web Push to the distributor | The distributor's one connection, shared with other apps |
| Android with Google services | FCM via the relay | Close to nothing |
| Android with neither | Foreground service on SSE, only during a shift, heartbeat 25 s server side, OS-level reconnect backoff | Small; tills are usually on chargers |

Rules:

- Push on state change only; at most five pushes per sale.
- `high` urgency only for waiting, paid, partial, expired and double spend;
  everything else `low`. APNs and FCM throttle apps that overuse high
  priority.
- `Topic` collapses superseded updates; `TTL` equals the time left on the
  code.
- iOS: `NSSupportsLiveActivitiesFrequentUpdates` for busy tills.
- The app never polls.

---

## 6. Monokulo code layout

```text
crates/push/                    new library (§3): Web Push, VAPID, RFC 8291
crates/push-relay/              new library + `monokulo-push-relay` binary (§3)
crates/monokulo/                depends on push; on push-relay behind feature "relay" (default)
crates/monokulo/src/
  apps/                         connected apps
    mod.rs                      AppConnection, scopes, connect tokens (generalised from http::connect)
    events.rs                   the sale.* event model, shared by SSE and push
  integrations/
    mod.rs                      the "sale" a POS integration produces
    square/                     OAuth, webhook receiver + signature check, API client, location linking
  push/                         monokulo's side of push: push_targets, push_deliveries,
                                fan-out from webhooks::subscriber, the delivery worker
  http/apps.rs                  /api/v1/apps/{connect,events,push-targets}
  http/square.rs                OAuth callback, webhook endpoint
  views/connected_apps.rs       the Connected apps card and Connect an app page
  relay.rs                      (feature "relay") registers push-relay's settings and, when
                                relay.enabled, starts a Relay on its own listener
```

### Migrations

New tables only; nothing existing changes shape except moving the
WooCommerce connection into `app_connections` (a data migration, so flag it
before running it on a production instance):

- `app_connections`, `app_connect_tokens`
- `push_targets`, `push_deliveries`
- `square_accounts` (encrypted tokens), `square_locations`, `square_payments` (dedupe by event and payment id)
- `vapid_keys` (encrypted private key)
- none for the relay: its handles live in its own SQLite file (§3)

### Settings (registry)

- `push.enabled`, `push.delivery.*` (attempts, backoff), `push.default_ttl`
- `integrations.square.enabled`, `integrations.square.application_id`,
  `integrations.square.environment`; secret `MONOKULO_SQUARE_APP_SECRET`
  (env only)
- `relay.*` (declared in push-relay, registered by monokulo when built with
  the relay, §3)

### Tests

In-process like the rest of monokulo: a test UnifiedPush endpoint and a test
relay run on the test's own axum router; `push::encrypt::decrypt` checks
what arrived. Square calls are recorded fixtures (the sandbox can't produce
POS payments). APNs and FCM are faked behind their provider traits; one
`over_http` test per provider talks to a local HTTP/2 stub.

---

## 7. The Monokular repository

A separate public repository, `monokular`, holding both apps and their
shared contract. The apps are native (SwiftUI, Jetpack Compose); what they
share is the contract and test vectors, not code.

GitHub description:

> Monokular: iPhone, iPad and Android companion for Monokulo. Take Monero next to your till: it sees Monero sales in your POS (Square first) and shows the customer the payment code, in the Dynamic Island, Slide Over or a floating bubble.

```text
monokular/
  README.md
  protocol/                     the contract with monokulo
    openapi.yaml                /api/v1/apps/* (connect, events, push-targets) and the relay's /v1/register
    events.schema.json          sale.* payloads and the Live Activity content state
    vectors/                    JSON test vectors generated by monokulo's xtask:
                                connect links, RFC 8291 payloads (keys, ciphertext, plaintext), events
  ios/
    project.yml                 XcodeGen (no .xcodeproj in git)
    Packages/MonokularKit/      SwiftPM package, no UIKit; builds and tests on Linux CI too
      Sources/
        Model/                  Store, Sale, SalePhase, Event decoding
        API/                    connect, SSE client, push-target registration
        Crypto/                 RFC 8291 decrypt (swift-crypto, which is CryptoKit's API off Apple platforms)
        Links/                  connect-link parsing
      Tests/                    runs protocol/vectors
    Monokular/                  the app
      App/                      entry point, routing, monokular:// links
      Features/
        Onboarding/  Sale/  Settings/
      Services/                 Keychain, LiveActivityController, PushRegistration, Scanner
      Resources/                assets, the loading-mark animation, strings
    MonokularWidgets/           Live Activity (compact, minimal, expanded, lock screen)
    MonokularNotifications/     Notification Service Extension: decrypt and fill in alerts
    MonokularUITests/           snapshot tests of every screen for CI screenshots
  android/
    settings.gradle.kts
    core/                       pure Kotlin (JVM): model, API, SSE, RFC 8291 decrypt, links; runs protocol/vectors
    push/                       UnifiedPush connector, FCM fallback, registration with monokulo
    overlay/                    the bubble and floating panel (display over other apps)
    notifications/              Live Update / ongoing notifications and their progress
    app/                        Compose UI: onboarding, sale, settings; foreground service
  docs/
    architecture.md  release.md
  .github/workflows/
    core.yml                    MonokularKit on Linux (swift test) + android core (JVM tests)
    ios.yml                     macos runner: XcodeGen, build, UI snapshots, development .ipa artifact
    android.yml                 ubuntu: build, unit tests, debug APK artifact
    release.yml                 TestFlight / Play internal track / F-Droid metadata
```

Choices:

- **Native per platform** over a shared UI toolkit: Live Activities, Slide
  Over, overlays and Live Updates are each platform's own APIs.
- **The pure core is a package/module with no UI framework.** The parts most
  likely to be wrong (decryption, event decoding, link parsing) build and
  test on Linux, so most work doesn't wait on a macOS runner.
- **monokulo is the source of truth for the contract:** its xtask writes
  `protocol/vectors/` from the real Rust code, and both apps' tests read
  them. Changing an event shape breaks the app tests, not a till.
- A shared Rust core via UniFFI is possible later if the cores drift; it
  isn't worth the build complexity for this amount of logic.

---

## 8. Delivery plan

1. **Square spike** on a real account with $0 Other tenders: the
   `payment.created` shape for Other tenders, `RefundPayment` on them, routing
   by location/device.
2. **`crates/push`**: Web Push send, VAPID, RFC 8291, with vectors; no relay
   yet.
3. **Connected apps** in monokulo: tables, the Connected apps card, the
   connect page and link, `/api/v1/apps/connect` and `/events`; the
   WooCommerce connection moves in.
4. **`integrations::square`**: OAuth, webhook, invoice creation, `sale.*`
   events.
5. **Push targets and delivery** in monokulo; UnifiedPush end to end with
   ntfy.
6. **`push-relay`**: APNs (alert, liveactivity) and FCM, the standalone
   binary and the embedded mount; the official relay deployed standalone.
7. **Monokular Android** (UnifiedPush first, then FCM, then overlay and Live
   Updates).
8. **Monokular iOS** (iPad Slide Over first; iPhone Live Activity once the
   relay is up).

Android goes first after the server work because it needs no relay and no
Mac.

---

## 9. Open questions

- Square: Other tenders in the Payments API and webhooks; refunds of them;
  whether `device_details` is enough to route a sale to one till.
- Square POS for Android: does it hide other apps' overlays on its checkout
  screens (`HIDE_OVERLAY_WINDOWS`)? If so the bubble disappears there.
- UnifiedPush embedded FCM distributor: can it point at our relay, or does
  Android talk to the relay's FCM path directly?
- iPadOS 26.1: the exact steps to put an app in Slide Over, for onboarding
  copy.
- `monokular.app`: the domain for links and the relay.
- App Store review of a crypto-payments companion; Google Play's "display
  over other apps" declaration.
