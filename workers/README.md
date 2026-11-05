# Workers

The jobs that cost money and take time, kept out of the alert path on purpose.
By the time the phone asks for a fence set, the answer is already a row in a
table.

| Script | Job |
| --- | --- |
| `refresh_places.py` | Pull restaurants into `places`, normalise their opening hours, and delete rows past the 30-day caching window |
| `score_places.py` | Score every unscored place against every active want |
| `hours.py` | Opening-hours normalisation and evaluation. Imported by the above; 16 tests in `test_hours.py` |

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

## Opening hours

Normalised once here, evaluated on the phone at alert time — not at fence-set
time, because a set is drawn once and then used for hours, so "open now"
computed when the set is drawn is stale by the time it matters.

The format is a list of periods in **minutes since Sunday 00:00 local time**,
where `c` is always greater than `o`:

```json
{"periods": [{"o": 6420, "c": 7140}]}
```

A period running past midnight simply has a `c` beyond its day, and one running
past Saturday midnight has a `c` beyond 10080. That removes wrap-around as a
special case: a check only tests `t` and `t + 10080`. Open all week is one
period of 0 to 10080.

**Unknown is not closed.** Google and OSM both leave plenty of small places
without hours, and treating that as shut would silence real matches. Unknown
never suppresses an alert, so the cost is an occasional alert about somewhere
that turns out to be closed.

OSM's `opening_hours` grammar has holidays, months, week numbers and sunset
offsets. Only the common forms are parsed — `24/7`, and rules like
`Mo-Fr 09:00-18:00; Sa 10:00-14:00`. Anything else returns unknown rather than a
guess.

See what your own area actually publishes:

```bash
python3 refresh_places.py --db ../server/taste-radar.db --report-open
```

```bash
python3 -m unittest test_hours -v
```

`hours.py` is mirrored line for line by `android/.../Hours.kt`, which cannot be
tested here. The Python side carries the tests; change one and change both.
