#!/usr/bin/env python3
"""Validate the user-approved staging duration and Singapore shutdown time."""
import json
import math
import sys
from datetime import datetime
from zoneinfo import ZoneInfo


def build_window(hours, end_text, now=None):
    hours = float(hours)
    if not math.isfinite(hours) or not 0 < hours <= 24:
        raise ValueError("The approved staging window must be greater than zero and no more than 24 hours.")
    zone = ZoneInfo("Asia/Singapore")
    now = now or datetime.now(zone)
    if now.tzinfo is None:
        raise ValueError("The current time must include a time zone.")
    end = datetime.strptime(end_text, "%Y-%m-%d %H:%M").replace(tzinfo=zone)
    remaining = (end - now.astimezone(zone)).total_seconds() / 3600
    if remaining <= 0 or hours > remaining:
        raise ValueError("The confirmed staging shutdown time must be in the future and cover the requested window.")
    return {"startedAtSgt": now.astimezone(zone).isoformat(), "plannedEndSgt": end.isoformat(), "approvedHours": hours}


if __name__ == "__main__":
    try:
        if len(sys.argv) != 3:
            raise ValueError("Usage: validate_window.py HOURS 'YYYY-MM-DD HH:MM'")
        print(json.dumps(build_window(sys.argv[1], sys.argv[2]), indent=2))
    except ValueError as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        raise SystemExit(2)
