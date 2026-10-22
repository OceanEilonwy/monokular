"""Builds the receipt QR scan test page from out/report.json, out/receipts.json
and the SVGs. Usage: python3 page.py out out/receipt-qr-test.html"""
import html
import json
import re
import sys
from pathlib import Path

out = Path(sys.argv[1])
dest = Path(sys.argv[2])
report = json.loads((out / "report.json").read_text())
receipts = {r["slug"]: r for r in json.loads((out / "receipts.json").read_text())}


def svg(name: str, label: str) -> str:
    s = (out / name).read_text()
    s = re.sub(r"^<\?xml[^>]*\?>", "", s)
    s = re.sub(r'width="\d+" height="\d+" ', "", s, count=1)
    return s.replace("<svg ", f'<svg role="img" aria-label="{html.escape(label)}" class="qr" ', 1)


def money(cents: int) -> str:
    return f"${cents / 100:,.2f}"


def receipt_html(r) -> str:
    rows = "".join(
        f"<tr><td>{html.escape(n)}</td><td class=n>{q}</td><td class=n>{money(q * c)}</td></tr>"
        for n, q, c in r["items"]
    )
    tax = f"<tr><td>Tax</td><td></td><td class=n>{money(r['tax'])}</td></tr>" if r["tax"] else ""
    tip = f"<tr><td>Tip</td><td></td><td class=n>{money(r['tip'])}</td></tr>" if r["tip"] else ""
    return f"""<div class="paper">
  <div class="paper-head"><strong>{html.escape(r['store'])}</strong><span>{html.escape(r['ref'])}</span></div>
  <div class="paper-scroll"><table>
    <tbody>{rows}</tbody>
    <tfoot>
      <tr><td>Subtotal</td><td></td><td class=n>{money(r['subtotal'])}</td></tr>{tax}{tip}
      <tr class="tot"><td>Total</td><td></td><td class=n>{money(r['total'])}</td></tr>
      <tr><td>Paid</td><td></td><td class=n>{r['xmr']} XMR</td></tr>
    </tfoot>
  </table></div>
</div>"""


def band(version: int) -> tuple[str, str]:
    if version <= 13:
        return "easy", "Easy to scan"
    if version <= 18:
        return "ok", "Fine in good light"
    return "hard", "Dense: test carefully"


def qr_card(d, title: str, label: str, extra: str = "") -> str:
    cls, words = band(d["qr_version_m"])
    return f"""<figure class="qr-card">
  <figcaption><span class="kind">{title}</span><span class="pill {cls}">{words}</span></figcaption>
  <div class="qr-wrap">{svg(d['svg'], label)}</div>
  <dl>
    <div><dt>QR version</dt><dd>{d['qr_version_m']} <small>(M)</small> · {d['qr_version_l']} <small>(L)</small></dd></div>
    <div><dt>Grid</dt><dd>{d['modules']}×{d['modules']}</dd></div>
    {extra}
    <div><dt>Whole URI</dt><dd>{d['uri_chars']} chars</dd></div>
  </dl>
</figure>"""


sections = []
summary = []
for row in report["receipts"]:
    r = receipts[row["slug"]]
    b, a = row["before"]["unsigned"], row["after"]
    store = row["store"]
    after_extra = (
        f"<div><dt>Receipt data</dt><dd>{a['payload_bytes']} B</dd></div>"
        f"<div><dt>Numbers / text</dt><dd>{a['numbers_bytes']} / {a['text_compressed_bytes']} B</dd></div>"
    )
    comp = "".join(
        f"<tr><td>{html.escape(k)}</td><td class=n>{v} B</td></tr>"
        for k, v in row["text_compressors"].items()
    )
    sections.append(f"""<section class="case" id="{row['slug']}">
  <header class="case-head">
    <h2>{html.escape(store)}</h2>
    <p>{row['items']} items · version {b['qr_version_m']} before, {a['qr_version_m']} after</p>
  </header>
  <div class="case-body">
    {receipt_html(r)}
    {qr_card(b, "Before", f"{store} receipt, first encoding, version {b['qr_version_m']}")}
    {qr_card(a, "After", f"{store} receipt, compact encoding, version {a['qr_version_m']}", after_extra)}
    <details class="sizes"><summary>Item text ({a['text_bytes']} B) under each compressor</summary><table>{comp}</table></details>
  </div>
</section>""")
    summary.append(
        f"<tr><td>{html.escape(store)}</td><td class=n>{row['items']}</td>"
        f"<td class=n>{row['before']['signed']['qr_version_m']}</td><td class=n>{b['qr_version_m']}</td>"
        f"<td class=n><strong>{a['qr_version_m']}</strong></td><td class=n>{a['qr_version_l']}</td>"
        f"<td class=n>{a['payload_bytes']} B</td></tr>"
    )

base = report["baseline"]
d = report["dictionary"]
baseline = f"""<figure class="qr-card baseline">
  <figcaption><span class="kind">Payment only</span><span class="pill easy">Easy to scan</span></figcaption>
  <div class="qr-wrap">{svg(base['svg'], 'Payment-only QR, version ' + str(base['qr_version_m']))}</div>
  <dl>
    <div><dt>QR version</dt><dd>{base['qr_version_m']} <small>(M)</small></dd></div>
    <div><dt>Whole URI</dt><dd>{base['uri_chars']} chars</dd></div>
  </dl>
</figure>"""

page = f"""<title>Receipt QR Scan Test</title>
<meta name="claude:viewport-fit-sides" content="cover">
<link rel="preconnect" href="https://fonts.googleapis.com">
<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Manrope:wght@500;700;800&family=JetBrains+Mono:wght@400;600&display=swap">
<style>
/* A test sheet: summary first, then one row per receipt (paper receipt, before QR, after QR). */
:root {{
  --bg: #f4f1ea; --surface: #ffffff; --ink: #1a1917; --muted: #5a564c; --line: #dcd5c7;
  --accent-text: #b34700;
  --ok: #1a7f37; --ok-bg: #e2f0e5; --warn: #7d5e00; --warn-bg: #f3ead2; --bad: #b00020; --bad-bg: #f6dfe2;
  --paper: #fffdf8; --qr-bg: #ffffff;
  --font-body: Manrope, system-ui, sans-serif; --font-mono: "JetBrains Mono", ui-monospace, Menlo, monospace;
  --qr: 300px;
}}
@media (prefers-color-scheme: dark) {{ :root:not([data-theme="light"]) {{
  --bg: #1e1e1e; --surface: #2a2a2a; --ink: #d4d4d4; --muted: #9d9d9d; --line: #3d3d3d; --accent-text: #ff8a3d;
  --ok: #4cc27a; --ok-bg: #1f3326; --warn: #e2b93b; --warn-bg: #3a3220; --bad: #ff6b7d; --bad-bg: #3d2226;
  --paper: #262626; color-scheme: dark; }} }}
:root[data-theme="dark"] {{
  --bg: #1e1e1e; --surface: #2a2a2a; --ink: #d4d4d4; --muted: #9d9d9d; --line: #3d3d3d; --accent-text: #ff8a3d;
  --ok: #4cc27a; --ok-bg: #1f3326; --warn: #e2b93b; --warn-bg: #3a3220; --bad: #ff6b7d; --bad-bg: #3d2226;
  --paper: #262626; color-scheme: dark; }}
body {{ background: var(--bg); color: var(--ink); font-family: var(--font-body); font-size: 16px; line-height: 1.5;
  padding-left: max(16px, env(safe-area-inset-left, 0px)); padding-right: max(16px, env(safe-area-inset-right, 0px)); padding-block: 32px 64px; }}
.wrap {{ max-width: 1180px; margin: 0 auto; display: flex; flex-direction: column; gap: 40px; }}
h1, h2 {{ text-wrap: balance; margin: 0; letter-spacing: -0.02em; font-weight: 800; }}
h1 {{ font-size: clamp(28px, 4vw, 40px); }}
h2 {{ font-size: 22px; }}
p {{ margin: 0; max-width: 70ch; }}
.muted {{ color: var(--muted); }}
.intro {{ display: flex; flex-direction: column; gap: 12px; }}
.intro ul {{ margin: 0; padding-left: 20px; max-width: 70ch; color: var(--muted); }}
.warn {{ border: 1px solid var(--line); background: var(--surface); border-radius: 10px; padding: 12px 16px; font-size: 15px; }}
.warn strong {{ color: var(--accent-text); }}
.controls {{ display: flex; flex-wrap: wrap; align-items: center; gap: 12px; font-size: 15px; }}
.controls input {{ accent-color: var(--accent-text); width: min(320px, 60vw); }}
.controls output {{ font-family: var(--font-mono); font-variant-numeric: tabular-nums; }}
.top {{ display: grid; grid-template-columns: minmax(0, 1fr) auto; gap: 24px; align-items: start; }}
@media (max-width: 760px) {{ .top {{ grid-template-columns: minmax(0, 1fr); }} }}
.table-box {{ overflow-x: auto; background: var(--surface); border: 1px solid var(--line); border-radius: 10px; }}
table {{ border-collapse: collapse; width: 100%; font-size: 15px; }}
th, td {{ text-align: left; padding: 8px 12px; border-bottom: 1px solid var(--line); vertical-align: top; }}
th {{ font-size: 12px; text-transform: uppercase; letter-spacing: 0.06em; color: var(--muted); font-weight: 700; }}
td.n, th.n {{ text-align: right; font-variant-numeric: tabular-nums; font-family: var(--font-mono); font-size: 14px; white-space: nowrap; }}
tr:last-child td {{ border-bottom: 0; }}
.case {{ display: flex; flex-direction: column; gap: 16px; padding-top: 24px; border-top: 1px solid var(--line); }}
.case-head p {{ color: var(--muted); font-size: 15px; }}
.case-body {{ display: grid; grid-template-columns: minmax(240px, 1fr) auto auto; gap: 20px; align-items: start; }}
@media (max-width: 1100px) {{ .case-body {{ grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); }} .paper {{ grid-column: 1 / -1; }} }}
@media (max-width: 700px) {{ .case-body {{ grid-template-columns: minmax(0, 1fr); }} }}
.paper {{ background: var(--paper); border: 1px solid var(--line); border-radius: 4px; padding: 16px; font-family: var(--font-mono); font-size: 13px; min-width: 0; }}
.paper-head {{ display: flex; justify-content: space-between; gap: 8px; padding-bottom: 8px; border-bottom: 1px dashed var(--line); margin-bottom: 4px; }}
.paper-scroll {{ max-height: 360px; overflow: auto; }}
.paper table {{ font-size: 13px; }}
.paper td {{ padding: 3px 4px; border: 0; }}
.paper tfoot td {{ border-top: 1px dashed var(--line); }}
.paper tfoot tr + tr td {{ border-top: 0; }}
.paper .tot td {{ font-weight: 600; }}
.qr-card {{ margin: 0; display: flex; flex-direction: column; gap: 10px; min-width: 0; }}
.qr-card figcaption {{ display: flex; justify-content: space-between; align-items: center; gap: 8px; }}
.kind {{ font-weight: 700; }}
.pill {{ font-size: 12px; font-weight: 700; padding: 3px 10px; border-radius: 999px; white-space: nowrap; }}
.pill.easy {{ color: var(--ok); background: var(--ok-bg); }}
.pill.ok {{ color: var(--warn); background: var(--warn-bg); }}
.pill.hard {{ color: var(--bad); background: var(--bad-bg); }}
.qr-wrap {{ background: var(--qr-bg); border-radius: 14px; padding: 6px; width: var(--qr); max-width: 100%; box-sizing: border-box; }}
.qr {{ display: block; width: 100%; height: auto; }}
dl {{ margin: 0; display: grid; grid-template-columns: 1fr 1fr; gap: 6px 16px; font-size: 14px; max-width: var(--qr); }}
dl div {{ display: flex; flex-direction: column; }}
dt {{ font-size: 12px; color: var(--muted); }}
dd {{ margin: 0; font-family: var(--font-mono); font-variant-numeric: tabular-nums; }}
small {{ color: var(--muted); }}
.sizes {{ grid-column: 1 / -1; font-size: 14px; }}
.sizes summary {{ cursor: pointer; color: var(--accent-text); font-weight: 700; }}
.sizes table {{ max-width: 420px; margin-top: 8px; }}
:focus-visible {{ outline: 2px solid var(--accent-text); outline-offset: 2px; }}
</style>

<div class="wrap">
  <header class="intro">
    <h1>Receipt QR scan test</h1>
    <p class="muted">Each code is a real Monero payment URI with the whole receipt in one extra parameter. <strong>Before</strong> is the first prototype. <strong>After</strong> adds:</p>
    <ul>
      <li>no signature in the code (the Monero transaction proves payment);</li>
      <li>nothing that can be recomputed: totals, tax amount, the XMR amount (already in <code>tx_amount</code>), store id; time in minutes;</li>
      <li>prices and quantities bit-packed, quantity 1 as a single bit;</li>
      <li>item text compressed with zstd and a {d['chosen_bytes'] // 1024} KB dictionary trained on {d['training_names']:,} product names from Open Food Facts and Open Products Facts;</li>
      <li>the result written as digits, which QR packs more tightly than letters.</li>
    </ul>
    <p class="warn"><strong>Don't pay any of these.</strong> The address is a made-up placeholder and the amounts are examples. A plain camera app shows the URI text.</p>
    <div class="controls">
      <label for="qr-size">Code size on screen</label>
      <input id="qr-size" type="range" min="180" max="440" step="10" value="300">
      <output id="qr-size-out" for="qr-size">300 px</output>
      <span class="muted">About 280 px matches the Monokular full-screen code on an iPhone.</span>
    </div>
  </header>

  <div class="top">
    <div class="table-box">
      <table>
        <thead><tr><th>Receipt</th><th class=n>Items</th><th class=n>Before, signed</th><th class=n>Before</th><th class=n>After (M)</th><th class=n>After (L)</th><th class=n>After data</th></tr></thead>
        <tbody>{''.join(summary)}</tbody>
      </table>
    </div>
    {baseline}
  </div>

  {''.join(sections)}

  <p class="muted">QR versions at error correction M (15% recovery), with L (7%) for comparison; codes on this page use M. The dictionary is trained mostly on food names, which is why the hardware receipt gains least.</p>
</div>

<script>
(() => {{
  const input = document.getElementById('qr-size');
  const out = document.getElementById('qr-size-out');
  const apply = v => {{ document.documentElement.style.setProperty('--qr', v + 'px'); out.textContent = v + ' px'; }};
  try {{ const saved = localStorage.getItem('qr-size'); if (saved) {{ input.value = saved; }} }} catch (e) {{}}
  apply(input.value);
  input.addEventListener('input', () => {{ apply(input.value); try {{ localStorage.setItem('qr-size', input.value); }} catch (e) {{}} }});
}})();
</script>
"""
dest.write_text(page)
print(dest, len(page))
