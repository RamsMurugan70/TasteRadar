# Taste Radar — the two spikes

Spec and build plan: https://claude.ai/artifact/FQx5QUyNeKAZAPuzWthBMH

Scope set 2026-11-04: **Chennai**, **80% food**. Both spikes are built for that.

These are the two findings that come before any real building. Either one can
tell you to build something different, and both cost a weekend.

| | Question | Gate |
| --- | --- | --- |
| Spike 1 | Can "good beef nearby" be inferred from data you can legally get? | 70% agreement with your own judgement, 7 of the model's top 10 |
| Spike 2 | Do geofence alerts actually fire, on your phone, with the app closed? | A week of transitions logged, median report delay under 2 minutes |

---

## What has and has not been verified

Built and run in a cloud container with no Google key, no Anthropic key, and a
network policy that blocks Overpass and Google. So:

- **Spike 1 pipeline: verified.** `fetch --source fixture` → `score --dry-run` →
  `grade --sheet` → `grade --compare` runs end to end, on a bundled 20-row
  synthetic fixture. The keyword baseline scores 65% against a simulated grader,
  i.e. it **fails** the gate — which is the point of having a baseline.
- **Spike 1 network paths: not run.** The Google Places and Overpass fetchers and
  both Claude calls are written but have never executed. Expect to fix something
  on first run.
- **Spike 2: not compiled.** There is no Android SDK in that container. The code
  is conservative on purpose — no Compose, no XML layouts, one dependency — but
  treat the first build as part of the spike.

---

## Spike 1 — does the scoring work

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

### Reading the result

`--compare` prints agreement, top-10 precision and rank correlation, then the
list that actually matters: **places it would have alerted you about that you
said no to**. Those are what make a person mute the app. One or two is fine;
five means the data is not there.

Run it twice, once with `--source google` and once with `--source osm`. The gap
between them is the value of the Google bill.

### A sanity check, not an answer key

Chennai places that web sources associate with beef, worth seeing near the top:
Kappa Chakka Kandhari (Nungambakkam), Vembanad (Thousand Lights), Ikkakas
(Teynampet), Travancore, Malabar Camboose, Mishwar, Puttopia. Unverified and
possibly stale — grade on your own knowledge, not this list.

---

## Spike 2 — do the alerts fire

```bash
cd spike2-geofence
# Android Studio: File > Open, let it generate the Gradle wrapper and sync.
# It will likely offer to upgrade AGP and Kotlin — accept.
```

Install on the phone you actually carry, then:

1. **Grant location permissions.** Two separate requests. On Android 11+ the
   background one opens Settings rather than a dialog — pick *Allow all the time*.
2. **Drop a fence here**, at ten or fifteen places you pass in a normal week:
   home, office, the usual junction, a couple of restaurants. No coordinates to
   type; it uses your current fix.
3. **Register all fences.**
4. Then forget about it for a week. Swipe the app away. Reboot the phone at
   least once — that is the test that matters most.

Afterwards:

```bash
adb pull /sdcard/Android/data/com.tasteradar.spike/files/fence-log.csv
```

Columns: `time, fence_id, fence_name, transition, report_delay_ms, accuracy_m, lat, lng`.

### What to look for

- **Did anything fire at all after the reboot?** If not, the `BootReceiver` is
  being killed by the OEM battery manager. That is the single most common way a
  geofencing app dies, and the fix is the battery-optimisation exemption.
- **`report_delay_ms`** is the gap between when the OS located you and when the
  app heard about it. Responsiveness is set to 0 here, i.e. as fast as Android
  will go. Whatever you see is the *best* case; production at 30–60s will be worse.
- **Missed crossings.** You know where you went. Count the places you passed that
  produced no row.
- **Duplicate ENTERs** on the same visit mean the radius is too tight for the GPS
  accuracy in that spot. 150m is the default here; central Chennai may need more.

### Deliberate choices worth knowing

- `PendingIntent` is **MUTABLE**. With `FLAG_IMMUTABLE` every event arrives empty
  and the app looks broken for no visible reason.
- `setInitialTrigger(0)` — no alert for fences you are already standing in.
- ENTER, EXIT and DWELL are all logged; DWELL after 60s. The real app will mostly
  care about DWELL, since passing a place at speed is not an opportunity.
