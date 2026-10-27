# The Monokular Bell: report

The project report for the push system and the Bell, written 2026-10-27
for the owner's review, is monokulo's
[`docs/reports/bell-and-relay.md`](https://github.com/OceanEilonwy/monokulo/blob/push-relay/docs/reports/bell-and-relay.md)
(on the `push-relay` branch until the stack merges): it covers both
repositories, with every decision made without the owner, the design's
deviations, the tests, the gates, the risks and what is left. This page is
the Bell's part of it.

## What was built

`crates/monokular-bell`, a library and the `monokular-bell` binary (branch
`monokular-bell`, draft PR [#14](https://github.com/OceanEilonwy/monokular/pull/14)):
a small service that turns a Square application's payment webhooks into Web
Push dings for the tills registered at that location. Its API is
[bell.md](../bell.md); how to run one is the README's "Running a Bell"; the
32 decisions behind it and the 3 from the integration are
[decisions/bell.md](../decisions/bell.md).

- `POST /v1/register`: a till's Web Push subscription for one location,
  with the merchant's Square token, used once for `GET /v2/locations/{id}`
  (its `merchant_id` proves the token and the location), then dropped,
  never stored or logged, wiped from memory.
- `POST /v1/square/webhook`: Square's HMAC-SHA256 signature over
  `bell.public_uri` + `/v1/square/webhook` and the body, compared in
  constant time; only `type`, `merchant_id`, `location_id` and `status` are
  read; `200` at once, then one ding per registration at the location: an
  encrypted `{"wake":"square"}`, `Urgency: high`, `TTL: 60`, `Topic: wake`,
  VAPID-signed. No body is stored or logged (a test reads the database files
  and a trace-level log to check).
- `DELETE /v1/register/{id}`, `GET /v1/info`; registrations limited per
  address behind a trusted proxy; 32 per location; `404`/`410` and 20
  failures in a row delete a registration; dings go to public addresses only
  unless `bell.allow_private_endpoints`.
- Settings through monokulo's `live-settings` (`bell.toml` + CLI, `--init`,
  secrets `MONOKULAR_BELL_SQUARE_WEBHOOK_KEY` and `MONOKULAR_BELL_VAPID_KEY`
  from the environment only, anything invalid stops the process); its own
  SQLite file; graceful shutdown with dings in flight finished.
- It builds on monokulo's `push`, `live-settings`, `sqlite`, `rate-limit`,
  `abuse` and `service-runtime` as git dependencies pinned to one monokulo
  commit (the head of its `push-relay` branch, decision 33); after the
  monokulo push PRs merge the pin moves to `main`.
- `tools/fake-square` answers `GET /v2/locations/{id}` and sends a Bell
  signed `payment.created` webhooks, so a Bell runs end to end on one
  machine; `rust.yml` tests the Bell beside the receipt codec.

## Tests and gates

71 tests (`cargo nextest run -p monokular-bell`, about a second): 16
in-process, 25 over real HTTP (named `over_http`: a completed payment's
webhook dings every till at its location and the ding decrypts to
`{"wake":"square"}` with the right headers; other events ding nothing;
Square refusing, redirecting or timing out; gone and failing subscriptions;
the token left nowhere; private endpoints refused; racing registrations
filling a location exactly), 4 of the binary (`--init`, missing secrets,
SIGTERM), 26 unit (the signature against Square's documented example,
settings, the command line, the store, addresses).

Gates, as `rust.yml` runs them, on stable (1.98.1) against the pinned
monokulo commit `35711592a`, 2026-10-27: `cargo fmt -p monokular-bell
--check`, `cargo test --locked -p orf-codec -p orf-codec-ffi -p
monokular-bell`, `cargo clippy --locked --workspace --all-targets -- -D
warnings` and `cargo nextest run --locked -p monokular-bell` (71 passed),
all passed.

## Risks and what is left

- `crypto-bigint` 0.7.5 (under `p256`) is yanked (RUSTSEC-2026-0387); it
  stays until 0.7.10 is two weeks old, 2026-11-09, both repositories moving
  together (decision 30).
- Square's location check and webhook shapes were tested against stand-ins
  built from Square's documentation, not against Square; the spike in
  design §10 is still open.
- The Android app isn't wired to the Bell (decision 28): Settings ›
  Wake-ups, `/v1/info` and `/v1/register` with its UnifiedPush or relay
  endpoint are the next step; `bell.md` is the contract. No deployment of
  the official Bell, no release job yet (decision 6).
- The `--init` header comes from live-settings and mentions an admin page
  the Bell doesn't have (decision 12).
