# Taste Radar

An Android app that notices where you are and taps you on the shoulder when you pass
something that fits what you have been wanting.

You type a want in plain words — *"craving a proper beef dish, not too expensive"* — and
forget about it. Days later, walking through Nungambakkam, your phone buzzes once:
*beef ularthiyathu, 300 m away, 4.4★, open till 11.* You tap it and Maps opens.

It is not a search app. You never open it to look something up. You open it to say what you
want, and then it interrupts you.

**Spec and build plan:** https://claude.ai/artifact/FQx5QUyNeKAZAPuzWthBMH

**Scope:** Chennai, 80% food.

---

## Status

The **server and its workers run today** — seed it, score it, and `POST /fence-set` returns
scored matches with reasons, verified against a live server. The **app is written but has never
been compiled**: there was no Android SDK where it was built, so treat the first build as part
of the work.

Two experiments still gate whether any of it is worth finishing, and either can say no.

| | Question it answers | Gate it has to clear |
| --- | --- | --- |
| [Spike 1](spike1-scoring/) | Can "good beef nearby" be inferred from data you can legally get? | 70% agreement with your own judgement, 7 of the model's top 10 |
| [Spike 2](spike2-geofence/) | Do geofence alerts actually fire, on your phone, with the app closed? | A week of transitions logged, median report delay under 2 minutes |

Spike 1 costs an evening and a Google Places key. Spike 2 costs a week of carrying your
phone, so start it first and let it run while you do the other.

---

## How it is meant to work

The phone holds **one large fence** around wherever you are, plus up to fifteen fences on
places that match your wants. Leave the big one and the OS wakes the app, which asks the
server what fits nearby and re-draws the set. Enter a small one and nine suppression checks
run on the device before anything is allowed to make a sound.

Two constraints shape everything. Android caps an app at 100 geofences, so you cannot fence
a city and the set has to follow you around. And **no maps API knows what is on a menu** —
"serves good beef" has to be inferred from place type, cuisine tags and review text, scored
on a server and cached, never worked out while you are walking.

The rule that makes it viable: the language model never runs in the alert path. By the time
you are on the street the answer is already a row in a table, and the phone is only doing
geometry.

---

## Layout

| Path | What it is |
| --- | --- |
| [`android/`](android/) | Kotlin. The app you carry: the umbrella-fence cycle, the local alert path, the three feedback buttons. |
| [`server/`](server/) | TypeScript + Fastify + SQLite. Compiles wants, holds the places, answers *what should I watch around here?* |
| [`workers/`](workers/) | Python. Nightly place refresh and batch scoring — everything that costs money, kept out of the alert path. |
| [`spike1-scoring/`](spike1-scoring/) | Python. Fetch Chennai restaurants, score them against a free-text want, grade the result blind against your own judgement. |
| [`spike2-geofence/`](spike2-geofence/) | A deliberately boring Android app. Drop fences at your current location, carry it for a week, read the CSV. |

---

## What has and has not been verified

Written in a cloud container with no Google key, no Anthropic key, and a network policy that
blocked both Overpass and Google. So:

- **Spike 1 pipeline: verified.** `fetch --source fixture` → `score --dry-run` →
  `grade --sheet` → `grade --compare` runs end to end against a bundled 20-row synthetic
  fixture. The keyword baseline scores 65% there, i.e. it **fails** the gate — which is
  exactly what a baseline is for.
- **Spike 1 network paths: never executed.** The Google Places and Overpass fetchers and both
  Claude calls are written but have not run. Expect to fix something on the first real run.
- **Spike 2: never compiled.** There was no Android SDK in that container. The code is
  conservative on purpose — no Compose, no XML layouts, one dependency — but treat the first
  build as part of the spike.
