"""Captures the running server's own API responses as the demo build's fixtures.

The demo has to behave exactly like the real dashboard, so its data is not written by
hand: it is the real API's output, taken from a server seeded by `server/seed-demo.py`.
Anything hand-written would drift from the response shapes the screens actually read.

The demo is published on the open web, so this refuses to run against a server that is
serving anything other than the example contracts in `contracts/`. A deployment pointed
at a plant's real contracts serves that plant's inspection stages, defect codes and line
designations, and none of that belongs in a published page. Refusing is deliberate:
scrubbing names out of the responses afterwards is the sort of filter that quietly
misses one.

    python server/seed-demo.py          # fill a plant (within its five-minute window)
    python web/demo/capture.py          # take its API and write the fixtures

Writes web/src/demo/data/fixtures.json.
"""

import json
import os
import sys
import urllib.error
import urllib.request
from datetime import datetime

BASE = (sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:8080/dashboard").rstrip("/")
HERE = os.path.dirname(os.path.abspath(__file__))
WEB = os.path.dirname(HERE)
REPO = os.path.dirname(WEB)
OUT = os.path.join(WEB, "src", "demo", "data")

# The windows the grid's filter offers, so every one of them works in the demo.
WINDOWS = [None, 5, 10, 30, 60, 120]
# Enough rows to scroll, filter and open, without carrying a shift of them in the bundle.
MAX_EVENTS = 60


def get(path):
    with urllib.request.urlopen(f"{BASE}/api{path}", timeout=30) as response:
        return json.loads(response.read())


def contract(name):
    with open(os.path.join(REPO, "contracts", name), encoding="utf-8") as f:
        return json.load(f)


def refuse_unless_example(catalog):
    """The published demo may only ever carry the example vocabulary."""
    example = contract("vision-catalog.json")
    topology = contract("topology.json")

    served = sorted(v["key"] for v in catalog["visionTypes"])
    expected = sorted(v["key"] for v in example["visionTypes"])
    if served != expected:
        raise SystemExit(
            "The server is not serving the example catalog, so capturing would publish "
            "a real plant's vocabulary.\n"
            f"  serving : {served}\n  expected: {expected}\n"
            "Point the server at contracts/vision-catalog.json and try again."
        )
    if sorted(catalog["lines"]) != sorted(topology["lines"]):
        raise SystemExit(
            "The server is not serving the example topology.\n"
            f"  serving : {sorted(catalog['lines'])}\n"
            f"  expected: {sorted(topology['lines'])}"
        )


def main():
    catalog = get("/catalog")
    refuse_unless_example(catalog)

    os.makedirs(OUT, exist_ok=True)
    lines = list(catalog["lines"])
    keys = [v["key"] for v in catalog["visionTypes"]]

    bundle = {
        # Every timestamp in here is fixed at the moment of capture. The demo shifts the
        # whole set forward on load, so the fleet always reads as running now rather
        # than as a plant that stopped on the day the fixtures were taken.
        "capturedAt": datetime.now().astimezone().isoformat(timespec="seconds"),
        "catalog": catalog,
        "grid": {},
        "detail": {},
        # One occurrence per vision type is enough: the demo builds the rest from the
        # table row that was opened, so every row opens.
        "occurrence": {},
        "settings": get("/settings"),
    }

    for window in WINDOWS:
        grid = get("/grid" if window is None else f"/grid?windowMinutes={window}")
        bundle["grid"][str(window or "lot")] = grid
        print(f"  grid window={window or 'lot'}")

    for line in lines:
        for key in keys:
            detail = get(f"/vision/{line}/{key}")
            events = detail.get("events") or []
            if len(events) > MAX_EVENTS:
                detail["events"] = events[:MAX_EVENTS]
            bundle["detail"][f"{line}/{key}"] = detail

            if key not in bundle["occurrence"] and events:
                try:
                    bundle["occurrence"][key] = get(
                        f"/vision/{line}/{key}/defects/{events[0]['id']}")
                except urllib.error.HTTPError:
                    pass
        print(f"  detail {line}: {len(keys)} inspectors")

    path = os.path.join(OUT, "fixtures.json")
    with open(path, "w", encoding="utf-8") as f:
        json.dump(bundle, f, separators=(",", ":"))

    print(f"\nwrote {path}  ({os.path.getsize(path) / 1024:.0f} KB)")


if __name__ == "__main__":
    main()
