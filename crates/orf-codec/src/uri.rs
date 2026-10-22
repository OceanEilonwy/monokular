//! Monero payment URIs carrying a receipt:
//! `monero:<address>?tx_amount=<XMR>&orf=<digits>`.
//!
//! The receipt is one extra query parameter. Wallets that don't know it
//! ignore it and still pay.

/// The query parameter that holds the receipt's digits.
pub const RECEIPT_PARAM: &str = "orf";

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PaymentUri {
    pub address: String,
    /// `tx_amount` as written, in XMR (e.g. "0.0803").
    pub tx_amount: Option<String>,
    /// The receipt's digits, for [`crate::decode_text`].
    pub receipt: Option<String>,
    /// Every other parameter, in order, still percent-encoded.
    pub other: Vec<(String, String)>,
}

#[derive(Debug, thiserror::Error, PartialEq, Eq)]
pub enum UriError {
    #[error("not a monero: URI")]
    Scheme,
    #[error("the URI has no address")]
    Address,
}

/// A payment URI for `piconero` (1 XMR = 10^12), with the receipt's digits
/// when there is one.
pub fn build(address: &str, piconero: u64, receipt: Option<&str>) -> String {
    let whole = piconero / 1_000_000_000_000;
    let frac = format!("{:012}", piconero % 1_000_000_000_000);
    let frac = frac.trim_end_matches('0');
    let amount = if frac.is_empty() { whole.to_string() } else { format!("{whole}.{frac}") };
    let mut uri = format!("monero:{address}?tx_amount={amount}");
    if let Some(r) = receipt {
        uri.push_str(&format!("&{RECEIPT_PARAM}={r}"));
    }
    uri
}

pub fn parse(uri: &str) -> Result<PaymentUri, UriError> {
    let rest = uri
        .trim()
        .strip_prefix("monero:")
        .or_else(|| uri.trim().strip_prefix("MONERO:"))
        .ok_or(UriError::Scheme)?;
    let (address, query) = rest.split_once('?').unwrap_or((rest, ""));
    if address.is_empty() {
        return Err(UriError::Address);
    }
    let mut out = PaymentUri { address: address.to_string(), tx_amount: None, receipt: None, other: Vec::new() };
    for pair in query.split('&').filter(|p| !p.is_empty()) {
        let (k, v) = pair.split_once('=').unwrap_or((pair, ""));
        match k {
            "tx_amount" => out.tx_amount = Some(v.to_string()),
            RECEIPT_PARAM => out.receipt = Some(v.to_string()),
            _ => out.other.push((k.to_string(), v.to_string())),
        }
    }
    Ok(out)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn build_and_parse_agree() {
        let uri = build("8Bx", 80_370_584_829, Some("0123"));
        assert_eq!(uri, "monero:8Bx?tx_amount=0.080370584829&orf=0123");
        let p = parse(&uri).unwrap();
        assert_eq!(p.address, "8Bx");
        assert_eq!(p.tx_amount.as_deref(), Some("0.080370584829"));
        assert_eq!(p.receipt.as_deref(), Some("0123"));
        assert_eq!(build("8Bx", 2_000_000_000_000, None), "monero:8Bx?tx_amount=2");
    }

    #[test]
    fn other_parameters_are_kept_and_bad_uris_refused() {
        let p = parse("monero:8Bx?recipient_name=Bean%20Room&tx_amount=1").unwrap();
        assert_eq!(p.other, vec![("recipient_name".into(), "Bean%20Room".into())]);
        assert_eq!(parse("bitcoin:abc"), Err(UriError::Scheme));
        assert_eq!(parse("monero:?tx_amount=1"), Err(UriError::Address));
    }
}
