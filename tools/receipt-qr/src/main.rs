//! Prototype: how big does a Monero payment QR get when it carries the whole
//! receipt? Trains zstd dictionaries on several mixes of the corpus, encodes
//! test receipts built only from held-out names, and reports QR versions per
//! category. Also writes before/after codes for the hand-written receipts.
//!
//! Run `./fetch-corpus.sh` once, then `cargo run --release`. Writes
//! `out/report.json`, `out/receipts.json` and SVGs; `page.py` builds the page.

mod receipts;
mod v1;
mod v2;

use std::collections::BTreeMap;
use std::fs;

use qrcode::bits::Bits;
use qrcode::{EcLevel, QrCode, Version};
use rand::rngs::StdRng;
use rand::seq::SliceRandom;
use rand::{Rng, SeedableRng};
use serde_json::{Map, Value, json};

use receipts::{Item, Receipt};

const DICT_SIZES: [usize; 3] = [32 << 10, 64 << 10, 110 << 10];
const TRAINING_SAMPLES: usize = 40_000;
const RECEIPTS_PER_CATEGORY: usize = 40;

/// Dictionary variants: which training sources each one mixes.
const VARIANTS: [(&str, &[&str]); 4] = [
    ("food only (before)", &["off"]),
    ("open", &["nypl", "wikidata", "synthetic"]),
    ("open + food", &["nypl", "wikidata", "synthetic", "off"]),
    ("all", &["nypl", "wikidata", "synthetic", "off", "amazon_tools", "amazon_clothing", "amazon_health", "amazon_grocery"]),
];

/// Test categories: held-out names, typical prices in cents, tax and tip.
struct Category {
    name: &'static str,
    stores: &'static [&'static str],
    cents: (u64, u64),
    tax_bp: u64,
    tip_pct: u64,
}

const CATEGORIES: [Category; 5] = [
    Category { name: "restaurant", stores: &["Harbour Kitchen", "The Olive Tree", "Saigon Street"], cents: (450, 3800), tax_bp: 888, tip_pct: 18 },
    Category { name: "grocery", stores: &["Corner Fresh", "Valley Co-op", "Market Hall"], cents: (89, 1499), tax_bp: 0, tip_pct: 0 },
    Category { name: "hardware", stores: &["Benson Hardware", "Tool & Timber", "Northside DIY"], cents: (199, 8999), tax_bp: 888, tip_pct: 0 },
    Category { name: "clothing", stores: &["Thread & Co", "Second Stitch", "Harlow Outfitters"], cents: (900, 12000), tax_bp: 888, tip_pct: 0 },
    Category { name: "pharmacy", stores: &["Riverside Pharmacy", "Green Cross Chemist"], cents: (299, 2499), tax_bp: 888, tip_pct: 0 },
];

enum Segment<'a> {
    Alphanumeric(&'a str),
    Numeric(&'a str),
}

fn main() {
    let train = load_dir("corpus/train");
    let test = load_dir("corpus/test");
    fs::create_dir_all("out").unwrap();
    let address = fake_subaddress();

    let mut tests: Vec<(String, Receipt)> = receipts::samples()
        .into_iter()
        .map(|(slug, r)| (format!("hand/{slug}"), r))
        .collect();
    for cat in &CATEGORIES {
        let names = test.get(cat.name).unwrap_or_else(|| panic!("corpus/test/{}.txt missing", cat.name));
        tests.extend(test_receipts(cat, names));
    }

    // Train every variant at every size and score it on every test receipt.
    let mut results = Vec::new();
    let mut best: Option<(f64, String, Vec<u8>)> = None;
    for (variant, sources) in VARIANTS {
        let samples = training_samples(sources, &train);
        for size in DICT_SIZES {
            let refs: Vec<&[u8]> = samples.iter().map(|s| s.as_bytes()).collect();
            let dict = zstd::dict::from_samples(&refs, size).expect("dictionary training");
            let label = format!("{variant}, {}k", size >> 10);
            let mut by_cat: BTreeMap<String, Vec<(usize, i16, i16)>> = BTreeMap::new();
            for (id, r) in &tests {
                let enc = v2::encode(r, &dict);
                assert_eq!(&v2::decode(&enc.payload, &dict), r, "{id}: round trip");
                let digits = v2::to_digits(&enc.payload);
                assert_eq!(v2::from_digits(&digits), enc.payload, "{id}: digits round trip");
                let prefix = uri_prefix(&address, r);
                let m =smallest_qr(&prefix, &Segment::Numeric(&digits), EcLevel::M).1;
                let l = smallest_qr(&prefix, &Segment::Numeric(&digits), EcLevel::L).1;
                let cat = id.split('/').next().unwrap().to_string();
                by_cat.entry(cat).or_default().push((enc.payload.len(), m, l));
            }
            let mean_version = by_cat.values().flatten().map(|x| x.1 as f64).sum::<f64>()
                / by_cat.values().map(Vec::len).sum::<usize>() as f64;
            let cats: Map<String, Value> = by_cat
                .iter()
                .map(|(cat, xs)| (cat.clone(), stats(xs)))
                .collect();
            println!("{label:<28} mean QR version {mean_version:.2}");
            results.push(json!({
                "variant": variant, "dict_bytes": size, "label": label,
                "mean_version_m": mean_version, "categories": cats,
            }));
            if best.as_ref().is_none_or(|b| mean_version < b.0) {
                best = Some((mean_version, label, dict));
            }
        }
    }
    let (_, best_label, best_dict) = best.unwrap();
    fs::write("out/receipts.zdict", &best_dict).unwrap();
    println!("best: {best_label}");

    // QR codes for the page: the hand-written receipts before and after, and
    // the median receipt of each test category.
    let mut shown = Vec::new();
    for (id, r) in &tests {
        let cat = id.split('/').next().unwrap();
        let is_hand = cat == "hand";
        if !is_hand && !is_median_of(id, &tests, &best_dict) {
            continue;
        }
        let slug = id.replace('/', "-");
        let prefix = uri_prefix(&address, r);
        let enc = v2::encode(r, &best_dict);
        let digits = v2::to_digits(&enc.payload);
        let mut after = qr_row(&format!("{slug}-v2"), &prefix, Segment::Numeric(&digits));
        after["payload_bytes"] = json!(enc.payload.len());
        after["numbers_bytes"] = json!(enc.bits_bytes);
        after["text_bytes"] = json!(enc.text_bytes);
        after["text_compressed_bytes"] = json!(enc.text_compressed);
        let before = if is_hand {
            qr_row(&format!("{slug}-v1"), &prefix, Segment::Alphanumeric(&v1::encode(r, false)))
        } else {
            Value::Null
        };
        shown.push(json!({ "id": id, "category": cat, "receipt": receipt_json(r), "before": before, "after": after }));
    }

    let baseline_uri = format!("monero:{address}?tx_amount={}", xmr(tests[0].1.piconero()));
    let baseline = QrCode::with_error_correction_level(baseline_uri.as_bytes(), EcLevel::M).unwrap();
    write_svg("out/baseline.svg", &baseline);
    let report = json!({
        "baseline": { "uri_chars": baseline_uri.len(), "qr_version_m": version_num(baseline.version()), "svg": "baseline.svg" },
        "best": best_label,
        "training": train.iter().map(|(k, v)| (k.clone(), json!(v.len()))).collect::<Map<_, _>>(),
        "test": test.iter().map(|(k, v)| (k.clone(), json!(v.len()))).collect::<Map<_, _>>(),
        "receipts_per_category": RECEIPTS_PER_CATEGORY,
        "results": results,
        "shown": shown,
    });
    fs::write("out/report.json", serde_json::to_string_pretty(&report).unwrap()).unwrap();
}

fn load_dir(dir: &str) -> BTreeMap<String, Vec<String>> {
    fs::read_dir(dir)
        .unwrap_or_else(|_| panic!("{dir} is missing: run ./fetch-corpus.sh first"))
        .map(|e| e.unwrap().path())
        .filter(|p| p.extension().is_some_and(|x| x == "txt"))
        .map(|p| {
            let names = fs::read_to_string(&p).unwrap().lines().map(str::to_string).collect();
            (p.file_stem().unwrap().to_string_lossy().into_owned(), names)
        })
        .collect()
}

/// Synthetic receipt texts in the layout the encoder compresses: a store line,
/// a reference line, then 1 to 25 names, all from one source (a real receipt
/// comes from one kind of shop).
fn training_samples(sources: &[&str], train: &BTreeMap<String, Vec<String>>) -> Vec<String> {
    let mut rng = StdRng::seed_from_u64(42);
    let pools: Vec<&Vec<String>> = sources
        .iter()
        .map(|s| train.get(*s).unwrap_or_else(|| panic!("corpus/train/{s}.txt missing")))
        .collect();
    (0..TRAINING_SAMPLES)
        .map(|_| {
            let pool = pools.choose(&mut rng).unwrap();
            let mut lines = vec![pool.choose(&mut rng).unwrap().clone(), format!("#{}", rng.gen_range(100..99_999))];
            let n = item_count(&mut rng);
            lines.extend((0..n).map(|_| pool.choose(&mut rng).unwrap().clone()));
            lines.join("\n")
        })
        .collect()
}

/// 1 to 25 items, mostly small baskets: P(n) falls by 15% per extra item.
fn item_count(rng: &mut StdRng) -> usize {
    let mut n = 1;
    while n < 25 && rng.gen_bool(0.85) {
        n += 1;
    }
    n
}

fn test_receipts(cat: &Category, names: &[String]) -> Vec<(String, Receipt)> {
    let mut rng = StdRng::seed_from_u64(1000 + cat.name.len() as u64);
    (0..RECEIPTS_PER_CATEGORY)
        .map(|i| {
            let n = item_count(&mut rng);
            let items: Vec<Item> = (0..n)
                .map(|_| Item {
                    name: names.choose(&mut rng).unwrap().clone(),
                    qty: if rng.gen_bool(0.8) { 1 } else { rng.gen_range(2..=4) },
                    cents: rng.gen_range(cat.cents.0..=cat.cents.1) / 5 * 5 + 4 * rng.gen_range(0..2),
                })
                .collect();
            let sub: u64 = items.iter().map(|i| i.qty as u64 * i.cents).sum();
            let r = Receipt {
                store: cat.stores.choose(&mut rng).unwrap().to_string(),
                sale_ref: format!("#{}", rng.gen_range(1000..99_999)),
                time: 1_760_890_020 + 60 * rng.gen_range(0..100_000),
                currency: "USD".into(),
                items,
                tax_bp: cat.tax_bp,
                tip: sub * cat.tip_pct / 100,
            };
            (format!("{}/{i:02}", cat.name), r)
        })
        .collect()
}

/// The test receipt whose QR version is the category's median, so the page
/// shows a typical one.
fn is_median_of(id: &str, tests: &[(String, Receipt)], dict: &[u8]) -> bool {
    let cat = id.split('/').next().unwrap();
    let mut sized: Vec<(usize, &str)> = tests
        .iter()
        .filter(|(i, _)| i.starts_with(&format!("{cat}/")))
        .map(|(i, r)| (v2::encode(r, dict).payload.len(), i.as_str()))
        .collect();
    sized.sort();
    sized[sized.len() / 2].1 == id
}

fn stats(xs: &[(usize, i16, i16)]) -> Value {
    let mut m: Vec<i16> = xs.iter().map(|x| x.1).collect();
    let mut l: Vec<i16> = xs.iter().map(|x| x.2).collect();
    let mut b: Vec<usize> = xs.iter().map(|x| x.0).collect();
    m.sort();
    l.sort();
    b.sort();
    let p = |v: &[i16], q: f64| v[((v.len() - 1) as f64 * q).round() as usize];
    json!({
        "count": xs.len(),
        "median_m": p(&m, 0.5), "p90_m": p(&m, 0.9), "max_m": m[m.len() - 1],
        "median_l": p(&l, 0.5), "p90_l": p(&l, 0.9),
        "median_bytes": b[b.len() / 2], "max_bytes": b[b.len() - 1],
    })
}

fn uri_prefix(address: &str, r: &Receipt) -> String {
    format!("monero:{address}?tx_amount={}&r=", xmr(r.piconero()))
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
        if fits && let Ok(code) = QrCode::with_bits(bits, ec) {
            return (code, v);
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

fn receipt_json(r: &Receipt) -> Value {
    json!({
        "store": r.store, "ref": r.sale_ref,
        "items": r.items.iter().map(|i| json!([i.name, i.qty, i.cents])).collect::<Vec<_>>(),
        "subtotal": r.subtotal(), "tax": r.tax(), "tip": r.tip, "total": r.total(),
        "xmr": xmr(r.piconero()),
    })
}
