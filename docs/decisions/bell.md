# Decisions: the Monokular Bell, first build

Made while building the Bell (`crates/monokular-bell`) on 2026-10-27, without
the owner to ask, as package 5 of the push plan whose decisions are logged in
monokulo's `docs/decisions/push.md` ("plan" below means that log's part C).
Each says what was chosen, why, and what else was possible, so any of them can
be reversed knowingly. The Bell's API is in [../bell.md](../bell.md).

## From the plan

1. **The Bell builds on monokulo's crates by git revision** (plan 22, widened).
   `push`, `live-settings`, `sqlite` and `rate-limit` as the plan said, and
   also `abuse` (the client's address behind a trusted proxy, IPv6 by its
   /64, decision 14) and `service-runtime` (the SIGTERM/Ctrl-C signal, its
   10 s grace and the clock), all at one commit: the head of monokulo's
   `push-crate` branch, `abeb0ddfb`. They are the only entries in the root
   `[workspace.dependencies]`, so the revision is written once; the Bell's
   other dependencies are declared in its own manifest, as the repo's other
   crates declare theirs. Once the push PRs are merged, `rev` moves to
   `main`'s commit (`cargo update -p push --precise <commit>`). Pinning
   `push-relay`'s head instead was possible: its `live-settings` changes are
   for services embedded in monokulo, which the Bell isn't. Writing the
   proxy-address and shutdown code again here was the alternative to the two
   extra pins; both crates build on stable and bring only `sysinfo` new.
2. **Every new `Cargo.lock` entry is dated on or before 2026-10-13** (plan 23).
   Checked against crates.io's release dates. `crypto-bigint` (0.7.10 → 0.7.5),
   `ctutils` (0.4.6 → 0.4.2), `der` (0.8.3 → 0.8.2), `elliptic-curve`
   (0.14.3 → 0.14.1) and `inout` (0.2.5 → 0.2.2) were held at the versions
   monokulo's lock has. `tokio` was already in this repository's lock at
   1.54.1 (2026-10-21, from `tools/square-sandbox`); it is held at 1.53.3, as
   the plan asks, which `square-sandbox`'s `1.53.1` requirement allows.
3. **`crypto-bigint` stays at 0.7.5 although it is yanked.** RUSTSEC-2026-0387
   (2026-10-23): several operations meant to be constant-time could branch on
   secret values; fixed in 0.7.6, but 0.7.6 to 0.7.8 were yanked as well, and
   the only releases left are 0.7.9 (2026-10-25) and 0.7.10 (2026-10-26),
   days old. `p256` does its arithmetic with it, so the Bell's VAPID signing
   and RFC 8291's ECDH run on it. Kept at 0.7.5 because that is what monokulo's
   lock (and so `push`'s own tests) has, because releases this fresh and this
   churned are what the two-week rule is for, and because the exposure here is
   small: the VAPID key signs once per push service every 11 hours (`push`
   reuses the token), and an ECDH key is new for each ding and protects only
   `{"wake":"square"}`. **To be decided with monokulo**: move both locks to
   0.7.10 or later together, at the latest when it is two weeks old
   (2026-11-09), or now if the fix should win over the rule.
4. **Registration proves the location with `GET /v2/locations/{location_id}`**
   (plan 24), with `Square-Version: 2026-09-16`, the till's. The answer's
   `merchant_id` is what the registration keeps, and its `location.id` must be
   the one asked for. `401`, `403` (no `MERCHANT_PROFILE_READ`) and `404` (not
   the merchant's) and any other `4xx` are `square-refused`; `429`, `5xx`, no
   answer and an answer that isn't a location are `square-unavailable`.
5. **A webhook is read for `type`, `merchant_id`,
   `data.object.payment.location_id` and `status` alone** (plan 25), in two
   steps: `type` first, so any other event is dropped with nothing else read,
   then the payment's three fields into structs that hold only them. A body
   that doesn't read is described by where it stopped (line, column, kind),
   never with `serde_json`'s message, which can quote the value it refused.
6. **Built and tested by `rust.yml`; no release job yet** (plan 27). The
   workflow's test step runs `cargo test --locked -p monokular-bell` beside the
   codec crates; a job that publishes the binary comes with a deployment.
7. **Registration ids are `br_` and 32 random hex digits, made here** (plan 28).

## Made while building

8. **`Bell::start(&BellConfig)` takes the loaded settings alone.** The plan's
   `start(config, env)`: the two secrets are settings declared from the
   environment only, read with the rest by `settings::load`, so there is no
   separate environment to pass. `bell.public_uri` and
   `bell.square.application_id` are options-file settings, which
   `live-settings` can't mark required (only an unstored one can be), and a
   section has to accept every setting at its default; so they are `Option`s
   in `BellConfig`, and `Bell::start` stops with "… must be set" without them,
   before anything is opened. The push relay does the same with its address.
9. **`bell.public_uri` is the Bell's base address; Square's webhook URL is it
   followed by `/v1/square/webhook`.** `https`, or `http` to this machine (a
   push endpoint's rule, `push::Endpoint`), never an onion address, no user
   name, query or fragment; a path is kept (a proxy serving the Bell under
   one), a trailing slash isn't, so `https://bell.example.org/` and
   `https://bell.example.org` give the same URL. The signature is checked
   against exactly that URL: a subscription in the Developer Console with a
   trailing slash or a query is refused, and the `401`'s detail names the URL
   expected. The plan's type was `HttpUrl`, which takes plain `http` anywhere.
10. **`bell.square.api_url` is `https` or loopback `http` too.** It is sent a
    merchant's access token, so plain `http` across a network would hand it
    to anyone on the path. Unset, the environment's own:
    `https://connect.squareup.com` or `https://connect.squareupsandbox.com`.
11. **An application ID must match `bell.square.environment`.** `sq0idp-…` is
    production, `sandbox-sq0idb-…` sandbox; a mismatch stops the Bell, as
    Square would never send it a webhook.
12. **Its files are where `live_settings::paths` puts them**:
    `~/.config/monokulo/bell.toml` and `~/.local/share/monokulo/bell.db`, not a
    `monokular` directory. `paths` names monokulo's directory alone; another
    would need a `live-settings` change in monokulo, outside the pinned
    commit. `--options` and `bell.store_path` name any other place. The
    `--init` file's header mentions an admin page, which the Bell doesn't
    have: that text is `live-settings`'.
13. **Dings go to public addresses only, unless
    `bell.allow_private_endpoints`** (off by default). Any app can register any
    endpoint, so without this a registration could make the Bell `POST` inside
    its own network. As monokulo's `crate::outbound` does for webhooks and
    pushes: a resolver that refuses a name with any private answer at connect
    time, no redirects, no proxy, and an IP-literal endpoint checked before the
    request, and at registration too (`400`). The address rule is a copy of
    monokulo's `credentials::webhook_sign::is_disallowed_address` (and
    `telemetry`'s `embedded_ipv4`), as `credentials` would bring its password
    hashing and `telemetry` along (plan 28's reason). The tests turn it on, as
    their push service is on loopback.
14. **Registrations are limited per address: `bell.registrations_per_minute`
    (30)**, IPv6 by its /64, on `rate-limit`, counted before the body is
    read, as each costs a request to Square. The Bell listens on loopback
    behind a proxy, so `bell.trusted_proxies` is `127.0.0.1, ::1` by default:
    a proxy on the same machine is trusted for `X-Forwarded-For`, and every
    till isn't counted as the proxy. Webhooks aren't limited: only Square
    can sign them.
15. **One registration per subscription endpoint.** Registered again (the app
    starting again, or the till moved to another location) it keeps its id,
    takes the new location and keys, and its failures start over; the answer
    is `201` either way. An endpoint is a device's capability, so two rows for
    one would only ding it twice.
16. **A location takes at most 32 registrations; one more is
    `409 too-many-registrations`**, a problem the plan didn't have. A shop has
    a till or a few; unbounded, one merchant's token could fill the database.
    A constant, as monokulo's eight push targets per app is.
17. **A ding is sent once.** No queue, no retry: with a 60-second TTL a retry
    is worth little and the till's 30-second timer is the backstop. Each of a
    location's tills is sent to at once (a task per registration). `404`/`410`
    delete the registration; every other failure (an error status, no answer,
    a private address) counts towards `bell.registration_failure_limit`; `429`
    doesn't, as that is the push service limiting the Bell, not the till's
    subscription failing; a ding taken starts the count again.
18. **A webhook is answered `200` once its signature checks, whatever it
    says**; a body that doesn't read is logged as dropped (decision 5's
    description) rather than refused, since refusing only has Square send it
    again. A bad signature is `401 invalid-signature` with a warning in the
    log. Square's signature has no timestamp, so a captured webhook can be
    replayed: it costs a ding.
19. **Every ding's VAPID `sub` is `bell.public_uri`** when it is `https`
    (RFC 8292 takes no `http` one), so a push service's operator can find
    whoever runs the Bell; the same rule as monokulo's.
20. **One timeout, `bell.timeout_ms` (10 s), for Square and the push
    services.** Both are a single request whose caller waits (a till
    registering) or nobody does (a ding); two settings would tune nothing
    useful.
21. **A stopping Bell lets dings in flight finish**, within the same 10 s
    grace as requests (`Bell::dings_sent`). A webhook already answered `200`
    would otherwise lose its dings to a restart. The tests wait on it too.
22. **The signature's tag is compared with `hmac`'s `verify_slice`**, which is
    constant-time on `subtle`; the plan named `subtle` itself. No direct
    dependency is needed for the same comparison.
23. **Logging is `tracing-subscriber`, `logging.level` alone**, as the plan
    said, rather than monokulo's `telemetry` (OpenTelemetry, a log database).
    Colour only at a terminal; no JSON format setting yet. No line carries a
    body, a token or an endpoint, only registration ids and endpoint hosts;
    the tests assert it on a captured log at trace level.
24. **The access token is wiped from memory when dropped** (`Zeroizing`), its
    `Debug` prints nothing of it, and it is dropped as soon as Square's
    request is sent.
25. **Problem `type`s point at sections of `docs/bell.md` on GitHub**, the
    Bell's API page, written with this build; the Bell has no site of its own.
26. **The stand-in Square (`tools/fake-square`) answers
    `GET /v2/locations/{id}` and can send each sale to a Bell as a signed
    `payment.created`**, so `bell.square.api_url` has the local use the plan
    gave it and a Bell runs end to end on one machine (README).
27. **`docs/design.md` §3 says what was built**: the location check, the
    settings, the git pins and the failure rule. §5 still says the Bell pins
    `push` "to a tag" (it pins a revision, decision 1): outside §3, which is
    all this package was to touch.
28. **Not wired into the app** (plan 1). The Android app registering with a
    Bell (Settings › Wake-ups, `/v1/info`, `/v1/register` with its UnifiedPush
    or relay endpoint) is the next step; `docs/bell.md` is the contract.
