# Workers

The jobs that cost money and take time, kept out of the alert path on purpose.
By the time the phone asks for a fence set, the answer is already a row in a
table.

| Script | Job |
| --- | --- |
| `refresh_places.py` | Pull restaurants into `places`, and delete rows past the 30-day caching window |
| `score_places.py` | Score every unscored place against every active want |

## A normal night

```bash
python3 refresh_places.py --db ../server/taste-radar.db --source google
```

```bash
python3 score_places.py --db ../server/taste-radar.db --reviews-from reviews.json
```

With no keys, both run offline:

```bash
python3 refresh_places.py --db ../server/taste-radar.db --source fixture && python3 score_places.py --db ../server/taste-radar.db --dry-run --reviews-from reviews.json
```

## Two things worth knowing

**Review text is never stored.** `refresh_places.py` writes it to `reviews.json`
for the scorer to read on the same run and then discard. Only the score and its
one-line reason persist. That keeps the database inside Google's caching terms
and means a copy of the file is not a copy of Google's content.

**The prune is the licence, not housekeeping.** Google permits keeping a place ID
indefinitely but requires most other cached content to be refreshed or deleted on
roughly a 30-day cycle. `prune()` enforces that, and takes the dependent scores
with it. Verified: backdating two rows and running `--prune-only` removed both
rows and both of their scores.

**`--dry-run` is a baseline, not a stub.** It counts keywords. It cannot read
negation, so a review saying *"no beef on the buffet"* scores as a match. If the
model cannot beat it on your own graded sample, you do not need the model.
