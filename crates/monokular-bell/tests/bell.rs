//! The Bell called in-process, for what it answers without asking Square
//! or sending a ding: its info, the webhook signature, and the
//! registrations it refuses before any request.

mod support;

use axum::body::Body;
use axum::http::{Method, Request, StatusCode};
use serde_json::json;
use support::*;

/// The public key `/v1/info` gives is the one `MONOKULAR_BELL_VAPID_KEY`'s
/// private key makes, with the application and its environment.
#[tokio::test]
async fn info_shows_the_application_and_the_public_key_of_the_vapid_key() {
    let bell = TestBell::without_square(&[]).await;
    let answered = bell
        .call(Request::get("/v1/info").body(Body::empty()).unwrap())
        .await;
    assert_eq!(answered.status, StatusCode::OK);
    let mut private = [0u8; 32];
    hex::decode_to_slice(VAPID_HEX, &mut private).unwrap();
    let public = push::VapidKey::from_bytes(&private).unwrap().public();
    assert_eq!(
        answered.json(),
        json!({
            "square_application_id": APPLICATION_ID,
            "environment": "production",
            "vapid_public_key": public.to_base64url(),
            "version": env!("CARGO_PKG_VERSION"),
        })
    );
    let sandbox = TestBell::without_square(&[
        ("bell.square.environment", "sandbox"),
        ("bell.square.application_id", "sandbox-sq0idb-MonokularTest"),
    ])
    .await;
    let answered = sandbox
        .call(Request::get("/v1/info").body(Body::empty()).unwrap())
        .await;
    assert_eq!(answered.json()["environment"], "sandbox");
    assert_eq!(
        answered.json()["square_application_id"],
        "sandbox-sq0idb-MonokularTest"
    );
}

/// Square's signature over the webhook URL and the body is what lets a
/// webhook in; without it, or for another body or URL, it is refused.
#[tokio::test]
async fn a_webhook_is_taken_only_with_squares_signature_for_this_url() {
    let bell = TestBell::without_square(&[]).await;
    let body = payment_webhook("payment.updated", "APPROVED", MERCHANT, LOCATION);
    let signed = bell.webhook(&body).await;
    assert_eq!(signed.status, StatusCode::OK, "{}", signed.text());

    let refused = [
        webhook_request(&body, None),
        webhook_request(&body, Some("not base64!")),
        webhook_request(
            &body.replace(LOCATION, OTHER_LOCATION),
            Some(&sign(WEBHOOK_URL, &body)),
        ),
        webhook_request(&body, Some(&sign(&format!("{WEBHOOK_URL}/"), &body))),
        webhook_request(&body, Some(&sign(&format!("{WEBHOOK_URL}?a=1"), &body))),
        webhook_request(
            &body,
            Some(&sign("https://other.example.org/v1/square/webhook", &body)),
        ),
        webhook_request(
            &body,
            Some(&sign("http://bell.example.org/v1/square/webhook", &body)),
        ),
    ];
    for request in refused {
        let answered = bell.call(request).await;
        assert_eq!(
            answered.status,
            StatusCode::UNAUTHORIZED,
            "{}",
            answered.text()
        );
        assert_eq!(answered.problem(), "invalid-signature");
    }
}

/// The address is the same written with or without its trailing slash;
/// one under a path signs for that path.
#[tokio::test]
async fn the_webhook_url_follows_the_public_address_as_written() {
    let body = payment_webhook("payment.updated", "APPROVED", MERCHANT, LOCATION);
    let slash = TestBell::without_square(&[("bell.public_uri", "https://bell.example.org/")]).await;
    assert_eq!(slash.webhook(&body).await.status, StatusCode::OK);

    let under = TestBell::without_square(&[("bell.public_uri", "https://example.org/bell")]).await;
    assert_eq!(under.webhook(&body).await.status, StatusCode::UNAUTHORIZED);
    let answered = under
        .call(webhook_request(
            &body,
            Some(&sign("https://example.org/bell/v1/square/webhook", &body)),
        ))
        .await;
    assert_eq!(answered.status, StatusCode::OK, "{}", answered.text());
}

#[tokio::test]
async fn a_webhook_over_the_size_taken_is_refused_unread() {
    let bell = TestBell::without_square(&[]).await;
    let body = format!("{{\"pad\":\"{}\"}}", "x".repeat(256 * 1024));
    let answered = bell
        .call(webhook_request(&body, Some(&sign(WEBHOOK_URL, &body))))
        .await;
    assert_eq!(answered.status, StatusCode::PAYLOAD_TOO_LARGE);
    assert_eq!(answered.problem(), "payload-too-large");
}

/// A subscription the Bell would never send to is refused before Square
/// is asked: an onion address, plain `http` across a network, or a
/// private address while those aren't allowed.
#[tokio::test]
async fn a_subscription_the_bell_would_not_send_to_is_refused() {
    let bell = TestBell::without_square(&[("bell.allow_private_endpoints", "false")]).await;
    let till = Till {
        keys: push::encrypt::ReceiverKeys::generate(),
        endpoint: String::new(),
    };
    for endpoint in [
        "https://2gzyxa5ihm7nsggfxnu52rck2vv4rvmdlkiu3zzui5du4xyclen53wid.onion/push/1",
        "http://push.example.org/push/1",
        "ftp://push.example.org/push/1",
        "https://user:pass@push.example.org/push/1",
        "https://10.0.0.7/push/1",
        "http://127.0.0.1:8080/push/1",
        "https://[::1]/push/1",
    ] {
        let mut subscription = till.subscription();
        subscription["endpoint"] = json!(endpoint);
        let answered = bell
            .call(registration(TOKEN, LOCATION, &subscription))
            .await;
        assert_eq!(
            answered.status,
            StatusCode::BAD_REQUEST,
            "{endpoint}: {}",
            answered.text()
        );
        assert_eq!(answered.problem(), "invalid-request");
    }
}

#[tokio::test]
async fn a_registration_that_doesnt_read_is_refused() {
    let bell = TestBell::without_square(&[]).await;
    let till = Till {
        keys: push::encrypt::ReceiverKeys::generate(),
        endpoint: "https://push.example.org/push/1".to_owned(),
    };
    let mut bad_keys = till.subscription();
    bad_keys["keys"]["p256dh"] = json!("AAAA");
    let cases = [
        json!({"location_id": LOCATION, "subscription": till.subscription()}),
        json!({"square_access_token": TOKEN, "location_id": "a/b", "subscription": till.subscription()}),
        json!({"square_access_token": "with space", "location_id": LOCATION, "subscription": till.subscription()}),
        json!({"square_access_token": TOKEN, "location_id": LOCATION, "subscription": bad_keys}),
        json!({"square_access_token": TOKEN, "location_id": LOCATION, "subscription": till.subscription(), "extra": 1}),
    ];
    for case in cases {
        let answered = bell
            .call(json_request(Method::POST, "/v1/register", case.clone()))
            .await;
        assert_eq!(
            answered.status,
            StatusCode::BAD_REQUEST,
            "{case}: {}",
            answered.text()
        );
        assert_eq!(answered.problem(), "invalid-request");
    }
}

/// Registrations are counted per address before anything else, so even
/// ones that would be refused use up the minute; behind a trusted proxy
/// the address is the one it forwarded.
#[tokio::test]
async fn registrations_are_limited_per_address() {
    let bell = TestBell::without_square(&[("bell.registrations_per_minute", "2")]).await;
    let attempt = || json_request(Method::POST, "/v1/register", json!({}));
    for _ in 0..2 {
        let answered = bell.call_from("192.0.2.7:1000", attempt()).await;
        assert_eq!(answered.status, StatusCode::BAD_REQUEST);
    }
    let limited = bell.call_from("192.0.2.7:1001", attempt()).await;
    assert_eq!(limited.status, StatusCode::TOO_MANY_REQUESTS);
    assert_eq!(limited.problem(), "rate-limited");
    let retry_after: u64 = limited.headers["retry-after"]
        .to_str()
        .unwrap()
        .parse()
        .unwrap();
    assert!((1..=60).contains(&retry_after), "{retry_after}");
    // Another address has its own minute.
    assert_eq!(
        bell.call_from("192.0.2.8:1000", attempt()).await.status,
        StatusCode::BAD_REQUEST
    );

    // Through the proxy on this machine (trusted by default), each client
    // is counted by its own address, and the proxy's isn't used up.
    let forwarded = |client: &str| {
        let mut request = attempt();
        request
            .headers_mut()
            .insert("x-forwarded-for", client.parse().unwrap());
        request
    };
    for _ in 0..2 {
        let answered = bell
            .call_from("127.0.0.1:1000", forwarded("198.51.100.1"))
            .await;
        assert_eq!(answered.status, StatusCode::BAD_REQUEST);
    }
    let limited = bell
        .call_from("127.0.0.1:1000", forwarded("198.51.100.1"))
        .await;
    assert_eq!(limited.status, StatusCode::TOO_MANY_REQUESTS);
    let other = bell
        .call_from("127.0.0.1:1000", forwarded("198.51.100.2"))
        .await;
    assert_eq!(other.status, StatusCode::BAD_REQUEST);
    // A client not behind a trusted proxy can't name another address.
    let spoofed = bell
        .call_from("192.0.2.7:1002", forwarded("198.51.100.9"))
        .await;
    assert_eq!(spoofed.status, StatusCode::TOO_MANY_REQUESTS);
}

#[tokio::test]
async fn unregistering_an_id_that_isnt_one_is_unknown() {
    let bell = TestBell::without_square(&[]).await;
    for id in ["br_0123456789abcdef0123456789abcdef", "nope", "br_XYZ"] {
        let answered = bell.call(unregistration(id)).await;
        assert_eq!(answered.status, StatusCode::NOT_FOUND, "{id}");
        assert_eq!(answered.problem(), "unknown-registration");
    }
}
