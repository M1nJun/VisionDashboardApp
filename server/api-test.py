"""Checks the read path: catalog, grid, line production, detail and settings.

Seeds counters directly (the ingest path that writes them is covered by
ingest-test.py and agent-test.py) and then asserts on what the HTTP API returns.

  python server/api-test.py
"""

import os
import json
import subprocess
import sys
import urllib.error
import urllib.request
from datetime import datetime, timedelta

BASE = "http://127.0.0.1:8080/dashboard"
MYSQL = os.environ.get("VISIONDASH_MYSQL", r"C:\Program Files\MySQL\MySQL Server 8.4\bin\mysql.exe")
DB = "visiondash"
# Credentials come from the environment, never from this file. MYSQL_PWD is the mysql
# client's own variable, so the password reaches it without ever appearing in a command
# line, a process listing, or a commit.
DB_USER = os.environ.get("VISIONDASH_DB_USER", "root")
if "VISIONDASH_DB_PASSWORD" not in os.environ:
    sys.exit("Set VISIONDASH_DB_PASSWORD (your MySQL password) before running this.")
MYSQL_ENV = {**os.environ, "MYSQL_PWD": os.environ["VISIONDASH_DB_PASSWORD"]}


failures = []


def check(label, actual, expected):
    ok = actual == expected
    print(f"  {'PASS' if ok else 'FAIL'}  {label}: {actual}" + ("" if ok else f"  (expected {expected})"))
    if not ok:
        failures.append(label)


def close(label, actual, expected, tolerance=1e-6):
    ok = actual is not None and abs(actual - expected) < tolerance
    print(f"  {'PASS' if ok else 'FAIL'}  {label}: {actual}" + ("" if ok else f"  (expected ~{expected})"))
    if not ok:
        failures.append(label)


def sql(statement):
    out = subprocess.run([MYSQL, "-u", DB_USER, "-N", "-B", DB, "-e", statement],
                         capture_output=True, text=True, env=MYSQL_ENV)
    if out.returncode != 0:
        raise RuntimeError(out.stderr.strip())
    return out.stdout


def get(path):
    with urllib.request.urlopen(f"{BASE}{path}", timeout=15) as resp:
        return json.loads(resp.read())


def get_status(path):
    """HTTP status only, for the paths that are supposed to be refused."""
    try:
        with urllib.request.urlopen(f"{BASE}{path}", timeout=15) as resp:
            return resp.status
    except urllib.error.HTTPError as e:
        return e.code


def post(path, body):
    req = urllib.request.Request(f"{BASE}{path}", data=json.dumps(body).encode(),
                                 headers={"Content-Type": "application/json"}, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=15) as resp:
            return resp.status, json.loads(resp.read())
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()[:200]


def put(path, body, token=None):
    """Settings writes carry the unlock token; pass token=False to send none."""
    headers = {"Content-Type": "application/json"}
    if token is not False:
        headers["X-Settings-Token"] = token or SETTINGS_TOKEN
    req = urllib.request.Request(f"{BASE}{path}", data=json.dumps(body).encode(),
                                 headers=headers, method="PUT")
    try:
        with urllib.request.urlopen(req, timeout=15) as resp:
            return resp.status, json.loads(resp.read())
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()[:200]


AGENTS = ["C-2_EXAMPLE_C", "C-2_EXAMPLE_D", "C-2_EXAMPLE_B_CATHODE", "C-2_EXAMPLE_E",
          "C-1_EXAMPLE_C", "C-1_EXAMPLE_B_CATHODE"]
QUOTED = ",".join(f"'{a}'" for a in AGENTS)
# Filled in by the unlock call in section [7]; settings writes carry it.
SETTINGS_TOKEN = ""
NOW = datetime.now()
LOT_START = NOW - timedelta(hours=3)


def cleanup():
    sql(f"DELETE FROM lot_history_judgement WHERE lot_history_id IN "
        f"(SELECT id FROM lot_history WHERE agent_id IN ({QUOTED}))")
    for table in ["raw_events", "alarms", "defect_occurrences", "lot_history",
                  "vision_rollup_judgement", "vision_rollup", "lot_counter_judgement",
                  "lot_counters", "agent_lot_progress", "agents"]:
        sql(f"DELETE FROM {table} WHERE agent_id IN ({QUOTED})")


def seed():
    stamp = NOW.strftime("%Y-%m-%d %H:%M:%S")
    lot_start = LOT_START.strftime("%Y-%m-%d %H:%M:%S")
    stale = (NOW - timedelta(minutes=30)).strftime("%Y-%m-%d %H:%M:%S")

    rows = [
        # agent_id, line, vision_key, heartbeat, inspected, defects, judgements
        ("C-2_EXAMPLE_C", "C-2", "EXAMPLE_C", stamp, 1000, 0, {}),
        ("C-2_EXAMPLE_D", "C-2", "EXAMPLE_D", stamp, 1100, 1, {"NG": 1}),
        ("C-2_EXAMPLE_B_CATHODE", "C-2", "EXAMPLE_B_CATHODE", stamp, 1200, 9,
         {"NG": 1, "DLNG": 6, "C-NG": 2}),
        ("C-2_EXAMPLE_E", "C-2", "EXAMPLE_E", stamp, 500, 1, {"NG": 1}),
        # C-1's Example C reference is offline, so its line production must fall back.
        ("C-1_EXAMPLE_C", "C-1", "EXAMPLE_C", stale, 900, 0, {}),
        ("C-1_EXAMPLE_B_CATHODE", "C-1", "EXAMPLE_B_CATHODE", stamp, 950, 5, {"NG": 5}),
    ]

    for agent, line, key, heartbeat, inspected, defects, judgements in rows:
        sql(f"INSERT INTO agents (agent_id, line, vision_key, current_lot_id, current_model_id, "
            f"last_event_at, last_heartbeat_at) VALUES "
            f"('{agent}','{line}','{key}','LOT-1','MDL','{stamp}','{heartbeat}')")
        sql(f"INSERT INTO lot_counters (agent_id, lot_id, lot_started_at, inspected_count, "
            f"defect_unit_count) VALUES ('{agent}','LOT-1','{lot_start}',{inspected},{defects})")
        for code, count in judgements.items():
            sql(f"INSERT INTO lot_counter_judgement (agent_id, judgement, unit_count) "
                f"VALUES ('{agent}','{code}',{count})")

    # Pouch align gets a full detail fixture: three rollup buckets summing to its counters,
    # two defect occurrences, images and an alarm.
    for offset, inspected, defects in [(120, 400, 0), (60, 400, 1), (5, 300, 0)]:
        bucket = (NOW - timedelta(minutes=offset)).replace(second=0, microsecond=0)
        bucket = bucket.replace(minute=bucket.minute // 5 * 5).strftime("%Y-%m-%d %H:%M:%S")
        sql(f"INSERT INTO vision_rollup (agent_id, bucket_start, lot_id, inspected_count, "
            f"defect_unit_count) VALUES ('C-2_EXAMPLE_D','{bucket}','LOT-1',{inspected},{defects})")
        if defects:
            sql(f"INSERT INTO vision_rollup_judgement (agent_id, bucket_start, lot_id, judgement, "
                f"unit_count) VALUES ('C-2_EXAMPLE_D','{bucket}','LOT-1','NG',{defects})")

    # Example E gets a rollup history so the window filter has something to slice:
    # 100 units / 1 NG three minutes ago, and 400 units / 9 NG an hour ago. The lot
    # counters above say 500 / 1 in total, which is what the unwindowed grid reports.
    for offset, inspected, defects in [(3, 100, 1), (60, 400, 0)]:
        bucket = (NOW - timedelta(minutes=offset)).replace(second=0, microsecond=0)
        stamp_b = bucket.strftime("%Y-%m-%d %H:%M:%S")
        sql(f"INSERT INTO vision_rollup (agent_id, bucket_start, lot_id, inspected_count, "
            f"defect_unit_count) VALUES ('C-2_EXAMPLE_E','{stamp_b}','LOT-1',{inspected},{defects})")
        if defects:
            sql(f"INSERT INTO vision_rollup_judgement (agent_id, bucket_start, lot_id, judgement, "
                f"unit_count) VALUES ('C-2_EXAMPLE_E','{stamp_b}','LOT-1','NG',{defects})")

    occurred = (NOW - timedelta(minutes=60)).strftime("%Y-%m-%d %H:%M:%S")
    sql("INSERT INTO defect_occurrences (agent_id, line, vision_key, source_file, unit_seq, "
        "cell_id, model_id, lot_id, judgement, item_summary, item_count, occurred_at) VALUES "
        f"('C-2_EXAMPLE_D','C-2','EXAMPLE_D','MDL_20260826.csv',101,'C101','MDL','LOT-1',"
        f"'NG','CHECK_A + CHECK_B',2,'{occurred}')")
    occ = int(sql("SELECT id FROM defect_occurrences WHERE agent_id='C-2_EXAMPLE_D' "
                  "AND unit_seq=101").strip())
    sql(f"INSERT INTO defect_items (occurrence_id, seq, item_name, raw_value, value_num, side) VALUES "
        f"({occ},1,'CHECK_A','14.16007',14.16007,''),({occ},2,'CHECK_B','24.59380',24.59380,'')")
    sql(f"INSERT INTO defect_images (occurrence_id, set_label, kind, source_path, state, local_path) VALUES "
        f"({occ},'LEFT','MAIN','F:\\\\a.jpg','ready','D:\\\\cache\\\\a.jpg'),"
        f"({occ},'LEFT','OVERLAY','F:\\\\a_ov.jpg','pending',NULL)")

    # A second unit failing only CHECK_A - proves grouping is by the whole label, so
    # "CHECK_A" and "CHECK_A + CHECK_B" stay distinct instead of being merged.
    sql("INSERT INTO defect_occurrences (agent_id, line, vision_key, source_file, unit_seq, "
        "cell_id, model_id, lot_id, judgement, item_summary, item_count, occurred_at) VALUES "
        f"('C-2_EXAMPLE_D','C-2','EXAMPLE_D','MDL_20260826.csv',102,'C102','MDL','LOT-1',"
        f"'NG','CHECK_A',1,'{occurred}')")

    alarm_at = (NOW - timedelta(minutes=45)).strftime("%Y-%m-%d %H:%M:%S")
    sql("INSERT INTO alarms (event_uid, agent_id, line, vision_key, alarm_code, alarm_name, "
        f"alarm_detail, alarm_at) VALUES ('uid-api-test-1','C-2_EXAMPLE_D','C-2','EXAMPLE_D',"
        f"'12','Cylinder Timeout','CYL-3','{alarm_at}')")
    # Older than the lot start: must not be counted, so the badge and the list agree.
    old = (LOT_START - timedelta(hours=1)).strftime("%Y-%m-%d %H:%M:%S")
    sql("INSERT INTO alarms (event_uid, agent_id, line, vision_key, alarm_code, alarm_name, "
        f"alarm_at) VALUES ('uid-api-test-2','C-2_EXAMPLE_D','C-2','EXAMPLE_D','99','Old','{old}')")


print("Seeding...")
cleanup()
seed()

try:
    print("\n[1] catalog is served, so the frontend keeps no copy of it")
    catalog = get("/api/catalog")
    check("vision types", len(catalog["visionTypes"]), 7)
    check("grid order", [v["key"] for v in catalog["visionTypes"]],
          ["EXAMPLE_A_ANODE", "EXAMPLE_B_ANODE", "EXAMPLE_A_CATHODE", "EXAMPLE_B_CATHODE",
           "EXAMPLE_C", "EXAMPLE_D", "EXAMPLE_E"])
    check("lines", len(catalog["lines"]), 7)
    example_b = next(v for v in catalog["visionTypes"] if v["key"] == "EXAMPLE_B_CATHODE")
    example_e = next(v for v in catalog["visionTypes"] if v["key"] == "EXAMPLE_E")
    check("example_b declares three judgements", [j["code"] for j in example_b["judgements"]],
          ["NG", "DLNG", "C-NG"])
    check("example_e declares one", [j["code"] for j in example_e["judgements"]], ["NG"])
    check("only NG drives colour",
          [j["code"] for j in example_b["judgements"] if j["drivesColor"]], ["NG"])
    check("image sets come from the catalog", sorted(example_b["imageSets"]), ["LOWER", "UPPER"])
    check("short codes come from the catalog, not the frontend",
          [v["shortName"] for v in catalog["visionTypes"]],
          ["EXA-A", "EXB-A", "EXA-C", "EXB-C", "EXC", "EXD", "EXE"])
    check("no short code exceeds the 240px column budget",
          max(len(v["shortName"]) for v in catalog["visionTypes"]) <= 6, True)
    check("thresholds are the live values", catalog["thresholds"]["example_e"],
          {"warnPct": 0.01, "critPct": 0.02})
    # The rank panel's Common filter reads this rather than keeping its own list.
    check("common vision keys are served", catalog["commonVisionKeys"],
          ["EXAMPLE_B_ANODE", "EXAMPLE_B_CATHODE", "EXAMPLE_C"])
    # The grid decides PD or BM from this rather than hard-coding three minutes.
    check("the downtime boundary is served", catalog["plannedDowntimeMaxMinutes"], 3)

    print("\n[2] grid covers every slot and colours from NG alone")
    grid = get("/api/grid")
    slots = len(catalog["lines"]) * len(catalog["visionTypes"])
    check("cells", len(grid["cells"]), slots)
    by_slot = {f"{c['line']}/{c['visionKey']}": c for c in grid["cells"]}

    w = by_slot["C-2/EXAMPLE_B_CATHODE"]
    check("example_b status", w["status"], "RUNNING")
    check("grid cells carry the short code too", w["shortName"], "EXB-C")
    check("example_b judgement counts", {j["code"]: j["count"] for j in w["judgements"]},
          {"NG": 1, "DLNG": 6, "C-NG": 2})
    close("example_b NG rate", next(j["ratePct"] for j in w["judgements"] if j["code"] == "NG"),
          100 * 1 / 1200, 1e-9)
    check("DLNG at 0.5% does not colour the cell", w["colorLevel"], "GREEN")
    close("example_b overall defect rate", w["defectRatePct"], 100 * 9 / 1200, 1e-9)

    check("pouch align is YELLOW", by_slot["C-2/EXAMPLE_D"]["colorLevel"], "YELLOW")
    check("example_e is RED", by_slot["C-2/EXAMPLE_E"]["colorLevel"], "RED")
    check("example_c with no defects is GREEN", by_slot["C-2/EXAMPLE_C"]["colorLevel"], "GREEN")
    check("offline cell is GREY", by_slot["C-1/EXAMPLE_C"]["colorLevel"], "GREY")
    check("offline cell status", by_slot["C-1/EXAMPLE_C"]["status"], "OFFLINE")
    check("undeployed slot", by_slot["A-1/EXAMPLE_C"]["status"], "NOT_DEPLOYED")
    check("pouch alarm badge counts only this lot", by_slot["C-2/EXAMPLE_D"]["alarmCount"], 1)

    print("\n[2b] an inspector that has registered but produced nothing")
    # Exactly the state every agent is in for the first seconds after deployment: a row in
    # agents, no counters at all. A null rate here used to throw inside the grid builder
    # and fail the whole page with a 500 - one new agent took the dashboard down.
    sql("INSERT INTO agents (agent_id, line, vision_key, last_event_at, last_heartbeat_at) "
        "VALUES ('A-1_EXAMPLE_E','A-1','EXAMPLE_E',NOW(3),NOW(3))")
    fresh = get("/api/grid")
    fresh_slot = {f"{c['line']}/{c['visionKey']}": c for c in fresh["cells"]}["A-1/EXAMPLE_E"]
    check("grid still renders", len(fresh["cells"]), slots)
    check("the new inspector is live", fresh_slot["status"], "RUNNING")
    check("with no rate yet", fresh_slot["judgements"][0]["ratePct"], None)
    check("and no colour claim", fresh_slot["colorLevel"], "GREEN")
    sql("DELETE FROM agents WHERE agent_id='A-1_EXAMPLE_E'")

    print("\n[3] line production is one reference inspector, not the sum of all of them")
    by_line = {l["line"]: l for l in grid["lines"]}
    check("C-2 uses Example C Vision", by_line["C-2"]["sourceVisionKey"], "EXAMPLE_C")
    check("C-2 production is Example C's count, not 1000+1100+1200+500",
          by_line["C-2"]["production"], 1000)
    check("C-2 is not an estimate", by_line["C-2"]["estimated"], False)
    check("C-1 falls back to Example B Cathode", by_line["C-1"]["sourceVisionKey"], "EXAMPLE_B_CATHODE")
    check("C-1 production", by_line["C-1"]["production"], 950)
    check("C-1 is flagged as an estimate", by_line["C-1"]["estimated"], True)
    check("a line with no agents has no production", by_line["A-1"]["production"], None)

    check("per-line target is served", by_line["C-2"]["targetCells"], 14286)

    print("\n[4] totals")
    totals = grid["totals"]
    check("total production is the sum of line production", totals["production"], 1950)
    check("total defect units span every inspector", totals["defectUnits"], 16)
    close("total defect rate", totals["defectRatePct"], 100 * 16 / 1950, 1e-9)
    check("running", totals["running"], 5)
    check("offline", totals["offline"], 1)
    check("not deployed", totals["notDeployed"], slots - 6)

    # The headline rates are narrower than defectUnits on purpose. Of the 16 defect
    # verdicts on screen only 6 are NG from a example_b or example_c inspector: pouch align's
    # NG, example_e's NG, and example_b's DLNG and C-NG are all excluded.
    rates = {r["key"]: r for r in totals["rates"]}
    check("both catalog metrics are served", sorted(rates), ["dlngRate", "ngRate"])
    check("NG rate counts example_b and example_c only", rates["ngRate"]["units"], 6)
    close("NG rate", rates["ngRate"]["ratePct"], 100 * 6 / 1950, 1e-9)
    check("DLNG rate counts example_b only", rates["dlngRate"]["units"], 6)
    close("DLNG rate", rates["dlngRate"]["ratePct"], 100 * 6 / 1950, 1e-9)
    check("DLNG label", rates["dlngRate"]["label"], "Example B DLNG")

    check("target", totals["targetCells"], 100000)

    print("\n[4b] the window scopes the cells without moving the headline totals")
    windowed = get("/api/grid?windowMinutes=10")
    check("the window is echoed back", windowed["windowMinutes"], 10)
    w_slot = {f"{c['line']}/{c['visionKey']}": c for c in windowed["cells"]}
    example_e_w = w_slot["C-2/EXAMPLE_E"]
    # The lot holds 500 inspected; only the 3-minute-old bucket is inside 10 minutes.
    check("cell counts only the window", example_e_w["inspectedCount"], 100)
    check("and its defects", example_e_w["defectUnitCount"], 1)
    close("so the rate is the window's", example_e_w["defectRatePct"], 1.0, 1e-9)
    check("judgement counts are windowed too",
          next(j["count"] for j in example_e_w["judgements"] if j["code"] == "NG"), 1)

    # This is the whole point of the split: an operator narrowing the grid to five
    # minutes must not silently retitle the lot's headline rates.
    check("totals stay on the lot", windowed["totals"]["production"], 1950)
    check("headline NG rate stays on the lot",
          windowed["totals"]["rates"][0]["units"], 6)
    check("line output stays on the lot",
          {l["line"]: l["production"] for l in windowed["lines"]}["C-2"], 1000)

    # An inspector that produced nothing in the window has no rate at all - ranking it
    # as a clean 0% would sort every stopped station to the bottom looking perfect.
    example_c_w = w_slot["C-2/EXAMPLE_C"]
    check("an inspector idle through the window is flagged", example_c_w["windowEmpty"], True)
    check("and carries no rate", example_c_w["defectRatePct"], None)
    check("and is not coloured", example_c_w["colorLevel"], "GREY")

    unwindowed = get("/api/grid")
    check("without a window nothing is flagged",
          any(c["windowEmpty"] for c in unwindowed["cells"]), False)
    check("and the lot's own counts come back",
          {f"{c['line']}/{c['visionKey']}": c for c in unwindowed["cells"]}["C-2/EXAMPLE_E"]["inspectedCount"],
          500)

    print("\n[5] detail page")
    detail = get("/api/vision/C-2/EXAMPLE_D")
    check("cell matches the grid", detail["cell"]["defectUnitCount"], 1)
    labels = {d["label"]: d["count"] for d in detail["topDefects"]}
    check("combined labels stay distinct", labels, {"CHECK_A + CHECK_B": 1, "CHECK_A": 1})
    # The table gets light rows - no items, no images - because it is polled every few
    # seconds and a lot can hold hundreds of NG.
    check("events are whole units, newest first", len(detail["events"]), 2)
    check("event total counts the whole lot", detail["eventTotal"], 2)
    check("event rows are light",
          sorted(detail["events"][0]),
          ["cellId", "id", "judgement", "label", "occurredAt", "unitSeq"])

    print("\n[5b] one defect in full, fetched only when a row is opened")
    row = next(e for e in detail["events"] if e["unitSeq"] == 101)
    first = get(f"/api/vision/C-2/EXAMPLE_D/defects/{row['id']}")
    check("items carry names and raw values",
          [(i["name"], i["rawValue"]) for i in first["items"]],
          [("CHECK_A", "14.16007"), ("CHECK_B", "24.59380")])
    check("numeric values parsed", [i["value"] for i in first["items"]], [14.16007, 24.5938])
    check("fetched image gets a url", next(
        i["url"] for i in first["images"] if i["kind"] == "MAIN"), "/dashboard/api/images/"
        + str(next(i["id"] for i in first["images"] if i["kind"] == "MAIN")))
    # A pending image still gets a URL: requesting it is what asks the server to pull it
    # from the inspection PC. Only one the fetcher gave up on has nothing to offer.
    check("pending image still gets a url",
          next(i["url"] for i in first["images"] if i["kind"] == "OVERLAY") is not None, True)
    sql("UPDATE defect_images SET state='unavailable' WHERE kind='OVERLAY' AND occurrence_id IN "
        "(SELECT id FROM defect_occurrences WHERE agent_id='C-2_EXAMPLE_D' AND unit_seq=101)")
    gone = get(f"/api/vision/C-2/EXAMPLE_D/defects/{row['id']}")
    check("an image given up on has no url",
          next(i["url"] for i in gone["images"] if i["kind"] == "OVERLAY"), None)
    # Looked up against the slot, not by id alone, so a stale id cannot render another
    # inspector's unit.
    check("a defect from another slot is refused",
          get_status(f"/api/vision/C-2/EXAMPLE_E/defects/{row['id']}"), 404)

    check("alarm list matches the badge", len(detail["alarms"]), 1)
    check("alarm content", detail["alarms"][0]["name"], "Cylinder Timeout")

    print("\n[6] the trend chart and the grid cell agree")
    trend = detail["trend"]
    check("buckets", len(trend), 3)
    check("trend inspected sums to the counter",
          sum(p["inspected"] for p in trend), detail["cell"]["inspectedCount"])
    check("trend defects sum to the counter",
          sum(p["defectUnits"] for p in trend), detail["cell"]["defectUnitCount"])
    check("trend carries the judgement breakdown",
          sum(p["judgements"].get("NG", 0) for p in trend), 1)

    print("\n[7] settings are locked, and a threshold change needs no restart")
    # The password lives on the server, so it is not sitting in the JavaScript bundle.
    # Reading stays open - the values are on the dashboard anyway - and only writing is
    # gated, which is what stops a stray click retuning how the floor is scored.
    status, _ = put("/api/settings/defect_rate_warning_pct_example_e", {"value": "5.0"}, token=False)
    check("a write with no token is refused", status, 401)
    status, _ = put("/api/settings/defect_rate_warning_pct_example_e", {"value": "5.0"}, token="not-a-token")
    check("and a made-up one too", status, 401)
    status, _ = post("/api/settings/unlock", {"password": "wrong"})
    check("the wrong password is refused", status, 401)
    check("reading settings needs no unlock", len(get("/api/settings")) > 0, True)

    status, unlocked = post("/api/settings/unlock", {"password": "mimi"})
    check("the right password unlocks", status, 200)
    globals()["SETTINGS_TOKEN"] = unlocked["token"]

    status, _ = put("/api/settings/defect_rate_warning_pct_example_e", {"value": "5.0"})
    check("PUT accepted", status, 200)
    status, _ = put("/api/settings/defect_rate_critical_pct_example_e", {"value": "9.0"})
    check("PUT accepted", status, 200)
    regrid = get("/api/grid")
    recolour = {f"{c['line']}/{c['visionKey']}": c for c in regrid["cells"]}
    check("example_e is now GREEN", recolour["C-2/EXAMPLE_E"]["colorLevel"], "GREEN")
    check("catalog reports the new threshold",
          get("/api/catalog")["thresholds"]["example_e"], {"warnPct": 5.0, "critPct": 9.0})
    status, _ = put("/api/settings/no_such_setting", {"value": "1"})
    check("unknown setting rejected", status, 404)
    put("/api/settings/defect_rate_warning_pct_example_e", {"value": "0.01"})
    put("/api/settings/defect_rate_critical_pct_example_e", {"value": "0.02"})
    check("thresholds restored", get("/api/catalog")["thresholds"]["example_e"],
          {"warnPct": 0.01, "critPct": 0.02})

    print("\n[9] judgement thresholds come from the catalog, not the profile")
    weld = next(v for v in catalog["visionTypes"] if v["key"] == "EXAMPLE_B_CATHODE")
    by_code = {j["code"]: j for j in weld["judgements"]}
    # NG is scored against the tunable profile, so it carries no limits of its own;
    # DLNG and C-NG run an order of magnitude higher and carry theirs.
    check("NG defers to the threshold profile", by_code["NG"]["warnPct"], None)
    check("DLNG warn", by_code["DLNG"]["warnPct"], 5.0)
    check("DLNG has no critical line", by_code["DLNG"]["critPct"], None)
    check("C-NG warn", by_code["C-NG"]["warnPct"], 0.5)

    print("\n[8] unknown slots are refused")
    try:
        get("/api/vision/9-9/EXAMPLE_C")
        check("unknown line rejected", "200", "404")
    except urllib.error.HTTPError as e:
        check("unknown line rejected", e.code, 404)
    try:
        get("/api/vision/C-2/NO_SUCH")
        check("unknown vision key rejected", "200", "404")
    except urllib.error.HTTPError as e:
        check("unknown vision key rejected", e.code, 404)

finally:
    print("\nCleaning up...")
    cleanup()

print()
if failures:
    print(f"FAILED: {len(failures)} check(s): {failures}")
    sys.exit(1)
print("All API checks passed.")
