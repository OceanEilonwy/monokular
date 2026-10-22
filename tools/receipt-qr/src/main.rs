//! Prototype: how big does a Monero payment QR get when it carries the whole
//! receipt? Compares the first prototype's encoding (`v1`) with the compact
//! one (`v2`): no signature, derived fields dropped, bit-packed numbers,
//! zstd with a dictionary trained on product names, QR numeric mode.
//!
//! Run `./fetch-corpus.sh` once, then `cargo run --release`. Writes
//! `out/report.json`, `out/receipts.json` and one SVG per QR code.

mod receipts;
mod v1;
mod v2;

use std::fs;

use qrcode::bits::Bits;
use qrcode::{EcLevel, QrCode, Version};
use rand::rngs::StdRng;
use rand::seq::SliceRandom;
use rand::{Rng, SeedableRng};
use serde_json::{json, Map, Value};

use receipts::Receipt;

const DICT_SIZES: [usize; 4] = [4 << 10, 16 << 10, 32 << 10, 64 << 10];
const TRAINING_SAMPLES: usize = 40_000;

enum Segment<'a> {
    Alphanumeric(&'a str),
    Numeric(&'a str),
}

fn main() {
    let names = fs::read_to_string("corpus/names.txt")
        .expect("corpus/names.txt is missing: run ./fetch-corpus.sh first");
    let names: Vec<&str> = names.lines().collect();
    let samples = training_samples(&names);
    fs::create_dir_all("out").unwrap();

    let dicts: Vec<(usize, Vec<u8>)> = DICT_SIZES
        .iter()
        .map(|&size| {
            let refs: Vec<&[u8]> = samples.iter().map(|s| s.as_bytes()).collect();
            let dict = zstd::dict::from_samples(&refs, size).expect("dictionary training");
            fs::write(format!("out/receipts-{}k.zdict", size >> 10), &dict).unwrap();
            (size, dict)
        })
        .collect();

    let receipts = receipts::samples();
    let address = fake_subaddress();

    // Pick the dictionary size with the smallest total over all receipts.
    let (best_size, best_dict) = dicts
        .iter()
        .min_by_key(|(_, d)| receipts.iter().map(|(_, r)| v2::encode(r, d).payload.len()).sum::<usize>())
        .unwrap();

    let baseline_uri = format!("monero:{address}?tx_amount={}", xmr(receipts[0].1.piconero()));
    let baseline = QrCode::with_error_correction_level(baseline_uri.as_bytes(), EcLevel::M).unwrap();
    write_svg("out/baseline.svg", &baseline);

    let mut rows = Vec::new();
    for (slug, r) in &receipts {
        let prefix = format!("monero:{address}?tx_amount={}&r=", xmr(r.piconero()));

        // Before: the first prototype.
        let mut before = Map::new();
        for signed in [false, true] {
            let text = v1::encode(r, signed);
            let name = format!("{slug}-v1{}", if signed { "-signed" } else { "" });
            before.insert(
                if signed { "signed" } else { "unsigned" }.into(),
                qr_row(&name, &prefix, Segment::Alphanumeric(&text)),
            );
        }

        // After: the compact encoding, with a round trip check.
        let enc = v2::encode(r, best_dict);
        let digits = v2::to_digits(&enc.payload);
        assert_eq!(v2::from_digits(&digits), enc.payload, "{slug}: digits round trip");
        assert_eq!(&v2::decode(&enc.payload, best_dict), r, "{slug}: receipt round trip");
        let mut after = qr_row(&format!("{slug}-v2"), &prefix, Segment::Numeric(&digits));
        let a = after.as_object_mut().unwrap();
        a.insert("payload_bytes".into(), json!(enc.payload.len()));
        a.insert("numbers_bytes".into(), json!(enc.bits_bytes));
        a.insert("text_bytes".into(), json!(enc.text_bytes));
        a.insert("text_compressed_bytes".into(), json!(enc.text_compressed));

        // The text stream under each compressor, to show what the trained dictionary buys.
        let text = v2_text(r);
        let mut text_sizes = Map::new();
        text_sizes.insert("none".into(), json!(text.len()));
        text_sizes.insert("zstd, no dictionary".into(), json!(v2::zstd_compress(text.as_bytes(), &[]).len()));
        text_sizes.insert(
            "zstd, hand-written dictionary".into(),
            json!(v2::zstd_compress(text.as_bytes(), v1::HAND_DICT.as_bytes()).len()),
        );
        for (size, dict) in &dicts {
            text_sizes.insert(
                format!("zstd, trained {}k", size >> 10),
                json!(v2::zstd_compress(text.as_bytes(), dict).len()),
            );
        }

        rows.push(json!({
            "slug": slug, "store": r.store, "items": r.items.len(),
            "before": before, "after": after, "text_compressors": text_sizes,
        }));
    }

    let receipts_json: Vec<Value> = receipts.iter().map(|(slug, r)| receipt_json(slug, r)).collect();
    fs::write("out/receipts.json", serde_json::to_string_pretty(&receipts_json).unwrap()).unwrap();
    let report = json!({
        "baseline": {
            "uri_chars": baseline_uri.len(),
            "qr_version_m": version_num(baseline.version()),
            "modules": baseline.width(), "svg": "baseline.svg",
        },
        "dictionary": {
            "chosen_bytes": best_size, "training_names": names.len(),
            "training_samples": samples.len(),
            "sizes_bytes": dicts.iter().map(|(s, _)| *s).collect::<Vec<_>>(),
        },
        "receipts": rows,
    });
    fs::write("out/report.json", serde_json::to_string_pretty(&report).unwrap()).unwrap();
    print_table(&report);
}

/// Synthetic receipt texts in the same layout the encoder compresses: a store
/// line, a reference line, then 1 to 25 product names from the corpus.
fn training_samples(names: &[&str]) -> Vec<String> {
    let mut rng = StdRng::seed_from_u64(42);
    (0..TRAINING_SAMPLES)
        .map(|_| {
            let mut lines = vec![names.choose(&mut rng).unwrap().to_string(), format!("#{}", rng.gen_range(100..99_999))];
            let n = rng.gen_range(1..=25);
            lines.extend((0..n).map(|_| names.choose(&mut rng).unwrap().to_string()));
            lines.join("\n")
        })
        .collect()
}

fn v2_text(r: &Receipt) -> String {
    let mut parts = vec![r.store.clone(), r.sale_ref.clone()];
    parts.extend(r.items.iter().map(|i| i.name.clone()));
    parts.join("\n")
}

fn xmr(piconero: u64) -> String {
    let s = format!("{}.{:012}", piconero / 1_000_000_000_000, piconero % 1_000_000_000_000);
    s.trim_end_matches('0').trim_end_matches('.').to_string()
}

fn qr_row(name: &str, prefix: &str, value: Segment) -> Value {
    let (code, m) = smallest_qr(prefix, &value, EcLevel::M);
    let (_, l) = smallest_qr(prefix, &value, EcLevel::L);
    write_svg(&format!("out/{name}.svg"), &code);
    let len = match value {
        Segment::Alphanumeric(s) | Segment::Numeric(s) => s.len(),
    };
    json!({
        "value_chars": len, "uri_chars": prefix.len() + len,
        "qr_version_m": m, "qr_version_l": l, "modules": code.width(),
        "svg": format!("{name}.svg"),
    })
}

/// The URI as two QR segments: byte mode for `monero:<address>?…&r=`, then
/// the packed receipt in the mode that suits it.
fn smallest_qr(prefix: &str, value: &Segment, ec: EcLevel) -> (QrCode, i16) {
    for v in 1..=40 {
        let mut bits = Bits::new(Version::Normal(v));
        let fits = bits.push_byte_data(prefix.as_bytes()).is_ok()
            && match value {
                Segment::Alphanumeric(s) => bits.push_alphanumeric_data(s.as_bytes()).is_ok(),
                Segment::Numeric(s) => bits.push_numeric_data(s.as_bytes()).is_ok(),
            }
            && bits.push_terminator(ec).is_ok();
        if fits {
            if let Ok(code) = QrCode::with_bits(bits, ec) {
                return (code, v);
            }
        }
    }
    panic!("doesn't fit in a QR code");
}

fn version_num(v: Version) -> i16 {
    match v {
        Version::Normal(n) | Version::Micro(n) => n,
    }
}

fn write_svg(path: &str, code: &QrCode) {
    let svg = code
        .render::<qrcode::render::svg::Color>()
        .min_dimensions(320, 320)
        .quiet_zone(true)
        .build();
    fs::write(path, svg).unwrap();
}

/// A subaddress-shaped string: 95 base58 characters starting with 8.
/// Seeded, so every run makes the same codes.
fn fake_subaddress() -> String {
    const B58: &[u8] = b"123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";
    let mut rng = StdRng::seed_from_u64(7);
    std::iter::once('8')
        .chain((0..94).map(|_| B58[rng.gen_range(0..B58.len())] as char))
        .collect()
}

fn receipt_json(slug: &str, r: &Receipt) -> Value {
    json!({
        "slug": slug, "store": r.store, "ref": r.sale_ref,
        "items": r.items.iter().map(|i| json!([i.name, i.qty, i.cents])).collect::<Vec<_>>(),
        "subtotal": r.subtotal(), "tax": r.tax(), "tip": r.tip, "total": r.total(),
        "xmr": xmr(r.piconero()),
    })
}

fn print_table(report: &Value) {
    println!(
        "dictionary: {} bytes, trained on {} names",
        report["dictionary"]["chosen_bytes"], report["dictionary"]["training_names"]
    );
    println!("payment-only QR: version {}", report["baseline"]["qr_version_m"]);
    println!(
        "{:<11} {:>5}  {:>9} {:>9}  {:>9} {:>9}",
        "receipt", "items", "v1 M", "v1+sig M", "v2 bytes", "v2 M (L)"
    );
    for r in report["receipts"].as_array().unwrap() {
        println!(
            "{:<11} {:>5}  {:>9} {:>9}  {:>9} {:>5} ({})",
            r["slug"].as_str().unwrap(),
            r["items"],
            r["before"]["unsigned"]["qr_version_m"],
            r["before"]["signed"]["qr_version_m"],
            r["after"]["payload_bytes"],
            r["after"]["qr_version_m"],
            r["after"]["qr_version_l"],
        );
    }
}
