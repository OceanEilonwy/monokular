#!/usr/bin/env python3
"""Ring up "Other: Monero" sales in Square's sandbox, for a till signed in to it.

Square's sandbox has no Point of Sale app, so a cashier can't tender a sale
there. This makes what the till looks for instead (docs/decisions/android.md
13): an order with line items, paid by a completed EXTERNAL payment whose
source is "Monero", at a location of the sandbox test seller.

    export SQUARE_SANDBOX_TOKEN=EAAA…   # Developer Console > Credentials > Sandbox access token
    python3 -I tools/square-sandbox/sandbox_sale.py locations
    python3 -I tools/square-sandbox/sandbox_sale.py sale "Flat white:2:450" "Almond croissant:1:375"

An item is NAME:QUANTITY:PRICE, the price in the currency's smallest unit
(cents). --location picks one when the seller has several; --dry-run prints
the requests instead of sending them. Standard library only.
"""
import argparse, json, os, sys, urllib.error, urllib.request, uuid

BASE = "https://connect.squareupsandbox.com"
VERSION = "2026-09-16"  # SquareConfig.API_VERSION


def call(method, path, body=None, dry=False):
    if dry:
        print(f"{method} {BASE}{path}")
        if body is not None:
            print(json.dumps(body, indent=2))
        return None
    token = os.environ.get("SQUARE_SANDBOX_TOKEN")
    if not token:
        sys.exit("Set SQUARE_SANDBOX_TOKEN to the sandbox access token from the Developer Console's Credentials page.")
    request = urllib.request.Request(
        BASE + path, method=method,
        data=json.dumps(body).encode() if body is not None else None,
        headers={"Authorization": f"Bearer {token}", "Square-Version": VERSION, "Content-Type": "application/json"},
    )
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            return json.load(response)
    except urllib.error.HTTPError as e:
        errors = json.load(e).get("errors", [])
        sys.exit(f"Square said {e.code}: " + "; ".join(err.get("detail", err.get("code", "")) for err in errors))


def locations():
    return [l for l in call("GET", "/v2/locations").get("locations", []) if l.get("status") != "INACTIVE"]


def item(text):
    try:
        name, quantity, price = text.rsplit(":", 2)
        return name, int(quantity), int(price)
    except ValueError:
        raise argparse.ArgumentTypeError(f"{text!r}: an item is NAME:QUANTITY:PRICE, e.g. 'Flat white:2:450'")


def sale(items, location, dry):
    if dry:
        location = {"id": location or "LOCATION_ID", "currency": "USD"}
    else:
        found = locations()
        if location:
            found = [l for l in found if l["id"] == location]
        if len(found) != 1:
            sys.exit("Pick a location with --location: " + ", ".join(f"{l['id']} ({l.get('name')})" for l in found)
                     if found else "No such active location.")
        location = found[0]
    currency = location["currency"]
    order = call("POST", "/v2/orders", {
        "idempotency_key": str(uuid.uuid4()),
        "order": {
            "location_id": location["id"],
            "line_items": [{"name": name, "quantity": str(quantity), "base_price_money": {"amount": price, "currency": currency}}
                           for name, quantity, price in items],
        },
    }, dry)
    order_id = order["order"]["id"] if order else "ORDER_ID"
    total = order["order"]["total_money"] if order else {"amount": sum(q * p for _, q, p in items), "currency": currency}
    payment = call("POST", "/v2/payments", {
        "idempotency_key": str(uuid.uuid4()),
        "source_id": "EXTERNAL",
        "external_details": {"type": "OTHER", "source": "Monero"},
        "amount_money": total,
        "order_id": order_id,
        "location_id": location["id"],
        "autocomplete": True,
    }, dry)
    if payment:
        p = payment["payment"]
        print(f"{p['status']} {p['id']}: {total['amount'] / 100:.2f} {total['currency']}, order {order_id}, at {location.get('name')}")


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("locations", help="list the test seller's active locations")
    s = sub.add_parser("sale", help="ring up a sale tendered as Other: Monero")
    s.add_argument("items", nargs="+", type=item, metavar="NAME:QUANTITY:PRICE")
    s.add_argument("--location", help="location id, when the seller has several")
    s.add_argument("--dry-run", action="store_true", help="print the requests instead of sending them")
    args = parser.parse_args()
    if args.command == "locations":
        for l in locations():
            print(f"{l['id']}  {l.get('name')}  {l.get('currency')}")
    else:
        sale(args.items, args.location, args.dry_run)


if __name__ == "__main__":
    main()
