//! The ding: an empty-of-meaning Web Push that tells a till to ask Square
//! for new payments now (`docs/design.md` §3). It carries no sale data, so
//! a lost, late, repeated or forged one costs the till one request to
//! Square at most; the till's timer is the backstop.
//!
//! Every ding is `{"wake":"square"}` encrypted to the till's subscription
//! (RFC 8291), signed with the Bell's VAPID key (RFC 8292), `Urgency:
//! high`, `TTL: 60` (a ding a minute late is worth nothing) and `Topic:
//! wake`, so a push service holding several for an offline till hands
//! over one. It is sent once: there is no queue and no retry.

use std::sync::Arc;

use push::{Message, Options, SendError, Sender, Topic, Ttl, Urgency};
use tokio::sync::watch;

use crate::outbound::{self, Reach};
use crate::registrations::{Failed, Registration, RegistrationStore};
use crate::square::{LocationId, MerchantId};

/// What a ding says, before it is encrypted.
pub const WAKE: &[u8] = br#"{"wake":"square"}"#;

/// A ding's time to live.
pub const TTL: Ttl = Ttl::seconds(60);

/// A ding's topic.
pub const TOPIC: &str = "wake";

/// Sends dings to registrations, and keeps their failures.
pub struct Dings {
    sender: Sender,
    store: RegistrationStore,
    reach: Reach,
    failure_limit: u32,
    options: Options,
    /// How many rings are running, for [`Dings::idle`].
    running: watch::Sender<usize>,
}

impl Dings {
    pub fn new(
        sender: Sender,
        store: RegistrationStore,
        reach: Reach,
        failure_limit: u32,
    ) -> Result<Self, push::message::TopicError> {
        Ok(Dings {
            sender,
            store,
            reach,
            failure_limit,
            options: Options {
                urgency: Urgency::High,
                ttl: TTL,
                topic: Some(Topic::parse(TOPIC)?),
            },
            running: watch::Sender::new(0),
        })
    }

    /// Dings every till registered for `merchant_id`'s `location_id`, on a
    /// task of its own: the webhook that asked is answered at once.
    pub fn ring(self: &Arc<Self>, merchant_id: MerchantId, location_id: LocationId) {
        let dings = Arc::clone(self);
        let running = Running::start(&self.running);
        tokio::spawn(async move {
            let _running = running;
            dings.ring_now(&merchant_id, &location_id).await;
        });
    }

    /// Waits until no ring is running: what shutdown waits for, so a ding
    /// started is sent.
    pub async fn idle(&self) {
        let mut running = self.running.subscribe();
        // The sender lives as long as `self`.
        let _ = running.wait_for(|count| *count == 0).await;
    }

    async fn ring_now(self: &Arc<Self>, merchant_id: &MerchantId, location_id: &LocationId) {
        let registrations = match self.store.at_location(merchant_id, location_id).await {
            Ok(registrations) => registrations,
            Err(error) => {
                tracing::error!(error = %error, "a payment's tills couldn't be read: no dings");
                return;
            }
        };
        let mut sending = tokio::task::JoinSet::new();
        for registration in registrations {
            let dings = Arc::clone(self);
            sending.spawn(async move { dings.ding(registration).await });
        }
        let rung = sending.len();
        while sending.join_next().await.is_some() {}
        tracing::debug!(rung, "dings sent");
    }

    /// One ding to `registration`, and what its answer means for it.
    async fn ding(&self, registration: Registration) {
        let now = service_runtime::time::now_unix();
        let endpoint = &registration.subscription.endpoint;
        let outcome = match outbound::check_host(endpoint.as_url(), self.reach) {
            Ok(()) => match push::encrypt(&registration.subscription.keys, WAKE) {
                Ok(encrypted) => {
                    self.sender
                        .send(endpoint, &Message::Encrypted(encrypted), &self.options, now)
                        .await
                }
                Err(error) => {
                    tracing::error!(registration = %registration.id, error = %error, "a ding couldn't be encrypted");
                    return;
                }
            },
            Err(error) => {
                tracing::warn!(registration = %registration.id, host = endpoint.host(), error = %error, "a ding wasn't sent");
                self.failed(&registration).await;
                return;
            }
        };
        let (id, host) = (&registration.id, endpoint.host());
        match outcome {
            Ok(_) => {
                if let Err(error) = self.store.delivered(id, now).await {
                    tracing::error!(registration = %id, error = %error, "a delivered ding couldn't be recorded");
                }
            }
            Err(SendError::Gone) => match self.store.delete(id).await {
                Ok(_) => {
                    tracing::info!(registration = %id, host, "the push service says the subscription is gone: registration deleted")
                }
                Err(error) => {
                    tracing::error!(registration = %id, error = %error, "a gone registration couldn't be deleted")
                }
            },
            // The push service is limiting the Bell, not refusing the
            // till: not the subscription's failure.
            Err(SendError::RateLimited { retry_after }) => {
                tracing::warn!(registration = %id, host, ?retry_after, "the push service is limiting the Bell: a ding was dropped");
            }
            Err(error) => {
                tracing::warn!(registration = %id, host, error = %error, "a ding wasn't taken");
                self.failed(&registration).await;
            }
        }
    }

    async fn failed(&self, registration: &Registration) {
        let id = &registration.id;
        match self.store.failed(id, self.failure_limit).await {
            Ok(Failed::Counted(_) | Failed::Gone) => {}
            Ok(Failed::Deleted) => tracing::info!(
                registration = %id,
                host = registration.subscription.endpoint.host(),
                limit = self.failure_limit,
                "dings failed too often in a row: registration deleted"
            ),
            Err(error) => {
                tracing::error!(registration = %id, error = %error, "a failed ding couldn't be recorded");
            }
        }
    }
}

/// One running ring, counted in [`Dings::idle`]'s count until dropped,
/// however its task ends.
struct Running(watch::Sender<usize>);

impl Running {
    fn start(running: &watch::Sender<usize>) -> Self {
        running.send_modify(|count| *count += 1);
        Running(running.clone())
    }
}

impl Drop for Running {
    fn drop(&mut self) {
        self.0.send_modify(|count| *count -= 1);
    }
}
