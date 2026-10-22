#!/usr/bin/env bash
# Downloads the raw sources for the receipt dictionary into corpus/raw/, then
# build_corpus.py turns them into corpus/train/*.txt and corpus/test/*.txt.
# Large exports are streamed and cut short instead of downloaded whole.
#
# Licences (see README.md): Open Food Facts / Open Products Facts ODbL; NYPL
# "What's on the Menu?" public domain; Wikidata CC0; Amazon Reviews 2023
# (McAuley Lab) research dataset, terms to be confirmed before shipping a
# dictionary trained on it.
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p corpus/raw
raw=corpus/raw
food_rows=${FOOD_ROWS:-400000}
amazon_rows=${AMAZON_ROWS:-120000}

fetch() { # url, destination, then an optional filter command
    local url=$1 dest=$2
    shift 2
    if [[ -s $dest ]]; then echo "have $dest"; return; fi
    echo "fetching $dest"
    curl -fsSL "$url" | "$@" > "$dest.part" || true
    mv "$dest.part" "$dest"
}

fetch https://static.openfoodfacts.org/data/en.openfoodfacts.org.products.csv.gz \
    $raw/off.tsv sh -c "gzip -dc 2>/dev/null | head -n $food_rows"
fetch https://static.openproductsfacts.org/data/en.openproductsfacts.org.products.csv.gz \
    $raw/opf.tsv gzip -dc

if [[ ! -s $raw/nypl/Dish.csv ]]; then
    echo "fetching NYPL menus"
    mkdir -p $raw/nypl
    curl -fsSL https://s3.amazonaws.com/menusdata.nypl.org/gzips/2021_08_01_07_01_17_data.tgz |
        tar -xz -C $raw/nypl Dish.csv
fi

amazon=https://huggingface.co/datasets/McAuley-Lab/Amazon-Reviews-2023/resolve/main/raw/meta_categories
for cat in Tools_and_Home_Improvement Clothing_Shoes_and_Jewelry Health_and_Personal_Care Grocery_and_Gourmet_Food; do
    fetch "$amazon/meta_$cat.jsonl" $raw/amazon-$cat.jsonl head -n "$amazon_rows"
done

python3 -I build_corpus.py
