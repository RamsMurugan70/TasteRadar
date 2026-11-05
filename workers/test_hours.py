#!/usr/bin/env python3
"""Run: python3 -m unittest test_hours -v"""
import unittest
from datetime import datetime

from hours import (
    WEEK_MINUTES,
    is_open_at,
    minutes_since_sunday,
    minutes_until_close,
    normalise_google,
    normalise_osm,
)

# 2026-11-05 is a Thursday.
THU_1200 = datetime(2026, 11, 5, 12, 0)
THU_2300 = datetime(2026, 11, 5, 23, 0)
FRI_0100 = datetime(2026, 11, 6, 1, 0)
SUN_1200 = datetime(2026, 11, 8, 12, 0)
SAT_2330 = datetime(2026, 11, 7, 23, 30)


class TestClock(unittest.TestCase):
    def test_sunday_is_day_zero(self):
        self.assertEqual(minutes_since_sunday(datetime(2026, 11, 8, 0, 0)), 0)
        self.assertEqual(minutes_since_sunday(datetime(2026, 11, 8, 12, 0)), 720)

    def test_thursday_offset(self):
        # Sunday 0, Monday 1 ... Thursday 4.
        self.assertEqual(minutes_since_sunday(THU_1200), 4 * 1440 + 720)


class TestGoogle(unittest.TestCase):
    def test_absent_hours(self):
        self.assertIsNone(normalise_google(None))
        self.assertIsNone(normalise_google({}))

    def test_simple_day(self):
        h = normalise_google({"periods": [
            {"open": {"day": 4, "hour": 11, "minute": 0},
             "close": {"day": 4, "hour": 23, "minute": 0}}
        ]})
        self.assertEqual(h, {"periods": [{"o": 4 * 1440 + 660, "c": 4 * 1440 + 1380}]})
        self.assertTrue(is_open_at(h, THU_1200))
        self.assertFalse(is_open_at(h, SUN_1200))

    def test_always_open(self):
        h = normalise_google({"periods": [{"open": {"day": 0, "hour": 0, "minute": 0}}]})
        self.assertEqual(h, {"periods": [{"o": 0, "c": WEEK_MINUTES}]})
        for when in (THU_1200, SUN_1200, FRI_0100, SAT_2330):
            self.assertTrue(is_open_at(h, when))

    def test_closes_after_midnight(self):
        # Thursday 18:00 to Friday 01:30 - the case that breaks naive code.
        h = normalise_google({"periods": [
            {"open": {"day": 4, "hour": 18, "minute": 0},
             "close": {"day": 5, "hour": 1, "minute": 30}}
        ]})
        self.assertTrue(is_open_at(h, THU_2300))
        self.assertTrue(is_open_at(h, FRI_0100))
        self.assertFalse(is_open_at(h, THU_1200))

    def test_saturday_night_wraps_into_sunday(self):
        # Saturday 22:00 to Sunday 02:00 crosses the end of the week.
        h = normalise_google({"periods": [
            {"open": {"day": 6, "hour": 22, "minute": 0},
             "close": {"day": 0, "hour": 2, "minute": 0}}
        ]})
        self.assertTrue(is_open_at(h, SAT_2330))
        self.assertTrue(is_open_at(h, datetime(2026, 11, 8, 1, 0)))
        self.assertFalse(is_open_at(h, datetime(2026, 11, 8, 3, 0)))


class TestOsm(unittest.TestCase):
    def test_24_7(self):
        self.assertEqual(normalise_osm("24/7"), {"periods": [{"o": 0, "c": WEEK_MINUTES}]})

    def test_weekday_range(self):
        h = normalise_osm("Mo-Fr 09:00-18:00")
        self.assertTrue(is_open_at(h, THU_1200))
        self.assertFalse(is_open_at(h, SUN_1200))

    def test_several_rules(self):
        h = normalise_osm("Mo-Fr 09:00-18:00; Sa 10:00-14:00")
        self.assertTrue(is_open_at(h, datetime(2026, 11, 7, 11, 0)))   # Saturday
        self.assertFalse(is_open_at(h, datetime(2026, 11, 7, 15, 0)))

    def test_comma_separated_days(self):
        h = normalise_osm("Mo,We,Fr 10:00-12:00")
        self.assertTrue(is_open_at(h, datetime(2026, 11, 6, 11, 0)))    # Friday
        self.assertFalse(is_open_at(h, THU_1200))                       # Thursday

    def test_range_wrapping_the_week(self):
        h = normalise_osm("Sa-Su 10:00-20:00")
        self.assertTrue(is_open_at(h, SUN_1200))
        self.assertFalse(is_open_at(h, THU_1200))

    def test_unparsed_grammar_is_unknown_not_closed(self):
        for weird in ("Mo-Fr 09:00-18:00; PH off", "sunrise-sunset", "Mo-Fr", ""):
            self.assertIsNone(normalise_osm(weird), weird)


class TestClosingSoon(unittest.TestCase):
    def setUp(self):
        self.h = normalise_google({"periods": [
            {"open": {"day": 4, "hour": 11, "minute": 0},
             "close": {"day": 4, "hour": 23, "minute": 0}}
        ]})

    def test_minutes_until_close(self):
        self.assertEqual(minutes_until_close(self.h, THU_1200), 11 * 60)
        self.assertEqual(minutes_until_close(self.h, datetime(2026, 11, 5, 22, 45)), 15)

    def test_closed_returns_none(self):
        self.assertIsNone(minutes_until_close(self.h, SUN_1200))

    def test_unknown_returns_none(self):
        self.assertIsNone(minutes_until_close(None, THU_1200))
        self.assertIsNone(is_open_at(None, THU_1200))


if __name__ == "__main__":
    unittest.main()
