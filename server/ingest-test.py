"""End-to-end check of the ingest path against a running server + dev DB.

Asserts the properties the whole redesign rests on, with real HTTP and real SQL
rather than by reading the code:
  - one physical unit counts once no matter how many items it failed
  - a replayed batch changes nothing
  - counters and the trend rollup always agree
  - a lot change resets counters and closes the old lot into history with its totals
  - judgements are data (1 for pouch align, 3 for example_b) with no schema change
  - a bad envelope is refused instead of stored under an unreachable identity

  python server/ingest-test.py [base_url]
"""

import os
import json
import socket
import subprocess
import sys
import urllib.error
import urllib.request
from datetime import datetime, timedelta, timezone

BASE = (sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:8080/dashboard").rstrip("/")
MYSQL = os.environ.get("VISIONDASH_MYSQL", r"C:\Program Files\MySQL\MySQL Server 8.4\bin\mysql.exe")
DB = "visiondash"
# Credentials come from the environment, never from this file. MYSQL_PWD is the mysql
# client's own variable, so the password reaches it without ever appearing in a command
# line, a process listing, or a commit.
DB_USER = os.environ.get("VISIONDASH_DB_USER", "root")
if "VISIONDASH_DB_PASSWORD" not in os.environ:
    sys.exit("Set VISIONDASH_DB_PASSWORD (your MySQL password) before running this.")
MYSQL_ENV = {**os.environ, "MYSQL_PWD": os.environ["VISIONDASH_DB_PASSWORD"]}

KST = timezone(timedelta(hours=-4))  # dev PC local offset; only relative order matters here

failures = []


def check(label, actual, expected):
    ok = actual == expected
    print(f"  {'PASS' if ok else 'FAIL'}  {label}: {actual}" + ("" if ok else f"  (expected {expected})"))
    if not ok:
        failures.append(label)


def sql(query):
    out = subprocess.run([MYSQL, "-u", DB_USER, "-N", "-B", DB, "-e", query],
                         capture_output=True, text=True, env=MYSQL_ENV)
    if out.returncode != 0:
        raise RuntimeError(out.stderr.strip())
    return [line.split("\t") for line in out.stdout.strip().splitlines() if line]


def scalar(query, default=0):
    rows = sql(query)
    if not rows or rows[0][0] == "NULL":
        return default
    value = rows[0][0]
    try:
        return int(value)
    except ValueError:
        return value


def post(payload):
    body = json.dumps(payload).encode()
    req = urllib.request.Request(f"{BASE}/api/ingest/events", data=body,
                                 headers={"Content-Type": "application/json"}, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            return resp.status, json.loads(resp.read())
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()[:200]


def ts(offset_seconds):
    return (datetime(2026, 8, 25, 14, 0, 0, tzinfo=KST) + timedelta(seconds=offset_seconds)).isoformat()


def unit(seq, judgement, lot, items=None, images=None, at=None):
    return {
        "type": "UNIT_INSPECTED",
        "sourceFile": "MDL_20260825.csv",
        "unitSeq": seq,
        "lotId": lot,
        "modelId": "MDL",
        "cellId": f"CELL-{seq:05d}",
        "judgement": judgement,
        "occurredAt": at or ts(seq),
        "items": items or [],
        "images": images or [],
        "warnings": [],
    }


def batch(line, vision_key, events):
    return {
        "agentId": f"{line}_{vision_key}",
        "line": line,
        "visionKey": vision_key,
        "agentVersion": "test",
        "sentAt": ts(0),
        "events": events,
    }


def cleanup():
    for table, column in [("raw_events", "agent_id"), ("alarms", "agent_id"),
                          ("lot_history_judgement", None), ("defect_occurrences", "agent_id"),
                          ("lot_history", "agent_id"), ("vision_rollup_judgement", "agent_id"),
                          ("vision_rollup", "agent_id"), ("lot_counter_judgement", "agent_id"),
                          ("lot_counters", "agent_id"), ("agent_lot_progress", "agent_id"),
                          ("agents", "agent_id")]:
        if column is None:
            sql("DELETE FROM lot_history_judgement WHERE lot_history_id IN "
                "(SELECT id FROM lot_history WHERE agent_id IN ('C-2_EXAMPLE_D','C-2_EXAMPLE_B_CATHODE'))")
        else:
            sql(f"DELETE FROM {table} WHERE {column} IN ('C-2_EXAMPLE_D','C-2_EXAMPLE_B_CATHODE')")


AGENT = "C-2_EXAMPLE_D"
IMAGES = [
    {"set": "LEFT", "kind": "MAIN", "path": "F:\\img\\a.jpg"},
    {"set": "LEFT", "kind": "OVERLAY", "path": "F:\\img\\a_ov.jpg"},
    {"set": "RIGHT", "kind": "MAIN", "path": "F:\\img\\b.jpg"},
    # An empty overlay path on purpose - it must be dropped without taking the
    # RIGHT main image down with it.
    {"set": "RIGHT", "kind": "OVERLAY", "path": ""},
]

print("Cleaning previous test data...")
cleanup()

print("\n[1] one unit = one count, however many items it failed")
first = batch("C-2", "EXAMPLE_D", [
    unit(1, "OK", "LOT-A"),
    unit(2, "OK", "LOT-A"),
    unit(3, "NG", "LOT-A", items=[
        {"name": "CHECK_A", "rawValue": "14.16007"},
        {"name": "CHECK_B", "rawValue": "24.59380"},
        {"name": "CHECK_C", "rawValue": "NG"},
    ], images=IMAGES),
    unit(4, "OK", "LOT-A"),
    unit(5, "NG", "LOT-A", items=[{"name": "CHECK_A", "rawValue": "9.11"}]),
])
status, result = post(first)
check("HTTP status", status, 200)
check("accepted", result["accepted"], 5)
check("inspected_count", scalar(f"SELECT inspected_count FROM lot_counters WHERE agent_id='{AGENT}'"), 5)
check("defect_unit_count (3-item unit counts once)",
      scalar(f"SELECT defect_unit_count FROM lot_counters WHERE agent_id='{AGENT}'"), 2)
check("defect_occurrences rows", scalar(f"SELECT COUNT(*) FROM defect_occurrences WHERE agent_id='{AGENT}'"), 2)
check("defect_items rows", scalar(
    f"SELECT COUNT(*) FROM defect_items i JOIN defect_occurrences o ON o.id=i.occurrence_id WHERE o.agent_id='{AGENT}'"), 4)
check("item_summary of the 3-item unit", scalar(
    f"SELECT item_summary FROM defect_occurrences WHERE agent_id='{AGENT}' AND unit_seq=3", ""),
      "CHECK_A + CHECK_B + CHECK_C")
check("raw measurements kept as numbers", scalar(
    f"SELECT COUNT(*) FROM defect_items i JOIN defect_occurrences o ON o.id=i.occurrence_id "
    f"WHERE o.agent_id='{AGENT}' AND i.value_num IS NOT NULL"), 3)

print("\n[2] images: one row per file, blank paths dropped, no duplication across items")
check("image rows for the 3-item unit", scalar(
    f"SELECT COUNT(*) FROM defect_images im JOIN defect_occurrences o ON o.id=im.occurrence_id "
    f"WHERE o.agent_id='{AGENT}' AND o.unit_seq=3"), 3)
check("RIGHT main survived the empty overlay", scalar(
    f"SELECT COUNT(*) FROM defect_images im JOIN defect_occurrences o ON o.id=im.occurrence_id "
    f"WHERE o.agent_id='{AGENT}' AND o.unit_seq=3 AND im.set_label='RIGHT' AND im.kind='MAIN'"), 1)
check("all queued as pending with a retry deadline", scalar(
    f"SELECT COUNT(*) FROM defect_images im JOIN defect_occurrences o ON o.id=im.occurrence_id "
    f"WHERE o.agent_id='{AGENT}' AND im.state='pending' AND im.expires_at IS NOT NULL"), 3)

print("\n[3] counters and the trend rollup agree")
check("rollup inspected", scalar(
    f"SELECT SUM(inspected_count) FROM vision_rollup WHERE agent_id='{AGENT}'"), 5)
check("rollup defects", scalar(
    f"SELECT SUM(defect_unit_count) FROM vision_rollup WHERE agent_id='{AGENT}'"), 2)
check("rollup judgement breakdown sums to defects", scalar(
    f"SELECT SUM(unit_count) FROM vision_rollup_judgement WHERE agent_id='{AGENT}'"), 2)

print("\n[4] replaying the identical batch changes nothing")
status, replay = post(first)
check("HTTP status", status, 200)
check("accepted", replay["accepted"], 0)
check("replayed", replay["replayed"], 5)
check("inspected_count unchanged", scalar(f"SELECT inspected_count FROM lot_counters WHERE agent_id='{AGENT}'"), 5)
check("defect_unit_count unchanged", scalar(f"SELECT defect_unit_count FROM lot_counters WHERE agent_id='{AGENT}'"), 2)
check("occurrences unchanged", scalar(f"SELECT COUNT(*) FROM defect_occurrences WHERE agent_id='{AGENT}'"), 2)
check("replay was recorded for inspection", scalar(
    f"SELECT replayed_count FROM agent_lot_progress WHERE agent_id='{AGENT}'"), 5)

print("\n[5] lot change resets counters and closes the old lot with its totals")
status, lotres = post(batch("C-2", "EXAMPLE_D", [
    {"type": "LOT_CHANGED", "sourceFile": "MDL_20260825.csv", "oldLotId": "LOT-A",
     "newLotId": "LOT-B", "detectedAtUnitSeq": 6, "occurredAt": ts(60)},
    unit(6, "OK", "LOT-B", at=ts(61)),
    unit(7, "OK", "LOT-B", at=ts(62)),
]))
check("HTTP status", status, 200)
check("current lot", scalar(f"SELECT lot_id FROM lot_counters WHERE agent_id='{AGENT}'", ""), "LOT-B")
check("counters reset to the new lot only",
      scalar(f"SELECT inspected_count FROM lot_counters WHERE agent_id='{AGENT}'"), 2)
check("new lot has no defects yet",
      scalar(f"SELECT defect_unit_count FROM lot_counters WHERE agent_id='{AGENT}'"), 0)
check("LOT-A archived with its inspected total", scalar(
    f"SELECT inspected_count FROM lot_history WHERE agent_id='{AGENT}' AND lot_id='LOT-A'"), 5)
check("LOT-A archived with its defect total", scalar(
    f"SELECT defect_unit_count FROM lot_history WHERE agent_id='{AGENT}' AND lot_id='LOT-A'"), 2)
check("LOT-A judgement breakdown archived", scalar(
    f"SELECT unit_count FROM lot_history_judgement j JOIN lot_history h ON h.id=j.lot_history_id "
    f"WHERE h.agent_id='{AGENT}' AND h.lot_id='LOT-A' AND j.judgement='NG'"), 2)
check("rollup still holds both lots separately", scalar(
    f"SELECT COUNT(DISTINCT lot_id) FROM vision_rollup WHERE agent_id='{AGENT}'"), 2)

print("\n[5b] NO restarts at 1 with the new lot, in the same file")
# The failure this reproduces: the vision software numbers rows against the lot, so NO
# drops back to 1 mid-file on every lot change. Replay filtering keyed by file read that
# as "already seen" and discarded the entire fleet's output from the first lot change
# onward - agents reporting healthy, dashboard stuck at zero, nothing in any log but a
# rising replayed count.
status, reset = post(batch("C-2", "EXAMPLE_D", [
    {"type": "LOT_CHANGED", "sourceFile": "MDL_20260825.csv", "oldLotId": "LOT-B",
     "newLotId": "LOT-C", "detectedAtUnitSeq": 1, "occurredAt": ts(120)},
    unit(1, "OK", "LOT-C", at=ts(121)),
    unit(2, "NG", "LOT-C", items=[{"name": "CHECK_A", "rawValue": "7.7"}], at=ts(122)),
    unit(3, "OK", "LOT-C", at=ts(123)),
]))
check("HTTP status", status, 200)
check("units below the previous lot's high-water are accepted", reset["accepted"], 4)
check("none discarded as replays", reset["replayed"], 0)
check("current lot", scalar(f"SELECT lot_id FROM lot_counters WHERE agent_id='{AGENT}'", ""), "LOT-C")
check("counted from the new lot's NO 1", scalar(
    f"SELECT inspected_count FROM lot_counters WHERE agent_id='{AGENT}'"), 3)
check("its defect stored despite reusing NO 2", scalar(
    f"SELECT COUNT(*) FROM defect_occurrences WHERE agent_id='{AGENT}' AND lot_id='LOT-C' AND unit_seq=2"), 1)
# The same NO in the same file, in two different lots, is two different units.
check("the earlier lot's NO 2 is still there", scalar(
    f"SELECT COUNT(*) FROM defect_occurrences WHERE agent_id='{AGENT}' AND unit_seq=2"), 1)
check("progress is tracked per lot", scalar(
    f"SELECT COUNT(*) FROM agent_lot_progress WHERE agent_id='{AGENT}'"), 3)

print("\n[5c] replays are still caught within a lot")
status, again = post(batch("C-2", "EXAMPLE_D", [
    unit(1, "OK", "LOT-C", at=ts(121)),
    unit(2, "NG", "LOT-C", items=[{"name": "CHECK_A", "rawValue": "7.7"}], at=ts(122)),
]))
check("replayed", again["replayed"], 2)
check("accepted", again["accepted"], 0)
check("counters unchanged", scalar(
    f"SELECT inspected_count FROM lot_counters WHERE agent_id='{AGENT}'"), 3)

print("\n[5d] a lot closed while it was still running comes back")
# The guard against replaying an old lot used to drop every unit for any lot that had
# ever been closed. When a stray lot change closed the lot that was actually running,
# that inspector stopped counting for the rest of the shift and there was no way back
# short of the next lot change - which is exactly what happened on the floor.
status, _ = post(batch("C-2", "EXAMPLE_D", [
    {"type": "LOT_CHANGED", "sourceFile": "MDL_20260825.csv", "oldLotId": "LOT-C",
     "newLotId": "LOT-D", "detectedAtUnitSeq": 1, "occurredAt": ts(200)},
    unit(1, "OK", "LOT-D", at=ts(201)),
    unit(2, "OK", "LOT-D", at=ts(202)),
]))
check("lot D running", scalar(
    f"SELECT inspected_count FROM lot_counters WHERE agent_id='{AGENT}'"), 2)

# A stray change to a lot that already ended: refused, so the live counters survive.
status, stray = post(batch("C-2", "EXAMPLE_D", [{"type": "LOT_CHANGED", "sourceFile": "MDL_20260825.csv", "oldLotId": "LOT-D",
     "newLotId": "LOT-C", "detectedAtUnitSeq": 1, "occurredAt": ts(203)}]))
check("the stray change is refused", stray["accepted"], 0)
check("lot D still current", scalar(
    f"SELECT lot_id FROM lot_counters WHERE agent_id='{AGENT}'", ""), "LOT-D")
check("and keeps its count", scalar(
    f"SELECT inspected_count FROM lot_counters WHERE agent_id='{AGENT}'"), 2)

# Now close D behind the agent's back, the way the out-of-order change used to, and let
# the agent carry on sending D. New units for a closed lot mean it never really ended.
sql(f"UPDATE lot_counters SET lot_id='LOT-E', lot_started_at=NOW(), inspected_count=0, "
    f"defect_unit_count=0 WHERE agent_id='{AGENT}'")
sql(f"INSERT INTO lot_history (agent_id, lot_id, started_at, ended_at, inspected_count, "
    f"defect_unit_count) VALUES ('{AGENT}','LOT-D',NOW(),NOW(),2,0)")

status, resumed = post(batch("C-2", "EXAMPLE_D", [
    unit(3, "OK", "LOT-D", at=ts(210)),
    unit(4, "NG", "LOT-D", items=[{"name": "CHECK_A", "rawValue": "9.9"}], at=ts(211)),
]))
check("the new units are accepted, not dropped", resumed["accepted"], 2)
check("back on the running lot", scalar(
    f"SELECT lot_id FROM lot_counters WHERE agent_id='{AGENT}'", ""), "LOT-D")
# 2 restored from history + 2 just sent: closing it early costs nothing.
check("its earlier count came back with it", scalar(
    f"SELECT inspected_count FROM lot_counters WHERE agent_id='{AGENT}'"), 4)
check("defects too", scalar(
    f"SELECT defect_unit_count FROM lot_counters WHERE agent_id='{AGENT}'"), 1)
check("and it is no longer in history", scalar(
    f"SELECT COUNT(*) FROM lot_history WHERE agent_id='{AGENT}' AND lot_id='LOT-D'"), 0)

print("\n[6] judgements are data: example_b carries three with no schema change")
WELD = "C-2_EXAMPLE_B_CATHODE"
status, weldres = post(batch("C-2", "EXAMPLE_B_CATHODE", [
    unit(1, "OK", "LOT-W"),
    unit(2, "NG", "LOT-W", items=[{"name": "BURNT", "rawValue": "NG", "side": "LOWER"}]),
    unit(3, "DLNG", "LOT-W", items=[{"name": "ITEM_E_L", "rawValue": "NG", "side": "UPPER"}]),
    unit(4, "C-NG", "LOT-W", items=[{"name": "ITEM_B_L", "rawValue": "NG", "side": "LOWER"}]),
    unit(5, "WEIRD", "LOT-W"),
]))
check("HTTP status", status, 200)
check("inspected includes the unrecognised judgement",
      scalar(f"SELECT inspected_count FROM lot_counters WHERE agent_id='{WELD}'"), 5)
check("unrecognised judgement is not a defect",
      scalar(f"SELECT defect_unit_count FROM lot_counters WHERE agent_id='{WELD}'"), 3)
check("unknown_count", scalar(f"SELECT unknown_count FROM lot_counters WHERE agent_id='{WELD}'"), 1)
check("three judgement rows", scalar(
    f"SELECT COUNT(*) FROM lot_counter_judgement WHERE agent_id='{WELD}'"), 3)
check("NG/DLNG/C-NG each counted once", scalar(
    f"SELECT GROUP_CONCAT(CONCAT(judgement,'=',unit_count) ORDER BY judgement) "
    f"FROM lot_counter_judgement WHERE agent_id='{WELD}'", ""), "C-NG=1,DLNG=1,NG=1")

print("\n[7] a bad envelope is refused, not stored")
status, _ = post(batch("C-2", "NO_SUCH_VISION", [unit(1, "OK", "LOT-X")]))
check("unknown visionKey rejected", status, 400)
status, _ = post(batch("9-9", "EXAMPLE_C", [unit(1, "OK", "LOT-X")]))
check("unknown line rejected", status, 400)
bad = batch("C-2", "EXAMPLE_C", [unit(1, "OK", "LOT-X")])
bad["agentId"] = "C-2_SOMETHING_ELSE"
status, _ = post(bad)
check("mismatched agentId rejected", status, 400)
check("nothing was stored for the rejected batches",
      scalar("SELECT COUNT(*) FROM agents WHERE agent_id IN ('C-2_EXAMPLE_C','9-9_EXAMPLE_C')"), 0)

print("\n[8] heartbeat alone registers an agent")
hb = json.dumps({"agentId": "C-2_EXAMPLE_D", "line": "C-2", "visionKey": "EXAMPLE_D",
                 "agentVersion": "test", "ts": ts(0)}).encode()
sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
sock.sendto(hb, ("127.0.0.1", 6002))
sock.close()
import time
time.sleep(1.0)
check("last_heartbeat_at recorded", scalar(
    f"SELECT COUNT(*) FROM agents WHERE agent_id='{AGENT}' AND last_heartbeat_at IS NOT NULL"), 1)

print("\n[9] raw payload ledger holds defects/lot changes but not OK units")
# Asserted as a property rather than a count: the ledger's job is to exclude the OK
# traffic that makes up the overwhelming majority, and a fixed number here only ever
# breaks when a test above adds an event.
check("one row per stored defect", scalar(
    f"SELECT COUNT(*) FROM raw_events WHERE event_type='UNIT_INSPECTED' "
    f"AND agent_id IN ('{AGENT}','{WELD}')"),
    scalar(f"SELECT COUNT(*) FROM defect_occurrences WHERE agent_id IN ('{AGENT}','{WELD}')"))
# Only the announced ones: a lot that is opened implicitly by its first unit - the very
# first lot an agent ever reports - has no event to store.
check("every announced lot change kept", scalar(
    f"SELECT COUNT(*) FROM raw_events WHERE event_type='LOT_CHANGED' "
    f"AND agent_id IN ('{AGENT}','{WELD}')"), 3)
check("no OK unit stored", scalar(
    f"SELECT COUNT(*) FROM raw_events r WHERE r.event_type='UNIT_INSPECTED' "
    f"AND r.agent_id IN ('{AGENT}','{WELD}') "
    f"AND JSON_UNQUOTE(JSON_EXTRACT(r.payload,'$.judgement')) = 'OK'"), 0)

print("\n[10] a resync clears the inspector, not the lot the counters happen to name")
# From the floor, after every agent was redeployed and every inspector resynced: a few
# came back counting from zero, missing the ten hours of the lot that had already run.
#
# The old agent misread one LOT-ID mid-lot, which opened a phantom lot and closed the
# real one into history behind it. From then on lot_counters named the phantom. The
# resync tool read the lot to clear out of lot_counters - the one value it exists to
# repair - so it cleared the phantom and left the real lot's read position untouched.
# The re-read was then refused unit by unit as a replay, and the inspector counted only
# what the machine produced after the resync.
#
# The clearing below is the one Resync-Lot.ps1 performs.
sql(f"UPDATE lot_counters SET lot_id='PHANTOM', lot_started_at=NOW(), inspected_count=1, "
    f"defect_unit_count=0 WHERE agent_id='{AGENT}'")
sql(f"INSERT INTO lot_history (agent_id, lot_id, started_at, ended_at, inspected_count, "
    f"defect_unit_count) VALUES ('{AGENT}','LOT-D',NOW(),NOW(),4,1)")

# The agent restarts and offers the running lot again from its very first unit.
replay = [{"type": "LOT_CHANGED", "sourceFile": "MDL_20260825.csv", "oldLotId": None,
           "newLotId": "LOT-D", "detectedAtUnitSeq": 1, "occurredAt": ts(220)}] + [
          unit(1, "OK", "LOT-D", at=ts(201)), unit(2, "OK", "LOT-D", at=ts(202)),
          unit(3, "OK", "LOT-D", at=ts(210)),
          unit(4, "NG", "LOT-D", items=[{"name": "CHECK_A", "rawValue": "9.9"}], at=ts(211))]
status, stuck = post(batch("C-2", "EXAMPLE_D", replay))
check("without a clear, every unit of the re-read is refused", stuck["accepted"], 0)
check("and the inspector shows the phantom lot", scalar(
    f"SELECT lot_id FROM lot_counters WHERE agent_id='{AGENT}'", ""), "PHANTOM")

sql(f"""DELETE FROM defect_occurrences      WHERE agent_id='{AGENT}';
DELETE FROM lot_counter_judgement   WHERE agent_id='{AGENT}';
DELETE FROM vision_rollup_judgement WHERE agent_id='{AGENT}';
DELETE FROM vision_rollup           WHERE agent_id='{AGENT}';
DELETE FROM agent_lot_progress      WHERE agent_id='{AGENT}';
DELETE FROM lot_history             WHERE agent_id='{AGENT}';
UPDATE lot_counters SET lot_id=NULL, lot_started_at=NULL,
       inspected_count=0, defect_unit_count=0, unknown_count=0 WHERE agent_id='{AGENT}';""")

status, resynced = post(batch("C-2", "EXAMPLE_D", replay))
check("after it, the whole lot lands", resynced["accepted"], 5)
check("on the lot the machine is actually running", scalar(
    f"SELECT lot_id FROM lot_counters WHERE agent_id='{AGENT}'", ""), "LOT-D")
check("with every unit of it, not just what came after the resync", scalar(
    f"SELECT inspected_count FROM lot_counters WHERE agent_id='{AGENT}'"), 4)
check("its defect too", scalar(
    f"SELECT defect_unit_count FROM lot_counters WHERE agent_id='{AGENT}'"), 1)
# Written INSERT IGNORE, so a defect row that survived the clear would have kept the
# timestamp it was first given - which is what a resync is often run to correct.
check("the defect carries the time it was re-read with", scalar(
    f"SELECT COUNT(*) FROM defect_occurrences WHERE agent_id='{AGENT}' "
    f"AND lot_id='LOT-D' AND unit_seq=4"), 1)
check("and the trend was not doubled by the re-read", scalar(
    f"SELECT COALESCE(SUM(inspected_count),0) FROM vision_rollup "
    f"WHERE agent_id='{AGENT}' AND lot_id='LOT-D'"), 4)

print("\nCleaning up test data...")
cleanup()
check("dev DB left clean", scalar(
    "SELECT COUNT(*) FROM agents WHERE agent_id IN ('C-2_EXAMPLE_D','C-2_EXAMPLE_B_CATHODE')"), 0)

print()
if failures:
    print(f"FAILED: {len(failures)} check(s): {failures}")
    sys.exit(1)
print("All ingest checks passed.")
