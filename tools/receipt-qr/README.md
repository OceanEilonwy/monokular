# receipt-qr

A prototype for putting a whole receipt inside the Monero payment QR code, so
a wallet can save it next to the transaction. It encodes with
[`orf-codec`](../../crates/orf-codec), compares that library's shipped
dictionary with ones trained on other mixes of the corpus, and builds a page
of real QR codes to test scanning on phones.

```sh
./fetch-corpus.sh                      # once: download sources, build corpus/train and corpus/test (not committed)
cargo run --release                    # score dictionaries on held-out test receipts, write out/
python3 -I page.py out out/receipt-qr-test.html
cargo run --release -- decode '<URI>'  # decode a scanned payment URI and check it against the known receipts
```

`src/v1.rs` is the first prototype's encoding (CBOR, deflate with a
hand-written dictionary, a base-41 alphabet), kept as the "before" on the page.

## Corpus and licences

`fetch-corpus.sh` downloads the sources and `build_corpus.py` splits every
real source by a hash of the name: 85% for training, 15% held out to build
test receipts from, so no test name is trained on.

| Source | Used for | Licence |
| --- | --- | --- |
| NYPL "What's on the Menu?" dishes | Training; restaurant test receipts | Public domain |
| Wikidata labels (dishes, drinks, clothing, tools, fasteners, materials, medicines) | Training | CC0 |
| Generated till-style names (`build_corpus.py`) | Training | Ours |
| Open Food Facts, Open Products Facts | Training; grocery test receipts | ODbL (share-alike) |
| Amazon Reviews 2023 item titles (McAuley Lab) | Training; hardware, clothing and pharmacy test receipts | Research dataset: confirm terms before shipping a dictionary trained on it |

A dictionary is frozen into the format and shipped in every wallet, so its
training data must allow that. Check the ODbL's share-alike terms and the
Amazon dataset's terms before choosing one of those mixes for a spec.

## Chosen dictionary

`crates/orf-codec/dict/receipts-open-64k.zdict` (64 KB, checksum in
`dict/SHA256SUMS` beside it) is the dictionary chosen for the format. It is trained only on the open mix: NYPL
menus (public domain), Wikidata labels (CC0) and the generated names (ours),
so wallets can ship it without inheriting share-alike or research-only terms.

On the held-out test receipts its median is QR version 11 to 13 per category
(payment-only codes are version 8), about half a version behind the best mix,
which also trains on Amazon titles. Every retraining downloads live data, so
this committed file, not a rerun, is the reference: a new dictionary is a new
format version.
