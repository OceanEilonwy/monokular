//! The receipt as one URI query parameter, `orf=<digits>`.
//!
//! This knows nothing about the URI it goes in. The app adds the parameter
//! to its own payment URI, and a reader that doesn't know it ignores it.

use crate::{DecodeError, EncodeError, Receipt};

/// The query parameter's name.
pub const PARAM: &str = "orf";

/// `orf=<digits>`, to append to a URI's query with `?` or `&`. Digits need
/// no percent-encoding.
pub fn to_param(receipt: &Receipt) -> Result<String, EncodeError> {
    Ok(format!("{PARAM}={}", crate::encode_text(receipt)?))
}

/// The value of the first `orf` parameter in `uri`'s query, if there is one.
/// Only the query is read: the part after the first `?` and before any `#`.
pub fn find(uri: &str) -> Option<&str> {
    let query = uri.split_once('?')?.1;
    let query = query.split_once('#').map_or(query, |(q, _)| q);
    query.split('&').find_map(|pair| match pair.split_once('=') {
        Some((PARAM, value)) => Some(value),
        _ => None,
    })
}

/// The receipt in `uri`'s `orf` parameter: `None` when there isn't one, an
/// error when there is one that doesn't decode.
pub fn from_uri(uri: &str) -> Result<Option<Receipt>, DecodeError> {
    find(uri).map(crate::decode_text).transpose()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn finds_the_parameter_in_any_uri() {
        assert_eq!(find("pay:abc?amount=1&orf=0123&x=y"), Some("0123"));
        assert_eq!(find("https://example.com/r?orf=42#orf=9"), Some("42"));
        assert_eq!(find("pay:abc?xorf=1&orf_=2"), None);
        assert_eq!(find("pay:abc#orf=1"), None);
        assert_eq!(find("pay:abc"), None);
    }

    #[test]
    fn a_damaged_parameter_is_an_error_and_a_missing_one_is_none() {
        assert!(from_uri("pay:abc?amount=1").unwrap().is_none());
        assert!(from_uri("pay:abc?orf=12x").is_err());
    }
}
