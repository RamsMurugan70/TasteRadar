# The app

The thing you carry. It holds one umbrella fence and up to fifteen place fences,
asks the server for a new set whenever you leave the umbrella, and decides
locally whether a crossing is worth interrupting you for.

**Not compiled.** There was no Android SDK where this was written, so the first
build is part of the job. The code is deliberately conservative to make that
first build likely: two dependencies, no Compose, no XML layouts, no annotation
processors. What *was* checked is that delimiters balance and that every
cross-object call resolves to a real declaration — which catches typos, not
type errors.

## How a crossing becomes an alert

```
leave the umbrella  ->  GeofenceReceiver  ->  POST /fence-set  ->  cache + register
enter a place fence ->  GeofenceReceiver  ->  Gates.check      ->  notification
```

The alert path reads the cache and nothing else. By the time you reach a place
its name, score and reason are already on the device, so an alert fires in a
basement with no signal. The server is told about it afterwards, and if that
fails the alert still happened.

ENTER tries first and DWELL tries again. That second attempt is what rescues the
case where you drove past, the speed gate suppressed it, and then you parked.

## Which gates live here

The phone owns the checks that depend on *this moment*; the server already
applied the ones that depend on history.

| Here, in `Gates.kt` | On the server |
| --- | --- |
| Quiet hours, 22:30–08:00 | Score threshold |
| Speed above 25 km/h | 30-day place cooldown |
| Outside mealtimes | 90 days after a *Not now* |
| Two alerts a day, 90 minutes apart | Permanent exclusion after a *Wrong* |
| | Within 300 m of home or work |

## Trying it

Start the server and give it something to match, from `../server` and `../workers`:

```bash
npm run build && npm run migrate && npm run seed && npm start
```

Then find your machine's LAN address — `10.0.2.2` only works from the emulator:

```bash
ip route get 1.1.1.1 | awk '{print $7; exit}'
```

In the app: set the server URL, press **Save and check**, grant permissions in
the order it asks, and add a want. A new want has no scores yet, so score it:

```bash
python3 score_places.py --db ../server/taste-radar.db --dry-run --reviews-from ../spike1-scoring/fixture_candidates.json
```

Now press **Redraw around me now**. With the fixture seeded you are unlikely to
be standing in Nungambakkam, so expect zero fences unless you are — point
`refresh_places.py` at where you actually live to see it work.

## Known gaps

- **Hours are only as good as the source.** The open-now gate works, but places
  with no published hours are treated as *unknown*, which never suppresses an
  alert — guessing would silence real matches. `refresh_places.py --report-open`
  prints what is known, so you can see the coverage for your own area.
- **Hours are local wall clock.** For a Chennai-only app that is unambiguous.
  Travel across a timezone and the gate reads the wrong hours; fixing it means
  storing an IANA zone per place and comparing in that zone.
- **Feedback is not queued.** If a *Going* tap happens with no signal it is
  logged and dropped. A retry queue is worth building once the app is in daily
  use and the loss is measurable, not before.
- **No authentication anywhere.** The server trusts whoever can reach it, and the
  app sends no credential. Keep it on your own network.
- **Cleartext HTTP is permitted for private address ranges only**, via
  `res/xml/network_security_config.xml`. Put the server behind HTTPS and that
  file can go.

## Deliberate omissions

Room, Retrofit, Moshi and Compose are all absent. The fence cache is fifteen rows
of JSON, the API is four endpoints of flat JSON, and `HttpURLConnection` and
`org.json` are in the platform. Every dependency with a code generator is a build
that can fail in a way this code had no way to test.
