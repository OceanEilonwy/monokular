# receipt-qr

A prototype for putting a whole receipt inside the Monero payment QR code, so
a wallet can save it next to the transaction. It compares two encodings and
builds a page of real QR codes to test scanning on phones.

```sh
./fetch-corpus.sh        # once: product names to train on (corpus/, not committed)
cargo run --release      # encode, check round trips, print the table, write out/
python3 page.py out out/receipt-qr-test.html
```

## Encodings

**v1 (first prototype):** CBOR with every field, raw deflate with a
hand-written preset dictionary, optional 64-byte signature, a URI-safe
base-41 alphabet in QR alphanumeric mode.

**v2 (compact):** `src/v2.rs`.
- No signature in the code; the Monero transaction proves payment.
- Nothing derivable is sent: subtotal, tax amount, total, the XMR amount
  (already `tx_amount`), store id. Time is minutes since 2025.
- Numbers are bit-packed with Exp-Golomb codes; quantity 1 is one bit.
- Text (store, reference, item names) is compressed on its own with zstd in
  magicless mode and a dictionary trained on product names.
- The bytes are written as decimal digits for QR numeric mode
  (7 bytes to 17 digits).

## Corpus and licence

`fetch-corpus.sh` takes product names from Open Food Facts and Open Products
Facts, which are under the Open Database License (ODbL). A dictionary trained
on them may count as a derivative database, so before freezing one into a
spec, check the ODbL's attribution and share-alike terms, or train on data
under a more permissive licence.
