# Spike 1 — does the scoring work

```bash
cd spike1-scoring
python3 -m venv .venv && source .venv/bin/activate
pip install anthropic requests
```

Fetch candidates for three Chennai areas (Nungambakkam, Teynampet/Royapettah,
Triplicane). It pulls **every** restaurant, not only likely beef places — the
spike is about discrimination, so the scorer has to separate them itself.

```bash
export GOOGLE_PLACES_API_KEY=...
python3 fetch.py --source google
```

No Google key? The free fallback works and is itself worth seeing:

```bash
python3 fetch.py --source osm
```

Score them, first with the dumb baseline, then for real:

```bash
python3 score.py --dry-run --want "craving a proper beef dish, not too expensive"

export ANTHROPIC_API_KEY=...
python3 score.py --want "craving a proper beef dish, not too expensive"
```

Grade it blind — the scores are deliberately hidden from the sheet, because if
you can see them you will anchor to them:

```bash
python3 grade.py --sheet      # writes grading.csv
# fill the verdict column with y / n / m
python3 grade.py --compare
```

## Reading the result

`--compare` prints agreement, top-10 precision and rank correlation, then the
list that actually matters: **places it would have alerted you about that you
said no to**. Those are what make a person mute the app. One or two is fine;
five means the data is not there.

Run it twice, once with `--source google` and once with `--source osm`. The gap
between them is the value of the Google bill.

## A sanity check, not an answer key

Chennai places that web sources associate with beef, worth seeing near the top:
Kappa Chakka Kandhari (Nungambakkam), Vembanad (Thousand Lights), Ikkakas
(Teynampet), Travancore, Malabar Camboose, Mishwar, Puttopia. Unverified and
possibly stale — grade on your own knowledge, not this list.
