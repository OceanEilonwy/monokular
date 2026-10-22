//! The compact encoding:
//!
//! ```text
//! payload = version byte | bit-packed numbers | zstd(text, trained dictionary)
//! r=      = payload as decimal digits (QR numeric mode)
//! ```
//!
//! - Numbers and flags are bit-packed with Exp-Golomb codes; the text (store,
//!   reference, item names) is compressed separately, because a compressor
//!   does far better on text alone than on text interleaved with binary.
//! - Nothing that can be derived is sent: subtotal, tax amount (when it is
//!   floor(subtotal × rate)), total, and the XMR amount (it is `tx_amount` in
//!   the same URI). Quantity 1 is a single bit. Time is minutes since 2025.
//! - No signature in the QR; the Monero transaction already proves payment.
//! - zstd runs in magicless mode with no checksum, content size or
//!   dictionary id: the version byte says which frozen dictionary to use.

use std::io;

use zstd::zstd_safe::{CParameter, DParameter, FrameFormat};

use crate::receipts::{Item, Receipt};

pub const VERSION: u8 = 2;
const EPOCH_2025: u64 = 1_735_689_600;
const CURRENCIES: [&str; 3] = ["USD", "EUR", "GBP"];
const SEP: char = '\n';

// ---- bit writer / reader --------------------------------------------------

struct Bits {
    out: Vec<u8>,
    acc: u64,
    n: u32,
}

impl Bits {
    fn new() -> Self {
        Bits { out: Vec::new(), acc: 0, n: 0 }
    }
    fn put(&mut self, value: u64, width: u32) {
        for i in (0..width).rev() {
            self.acc = (self.acc << 1) | ((value >> i) & 1);
            self.n += 1;
            if self.n == 8 {
                self.out.push(self.acc as u8);
                self.acc = 0;
                self.n = 0;
            }
        }
    }
    fn flag(&mut self, b: bool) {
        self.put(b as u64, 1);
    }
    /// Exp-Golomb of order k: small values cheap, any value representable.
    fn eg(&mut self, value: u64, k: u32) {
        let v = value + (1 << k);
        let len = 64 - v.leading_zeros();
        self.put(0, len - 1 - k);
        self.put(v, len);
    }
    fn finish(mut self) -> Vec<u8> {
        if self.n > 0 {
            self.put(0, 8 - self.n);
        }
        self.out
    }
}

struct Reader<'a> {
    data: &'a [u8],
    pos: usize,
}

impl Reader<'_> {
    fn bit(&mut self) -> u64 {
        let b = (self.data[self.pos / 8] >> (7 - self.pos % 8)) & 1;
        self.pos += 1;
        b as u64
    }
    fn get(&mut self, width: u32) -> u64 {
        (0..width).fold(0, |acc, _| (acc << 1) | self.bit())
    }
    fn flag(&mut self) -> bool {
        self.bit() == 1
    }
    fn eg(&mut self, k: u32) -> u64 {
        let mut zeros = 0;
        while self.bit() == 0 {
            zeros += 1;
        }
        let rest = self.get(zeros + k);
        ((1 << (zeros + k)) | rest) - (1 << k)
    }
    fn bytes_used(&self) -> usize {
        self.pos.div_ceil(8)
    }
}

// ---- zstd with a trained dictionary, magicless -----------------------------

pub fn zstd_compress(text: &[u8], dict: &[u8]) -> Vec<u8> {
    let mut c = zstd::bulk::Compressor::with_dictionary(22, dict).unwrap();
    for p in [
        CParameter::Format(FrameFormat::Magicless),
        CParameter::ChecksumFlag(false),
        CParameter::ContentSizeFlag(false),
        CParameter::DictIdFlag(false),
    ] {
        c.set_parameter(p).unwrap();
    }
    c.compress(text).unwrap()
}

pub fn zstd_decompress(data: &[u8], dict: &[u8]) -> io::Result<Vec<u8>> {
    let mut d = zstd::bulk::Decompressor::with_dictionary(dict)?;
    d.set_parameter(DParameter::Format(FrameFormat::Magicless))?;
    d.decompress(data, 1 << 16)
}

// ---- encode / decode -------------------------------------------------------

/// The receipt's text, one field per line: store, reference, then items.
fn text_of(r: &Receipt) -> String {
    let mut parts = vec![r.store.clone(), r.sale_ref.clone()];
    if !CURRENCIES.contains(&r.currency.as_str()) {
        parts.push(r.currency.clone());
    }
    parts.extend(r.items.iter().map(|i| i.name.clone()));
    parts.join(&SEP.to_string())
}

pub struct Encoded {
    pub payload: Vec<u8>,
    pub bits_bytes: usize,
    pub text_bytes: usize,
    pub text_compressed: usize,
}

pub fn encode(r: &Receipt, dict: &[u8]) -> Encoded {
    let mut b = Bits::new();
    b.put(((r.time - EPOCH_2025) / 60) as u64, 23); // minutes; 23 bits ≈ 15 years
    let cur = CURRENCIES.iter().position(|c| *c == r.currency).unwrap_or(3);
    b.put(cur as u64, 2);
    b.eg(r.items.len() as u64 - 1, 2);
    for item in &r.items {
        b.flag(item.qty != 1);
        if item.qty != 1 {
            b.eg(item.qty as u64 - 2, 1);
        }
        b.eg(item.cents, 8);
    }
    b.flag(r.tax_bp != 0);
    if r.tax_bp != 0 {
        b.eg(r.tax_bp, 8);
    }
    b.flag(r.tip != 0);
    if r.tip != 0 {
        b.eg(r.tip, 9);
    }
    let bits = b.finish();
    let text = text_of(r);
    let compressed = zstd_compress(text.as_bytes(), dict);
    let mut payload = vec![VERSION];
    payload.extend_from_slice(&bits);
    payload.extend_from_slice(&compressed);
    Encoded {
        bits_bytes: bits.len(),
        text_bytes: text.len(),
        text_compressed: compressed.len(),
        payload,
    }
}

/// The receipt back from a payload; time comes back at minute precision.
pub fn decode(payload: &[u8], dict: &[u8]) -> Receipt {
    assert_eq!(payload[0], VERSION);
    let mut rd = Reader { data: &payload[1..], pos: 0 };
    let time = EPOCH_2025 + rd.get(23) * 60;
    let cur = rd.get(2) as usize;
    let n = rd.eg(2) as usize + 1;
    let mut nums = Vec::with_capacity(n);
    for _ in 0..n {
        let qty = if rd.flag() { rd.eg(1) as u32 + 2 } else { 1 };
        nums.push((qty, rd.eg(8)));
    }
    let tax_bp = if rd.flag() { rd.eg(8) } else { 0 };
    let tip = if rd.flag() { rd.eg(9) } else { 0 };
    let text_start = 1 + rd.bytes_used();
    let text = String::from_utf8(zstd_decompress(&payload[text_start..], dict).unwrap()).unwrap();
    let mut lines = text.split(SEP);
    let store = lines.next().unwrap().to_string();
    let sale_ref = lines.next().unwrap().to_string();
    let currency = match CURRENCIES.get(cur) {
        Some(c) => c.to_string(),
        None => lines.next().unwrap().to_string(),
    };
    let items = nums
        .into_iter()
        .zip(lines)
        .map(|((qty, cents), name)| Item { name: name.to_string(), qty, cents })
        .collect();
    Receipt { store, sale_ref, time, currency, items, tax_bp, tip }
}

// ---- QR numeric mode -------------------------------------------------------

/// Digits per final chunk of 1..=7 bytes: ceil(bytes × log10(256)).
const DIGITS: [usize; 8] = [0, 3, 5, 8, 10, 13, 15, 17];

/// Bytes as decimal digits, 7 bytes to 17 digits (2.43 digits per byte, close
/// to the 2.41 optimum), so the value fits QR numeric mode at 3.33 bits a digit.
pub fn to_digits(data: &[u8]) -> String {
    let mut s = String::new();
    for chunk in data.chunks(7) {
        let n = chunk.iter().fold(0u64, |acc, &b| (acc << 8) | b as u64);
        s.push_str(&format!("{n:0width$}", width = DIGITS[chunk.len()]));
    }
    s
}

pub fn from_digits(s: &str) -> Vec<u8> {
    let mut out = Vec::new();
    let mut rest = s;
    while !rest.is_empty() {
        let take = rest.len().min(17);
        let bytes = DIGITS.iter().position(|&d| d == take).expect("valid digit count");
        let n: u64 = rest[..take].parse().unwrap();
        for i in (0..bytes).rev() {
            out.push((n >> (8 * i)) as u8);
        }
        rest = &rest[take..];
    }
    out
}
