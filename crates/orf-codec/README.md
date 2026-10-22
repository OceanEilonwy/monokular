# orf-codec

A compact encoding of [Open Receipt Format](https://github.com/openreceiptformat/orf-spec)
(ORF 0.1) receipts, small enough to carry a whole receipt inside a Monero
payment URI, so a wallet can keep it with the payment.

```rust
let receipt: orf_codec::Receipt = serde_json::from_str(orf_json)?;
let digits = orf_codec::encode_text(&receipt)?;                  // for the URI
let uri = orf_codec::uri::build(address, piconero, Some(&digits));
// … the app turns `uri` into a QR code; a wallet scans it …
let back = orf_codec::decode_text(&orf_codec::uri::parse(&uri)?.receipt.unwrap())?;
assert_eq!(back, receipt);
```

The library knows nothing about QR codes. It returns bytes (`encode`) or
decimal digits (`encode_text`); the app that shows the payment makes the
code. Digits are used because they need no escaping in a URI and a QR code's
numeric mode packs them at 3.33 bits each.

## The URI

```text
monero:<address>?tx_amount=<XMR>&orf=<digits>
```

`orf` is one extra query parameter. Wallets that don't know it ignore it and
still pay.

## What round-trips

Every ORF 0.1 document decodes to exactly what was encoded, checked against
ORF's own examples and its JSON Schema. Encoding refuses, with an error,
what it can't represent rather than changing it:

- fields the ORF 0.1 schema doesn't define (free-form data goes in `extensions`, which is carried whole);
- timestamps with fractional seconds, or not written as `YYYY-MM-DDTHH:MM:SS` plus `Z` or `±HH:MM`;
- line breaks inside text;
- amounts finer than the currency's minor unit, quantities finer than 0.001, tax rates finer than 0.0001%.

## Format 1

```text
payload = header | LEB128(len(numbers)) | numbers | zstd(text, dictionary)
header  = format (high 4 bits, 1) | dictionary id (low 4 bits, 0)
text    = every string, one per line, starting with the merchant's name and the receipt id
digits  = payload in groups of 7 bytes, each written as 17 decimal digits
          (a shorter last group as 3, 5, 8, 10, 13 or 15 digits)
```

- **Numbers** (`src/codec.rs`): every number, enum and presence flag,
  bit-packed with Exp-Golomb codes. Amounts are integers in the currency's
  minor unit; quantities in thousandths; tax rates in millionths; the time is
  minutes since 2025-01-01 UTC, with seconds only when non-zero, and the
  offset in minutes. USD, EUR and GBP take two bits; other currencies their
  letters.
- **Derived values aren't sent:** a line total of quantity × unit price, a tax
  amount of base × rate (both rounded half away from zero), a total of line
  totals plus tax plus tip, line item ids "1", "2", … in order. A flag records
  any receipt that says otherwise, with the actual value.
- **Text** is compressed with zstd in magicless mode (no magic number,
  checksum, content size or dictionary id) and the dictionary named in the
  header.

## Dictionary 0

`dict/receipts-open-64k.zdict` (64 KB, SHA-256 in `dict/SHA256SUMS`),
trained by `tools/receipt-qr` on public domain restaurant menus (NYPL "What's
on the Menu?"), CC0 labels (Wikidata) and generated item names. Training
data with share-alike or research-only terms was left out so any wallet can
ship it. A dictionary is part of the format: changing it means a new
dictionary id.

## Size

On 205 test receipts of 1 to 25 items, the median QR code with the receipt
is version 12 to 13 (error correction M), against version 8 for a payment
alone. `tools/receipt-qr` measures this and makes a page of real codes.
