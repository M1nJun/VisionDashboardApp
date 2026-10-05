"""Seeds one plausible shift across the whole fleet, for looking at the UI.

Not a test: the assertions live in ingest-test.py / agent-test.py / api-test.py. This
exists so the dashboard can be reviewed against something that looks like a real
factory rather than three hand-made rows.

  python server/seed-demo.py          seed
  python server/seed-demo.py --clear  remove everything it seeded
"""

import json
import os
import random
import subprocess
import sys
import urllib.error
import urllib.request
from datetime import datetime, timedelta

HERE = os.path.dirname(os.path.abspath(__file__))
MYSQL = os.environ.get("VISIONDASH_MYSQL", r"C:\Program Files\MySQL\MySQL Server 8.4\bin\mysql.exe")
DB = "visiondash"
# Credentials come from the environment, never from this file. MYSQL_PWD is the mysql
# client's own variable, so the password reaches it without ever appearing in a command
# line, a process listing, or a commit.
DB_USER = os.environ.get("VISIONDASH_DB_USER", "root")
if "VISIONDASH_DB_PASSWORD" not in os.environ:
    sys.exit("Set VISIONDASH_DB_PASSWORD (your MySQL password) before running this.")
MYSQL_ENV = {**os.environ, "MYSQL_PWD": os.environ["VISIONDASH_DB_PASSWORD"]}

ROLLUP_MINUTES = 5

NOW = datetime.now()
LOT_START = NOW - timedelta(hours=4)
LOT_ID = "MDL-2608-A"


# Statements are buffered and shipped in one mysql invocation. Spawning the client
# once per INSERT turned a two-second seed into a several-minute one, almost all of it
# process start-up.
_buffer = []


def sql(statement):
    _buffer.append(statement.rstrip().rstrip(";") + ";")


def flush():
    if not _buffer:
        return ""
    script = "\n".join(_buffer)
    _buffer.clear()
    out = subprocess.run([MYSQL, "-u", DB_USER, "-N", "-B", DB],
                         input=script, capture_output=True, text=True, encoding="utf-8", env=MYSQL_ENV)
    if out.returncode != 0:
        raise RuntimeError(out.stderr.strip()[:600])
    return out.stdout


def query(statement):
    flush()
    out = subprocess.run([MYSQL, "-u", DB_USER, "-N", "-B", DB, "-e", statement],
                         capture_output=True, text=True, encoding="utf-8", env=MYSQL_ENV)
    if out.returncode != 0:
        raise RuntimeError(out.stderr.strip()[:600])
    return out.stdout


def load_catalog():
    with open(os.path.join(HERE, "..", "contracts", "vision-catalog.json"), encoding="utf-8") as f:
        catalog = json.load(f)
    with open(os.path.join(HERE, "..", "contracts", "topology.json"), encoding="utf-8") as f:
        topology = json.load(f)
    return catalog, topology


# Seeded rows cannot send heartbeats, and the production default declares an agent
# offline after ten seconds - so static demo data would be entirely grey within one poll.
# Widening the window is part of seeding and --clear puts it back.
DEMO_OFFLINE_SECONDS = 300
LIVE_OFFLINE_SECONDS = 10
API = "http://127.0.0.1:8080/dashboard/api"


def settings_token():
    """Settings writes are behind a password; the default is fine for a dev database."""
    request = urllib.request.Request(
        f"{API}/settings/unlock", method="POST",
        data=json.dumps({"password": "mimi"}).encode(),
        headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(request, timeout=5) as response:
            return json.loads(response.read())["token"]
    except (urllib.error.URLError, OSError):
        return ""


def set_setting(key, value):
    """Through the API, not straight into the table: the server caches settings in
    memory and only re-reads them when written through this path, so a direct UPDATE
    is invisible to it until the next restart."""
    request = urllib.request.Request(
        f"{API}/settings/{key}", method="PUT",
        data=json.dumps({"value": str(value)}).encode(),
        headers={"Content-Type": "application/json",
                 "X-Settings-Token": settings_token()})
    try:
        with urllib.request.urlopen(request, timeout=5):
            return True
    except (urllib.error.URLError, OSError) as e:
        print(f"could not set {key} ({e}); start the server first if the fleet reads as offline")
        return False


def clear():
    for table in ["raw_events", "alarms", "defect_items", "defect_images", "defect_occurrences",
                  "lot_history_judgement", "lot_history", "vision_rollup_judgement",
                  "vision_rollup", "lot_counter_judgement", "lot_counters",
                  "agent_lot_progress", "agents"]:
        # defect_items/images cascade, but naming them keeps the order obvious.
        sql(f"DELETE FROM {table}")
    flush()
    set_setting("agent_offline_threshold_seconds", LIVE_OFFLINE_SECONDS)
    print("cleared, offline threshold back to %ds" % LIVE_OFFLINE_SECONDS)


def main():
    catalog, topology = load_catalog()
    types = {v["key"]: v for v in catalog["visionTypes"]}
    profiles = catalog["thresholdProfiles"]

    if "--clear" in sys.argv:
        clear()
        return

    clear()
    # Random by default. These figures end up in the published demo, and a plant whose
    # numbers are identical on every capture starts to look like a real one's records.
    # Pass --seed=N to reproduce a particular set.
    seed = next((int(a.split("=", 1)[1]) for a in sys.argv if a.startswith("--seed=")),
                random.randrange(1_000_000))
    rng = random.Random(seed)
    print(f"seed {seed}")

    # A couple of slots left undeployed and a couple of agents down, because a screen
    # that only ever shows the happy path hides whatever those states look like.
    undeployed = {("A-2", "EXAMPLE_E"), ("B-1", "EXAMPLE_A_ANODE")}
    offline = {("C-1", "EXAMPLE_C"), ("B-2", "EXAMPLE_E")}
    idle = {("B-2", "EXAMPLE_D"), ("A-1", "EXAMPLE_D")}

    stamp = NOW.strftime("%Y-%m-%d %H:%M:%S")
    lot_start = LOT_START.strftime("%Y-%m-%d %H:%M:%S")
    stale_beat = (NOW - timedelta(minutes=12)).strftime("%Y-%m-%d %H:%M:%S")
    stale_event = (NOW - timedelta(minutes=25)).strftime("%Y-%m-%d %H:%M:%S")

    total_slots = 0
    for pc in topology["pcs"]:
        for key in pc["hosts"]:
            line = pc["line"]
            if (line, key) in undeployed:
                continue
            total_slots += 1

            vision = types[key]
            agent_id = f"{line}_{key}"
            is_offline = (line, key) in offline
            is_idle = (line, key) in idle

            # Downstream stations see fewer cells than upstream ones, which is exactly
            # why line output is read off one reference inspector instead of summed.
            base = rng.randint(9000, 12000)
            stage_loss = {"EXAMPLE_D": 0, "EXAMPLE_E": 12, "EXAMPLE_A_ANODE": 25,
                          "EXAMPLE_A_CATHODE": 27, "EXAMPLE_B_ANODE": 40,
                          "EXAMPLE_B_CATHODE": 42, "EXAMPLE_C": 55}.get(key, 0)
            inspected = base - stage_loss - rng.randint(0, 20)

            profile = profiles[vision["thresholdProfile"]]
            # Most inspectors sit under their warning line; a few are pushed over so the
            # colours, the ranking and the line-output fallback all have something to show.
            roll = rng.random()
            if roll > 0.86:
                rate = profile["critPct"] * rng.uniform(1.1, 2.6)
            elif roll > 0.68:
                rate = profile["warnPct"] * rng.uniform(1.05, 1.9)
            else:
                rate = profile["warnPct"] * rng.uniform(0.05, 0.9)

            primary = next(j["code"] for j in vision["judgements"]["defect"] if j["drivesColor"])
            primary_count = max(0, round(inspected * rate / 100))
            counts = {primary: primary_count}
            for j in vision["judgements"]["defect"]:
                if not j["drivesColor"]:
                    # Example B's DLNG dominates its volume in practice; it must not colour
                    # the card, which is only visible if it is genuinely large here.
                    counts[j["code"]] = round(inspected * rng.uniform(0.002, 0.009))
            defect_units = sum(counts.values())

            sql("INSERT INTO agents (agent_id, line, vision_key, current_lot_id, current_model_id, "
                "last_source_file, last_event_at, last_heartbeat_at, last_heartbeat_ip, agent_version) VALUES "
                f"('{agent_id}','{line}','{key}','{LOT_ID}','MDL','MDL_{NOW:%Y%m%d}.csv',"
                f"'{stale_event if is_idle else stamp}',"
                f"'{stale_beat if is_offline else stamp}','{pc['ip']}','2.0.0')")

            sql("INSERT INTO lot_counters (agent_id, lot_id, lot_started_at, inspected_count, "
                f"defect_unit_count, unknown_count) VALUES ('{agent_id}','{LOT_ID}','{lot_start}',"
                f"{inspected},{defect_units},0)")
            for code, count in counts.items():
                sql("INSERT INTO lot_counter_judgement (agent_id, judgement, unit_count) VALUES "
                    f"('{agent_id}','{code}',{count})")

            # Rollup buckets that add up to exactly the counters, so the trend chart and
            # the grid cell are two views of one number.
            buckets = 48
            remaining_inspected, remaining_defects = inspected, defect_units
            for b in range(buckets):
                at = LOT_START + timedelta(minutes=b * ROLLUP_MINUTES)
                if at > NOW:
                    break
                left = buckets - b
                take = remaining_inspected if left == 1 else min(remaining_inspected,
                                                                 max(0, inspected // buckets + rng.randint(-40, 40)))
                dtake = remaining_defects if left == 1 else min(remaining_defects,
                                                               1 if rng.random() < defect_units / max(buckets, 1) else 0)
                remaining_inspected -= take
                remaining_defects -= dtake
                if take == 0 and dtake == 0:
                    continue
                bucket = at.replace(second=0, microsecond=0)
                bucket = bucket.replace(minute=bucket.minute // ROLLUP_MINUTES * ROLLUP_MINUTES)
                sql("INSERT INTO vision_rollup (agent_id, bucket_start, lot_id, inspected_count, "
                    f"defect_unit_count) VALUES ('{agent_id}','{bucket:%Y-%m-%d %H:%M:%S}','{LOT_ID}',"
                    f"{take},{dtake}) AS new ON DUPLICATE KEY UPDATE "
                    "inspected_count = vision_rollup.inspected_count + new.inspected_count, "
                    "defect_unit_count = vision_rollup.defect_unit_count + new.defect_unit_count")
                if dtake:
                    sql("INSERT INTO vision_rollup_judgement (agent_id, bucket_start, lot_id, judgement, "
                        f"unit_count) VALUES ('{agent_id}','{bucket:%Y-%m-%d %H:%M:%S}','{LOT_ID}','{primary}',{dtake}) "
                        "AS new ON DUPLICATE KEY UPDATE unit_count = vision_rollup_judgement.unit_count + new.unit_count")

            # A sample of the actual defect rows behind the counters, so the detail page
            # has occurrences, items and image slots to render. Only a slice of the full
            # count - seeding every one of them would take minutes and prove nothing the
            # first twenty do not.
            item_pool = {
                "NAMED_COLUMN": ["B_L", "ITEM_F", "ITEM_E_L", "ITEM_B_L", "ITEM_D_L"],
            }.get(vision["source"]["itemDiscovery"]["mode"],
                  ["CHECK_A", "CHECK_B", "CHECK_C", "CHECK_E", "CHECK_D"])
            sides = vision["source"]["itemDiscovery"].get("sides", [])
            image_sets = list(vision["source"]["images"]["sets"].keys())

            for n in range(min(20, defect_units)):
                judgement = rng.choices(list(counts.keys()), weights=list(counts.values()))[0]
                # Roughly a third of units fail more than one check at once, which is the
                # case the whole occurrence/item split exists for.
                chosen = rng.sample(item_pool, rng.choice([1, 1, 1, 2, 3]))
                summary = " + ".join(sorted(chosen))
                at = LOT_START + timedelta(minutes=rng.randint(1, 235))
                sql("INSERT INTO defect_occurrences (agent_id, line, vision_key, source_file, "
                    "unit_seq, cell_id, model_id, lot_id, judgement, item_summary, item_count, "
                    f"occurred_at) VALUES ('{agent_id}','{line}','{key}','MDL_{NOW:%Y%m%d}.csv',"
                    f"{9000 + n},'C{rng.randint(100000, 999999)}','MDL','{LOT_ID}','{judgement}',"
                    f"'{summary}',{len(chosen)},'{at:%Y-%m-%d %H:%M:%S}')")
                sql("SET @occ := LAST_INSERT_ID()")
                for seq, item in enumerate(sorted(chosen), start=1):
                    side = f"'{rng.choice(sides)}'" if sides else "''"
                    raw = f"{rng.uniform(1, 30):.5f}"
                    sql("INSERT INTO defect_items (occurrence_id, seq, item_name, raw_value, "
                        f"value_num, side) VALUES (@occ,{seq},'{item}','{raw}',{raw},{side})")
                for label in image_sets:
                    for kind in ("MAIN", "OVERLAY"):
                        suffix = "_ov" if kind == "OVERLAY" else ""
                        sql("INSERT INTO defect_images (occurrence_id, set_label, kind, "
                            f"source_path, state, next_attempt_at, expires_at) VALUES "
                            f"(@occ,'{label}','{kind}','F:\\\\Files\\\\Image\\\\{n}_{label}{suffix}.jpg',"
                            f"'pending',NOW(3),DATE_ADD(NOW(3), INTERVAL 6 HOUR))")

            # A few equipment alarms so the BM badge is not always empty.
            for n in range(rng.choice([0, 0, 0, 1, 2])):
                at = LOT_START + timedelta(minutes=rng.randint(5, 230))
                sql("INSERT IGNORE INTO alarms (event_uid, agent_id, line, vision_key, alarm_code, "
                    f"alarm_name, alarm_detail, alarm_at) VALUES (MD5('{agent_id}{n}'),'{agent_id}',"
                    f"'{line}','{key}','{rng.randint(10, 90)}','Cylinder Timeout','CYL-{n + 1}',"
                    f"'{at:%Y-%m-%d %H:%M:%S}')")

    flush()
    print(f"seeded {total_slots} inspectors on lot {LOT_ID}")
    print(query("SELECT COUNT(*) FROM agents").strip(), "agents,",
          query("SELECT SUM(inspected_count) FROM lot_counters").strip(), "units inspected")
    if set_setting("agent_offline_threshold_seconds", DEMO_OFFLINE_SECONDS):
        print(f"offline threshold widened to {DEMO_OFFLINE_SECONDS}s so seeded rows stay alive; "
              f"the fleet reads as live for about {DEMO_OFFLINE_SECONDS // 60} minutes")


if __name__ == "__main__":
    main()
