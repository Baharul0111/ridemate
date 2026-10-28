"""Time sources. Production uses SystemClock; tests drive FakeClock. All display times are IST."""

from __future__ import annotations

import time
from datetime import datetime, timedelta, timezone
from typing import Protocol

IST = timezone(timedelta(hours=5, minutes=30), name="IST")


class Clock(Protocol):
    def now_utc(self) -> datetime: ...

    def monotonic(self) -> float: ...


class SystemClock:
    def now_utc(self) -> datetime:
        return datetime.now(timezone.utc)

    def monotonic(self) -> float:
        return time.monotonic()


class FakeClock:
    def __init__(self, start: datetime | None = None) -> None:
        self._now = start or datetime(2026, 10, 28, 6, 0, tzinfo=timezone.utc)
        self._mono = 1000.0

    def now_utc(self) -> datetime:
        return self._now

    def monotonic(self) -> float:
        return self._mono

    def advance(self, seconds: float) -> None:
        self._now += timedelta(seconds=seconds)
        self._mono += seconds


def ist_day(dt: datetime) -> str:
    """The IST calendar day (YYYY-MM-DD) used for daily quotas and caps."""
    return dt.astimezone(IST).strftime("%Y-%m-%d")


def next_ist_midnight(dt: datetime) -> datetime:
    local = dt.astimezone(IST)
    nxt = (local + timedelta(days=1)).replace(hour=0, minute=0, second=0, microsecond=0)
    return nxt.astimezone(timezone.utc)


def ist_hhmm(dt: datetime) -> str:
    """'hh:mm' in IST, 24-hour, for "AI reading resumes at hh:mm (IST)" (the phone renders 12-hour)."""
    return dt.astimezone(IST).strftime("%H:%M")
