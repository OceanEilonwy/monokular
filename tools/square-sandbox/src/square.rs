//! The few Square API calls the page makes, with the sandbox access token.

use serde::de::DeserializeOwned;
use serde::{Deserialize, Serialize};
use serde_json::json;
use std::sync::RwLock;

/// The API version every request asks for, as the till's (`SquareConfig.API_VERSION`).
const VERSION: &str = "2026-09-16";

/// Square's sandbox, or a stand-in for tests.
pub struct Square {
    http: reqwest::Client,
    base: String,
    /// The sandbox access token, in memory only: from the environment or the page.
    token: RwLock<Option<String>>,
}

#[derive(Clone, Debug, Deserialize)]
pub struct Location {
    pub id: String,
    #[serde(default)]
    pub name: String,
    pub currency: String,
    #[serde(default)]
    pub status: String,
}

#[derive(Clone, Debug, Deserialize, Serialize, PartialEq)]
pub struct Money {
    pub amount: i64,
    pub currency: String,
}

#[derive(Clone, Debug, Deserialize)]
pub struct External {
    pub source: String,
}

#[derive(Clone, Debug, Deserialize)]
pub struct Payment {
    pub id: String,
    pub status: String,
    pub created_at: String,
    pub amount_money: Money,
    pub total_money: Option<Money>,
    pub source_type: Option<String>,
    pub external_details: Option<External>,
    pub order_id: Option<String>,
    pub refunded_money: Option<Money>,
}

impl Payment {
    pub fn total(&self) -> &Money {
        self.total_money.as_ref().unwrap_or(&self.amount_money)
    }

    /// What it was tendered as: the Other tender's name, or Square's source type.
    pub fn tender(&self) -> String {
        match (&self.external_details, &self.source_type) {
            (Some(external), _) => format!("Other: {}", external.source),
            (None, Some(source)) => source.to_lowercase(),
            (None, None) => String::new(),
        }
    }
}

/// One line of a sale: a price in the currency's smallest unit.
#[derive(Clone, Debug, PartialEq)]
pub struct Item {
    pub name: String,
    pub quantity: u32,
    pub price: i64,
}

/// What Square answered when it refused, for the page.
#[derive(Debug)]
pub struct SquareError(pub String);

impl std::fmt::Display for SquareError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(&self.0)
    }
}

#[derive(Deserialize)]
struct Errors {
    #[serde(default)]
    errors: Vec<ErrorDetail>,
}

#[derive(Deserialize)]
struct ErrorDetail {
    code: Option<String>,
    detail: Option<String>,
}

impl Square {
    pub fn new(base: impl Into<String>, token: Option<String>) -> Self {
        Square {
            http: reqwest::Client::new(),
            base: base.into(),
            token: RwLock::new(token),
        }
    }

    pub fn has_token(&self) -> bool {
        self.token
            .read()
            .unwrap_or_else(|e| e.into_inner())
            .is_some()
    }

    /// Uses [token] from now on if Square takes it, else keeps the one before.
    pub async fn use_token(&self, token: String) -> Result<(), SquareError> {
        let before = self.set_token(Some(token));
        match self.locations().await {
            Ok(_) => Ok(()),
            Err(e) => {
                self.set_token(before);
                Err(e)
            }
        }
    }

    /// Replaces the token, returning the one before.
    pub fn set_token(&self, token: Option<String>) -> Option<String> {
        std::mem::replace(
            &mut *self.token.write().unwrap_or_else(|e| e.into_inner()),
            token,
        )
    }

    async fn send<T: DeserializeOwned>(
        &self,
        request: reqwest::RequestBuilder,
    ) -> Result<T, SquareError> {
        let token = self
            .token
            .read()
            .unwrap_or_else(|e| e.into_inner())
            .clone()
            .ok_or_else(|| SquareError("Enter the sandbox access token first.".into()))?;
        let response = request
            .bearer_auth(token)
            .header("Square-Version", VERSION)
            .send()
            .await
            .map_err(|e| SquareError(format!("Couldn't reach Square: {e}")))?;
        let status = response.status();
        let text = response
            .text()
            .await
            .map_err(|e| SquareError(format!("Square's answer was cut off: {e}")))?;
        if !status.is_success() {
            let said = serde_json::from_str::<Errors>(&text)
                .map(|e| {
                    e.errors
                        .into_iter()
                        .filter_map(|d| d.detail.or(d.code))
                        .collect::<Vec<_>>()
                        .join("; ")
                })
                .unwrap_or_default();
            return Err(SquareError(format!("Square said {status}: {said}")));
        }
        serde_json::from_str(&text)
            .map_err(|e| SquareError(format!("Square's answer wasn't understood: {e}")))
    }

    fn url(&self, path: &str) -> String {
        format!("{}{path}", self.base)
    }

    /// The test seller's active locations.
    pub async fn locations(&self) -> Result<Vec<Location>, SquareError> {
        #[derive(Deserialize)]
        struct Page {
            #[serde(default)]
            locations: Vec<Location>,
        }
        let page: Page = self.send(self.http.get(self.url("/v2/locations"))).await?;
        Ok(page
            .locations
            .into_iter()
            .filter(|l| l.status != "INACTIVE")
            .collect())
    }

    /// The location's latest payments, newest first.
    pub async fn payments(&self, location_id: &str) -> Result<Vec<Payment>, SquareError> {
        #[derive(Deserialize)]
        struct Page {
            #[serde(default)]
            payments: Vec<Payment>,
        }
        let request = self.http.get(self.url("/v2/payments")).query(&[
            ("location_id", location_id),
            ("sort_order", "DESC"),
            ("limit", "20"),
        ]);
        Ok(self.send::<Page>(request).await?.payments)
    }

    pub async fn payment(&self, id: &str) -> Result<Payment, SquareError> {
        #[derive(Deserialize)]
        struct PaymentResult {
            payment: Payment,
        }
        let url = self.url(&format!("/v2/payments/{id}"));
        Ok(self
            .send::<PaymentResult>(self.http.get(url))
            .await?
            .payment)
    }

    /// An order of [items], paid in full by a completed Other tender named
    /// [tender]: what a cashier's "Other: Monero" tap makes in Point of Sale,
    /// as the till reads it (`docs/decisions/android.md` 13).
    pub async fn sale(
        &self,
        location: &Location,
        items: &[Item],
        tender: &str,
    ) -> Result<Payment, SquareError> {
        #[derive(Deserialize)]
        struct OrderResult {
            order: Order,
        }
        #[derive(Deserialize)]
        struct Order {
            id: String,
            total_money: Money,
        }
        #[derive(Deserialize)]
        struct PaymentResult {
            payment: Payment,
        }
        let lines: Vec<_> = items
            .iter()
            .map(|i| {
                json!({
                    "name": i.name,
                    "quantity": i.quantity.to_string(),
                    "base_price_money": { "amount": i.price, "currency": location.currency },
                })
            })
            .collect();
        let order: OrderResult = self
            .send(self.http.post(self.url("/v2/orders")).json(&json!({
                "idempotency_key": uuid::Uuid::new_v4().to_string(),
                "order": { "location_id": location.id, "line_items": lines },
            })))
            .await?;
        let paid: PaymentResult = self
            .send(self.http.post(self.url("/v2/payments")).json(&json!({
                "idempotency_key": uuid::Uuid::new_v4().to_string(),
                "source_id": "EXTERNAL",
                "external_details": { "type": "OTHER", "source": tender },
                "amount_money": order.order.total_money,
                "order_id": order.order.id,
                "location_id": location.id,
                "autocomplete": true,
            })))
            .await?;
        Ok(paid.payment)
    }

    /// Refunds a payment in full, as the till does for a sale never paid.
    pub async fn refund(&self, payment: &Payment) -> Result<(), SquareError> {
        let _: serde_json::Value = self
            .send(self.http.post(self.url("/v2/refunds")).json(&json!({
                "idempotency_key": uuid::Uuid::new_v4().to_string(),
                "payment_id": payment.id,
                "amount_money": payment.total(),
                "reason": "Refunded from the sandbox page",
            })))
            .await?;
        Ok(())
    }
}
