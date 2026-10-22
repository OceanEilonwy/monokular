#!/usr/bin/env bash
# Product names to train the receipt compression dictionary on, from Open
# Food Facts and Open Products Facts (both ODbL: see README.md). Writes one
# name per line to corpus/names.txt. Streams only the start of the food
# export (1.5 GB compressed) instead of downloading it all.
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p corpus
food_rows=${FOOD_ROWS:-400000}

names() {
    # Tab-separated with a header row: print the product_name column, trimmed,
    # Latin-script names only (receipts in the test set are English).
    awk -F'\t' 'NR == 1 { for (i = 1; i <= NF; i++) if ($i == "product_name") c = i; next }
                c && $c != "" { gsub(/^[ \t]+|[ \t]+$/, "", $c); print $c }' |
        LC_ALL=C grep -E '^[ -~]{3,60}$' || true
}

curl -fsSL https://static.openfoodfacts.org/data/en.openfoodfacts.org.products.csv.gz |
    gzip -dc 2>/dev/null | head -n "$food_rows" | names > corpus/food.txt || true
curl -fsSL https://static.openproductsfacts.org/data/en.openproductsfacts.org.products.csv.gz |
    gzip -dc | names > corpus/products.txt

LC_ALL=C sort -u corpus/food.txt corpus/products.txt | shuf --random-source=<(yes) > corpus/names.txt
wc -l corpus/food.txt corpus/products.txt corpus/names.txt
