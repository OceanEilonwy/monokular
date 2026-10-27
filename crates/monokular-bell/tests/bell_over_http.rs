//! The Bell asking a stand-in Square on loopback to check a registration,
//! and sending dings to a stand-in push service there, read back as a
//! till would read them.

mod support;

use axum::http::StatusCode;
use push::wire::PushRequest;
use push::{Message, Origin};
use support::*;

/// Registered with the merchant's token, the till is kept with the
/// merchant Square named for the location; the token went to Square once,
/// and is nowhere in the database or the log.
#[tokio::test]
async fn registration_over_http_keeps_the_merchant_square_names() {
    let (_guard, log) = capture_log();
    let square = Stub::square().await;
    let push = Stub::push_service().await;
    let bell = TestBell::start(&square.url(), &[]).await;
    let till = Till::at(&push, "till-1");

    let answered = bell.register(TOKEN, LOCATION, &till).await;
    assert_eq!(answered.status, StatusCode::CREATED, "{}", answered.text());
    let id = answered.json()["registration_id"]
        .as_str()
        .unwrap()
        .to_owned();
    assert!(id.starts_with("br_") && id.len() == 35, "{id}");
    assert_eq!(answered.json()["square_application_id"], APPLICATION_ID);

    let asked = square.next().await;
    assert_eq!(asked.path, format!("/v2/locations/{LOCATION}"));
    assert_eq!(asked.header("authorization"), format!("Bearer {TOKEN}"));
    assert_eq!(asked.header("square-version"), "2026-09-16");

    let rows = bell.rows();
    assert_eq!(rows.len(), 1);
    assert_eq!(
        (&rows[0].0, rows[0].1.as_str(), rows[0].2.as_str()),
        (&id, MERCHANT, LOCATION)
    );
    let stored = String::from_utf8_lossy(&bell.stored_bytes()).into_owned();
    assert!(!stored.contains(TOKEN), "the token is in the database");
    let log = log.text();
    assert!(log.contains("registered"), "{log}");
    for secret in [TOKEN, till.endpoint.as_str()] {
        assert!(!log.contains(secret), "{secret} is in the log: {log}");
    }

    // Registered again, the till keeps its registration.
    let again = bell.registered(TOKEN, LOCATION, &till).await;
    assert_eq!(again, id);
    assert_eq!(bell.rows().len(), 1);
}

/// A token Square refuses, and a location that isn't the token's
/// merchant's, are refused as Square refused them, and nothing is kept.
#[tokio::test]
async fn a_refused_token_or_another_merchants_location_is_square_refused_over_http() {
    let square = Stub::square().await;
    let push = Stub::push_service().await;
    let bell = TestBell::start(&square.url(), &[]).await;
    let till = Till::at(&push, "till-1");
    for (token, location) in [
        ("EAAAl-a-token-square-never-gave", LOCATION),
        (TOKEN, OTHER_MERCHANTS_LOCATION),
        (OTHER_TOKEN, LOCATION),
    ] {
        let answered = bell.register(token, location, &till).await;
        assert_eq!(
            answered.status,
            StatusCode::UNAUTHORIZED,
            "{}",
            answered.text()
        );
        assert_eq!(answered.problem(), "square-refused");
    }
    assert!(bell.rows().is_empty());
}

/// Square down, slow to the point of the timeout, limiting the Bell, or
/// not there at all: try again later.
#[tokio::test]
async fn a_square_that_cant_answer_is_square_unavailable_over_http() {
    let square = Stub::square().await;
    let push = Stub::push_service().await;
    let bell = TestBell::start(&square.url(), &[]).await;
    let till = Till::at(&push, "till-1");
    for status in [500, 503, 429] {
        square.answer(move |_| Answer::status(status));
        let answered = bell.register(TOKEN, LOCATION, &till).await;
        assert_eq!(answered.status, StatusCode::SERVICE_UNAVAILABLE, "{status}");
        assert_eq!(answered.problem(), "square-unavailable");
    }
    square.answer(|_| Answer::status(200).json(serde_json::json!({"nothing": "here"})));
    let answered = bell.register(TOKEN, LOCATION, &till).await;
    assert_eq!(answered.problem(), "square-unavailable");

    let gone = TestBell::start(&unreachable_url(), &[]).await;
    let answered = gone.register(TOKEN, LOCATION, &till).await;
    assert_eq!(answered.status, StatusCode::SERVICE_UNAVAILABLE);
    assert_eq!(answered.problem(), "square-unavailable");
    assert!(bell.rows().is_empty() && gone.rows().is_empty());
}

/// A completed payment dings each till at its merchant's location, and
/// only those: encrypted `{"wake":"square"}`, high urgency, a minute to
/// live, topic `wake`, signed with the Bell's VAPID key for the push
/// service.
#[tokio::test]
async fn a_completed_payment_dings_every_till_at_its_location_over_http() {
    let square = Stub::square().await;
    let push = Stub::push_service().await;
    let bell = TestBell::start(&square.url(), &[]).await;
    let (front, back) = (Till::at(&push, "front"), Till::at(&push, "back"));
    let elsewhere = Till::at(&push, "elsewhere");
    let other_merchant = Till::at(&push, "other-merchant");
    bell.registered(TOKEN, LOCATION, &front).await;
    bell.registered(TOKEN, LOCATION, &back).await;
    bell.registered(TOKEN, OTHER_LOCATION, &elsewhere).await;
    bell.registered(OTHER_TOKEN, OTHER_MERCHANTS_LOCATION, &other_merchant)
        .await;

    let answered = bell
        .webhook(&payment_webhook(
            "payment.created",
            "COMPLETED",
            MERCHANT,
            LOCATION,
        ))
        .await;
    assert_eq!(answered.status, StatusCode::OK, "{}", answered.text());

    let mut dings = push.drain().await;
    dings.sort_by(|a, b| a.path.cmp(&b.path));
    assert_eq!(
        dings
            .iter()
            .map(|ding| ding.path.as_str())
            .collect::<Vec<_>>(),
        ["/push/back", "/push/front"]
    );
    let mut private = [0u8; 32];
    hex::decode_to_slice(VAPID_HEX, &mut private).unwrap();
    let bell_key = push::VapidKey::from_bytes(&private).unwrap().public();
    let now = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap()
        .as_secs() as i64;
    for (ding, till) in dings.iter().zip([&back, &front]) {
        assert_eq!(ding.header("urgency"), "high");
        assert_eq!(ding.header("ttl"), "60");
        assert_eq!(ding.header("topic"), "wake");
        assert_eq!(ding.header("content-encoding"), "aes128gcm");
        // Read as the push service reads it, signed as it checks.
        let request = PushRequest::parse(&ding.headers, &ding.body).unwrap();
        let claims = push::vapid::verify(
            &request.authorization,
            &bell_key,
            &Origin::parse(&push.url()).unwrap(),
            now,
        )
        .unwrap();
        assert_eq!(
            claims.sub.map(|sub| sub.to_string()).as_deref(),
            Some("https://bell.example.org/")
        );
        let Message::Encrypted(body) = request.message else {
            panic!("a ding is encrypted");
        };
        let plaintext = push::encrypt::decrypt(&till.keys, body.as_bytes()).unwrap();
        assert_eq!(plaintext, br#"{"wake":"square"}"#);
    }
    // Each took it: noted, with nothing failed.
    let rows = bell.rows();
    for row in &rows {
        let at_location = row.2 == LOCATION && row.1 == MERCHANT;
        assert_eq!(row.3.is_some(), at_location, "{row:?}");
        assert_eq!(row.4, 0);
    }

    // payment.updated to COMPLETED (a card payment captured) dings too.
    bell.webhook(&payment_webhook(
        "payment.updated",
        "COMPLETED",
        MERCHANT,
        OTHER_LOCATION,
    ))
    .await;
    let dings = push.drain().await;
    assert_eq!(
        dings
            .iter()
            .map(|ding| ding.path.as_str())
            .collect::<Vec<_>>(),
        ["/push/elsewhere"]
    );
}

/// A payment that hasn't completed, and every other event, ding nobody.
#[tokio::test]
async fn other_events_ding_nothing_over_http() {
    let square = Stub::square().await;
    let push = Stub::push_service().await;
    let bell = TestBell::start(&square.url(), &[]).await;
    bell.registered(TOKEN, LOCATION, &Till::at(&push, "front"))
        .await;
    let refund = payment_webhook("payment.created", "COMPLETED", MERCHANT, LOCATION)
        .replace("payment.created", "refund.created");
    for body in [
        payment_webhook("payment.updated", "APPROVED", MERCHANT, LOCATION),
        payment_webhook("payment.created", "PENDING", MERCHANT, LOCATION),
        payment_webhook("payment.updated", "FAILED", MERCHANT, LOCATION),
        payment_webhook("payment.updated", "CANCELED", MERCHANT, LOCATION),
        refund,
        // Completed, but for a location (and merchant) nobody registered.
        payment_webhook("payment.created", "COMPLETED", MERCHANT, OTHER_LOCATION),
        payment_webhook("payment.created", "COMPLETED", OTHER_MERCHANT, LOCATION),
        "{\"type\":\"payment.created\",\"merchant_id\":\"x\"}".to_owned(),
        "not json".to_owned(),
    ] {
        let answered = bell.webhook(&body).await;
        assert_eq!(answered.status, StatusCode::OK, "{body}");
    }
    assert!(push.drain().await.is_empty());
    // The registration is still there, and dinged by the next payment.
    bell.webhook(&payment_webhook(
        "payment.created",
        "COMPLETED",
        MERCHANT,
        LOCATION,
    ))
    .await;
    assert_eq!(push.drain().await.len(), 1);
}

/// A push service that says the subscription is gone (`410`, or `404`)
/// has its registration deleted at once.
#[tokio::test]
async fn a_gone_subscription_deletes_its_registration_over_http() {
    let square = Stub::square().await;
    let push = Stub::start(|received| {
        if received.path.ends_with("/gone") {
            Answer::status(410)
        } else if received.path.ends_with("/missing") {
            Answer::status(404)
        } else {
            Answer::status(201)
        }
    })
    .await;
    let bell = TestBell::start(&square.url(), &[]).await;
    let kept = bell
        .registered(TOKEN, LOCATION, &Till::at(&push, "kept"))
        .await;
    bell.registered(TOKEN, LOCATION, &Till::at(&push, "gone"))
        .await;
    bell.registered(TOKEN, LOCATION, &Till::at(&push, "missing"))
        .await;
    bell.webhook(&payment_webhook(
        "payment.created",
        "COMPLETED",
        MERCHANT,
        LOCATION,
    ))
    .await;
    assert_eq!(push.drain().await.len(), 3);
    let rows = bell.rows();
    assert_eq!(rows.iter().map(|row| &row.0).collect::<Vec<_>>(), [&kept]);
}

/// Dings a push service doesn't take are counted, and at
/// `bell.registration_failure_limit` in a row the registration goes; one
/// taken starts the count again, and a push service limiting the Bell
/// isn't the subscription's failure.
#[tokio::test]
async fn failing_dings_delete_a_registration_at_the_limit_over_http() {
    let square = Stub::square().await;
    let push = Stub::push_service().await;
    let bell = TestBell::start(&square.url(), &[("bell.registration_failure_limit", "3")]).await;
    let id = bell
        .registered(TOKEN, LOCATION, &Till::at(&push, "front"))
        .await;
    let ring = || payment_webhook("payment.created", "COMPLETED", MERCHANT, LOCATION);

    push.answer(|_| Answer::status(500));
    bell.webhook(&ring()).await;
    push.answer(|_| Answer::status(400));
    bell.webhook(&ring()).await;
    assert_eq!(bell.rows()[0].4, 2);
    push.answer(|_| Answer::status(429).header("retry-after", "30"));
    bell.webhook(&ring()).await;
    assert_eq!(bell.rows()[0].4, 2, "a rate limit isn't a failure");
    push.answer(|_| Answer::status(201));
    bell.webhook(&ring()).await;
    assert_eq!(bell.rows()[0].4, 0);

    push.answer(|_| Answer::status(503));
    for _ in 0..2 {
        bell.webhook(&ring()).await;
    }
    assert_eq!(bell.rows()[0].0, id);
    bell.webhook(&ring()).await;
    assert!(
        bell.rows().is_empty(),
        "deleted at the third failure in a row"
    );
    assert_eq!(push.drain().await.len(), 7);
}

/// A push service that can't be reached fails the ding like any other.
#[tokio::test]
async fn an_unreachable_push_service_counts_as_a_failure_over_http() {
    let square = Stub::square().await;
    let bell = TestBell::start(&square.url(), &[("bell.registration_failure_limit", "1")]).await;
    let till = Till {
        keys: push::encrypt::ReceiverKeys::generate(),
        endpoint: format!("{}/push/1", unreachable_url()),
    };
    bell.registered(TOKEN, LOCATION, &till).await;
    bell.webhook(&payment_webhook(
        "payment.created",
        "COMPLETED",
        MERCHANT,
        LOCATION,
    ))
    .await;
    assert!(bell.rows().is_empty());
}

/// A webhook with a payment's amounts, card and buyer leaves none of them
/// in the database or the log, at any level.
#[tokio::test]
async fn a_webhook_leaves_no_trace_of_its_payment_over_http() {
    let (_guard, log) = capture_log();
    let square = Stub::square().await;
    let push = Stub::push_service().await;
    let bell = TestBell::start(&square.url(), &[]).await;
    let till = Till::at(&push, "front");
    bell.registered(TOKEN, LOCATION, &till).await;
    for body in [
        payment_webhook("payment.created", "COMPLETED", MERCHANT, LOCATION),
        payment_webhook("payment.updated", "APPROVED", MERCHANT, LOCATION),
        payment_webhook("payment.created", "COMPLETED", MERCHANT, LOCATION)
            .replace(LOCATION, "a/b"),
    ] {
        assert_eq!(bell.webhook(&body).await.status, StatusCode::OK);
    }
    let unsigned = payment_webhook("payment.created", "COMPLETED", MERCHANT, LOCATION);
    let refused = bell
        .call(webhook_request(
            &unsigned,
            Some(&sign(&format!("{WEBHOOK_URL}/"), &unsigned)),
        ))
        .await;
    assert_eq!(refused.status, StatusCode::UNAUTHORIZED);
    assert!(!refused.text().contains(CARD_LAST_4));
    assert_eq!(push.drain().await.len(), 1);

    let log = log.text();
    assert!(log.contains("a signed webhook was dropped"), "{log}");
    let stored = String::from_utf8_lossy(&bell.stored_bytes()).into_owned();
    for trace in [
        CARD_LAST_4,
        FINGERPRINT,
        BUYER_EMAIL,
        PAYMENT_ID,
        &AMOUNT.to_string(),
        "VISA",
        "424242",
        "13b867cf-db3d-4b1c-90b6-2f32a9d78124",
        "uZhPq6R6rkK3ZT9lYBJ8NawqHTMZY",
        TOKEN,
    ] {
        assert!(!log.contains(trace), "{trace} is in the log: {log}");
        assert!(!stored.contains(trace), "{trace} is in the database");
    }
    // The endpoint is kept, to ding, and never logged.
    assert!(
        !log.contains(&till.endpoint),
        "the endpoint is in the log: {log}"
    );
}

/// Unregistering takes the registration back once: then it is unknown,
/// and its till isn't dinged.
#[tokio::test]
async fn unregistering_twice_is_no_content_then_unknown_over_http() {
    let square = Stub::square().await;
    let push = Stub::push_service().await;
    let bell = TestBell::start(&square.url(), &[]).await;
    let id = bell
        .registered(TOKEN, LOCATION, &Till::at(&push, "front"))
        .await;
    let first = bell.call(unregistration(&id)).await;
    assert_eq!(first.status, StatusCode::NO_CONTENT);
    assert!(first.body.is_empty());
    let second = bell.call(unregistration(&id)).await;
    assert_eq!(second.status, StatusCode::NOT_FOUND);
    assert_eq!(second.problem(), "unknown-registration");
    bell.webhook(&payment_webhook(
        "payment.created",
        "COMPLETED",
        MERCHANT,
        LOCATION,
    ))
    .await;
    assert!(push.drain().await.is_empty());
}

/// A location takes so many tills; one more is refused until one goes.
#[tokio::test]
async fn a_full_location_refuses_another_till_over_http() {
    let square = Stub::square().await;
    let push = Stub::push_service().await;
    let bell = TestBell::start(&square.url(), &[("bell.registrations_per_minute", "100")]).await;
    let mut first = None;
    for n in 0..32 {
        let id = bell
            .registered(TOKEN, LOCATION, &Till::at(&push, &n.to_string()))
            .await;
        first.get_or_insert(id);
    }
    let answered = bell.register(TOKEN, LOCATION, &Till::at(&push, "33")).await;
    assert_eq!(answered.status, StatusCode::CONFLICT);
    assert_eq!(answered.problem(), "too-many-registrations");
    bell.call(unregistration(&first.unwrap())).await;
    bell.registered(TOKEN, LOCATION, &Till::at(&push, "33"))
        .await;
}

/// The sandbox's Bell asks the Square `bell.square.api_url` names, as it
/// would the sandbox's own.
#[tokio::test]
async fn a_sandbox_bell_asks_the_square_it_is_pointed_at_over_http() {
    let square = Stub::square().await;
    let push = Stub::push_service().await;
    let bell = TestBell::start(
        &square.url(),
        &[
            ("bell.square.environment", "sandbox"),
            ("bell.square.application_id", "sandbox-sq0idb-MonokularTest"),
        ],
    )
    .await;
    let answered = bell
        .register(TOKEN, LOCATION, &Till::at(&push, "front"))
        .await;
    assert_eq!(answered.status, StatusCode::CREATED, "{}", answered.text());
    assert_eq!(
        answered.json()["square_application_id"],
        "sandbox-sq0idb-MonokularTest"
    );
    assert_eq!(
        square.next().await.path,
        format!("/v2/locations/{LOCATION}")
    );
}
