# Monokular: companion app, POS integration and push

Monokular is the iPhone, iPad and Android app that sits next to a point of
sale (Square first) and shows the customer a Monero payment code when the
cashier takes a Monero sale. The code can carry the whole receipt, so the
customer's wallet keeps it with the payment. This document covers what the
app needs from [monokulo](https://github.com/OceanEilonwy/monokulo), the
Monokular Bell that wakes tills up, and the design of the app itself. Paths
under `crates/` in §5 and §8 refer to the monokulo repository; §9 is the
layout of this one.

1. [The sale flow](#1-the-sale-flow): the till talks to Square; monokulo
   never does.
2. [Connected apps](#2-connected-apps): how a device connects to one store on
   a self-hosted monokulo, and the invoice API it uses.
3. [Wake-ups](#3-wake-ups): what makes a till check Square, from a timer or
   the Monokular Bell.
4. [The receipt](#4-the-receipt): built on the till, carried in the code.
5. [Push](#5-push): a reusable `push` crate, Web Push everywhere,
   UnifiedPush, and a `push-relay` crate for APNs and FCM.
6. [The Live Activity](#6-the-live-activity).
7. [Battery and latency](#7-battery-and-latency).
8. [Monokulo code layout](#8-monokulo-code-layout), settings and migrations.
9. [The Monokular repository](#9-the-monokular-repository).
10. [Delivery plan](#10-delivery-plan) and [open questions](#11-open-questions).

Design mockups (iPhone, iPad, Android, web connect pages, onboarding and
settings): <https://claude.ai/artifact/Kb9AtJFBF3ockyddomLc6t>.

---

## 1. The sale flow

### Principles

- **The till holds the Square connection, not monokulo.** Monokulo has no
  Square permission, no Square settings and no Square code. It learns an
  amount and an opaque reference, nothing else, on the public instance and on
  every self-hosted one.
- **What someone bought never leaves the till except in the QR code** (§4).
- **Self-hosters set up nothing for Square.** Tills sign in to Square through
  the project's Square application; any monokulo works with it.
- **Monokulo stays POS-agnostic.** Another POS is app work only.

### Why the cashier tenders "Other: Monero"

Square doesn't expose a sale rung up in Square Point of Sale until it has been
tendered, and a third party can't add a payment to a POS-created order or
complete it (`UpdateOrder` can't touch POS orders; the "open orders" alpha is
closed to new apps). So the cashier tenders with a custom payment type and
the till reconciles.

### Flow

```text
cashier: rings up in Square POS ─▶ taps "Other: Monero" ─▶ Square closes the sale
till:    woken by a timer or the Monokular Bell (§3)
         GET Square payments since its cursor, at its location
         ─▶ a new payment whose Other tender is "Monero"
         GET /v2/orders/{order_id} (line items, for the receipt)
         POST monokulo /api/v1/apps/invoices
              { amount, currency, reference, idempotency_key }   (§2)
         ◀─ { invoice id, plain payment URI, expires_at }
         orf_codec::query::append(payment URI, receipt) (§4)
         shows amount + code (Slide Over / bubble / app; Live Activity on iPhone)
customer pays ─▶ engine: order.unconfirmed ─▶ order.confirming ─▶ order.paid
monokulo ─▶ SSE / push "sale.seen" / "sale.confirming" / "sale.paid" (§5)
never paid ─▶ order.expired ─▶ "sale.expired" ─▶ the till offers
         "Refund the Square sale" (RefundPayment with its own token,
         or the cashier refunds in POS)
```

Nothing is written back to Square when the payment arrives: the Square sale
was recorded as "Monero" when the cashier tapped it. The only write is the
refund of a sale that never pays.

### Sales from Square, on the till

| Item | Value |
| --- | --- |
| Square application | One, owned by the project. Tills use it unless they're connected to a self-hosted Bell, which brings its own (§3) |
| Sign-in | OAuth with PKCE on the device: no secret anywhere. Redirect to `https://monokular.app/square`, an App Link / universal link, so Square hands the code straight back to the app |
| Scopes | `PAYMENTS_READ` (find sales), `ORDERS_READ` (items for the receipt), `MERCHANT_PROFILE_READ` (pick the till's location), `PAYMENTS_WRITE` (refund a sale that never pays) |
| Tokens | Kept in the Keystore / Keychain, never sent to monokulo. PKCE refresh tokens are single-use and expire (90 days at the time of writing; the spike confirms): the app refreshes on every use and asks for a new sign-in when one lapses |
| Finding sales | `ListPayments` for the till's location since a saved cursor (`begin_time`, plus payment ids already handled) |
| Filter | Other tender whose name is the configured one (default "Monero"); status `COMPLETED` |
| Which till | The till's location, and its own `device_details` when Square gives them; otherwise every Monokular at the location shows the sale |
| Two tills, one sale | `idempotency_key` is a hash of the Square payment id, so every till that finds it gets the same invoice |
| Sandbox | Square's sandbox can't run Square POS: verify with $0 Other tenders on a real account |

Say on the Square sign-in screen what it allows: `ORDERS_READ` lets the till
read every order of the business, not just Monero sales. That's the right
place for it, a device the merchant controls, but it should be said.

### POS sources

A POS is a **sale source** in the app: "the new Monero sales since my
cursor", plus "refund this one". Square is the first. Another POS adds a
source (and, if it has webhooks, a Bell receiver, §3); monokulo, the receipt,
wake-ups and the screens don't change.

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
        "vapid_public_key": "BJ…" (this instance's Web Push key, §5) }
```

The app keeps `app_key` in the Keychain / Android Keystore. Every later call
uses it. Disconnecting (either side) deletes the connection and its push
targets. Square sign-in is a separate step in the app (§1) and has nothing to
do with monokulo.

### Creating an invoice

```http
POST /api/v1/apps/invoices    (Authorization: Bearer ak_…)
{ "amount": "24.50", "currency": "USD",
  "reference": "#1042",                 (shown on the store's order list; the
                                         app sends Square's receipt number or nothing)
  "idempotency_key": "sq_9f2c…" }       (hash of the Square payment id)
→ 201 | 200 (same key again) {
    "invoice_id": "…", "payment_uri": "monero:…?tx_amount=…",
    "xmr": "0.1421", "expires_at": … }
```

No line items, customer or card data are accepted: the request schema has no
field for them. The plain `payment_uri` is what the app appends the receipt
to (§4).

### What a connected app can do

Scopes, per connection: `orders:read`, `invoices:create` (Monokular needs
both), `push:subscribe`. Never wallet changes or spending. The scope list is
shown on both the web connect page and the app's confirm screen.

### Live data while the app is open

```http
GET /api/v1/apps/events    (SSE, Authorization: Bearer ak_…)
```

The same "sale" events as the pushes (§5), plus a heartbeat comment every
25 s. Used whenever the app is in the foreground, so on-screen updates never
wait for a push. Because every till at a store gets `sale.waiting` when any
of them creates an invoice, a device without a Square sign-in (a second
iPhone, say) still shows the store's sales, without receipts.

---

## 3. Wake-ups

The till finds sales by asking Square (§1). What makes it ask is separate,
so the sale source never cares why it was woken.

| Trigger | Where | When |
| --- | --- | --- |
| **Timer** | Every platform | Always, during a shift: every 2 s without the Bell, every 30 s with it (as a backstop for a lost ding) |
| **Ding** from the Monokular Bell | Every platform that registered with one | Within a second or two of Square's webhook |
| **App opened / brought forward** | Every platform | Immediately |

Every trigger does the same thing: fetch since the cursor, idempotently. A
ding carries no sale data, so a lost, late, repeated or forged ding costs at
most one extra request to Square.

A "shift" is the time the app is set to watch for sales (on by default
while the app runs; the cashier can stop it in Settings). Outside a shift
nothing runs.

### Per platform

| Platform | Without the Bell | With the Bell |
| --- | --- | --- |
| Android | Foreground service, 2 s timer during a shift | Ding through UnifiedPush or FCM wakes the service; timer slows to 30 s |
| iPad | Slide Over keeps the app in the foreground: 2 s timer | Ding as an alert push (below); timer slows to 30 s |
| iPhone | Only while the app is open: the cashier switches to it after tendering | Ding as an alert push; the Notification Service Extension fetches the sale and rewrites the alert |

**iPhone and iPad dings are alert pushes, never silent ones.** iOS rations
and delays background (silent) pushes, so they can't be trusted to wake a
till. The Bell sends an alert with `mutable-content`. The Notification Service
Extension, which runs for up to about 30 s with network access, reads the
Square token from a shared Keychain group, fetches the new payment, creates
the invoice and rewrites the alert as "Monero sale · $24.50 · Tap to show
code". Tapping opens the app, which builds the receipt and shows the code,
and starts or updates the Live Activity (§6). If the extension runs out of
time the alert stays "New sale · Tap to show code" and the app does the rest.

### The Monokular Bell

A small service that turns a POS's webhooks into dings for the right tills.
It lives in this repository (`crates/monokular-bell`, a library and the
`monokular-bell` binary), because it is POS-specific and app-side; monokulo
knows nothing about it.

```text
Square ──payment.created webhook──▶ Bell: verify signature, read merchant_id
                                         + location_id, drop the body
Bell ──Web Push (empty, encrypted)──▶ each till registered for that location
        (UnifiedPush distributor directly, or APNs/FCM via push-relay, §5)
```

- **Dings go over Web Push**, exactly like monokulo's own pushes (§5): the
  till hands the Bell a Web Push subscription, so the Bell never talks to
  Apple or Google and UnifiedPush needs no relay. The payload is an
  encrypted `{"wake":"square"}`; `Urgency: high`, `TTL: 60`, `Topic: wake`
  so a burst collapses into one.
- **One Square application per Bell.** Square sends webhooks to the
  application a merchant signed in through, so a Bell and the tills that use
  it share one. The project's Bell (`bell.monokular.app`) uses the project's
  application. A self-hosted Bell is configured with its own application,
  and tills that connect to it sign in to Square through that one (the app
  asks the cashier to sign in again when they switch).
- **What the Bell sees:** Square's webhook bodies, which include each
  payment's amount and card details, for every merchant signed in through
  its application. It reads `merchant_id` and `location_id`, then drops the
  body: none of it is stored or logged. It never sees orders and holds no
  Square token. This is why the Bell is optional and anyone can run their
  own.

Registration, from the app:

```http
POST https://bell.monokular.app/v1/register
{ "square_access_token": "…",           (used once to confirm the merchant with
                                          GET /v2/merchants/me, never stored)
  "location_id": "L…",
  "subscription": { "endpoint", "keys": { "p256dh", "auth" } } }
→ 201 { "registration_id": "br_…", "square_application_id": "…" }
DELETE /v1/register/{registration_id}
GET    /v1/info → { "square_application_id", "environment", "vapid_public_key", "version" }
```

The token check stops anyone from subscribing to dings for a business they
don't control (which would tell them when it takes payments).

Storage: its own SQLite file with `registrations(id, merchant_id,
location_id, endpoint, p256dh, auth, created_at, last_ok_at, failures)`.
Registrations whose endpoint answers 404/410 are deleted, the same rule as
monokulo's push targets.

Settings (TOML options file plus CLI, invalid values stop the process):
`bell.listen`, `bell.public_url`, `bell.store_path`,
`bell.square.application_id`, `bell.square.environment`. Secrets from the
environment only: `MONOKULAR_BELL_SQUARE_WEBHOOK_KEY` (the webhook signature
key) and `MONOKULAR_BELL_VAPID_KEY`.

In the app: **Settings › Wake-ups**: "Timer only" (default), "Monokular Bell
(bell.monokular.app)", or "My own Bell" with a URL. The app reads
`/v1/info` and, if the Square application differs, asks for a new Square
sign-in.

---

## 4. The receipt

The payment code can carry the whole receipt (`orf-codec`, in this
repository: an Open Receipt Format document compacted into one
`orf=<digits>` query parameter). What someone bought must not reach
monokulo, the Bell, the push relay, or anything else between the till and
the customer.

1. **Monokulo never sees the items.** It has no Square access at all, and its
   invoice API has no field for them (§2).
2. **The till fetches the order from Square itself** with its own
   `ORDERS_READ` token. Square POS has no on-device API for other apps, so
   this is a Square API call; the items travel Square ─▶ till ─▶ QR code, and
   Square has them anyway.
3. **`orf-codec` runs in the apps** through UniFFI bindings to the Rust crate
   (Kotlin and Swift), so there is one implementation of the format and its
   dictionary (§9).
4. **The app appends the receipt** to the plain payment URI from monokulo
   with `orf_codec::query::append(uri, &receipt)`, which adds `?orf=` or
   `&orf=` correctly and refuses a URI that already has one. The app keeps
   monokulo's URI and appends to it fresh each time. `orf-codec` knows
   nothing about Monero.
5. **Without the order, the code still works.** If the order can't be read
   or the receipt doesn't encode, the app shows the plain payment URI.
   Paying never depends on the receipt.

Only the app's own screens show the receipt code. Anything drawn from a push
(the Live Activity's "Show code in island", §6) uses the plain payment URI,
because that content comes through the relay.

---

## 5. Push

Monokulo pushes the state of each invoice (seen, confirming, paid, expired)
to the store's tills. The Bell's dings (§3) use the same Web Push transport.

### Goals

- A **reusable** piece of monokulo: any connected app can ask for pushes;
  Monokular is only the first user.
- **One transport**: Web Push (RFC 8030), encrypted (RFC 8291, `aes128gcm`)
  and signed (VAPID, RFC 8292), from monokulo and from the Bell.
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

So a sender only ever does "encrypt, sign, POST to endpoint". Whether that
lands at a UnifiedPush distributor or the relay is the endpoint's business.

### VAPID ties a subscription to one sender

Each monokulo, and each Bell, has one VAPID key pair (P-256), generated on
first start and kept secret. The public key goes to the app when it connects
(§2) or reads the Bell's `/v1/info`. The app passes it to whoever issues the
endpoint:

- UnifiedPush: the app gives it to the distributor at registration (the
  AND_3 spec's VAPID field), which then refuses pushes not signed by it.
- The relay: the app registers its APNs/FCM token **with the VAPID key**, and
  the relay accepts pushes to that handle only when the VAPID JWT verifies
  against it.

This needs no per-sender relay account: a sender is identified by its VAPID
key, rate limits are per key, and a stolen endpoint URL is useless without
the sender's private key. A till holds one subscription per sender (its
store's monokulo, and the Bell if it uses one).

### Two kinds of subscription

| Kind | Payload | Used for |
| --- | --- | --- |
| `encrypted` | RFC 8291 ciphertext | Every notification and ding, and every Android message |
| `live-activity` | Plain JSON (iOS applies Live Activity pushes itself, with no extension to decrypt) | Live Activity updates only: `phase`, `sale`, `amount`, `xmr`, `expiresAt`, `waitingCount`, and `paymentURI` only when the device turned on "Show code in island" (§6) |

For `encrypted`, the iOS Notification Service Extension and the Android
receiver decrypt with the subscription's private key, which never leaves the
device.

### Two crates: `push` and `push-relay`

Push is split like the engine: a library every sender uses, and a relay that
runs embedded in monokulo or on its own, the way the engine runs inside
monokulo by default and as `monokulo-engine` when separate.

| Crate | Kind | Used by |
| --- | --- | --- |
| `push` | library | Every monokulo and the Bell (sending), `push-relay` (VAPID checks), tests |
| `push-relay` | library + binary `monokulo-push-relay` | Monokulo (embedded, cargo feature `relay`, on by default), or standalone |

Keeping them apart means a monokulo built with `--no-default-features`
drops the relay's server code, APNs/FCM clients and OAuth entirely, and
another Rust sender (the Bell) only takes the small crate. The Bell depends
on `push` from the monokulo repository (a git dependency pinned to a tag
until `push` is published to crates.io).

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
| Server | `axum`, `tokio` (already used) | Relay and Bell routes. |
| Rate limits | `governor` | Token buckets keyed by VAPID key and handle. |
| Storage | `rusqlite` (already used) | Monokulo's tables, the relay's handles, the Bell's registrations. |

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
  "vapid_public_key": "BJ…" }           // the sender: a monokulo or a Bell
→ 201 { "endpoint": "https://relay.monokular.app/v1/push/ph_7f3k…",
        "expires_at": … }
```

- One registration per (token, VAPID key): a device connected to two stores
  gets two handles, which the relay can't link to each other.
- Tokens change; the app re-registers whenever iOS or Android hands it a new
  one and sends the new endpoint to monokulo and the Bell.

Delivery, from a sender (plain Web Push):

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
| `apns` | `apns-push-type: alert`, `mutable-content: 1`, the ciphertext in a custom key; the Notification Service Extension decrypts and fills in title and body (for a ding, it fetches the sale first, §3). Fallback text: "New sale". |
| `apns-liveactivity` | `apns-push-type: liveactivity`, topic `<bundle>.push-type.liveactivity`, `aps.content-state` from the plain JSON, `event: update` / `end`, `alert` only for paid / expired. |
| `apns-pushtostart` | `event: start` (iOS 17.2+), only if the app opted in (it then sends attributes through the relay). |
| `fcm` | Data message, priority from `Urgency`, the ciphertext as data; the app decrypts. |

Answers back to the sender: `201`; `404`/`410` when APNs/FCM say the token
is dead (the relay deletes the handle; the sender deletes the target);
`429` with `Retry-After`.

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

**Who runs what:** the project runs the official relay **standalone**, next
to the official Bell, so both can be deployed and scaled on their own
schedule. Embedding suits a small operator shipping their own app with their
own APNs/FCM keys: one process, one upgrade. Either way their app registers
with that relay and hands the endpoint to its senders, with no change to
monokulo.

**Release artifacts:** `monokulo` (relay included), `monokulo-engine` and
`monokulo-push-relay` from the monokulo workspace; `monokular-bell` from
this one.

### UnifiedPush

On Android the app uses the UnifiedPush connector. With a distributor
installed (ntfy, NextPush, …) the endpoint is the distributor's and pushes
go **straight from the sender to the distributor**, with no relay. Without
one, the app falls back to FCM through the relay (or the UnifiedPush
embedded FCM distributor pointed at the relay; check that library's gateway
contract before choosing). On a phone without Google services and without a
distributor, Monokular keeps its foreground service on the store's SSE
stream and the Square timer during a shift (§7).

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
- `events` filters by event name; Monokular subscribes to `sale.*`.

### Events

Every event is about an invoice a connected app created (§2); the payload
has the invoice id, the app's `reference`, the amount, `xmr`, the plain
payment URI and `expires_at`. No event carries items.

| Event | From | Urgency | Notification | Live Activity |
| --- | --- | --- | --- | --- |
| `sale.waiting` | An app created an invoice | normal | none (the till that created it already shows it) | phase waiting |
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

## 6. The Live Activity

A status display for the current sale on iPhone. It creates nothing and
fetches nothing (Live Activity code has no network); the app starts or
updates it when it finds a sale (§3), monokulo's pushes move it through the
later phases, and iOS redraws it.

**Static attributes** (set by the app when it starts the activity, so never
through the relay): location label, store name.

**Content state**:

| Field | Example | Use |
| --- | --- | --- |
| `phase` | idle / waiting / seen / confirming / paid / expired | Layout and colour |
| `sale` | "#1042" | Label |
| `amount` | "$24.50" | Large amount |
| `xmr` | "0.1421" | Second line |
| `expiresAt` | timestamp | A countdown iOS draws itself (`Text(timerInterval:)`), and `stale-date` |
| `waitingCount` | 2 | "+1 more" when sales overlap |
| `paymentURI` | `monero:…` | **Only** when the device has "Show code in island" on; the plain URI, never the receipt (§4) |

**Show code in island** is a per-device setting, off by default. Turning it
on explains that the payment address of each sale then passes through the
Monokular push relay, and that the island's code has no receipt. Off: the
expanded island shows amount, status and "Tap to show code"; the QR appears
only in the app.

| Phase | Compact | Expanded / lock screen | Set by |
| --- | --- | --- | --- |
| idle | Logo + green dot | "Watching for sales · Front Counter" | the app |
| waiting | Logo + "$24.50" | Amount, XMR, countdown, QR if enabled | the app, on finding a sale |
| seen | Logo + "Seen" | "Seen on the network" | push, silent, low |
| confirming | Logo + "1/2" | "Confirming" | push, silent, low |
| paid | Tick + "$24.50" | "Paid · 0.1421 XMR received" | push, alert, high, then idle |
| expired | Clock | "No payment yet · refund in Square" | push, alert, high |

Lifecycle: one activity per shift, started by the app (iOS ends it after 8 h;
the next app launch starts a new one; push-to-start is opt-in because it
sends the attributes through the relay). Overlapping sales show the newest
plus "+N more". Tapping opens `monokular://sale/{id}`.

The waiting phase needs the app to run, so on iPhone it appears when the
cashier opens the app or taps the Bell's alert (§3), not on its own.

---

## 7. Battery and latency

| State | Finding sales | Invoice updates | Cost |
| --- | --- | --- | --- |
| App in the foreground | 2 s timer (30 s with the Bell) | SSE to the store's monokulo | Normal app use |
| iPad in Slide Over | Same | Same | Normal app use; tills are usually on chargers |
| iPhone in the background | Bell alert + Notification Service Extension; nothing without the Bell | APNs via the relay | Close to nothing |
| Android with a UnifiedPush distributor | Foreground service: Bell ding or 2 s timer | Direct Web Push to the distributor | The distributor's one connection, plus the timer during a shift |
| Android with Google services | Same, dings via FCM | FCM via the relay | Close to nothing beyond the timer |
| Android with neither | Foreground service on the 2 s timer | SSE in the same service, heartbeat 25 s | Small; only during a shift |

Rules:

- Timers run only during a shift and slow to 30 s whenever a Bell is set up.
- Push on state change only; at most five pushes per sale.
- `high` urgency only for dings, paid, partial, expired and double spend;
  everything else `low` or `normal`. APNs and FCM throttle apps that overuse
  high priority.
- `Topic` collapses superseded updates; `TTL` equals the time left on the
  code (60 s for dings).
- iOS: `NSSupportsLiveActivitiesFrequentUpdates` for busy tills.
- Square requests: one `ListPayments` per tick per till, plus one order read
  per Monero sale. The spike measures Square's limits for a busy store with
  several tills on one application.

---

## 8. Monokulo code layout

```text
crates/push/                    new library (§5): Web Push, VAPID, RFC 8291
crates/push-relay/              new library + `monokulo-push-relay` binary (§5)
crates/monokulo/                depends on push; on push-relay behind feature "relay" (default)
crates/monokulo/src/
  apps/                         connected apps
    mod.rs                      AppConnection, scopes, connect tokens (generalised from http::connect)
    invoices.rs                 invoice creation for apps: amount, reference, idempotency
    events.rs                   the sale.* event model, shared by SSE and push
  push/                         monokulo's side of push: push_targets, push_deliveries,
                                fan-out from webhooks::subscriber, the delivery worker
  http/apps.rs                  /api/v1/apps/{connect,invoices,events,push-targets}
  views/connected_apps.rs       the Connected apps card and Connect an app page
  relay.rs                      (feature "relay") registers push-relay's settings and, when
                                relay.enabled, starts a Relay on its own listener
```

There is no Square code in monokulo.

### Migrations

New tables only; nothing existing changes shape except moving the
WooCommerce connection into `app_connections` (a data migration, so flag it
before running it on a production instance):

- `app_connections`, `app_connect_tokens`
- `app_invoices` (connection, idempotency key, reference ─▶ order)
- `push_targets`, `push_deliveries`
- `vapid_keys` (encrypted private key)
- none for the relay: its handles live in its own SQLite file (§5)

### Settings (registry)

- `push.enabled`, `push.delivery.*` (attempts, backoff), `push.default_ttl`
- `relay.*` (declared in push-relay, registered by monokulo when built with
  the relay, §5)

### Tests

In-process like the rest of monokulo: a test UnifiedPush endpoint and a test
relay run on the test's own axum router; `push::encrypt::decrypt` checks
what arrived. APNs and FCM are faked behind their provider traits; one
`over_http` test per provider talks to a local HTTP/2 stub. The invoice API
is tested for idempotency and for refusing any field beyond its schema.

---

## 9. The Monokular repository

A separate public repository, `monokular`, holding both apps, the Bell, the
receipt codec and their shared contract. The apps are native (SwiftUI,
Jetpack Compose); what they share is the contract, test vectors and
`orf-codec`.

GitHub description:

> Monokular: iPhone, iPad and Android companion for Monokulo. Take Monero next to your till: it sees Monero sales in your POS (Square first) and shows the customer the payment code, in the Dynamic Island, Slide Over or a floating bubble.

```text
monokular/
  README.md
  Cargo.toml                    Rust workspace
  crates/
    orf-codec/                  ORF receipts to and from `orf=<digits>` (library, no Monero, no QR)
    orf-codec-ffi/              UniFFI bindings: a Kotlin package and a Swift package
    monokular-bell/             the Bell (§3): library + `monokular-bell` binary
  tools/receipt-qr/             corpus, dictionary training and QR size measurements
  protocol/                     the contract with monokulo and the Bell
    openapi.yaml                /api/v1/apps/* (connect, invoices, events, push-targets),
                                the relay's /v1/register, the Bell's /v1/*
    events.schema.json          sale.* payloads, dings and the Live Activity content state
    vectors/                    JSON test vectors generated by monokulo's xtask:
                                connect links, RFC 8291 payloads (keys, ciphertext, plaintext), events
    square/                     recorded Square payments and orders (Other tenders, refunds)
  ios/
    project.yml                 XcodeGen (no .xcodeproj in git)
    Packages/MonokularKit/      SwiftPM package, no UIKit; builds and tests on Linux CI too
      Sources/
        Model/                  Store, Sale, SalePhase, Event decoding
        API/                    connect, invoices, SSE client, push-target and Bell registration
        Sales/                  SaleSource (Square: PKCE, payments cursor, orders, refunds),
                                wake triggers (timer, ding, app opened)
        Receipt/                Square order ─▶ ORF, then orf-codec through its Swift bindings
        Crypto/                 RFC 8291 decrypt (swift-crypto, which is CryptoKit's API off Apple platforms)
        Links/                  connect-link parsing
      Tests/                    runs protocol/vectors and protocol/square
    Monokular/                  the app
      App/                      entry point, routing, monokular:// links
      Features/
        Onboarding/  Sale/  Settings/
      Services/                 Keychain (shared group), LiveActivityController, PushRegistration, Scanner
      Resources/                assets, the loading-mark animation, strings
    MonokularWidgets/           Live Activity (compact, minimal, expanded, lock screen)
    MonokularNotifications/     Notification Service Extension: decrypt, handle dings (fetch the
                                sale, create the invoice), fill in alerts
    MonokularUITests/           snapshot tests of every screen for CI screenshots
  android/
    settings.gradle.kts
    core/                       pure Kotlin (JVM): model, API, SSE, RFC 8291 decrypt, links; runs protocol/vectors
    sales/                      SaleSource (Square: PKCE sign-in, payments cursor, orders, refunds),
                                wake triggers, the shift's foreground service
    receipt/                    Square order ─▶ ORF, orf-codec through its Kotlin bindings
                                (native library per ABI)
    push/                       UnifiedPush connector, FCM fallback, registration with monokulo and the Bell
    overlay/                    the bubble and floating panel (display over other apps)
    notifications/              Live Update / ongoing notifications and their progress
    app/                        Compose UI: onboarding, sale, settings
  docs/
    architecture.md  release.md
  .github/workflows/
    rust.yml                    orf-codec, orf-codec-ffi and monokular-bell: tests and clippy
    core.yml                    MonokularKit on Linux (swift test) + android core (JVM tests)
    ios.yml                     macos runner: XcodeGen, build, UI snapshots, development .ipa artifact
    android.yml                 ubuntu: build, unit tests, debug APK artifact
    release.yml                 TestFlight / Play internal track / F-Droid metadata; the Bell's binaries
```

Choices:

- **Native per platform** over a shared UI toolkit: Live Activities, Slide
  Over, overlays and Live Updates are each platform's own APIs.
- **The pure core is a package/module with no UI framework.** The parts most
  likely to be wrong (decryption, event decoding, Square reconciliation, link
  parsing) build and test on Linux, so most work doesn't wait on a macOS
  runner.
- **monokulo is the source of truth for its contract:** its xtask writes
  `protocol/vectors/` from the real Rust code, and both apps' tests read
  them. Changing an event shape breaks the app tests, not a till.
- **The receipt codec is shared Rust, through UniFFI.** A bit-exact format
  with a trained zstd dictionary shouldn't be written twice. The rest of the
  core stays native: a shared Rust core for it isn't worth the build
  complexity for this amount of logic.

---

## 10. Delivery plan

1. **Square spike** on a real account with $0 Other tenders: Other tenders in
   `ListPayments` and in `payment.created`; `RefundPayment` on them;
   `device_details` for routing; PKCE sign-in from a phone with an App Link
   redirect, and its refresh token rules; request limits with several tills
   polling every 2 s on one application. Record the responses into
   `protocol/square/`.
2. **Connected apps** in monokulo: tables, the Connected apps card, the
   connect page and link, `/api/v1/apps/connect`, `/invoices` and `/events`;
   the WooCommerce connection moves in.
3. **Monokular Android, timer only:** connect, Square sign-in, the payments
   cursor, invoice creation, the receipt (`orf-codec-ffi` Kotlin bindings,
   `query::append`, the plain-code fallback), SSE, the foreground service.
   **iPad** follows the same way (Slide Over, timer only).
4. **`crates/push`** and push targets in monokulo; UnifiedPush end to end
   with ntfy for invoice updates.
5. **The Monokular Bell:** Square webhooks, registration with the token
   check, dings over Web Push; UnifiedPush dings on Android.
6. **`push-relay`**: APNs (alert, liveactivity) and FCM, the standalone
   binary and the embedded mount; the official relay and Bell deployed.
7. **iPhone:** the Notification Service Extension handling dings, then the
   Live Activity. Android's overlay bubble and Live Updates alongside.

Android and iPad come first because they work with a timer alone: no relay,
no Bell, no Mac for Android.

---

## 11. Open questions

- Square: Other tenders in the Payments API and webhooks; refunds of them;
  whether `device_details` is enough to route a sale to one till; request
  limits per application with many tills polling.
- Square PKCE: confirm the refresh token lifetime and single-use rule, that a
  mobile redirect to an App Link / universal link is accepted, and that a
  shared Keychain group lets the Notification Service Extension use and
  refresh the token safely alongside the app.
- Square POS for Android: does it hide other apps' overlays on its checkout
  screens (`HIDE_OVERLAY_WINDOWS`)? If so the bubble disappears there.
- UnifiedPush embedded FCM distributor: can it point at our relay, or does
  Android talk to the relay's FCM path directly?
- The island's code (when on) carries no receipt. Is that clear enough to
  cashiers, or should the island say "Open for receipt code"?
- iPadOS 26.1: the exact steps to put an app in Slide Over, for onboarding
  copy.
- `monokular.app`: the domain for links, the Square sign-in redirect, the
  relay and the Bell.
- Publishing `push` to crates.io so the Bell needn't use a git dependency.
- App Store review of a crypto-payments companion; Google Play's "display
  over other apps" declaration and its foreground service type for a shift.
