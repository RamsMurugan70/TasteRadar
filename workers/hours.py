#!/usr/bin/env python3
"""Opening hours: normalise them once, evaluate them at alert time.

The wire format is a list of periods in **minutes since Sunday 00:00 local time**:

    {"periods": [{"o": 660, "c": 870}, ...]}

`c` is always greater than `o`, so a period that runs past midnight simply has a
`c` beyond the end of its day, and one that runs past Saturday midnight has a `c`
beyond 10080. That removes wrap-around as a special case everywhere downstream:
a check only has to test `t` and `t + 10080`.

Open all week is one period of 0 to 10080.

The times are local wall clock, as the source reports them. For a Chennai-only
app that is unambiguous. It breaks if you travel across a timezone, which is a
documented limitation rather than a silent bug - see android/README.md.
"""
from __future__ import annotations

import json
import re
from datetime import datetime

WEEK_MINUTES = 7 * 24 * 60

# Google's day numbering: 0 = Sunday.
_OSM_DAYS = {"su": 0, "mo": 1, "tu": 2, "we": 3, "th": 4, "fr": 5, "sa": 6}


def minutes_since_sunday(dt: datetime) -> int:
    """Python's weekday() is 0 = Monday, so Sunday has to come back round to 0."""
    day = (dt.weekday() + 1) % 7
    return day * 24 * 60 + dt.hour * 60 + dt.minute


def _period(open_day: int, open_min: int, close_day: int, close_min: int) -> dict:
    o = open_day * 1440 + open_min
    c = close_day * 1440 + close_min
    if c <= o:
        c += WEEK_MINUTES  # closes after midnight, or after Saturday midnight
    return {"o": o, "c": c}


def normalise_google(regular_opening_hours: dict | None) -> dict | None:
    """From the Places API `regularOpeningHours` field."""
    if not regular_opening_hours:
        return None
    periods = regular_opening_hours.get("periods") or []
    out = []
    for p in periods:
        open_ = p.get("open")
        if not open_:
            continue
        close = p.get("close")
        if close is None:
            # Google marks an always-open place as an open with no close.
            return {"periods": [{"o": 0, "c": WEEK_MINUTES}]}
        out.append(
            _period(
                int(open_.get("day", 0)),
                int(open_.get("hour", 0)) * 60 + int(open_.get("minute", 0)),
                int(close.get("day", 0)),
                int(close.get("hour", 0)) * 60 + int(close.get("minute", 0)),
            )
        )
    return {"periods": out} if out else None


def normalise_osm(opening_hours: str | None) -> dict | None:
    """
    A deliberately small subset of OSM's opening_hours grammar: "24/7", and
    rules like "Mo-Fr 09:00-18:00; Sa 10:00-14:00".

    The full grammar has holidays, months, week numbers, sunset offsets and
    comments. Anything this does not understand returns None, which reads as
    "hours unknown" downstream and never as "closed" - guessing here would
    suppress real alerts.
    """
    if not opening_hours:
        return None
    text = opening_hours.strip().lower()
    if text in ("24/7", "mo-su 00:00-24:00"):
        return {"periods": [{"o": 0, "c": WEEK_MINUTES}]}

    periods: list[dict] = []
    for rule in text.split(";"):
        rule = rule.strip()
        if not rule:
            continue
        m = re.fullmatch(
            r"((?:[a-z]{2}(?:-[a-z]{2})?)(?:,[a-z]{2}(?:-[a-z]{2})?)*)\s+"
            r"(\d{1,2}):(\d{2})\s*-\s*(\d{1,2}):(\d{2})",
            rule,
        )
        if not m:
            return None  # one unparsed rule invalidates the whole string
        day_spec, h1, m1, h2, m2 = m.groups()
        open_min = int(h1) * 60 + int(m1)
        close_min = int(h2) * 60 + int(m2)

        days: list[int] = []
        for part in day_spec.split(","):
            if "-" in part:
                a, b = part.split("-")
                if a not in _OSM_DAYS or b not in _OSM_DAYS:
                    return None
                start, end = _OSM_DAYS[a], _OSM_DAYS[b]
                day = start
                while True:
                    days.append(day)
                    if day == end:
                        break
                    day = (day + 1) % 7
            else:
                if part not in _OSM_DAYS:
                    return None
                days.append(_OSM_DAYS[part])

        for day in days:
            close_day = day if close_min > open_min else (day + 1) % 7
            periods.append(_period(day, open_min, close_day, close_min))

    return {"periods": periods} if periods else None


def is_open_at(hours: dict | None, at: datetime) -> bool | None:
    """None means the hours are unknown, which is not the same as closed."""
    if not hours or not hours.get("periods"):
        return None
    t = minutes_since_sunday(at)
    return any(p["o"] <= t < p["c"] or p["o"] <= t + WEEK_MINUTES < p["c"] for p in hours["periods"])


def minutes_until_close(hours: dict | None, at: datetime) -> int | None:
    """Minutes left in the period currently open, or None if unknown or closed."""
    if not hours or not hours.get("periods"):
        return None
    t = minutes_since_sunday(at)
    remaining = []
    for p in hours["periods"]:
        for candidate in (t, t + WEEK_MINUTES):
            if p["o"] <= candidate < p["c"]:
                remaining.append(p["c"] - candidate)
    return min(remaining) if remaining else None


def dumps(hours: dict | None) -> str | None:
    return json.dumps(hours, separators=(",", ":")) if hours else None
