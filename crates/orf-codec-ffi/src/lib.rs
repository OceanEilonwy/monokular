//! [`orf_codec`] for the Kotlin and Swift apps, through UniFFI. The apps
//! build an Open Receipt Format document as JSON and hand it here with the
//! payment URI they were given; nothing else crosses the boundary, so the
//! apps never re-implement the format or its dictionary.

uniffi::setup_scaffolding!();

/// Why a receipt couldn't be added or read. (Each variant's field is
/// `reason`, not `message`, which Kotlin's `Throwable` already has.)
#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum OrfError {
    /// The JSON isn't an ORF 0.1 receipt.
    #[error("not an ORF 0.1 receipt: {reason}")]
    Invalid { reason: String },
    /// A receipt the format can't carry exactly (`orf_codec::EncodeError`),
    /// or a URI that already has one.
    #[error("{reason}")]
    Unsupported { reason: String },
    /// A receipt in a URI that doesn't decode.
    #[error("{reason}")]
    Damaged { reason: String },
}

fn parse(receipt_json: &str) -> Result<orf_codec::Receipt, OrfError> {
    serde_json::from_str(receipt_json).map_err(|e| OrfError::Invalid {
        reason: e.to_string(),
    })
}

/// `uri` with the receipt added as its `orf` query parameter
/// (`orf_codec::query::append`).
#[uniffi::export]
pub fn append_receipt(uri: String, receipt_json: String) -> Result<String, OrfError> {
    let receipt = parse(&receipt_json)?;
    orf_codec::query::append(&uri, &receipt).map_err(|e| OrfError::Unsupported {
        reason: e.to_string(),
    })
}

/// The receipt in `uri`'s `orf` parameter, as JSON, or `None` when it has
/// none.
#[uniffi::export]
pub fn receipt_from_uri(uri: String) -> Result<Option<String>, OrfError> {
    let receipt = orf_codec::query::from_uri(&uri).map_err(|e| OrfError::Damaged {
        reason: e.to_string(),
    })?;
    Ok(receipt.map(|r| serde_json::to_string(&r).expect("a receipt serialises")))
}

/// The query parameter's name, `orf`.
#[uniffi::export]
pub fn receipt_param() -> String {
    orf_codec::query::PARAM.to_owned()
}

#[cfg(test)]
mod tests {
    use super::*;

    const RECEIPT: &str = r#"{
      "orf_version": "0.1.0",
      "receipt": { "receipt_id": "7", "issue_timestamp": "2026-10-22T12:00:00Z", "receipt_type": "sale" },
      "merchant": { "legal_name": "Corner Shop", "country_code": "US" },
      "line_items": [{ "description": "Tea", "quantity": 1, "unit_price": 2.5, "line_total": 2.5 }],
      "total": 2.5,
      "currency": "USD"
    }"#;

    #[test]
    fn a_receipt_goes_in_and_comes_back_out() {
        let uri = append_receipt("monero:8Bx?tx_amount=0.1".into(), RECEIPT.into()).unwrap();
        assert!(uri.starts_with("monero:8Bx?tx_amount=0.1&orf="), "{uri}");
        let back = receipt_from_uri(uri).unwrap().unwrap();
        let back: serde_json::Value = serde_json::from_str(&back).unwrap();
        assert_eq!(back["merchant"]["legal_name"], "Corner Shop");
        assert_eq!(receipt_from_uri("monero:8Bx".into()).unwrap(), None);
    }

    #[test]
    fn bad_input_is_an_error_of_its_kind() {
        assert!(matches!(
            append_receipt("monero:8Bx".into(), "{}".into()),
            Err(OrfError::Invalid { .. })
        ));
        assert!(matches!(
            append_receipt("monero:8Bx?orf=1".into(), RECEIPT.into()),
            Err(OrfError::Unsupported { .. })
        ));
        assert!(matches!(
            receipt_from_uri("monero:8Bx?orf=12x".into()),
            Err(OrfError::Damaged { .. })
        ));
    }
}
