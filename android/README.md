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

Feedback works the same way. Tapping Going, Not now or Wrong writes to
`FeedbackQueue` synchronously and returns — no network on the tap path at all.
`FeedbackWorker` delivers it when there is a connection, with WorkManager doing
the waiting, the exponential backoff and the surviving of reboots. A tap in a
basement is recorded exactly like a tap on wifi, which matters because feedback
from a basement is feedback about an alert the local path was built for.

Three details that make the queue safe rather than merely present:

- **Retrying is only safe because the server is idempotent.** Re-delivering the
  same Wrong leaves one correction, not three — enforced by a unique index on
  `corrections`, not by hoping.
- **A 4xx is dropped, not retried.** A deleted want or an alert the server has no
  record of will never succeed, and a queue that retries it forever is a battery
  bug. Anything else — no signal, a 5xx, the server being off — is retried.
- **One pending item per place and want, newest wins.** Tapping Not now and then
  Wrong sends Wrong, not both in whatever order they happen to drain.

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

## Security

The server requires a bearer token on every request, and the app sends it. Two
things follow from that, both deliberate:

- **With no `AUTH_TOKEN` set, the server binds to `127.0.0.1` only.** "No token"
  therefore cannot quietly mean "open to the whole network" — the insecure
  configuration is unreachable by construction rather than by remembering to read
  a warning.
- **Repeated wrong tokens lock the client out**, and the app says so rather than
  reporting the server as unreachable. A 429 is treated as retryable by the
  feedback queue, not permanent — otherwise a lockout would make the queue throw
  away exactly the data it exists to protect.
- **Over plain HTTP the token is sniffable.** Cleartext is allowed for loopback
  and private ranges so you can run this at home, and the app's first screen says
  plainly when the token is crossing a network in the clear. The real fix is a
  tunnel in front of the server; `res/xml/network_security_config.xml` says how.

## Known gaps

- **Hours come from one configured zone, not per place.** `PLACES_TIMEZONE`
  (default `Asia/Kolkata`) is written onto every place the worker fetches, and
  the phone judges each place's hours in that zone rather than its own. That
  fixes the real bug — a Chennai place is still judged in Chennai time when you
  are in London. What it does not do is support places in *different* zones in
  one install, which needs a per-place zone from the Places API. I could not
  confirm that field's name from this environment, so I did not guess it into
  the worker.
- **Places that publish no hours are treated as unknown**, which never
  suppresses an alert — guessing would silence real matches. The notification now
  says "hours unknown" instead of pretending confidence.
  `refresh_places.py --report-open` shows the coverage for your own area.

## Deliberate omissions

Room, Retrofit, Moshi and Compose are all absent. The fence cache is fifteen rows
of JSON, the API is four endpoints of flat JSON, and `HttpURLConnection` and
`org.json` are in the platform. Every dependency with a code generator is a build
that can fail in a way this code had no way to test.
