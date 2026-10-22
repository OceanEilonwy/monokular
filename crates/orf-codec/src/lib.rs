//! A compact encoding of [Open Receipt Format](https://github.com/openreceiptformat/orf-spec)
//! (ORF 0.1) receipts, small enough to carry a whole receipt inside a Monero
//! payment URI.
//!
//! ```
//! let receipt: orf_codec::Receipt = serde_json::from_str(r##"{
//!   "orf_version": "0.1.0",
//!   "receipt": { "receipt_id": "#1042", "issue_timestamp": "2026-10-19T09:15:30-07:00", "receipt_type": "sale" },
//!   "merchant": { "legal_name": "The Bean Room", "country_code": "US" },
//!   "line_items": [
//!     { "description": "Flat white, oat milk", "quantity": 2, "unit_price": 4.5, "line_total": 9.0 }
//!   ],
//!   "total": 9.0,
//!   "currency": "USD"
//! }"##).unwrap();
//!
//! let text = orf_codec::encode_text(&receipt).unwrap();     // digits, for the URI
//! let uri = orf_codec::uri::build("8Bx…", 52_113_000_000, Some(&text));
//! let parsed = orf_codec::uri::parse(&uri).unwrap();
//! assert_eq!(orf_codec::decode_text(parsed.receipt.as_deref().unwrap()).unwrap(), receipt);
//! ```
//!
//! - [`encode`] / [`decode`]: a receipt to and from compact bytes ([`codec`]
//!   describes the layout).
//! - [`encode_text`] / [`decode_text`]: the same as decimal digits, which go
//!   into a URI unescaped and pack densely into a QR code's numeric mode.
//!   Making the QR code is up to the app.
//! - [`uri`]: Monero payment URIs with the receipt in an `orf` parameter.
//!
//! Every ORF 0.1 document round-trips exactly, except what it can't
//! represent and refuses with an [`EncodeError`]: unknown fields, fractional
//! seconds, line breaks inside text, and amounts finer than the currency's
//! minor unit.

mod bits;
pub mod codec;
mod digits;
mod model;
pub mod uri;

pub use codec::{Codec, DEFAULT_DICTIONARY, FORMAT};
pub use digits::{from_digits, to_digits};
pub use model::*;

#[derive(Debug, thiserror::Error)]
pub enum EncodeError {
    #[error("{field}: not supported ({reason})")]
    Unsupported { field: String, reason: &'static str },
    #[error("{field}: {value} isn't a whole number of the currency's smallest unit")]
    Amount { field: String, value: f64 },
    #[error("compressing the text: {0}")]
    Compress(std::io::Error),
}

#[derive(Debug, thiserror::Error)]
pub enum DecodeError {
    #[error("the receipt ends early")]
    Truncated,
    #[error("unknown receipt format {0}; a newer version of this library may read it")]
    UnknownFormat(u8),
    #[error("unknown dictionary {0}; a newer version of this library may read it")]
    UnknownDictionary(u8),
    #[error("the receipt is damaged: {0}")]
    Corrupt(&'static str),
    #[error("the receipt text isn't decimal digits in valid groups")]
    Digits,
    #[error("decompressing the text: {0}")]
    Decompress(std::io::Error),
    #[error("the extensions aren't valid JSON: {0}")]
    Json(serde_json::Error),
}

/// A receipt as compact bytes, with the default dictionary.
pub fn encode(receipt: &Receipt) -> Result<Vec<u8>, EncodeError> {
    Codec::default().encode(receipt)
}

/// A receipt back from compact bytes, with the default dictionary.
pub fn decode(bytes: &[u8]) -> Result<Receipt, DecodeError> {
    Codec::default().decode(bytes)
}

/// A receipt as decimal digits: [`encode`], then [`to_digits`].
pub fn encode_text(receipt: &Receipt) -> Result<String, EncodeError> {
    encode(receipt).map(|b| to_digits(&b))
}

/// A receipt back from decimal digits: [`from_digits`], then [`decode`].
pub fn decode_text(text: &str) -> Result<Receipt, DecodeError> {
    decode(&from_digits(text)?)
}
