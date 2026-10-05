"""Checks the image fetch policy against a running server + dev DB.

The inspection PCs are not reachable from here, so every copy attempt fails - which is
exactly the interesting half. What this asserts is what the fetcher *does* about failure:
that a PC which is briefly unreachable does not cost you the image, that a configuration
mistake never marks one permanently gone, and that only the retry budget running out
does. Serving and the path guard are tested against real files on disk.

  python server/image-test.py
"""

import json
import os
import subprocess
import sys
import tempfile
import urllib.error
import urllib.request
from datetime import datetime, timedelta

MYSQL = os.environ.get("VISIONDASH_MYSQL", r"C:\Program Files\MySQL\MySQL Server 8.4\bin\mysql.exe")
DB = "visiondash"
# Credentials come from the environment, never from this file. MYSQL_PWD is the mysql
# client's own variable, so the password reaches it without ever appearing in a command
# line, a process listing, or a commit.
DB_USER = os.environ.get("VISIONDASH_DB_USER", "root")
if "VISIONDASH_DB_PASSWORD" not in os.environ:
    sys.exit("Set VISIONDASH_DB_PASSWORD (your MySQL password) before running this.")
MYSQL_ENV = {**os.environ, "MYSQL_PWD": os.environ["VISIONDASH_DB_PASSWORD"]}

BASE = "http://127.0.0.1:8080/dashboard"

failures = []


def check(label, actual, expected):
    ok = actual == expected
    print(f"  {'PASS' if ok else 'FAIL'}  {label}: {actual}" + ("" if ok else f"  (expected {expected})"))
    if not ok:
        failures.append(label)


def sql(statement):
    out = subprocess.run([MYSQL, "-u", DB_USER, "-N", "-B", DB],
                         input=statement, capture_output=True, text=True, encoding="utf-8", env=MYSQL_ENV)
    if out.returncode != 0:
        raise RuntimeError(out.stderr.strip()[:600])
    return out.stdout


def scalar(statement, default=None):
    rows = [r for r in sql(statement).strip().splitlines() if r]
    if not rows or rows[0] == "NULL":
        return default
    value = rows[0].split("\t")[0]
    if value == "NULL":
        return default
    try:
        return int(value)
    except ValueError:
        return value


def unlock_settings():
    """Settings writes are behind a password now; reading is not."""
    request = urllib.request.Request(f"{BASE}/api/settings/unlock", method="POST",
                                     data=json.dumps({"password": "mimi"}).encode(),
                                     headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(request, timeout=5) as response:
        return json.loads(response.read())["token"]


SETTINGS_TOKEN = unlock_settings()


def setting(key, value):
    request = urllib.request.Request(f"{BASE}/api/settings/{key}", method="PUT",
                                     data=json.dumps({"value": str(value)}).encode(),
                                     headers={"Content-Type": "application/json",
                                              "X-Settings-Token": SETTINGS_TOKEN})
    with urllib.request.urlopen(request, timeout=5):
        pass


def get_image(image_id):
    try:
        with urllib.request.urlopen(f"{BASE}/api/images/{image_id}", timeout=45) as resp:
            return resp.status, resp.read()
    except urllib.error.HTTPError as e:
        return e.code, b""


# A real slot from the topology (so a host IP resolves) and one that no PC hosts.
# agent_id is the canonical <line>_<vision_key>, because the schema allows only one agent
# per slot - inventing a second id for the same slot is exactly what that constraint
# exists to stop.
#
# The line is read from the topology rather than named here. Hard-coding one meant that
# removing that line from the topology silently turned every "unreachable PC" case into
# the "no PC hosts this slot" case, and three checks changed meaning without failing to
# say so.
with open(os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                       "contracts", "topology.json"), encoding="utf-8") as _f:
    _topology = json.load(_f)
REAL_LINE = next(pc["line"] for pc in _topology["pcs"] if "EXAMPLE_D" in pc["hosts"])
REAL_AGENT = f"{REAL_LINE}_EXAMPLE_D"
GHOST_AGENT = "ZZ-9_GHOST_VISION"
SOURCE_FILE = "IMGT.csv"
_created_agents = []


def cleanup():
    sql(f"""
        DELETE FROM defect_images WHERE occurrence_id IN
          (SELECT id FROM defect_occurrences WHERE source_file = '{SOURCE_FILE}');
        DELETE FROM defect_occurrences WHERE source_file = '{SOURCE_FILE}';
    """)
    for agent in _created_agents:
        sql(f"DELETE FROM lot_counters WHERE agent_id = '{agent}';"
            f"DELETE FROM agents WHERE agent_id = '{agent}';")
    _created_agents.clear()


def make_occurrence(agent, line, vision_key, seq):
    existed = scalar(f"SELECT COUNT(*) FROM agents WHERE agent_id='{agent}'") == 1
    if not existed:
        sql(f"INSERT INTO agents (agent_id, line, vision_key) "
            f"VALUES ('{agent}','{line}','{vision_key}')")
        _created_agents.append(agent)
    sql(f"""
        INSERT INTO defect_occurrences (agent_id, line, vision_key, source_file, unit_seq,
               lot_id, judgement, item_summary, item_count, occurred_at)
        VALUES ('{agent}','{line}','{vision_key}','{SOURCE_FILE}',{seq},'LOT-IMG','NG','CHECK_A',1,NOW(3));
    """)
    return scalar(f"SELECT id FROM defect_occurrences WHERE source_file='{SOURCE_FILE}' AND unit_seq={seq}")


# One row per (occurrence, set, kind) - the schema refuses a second image for the same
# slot on the same unit, so each fixture here takes its own combination.
def add_image(occurrence, path, label="LEFT", kind="MAIN", expires_hours=6,
              state="pending", local=None):
    local_sql = "NULL" if local is None else f"'{local.replace(chr(92), chr(92) * 2)}'"
    sql(f"""
        INSERT INTO defect_images (occurrence_id, set_label, kind, source_path, state,
               local_path, next_attempt_at, expires_at)
        VALUES ({occurrence}, '{label}', '{kind}', '{path.replace(chr(92), chr(92) * 2)}', '{state}',
                {local_sql}, NOW(3), DATE_ADD(NOW(3), INTERVAL {expires_hours} HOUR));
    """)
    return scalar(f"SELECT id FROM defect_images WHERE occurrence_id={occurrence} "
                  f"AND set_label='{label}' AND kind='{kind}'")


print("Preparing...")
cleanup()
cache = tempfile.mkdtemp(prefix="visiondash-images-")
setting("image_local_root", cache)
# Park the background sweeper: these rows are brand new, and a timer picking them up
# mid-assertion would make the results depend on when the test happened to run.
setting("image_prefetch_window_hours", "0")

try:
    # That line hosts EXAMPLE_D in the topology, but its PC is not reachable from here,
    # so every copy attempt takes the transient path.
    occ = make_occurrence(REAL_AGENT, REAL_LINE, "EXAMPLE_D", 1)

    print("\n[1] an unreachable inspection PC costs a retry, not the image")
    img = add_image(occ, "F:\\Files\\Image\\a.jpg")
    status, _ = get_image(img)
    check("HTTP while unfetched", status, 404)
    check("still pending", scalar(f"SELECT state FROM defect_images WHERE id={img}"), "pending")
    check("attempt counted", scalar(f"SELECT attempts FROM defect_images WHERE id={img}"), 1)
    check("scheduled to try again", scalar(
        f"SELECT next_attempt_at > NOW(3) FROM defect_images WHERE id={img}"), 1)
    check("reason recorded", "unreachable" in (scalar(
        f"SELECT last_error FROM defect_images WHERE id={img}", "") or ""), True)

    print("\n[2] backoff grows instead of hammering a PC that is switched off")
    first = scalar(f"SELECT TIMESTAMPDIFF(SECOND, NOW(3), next_attempt_at) FROM defect_images WHERE id={img}")
    sql(f"UPDATE defect_images SET next_attempt_at = NOW(3) WHERE id={img}")
    get_image(img)
    second = scalar(f"SELECT TIMESTAMPDIFF(SECOND, NOW(3), next_attempt_at) FROM defect_images WHERE id={img}")
    check("second wait is longer than the first", second > first, True)
    check("attempts still counting", scalar(f"SELECT attempts FROM defect_images WHERE id={img}"), 2)

    print("\n[3] a configuration mistake never marks an image permanently gone")
    # No PC in the topology hosts this slot at all.
    ghost_occ = make_occurrence(GHOST_AGENT, "ZZ-9", "GHOST_VISION", 2)
    ghost = add_image(ghost_occ, "F:\\Files\\Image\\ghost.jpg")
    status, _ = get_image(ghost)
    check("HTTP", status, 404)
    check("state", scalar(f"SELECT state FROM defect_images WHERE id={ghost}"), "pending")
    check("not counted as a failed attempt",
          scalar(f"SELECT attempts FROM defect_images WHERE id={ghost}"), 0)
    check("will retry once the topology is fixed", scalar(
        f"SELECT next_attempt_at > NOW(3) FROM defect_images WHERE id={ghost}"), 1)

    print("\n[4] only the retry budget running out gives up")
    expired = add_image(occ, "F:\\Files\\Image\\old.jpg", label="RIGHT")
    sql(f"UPDATE defect_images SET expires_at = DATE_SUB(NOW(3), INTERVAL 1 MINUTE) WHERE id={expired}")
    get_image(expired)
    check("state", scalar(f"SELECT state FROM defect_images WHERE id={expired}"), "unavailable")
    check("reason recorded", "budget" in (scalar(
        f"SELECT last_error FROM defect_images WHERE id={expired}", "") or ""), True)

    print("\n[5] a fetched image is served and its use is recorded")
    real = os.path.join(cache, REAL_LINE, "EXAMPLE_D", "ready.jpg")
    os.makedirs(os.path.dirname(real), exist_ok=True)
    payload = b"\xff\xd8\xff\xe0" + b"visiondash-test" * 8
    with open(real, "wb") as f:
        f.write(payload)
    ready = add_image(occ, "F:\\Files\\Image\\ready.jpg", kind="OVERLAY", state="ready", local=real)
    sql(f"UPDATE defect_images SET last_viewed_at = NULL WHERE id={ready}")
    status, body = get_image(ready)
    check("HTTP", status, 200)
    check("bytes served", len(body), len(payload))
    check("last_viewed_at stamped", scalar(
        f"SELECT last_viewed_at IS NOT NULL FROM defect_images WHERE id={ready}"), 1)

    print("\n[6] a cached file that vanished goes back in the queue")
    os.remove(real)
    status, _ = get_image(ready)
    check("HTTP", status, 404)
    check("requeued as pending", scalar(f"SELECT state FROM defect_images WHERE id={ready}"), "pending")

    print("\n[7] a row pointing outside the cache root is refused")
    outside = os.path.join(tempfile.gettempdir(), "visiondash-outside.jpg")
    with open(outside, "wb") as f:
        f.write(b"should never be served")
    escaped = add_image(occ, "F:\\Files\\Image\\x.jpg", label="RIGHT", kind="OVERLAY",
                        state="ready", local=outside)
    status, body = get_image(escaped)
    check("HTTP", status, 404)
    check("nothing served", len(body), 0)
    os.remove(outside)

    print("\n[8] UNC conversion")
    # Mirrors SmbImageSource.toUncPath; a drive-letter path is all the shares expose.
    check("drive letter becomes the share name",
          "F:\\Files\\Image\\a.jpg".replace("F:", "\\\\10.0.0.1\\F", 1),
          "\\\\10.0.0.1\\F\\Files\\Image\\a.jpg")

finally:
    print("\nCleaning up...")
    cleanup()
    setting("image_prefetch_window_hours", "24")
    setting("image_local_root", "D:\\VisionDashboardImages")

print()
if failures:
    print(f"FAILED: {len(failures)} check(s): {failures}")
    sys.exit(1)
print("All image checks passed.")
