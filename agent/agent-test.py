"""End-to-end check of the agent against a running server + dev DB.

Builds synthetic CSVs shaped like each real vision type's output, runs the actual
built exe in --once mode against the live ingest endpoint, then asserts on what
landed in the database. This is the whole pipeline, not a parser unit test.

  python agent/agent-test.py
"""

import json
import os
import shutil
import socket
import subprocess
import sys
import tempfile
import threading
import time
from datetime import datetime

HERE = os.path.dirname(os.path.abspath(__file__))
EXE = os.path.join(HERE, "bin", "Debug", "net8.0", "win-x64", "VisionAgent.exe")
MYSQL = os.environ.get("VISIONDASH_MYSQL", r"C:\Program Files\MySQL\MySQL Server 8.4\bin\mysql.exe")
DB = "visiondash"
# Credentials come from the environment, never from this file. MYSQL_PWD is the mysql
# client's own variable, so the password reaches it without ever appearing in a command
# line, a process listing, or a commit.
DB_USER = os.environ.get("VISIONDASH_DB_USER", "root")
if "VISIONDASH_DB_PASSWORD" not in os.environ:
    sys.exit("Set VISIONDASH_DB_PASSWORD (your MySQL password) before running this.")
MYSQL_ENV = {**os.environ, "MYSQL_PWD": os.environ["VISIONDASH_DB_PASSWORD"]}

EVENTS_URL = "http://127.0.0.1:8080/dashboard/api/ingest/events"
TODAY = datetime.now().strftime("%Y%m%d")

# A line of its own: api-test.py seeds C-2 and C-1, and running both suites in one
# pass put one fixture inside the other's assertions.
LINE = "C-3"

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
    try:
        return int(rows[0][0])
    except ValueError:
        # mysql's batch output escapes backslashes, and Windows paths are full of them.
        return rows[0][0].replace("\\\\", "\\")


def paths(query):
    """Sorted in Python: MySQL's ORDER BY inside GROUP_CONCAT follows the column's
    collation, which orders '.' and '_' differently than a plain string sort."""
    value = scalar(query, "")
    return sorted(value.split(",")) if value else []


def run_agent(workdir, line, vision_keys, model_token="MDL"):
    config = {
        "line": line,
        "visionKeys": vision_keys,
        "modelToken": model_token,
        "pollIntervalMs": 500,
        "sourceFolderOverride": os.path.join(workdir, "csv"),
        "stateFile": os.path.join(workdir, "state.json"),
        "logFile": os.path.join(workdir, "agent.log"),
        "server": {"eventsUrl": EVENTS_URL, "heartbeatHost": "127.0.0.1",
                   "heartbeatPort": 6002, "timeoutSeconds": 10},
        "dryRun": False,
    }
    path = os.path.join(workdir, "agent.json")
    with open(path, "w", encoding="utf-8") as f:
        json.dump(config, f, indent=2)

    result = subprocess.run([EXE, path, "--once"], capture_output=True, text=True, timeout=120)
    if result.returncode != 0:
        print(result.stdout[-3000:])
        print(result.stderr[-2000:])
        raise RuntimeError(f"agent exited {result.returncode}")
    return result.stdout


def black_hole_server():
    """Accepts connections and never answers - what a wedged server looks like.

    A refused connection was always handled; a silent one was not, and that is the
    difference that took the whole fleet down.
    """
    listener = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    listener.bind(("127.0.0.1", 0))
    listener.listen(8)
    held = []

    def accept_forever():
        while True:
            try:
                conn, _ = listener.accept()
                held.append(conn)
            except OSError:
                return

    threading.Thread(target=accept_forever, daemon=True).start()
    return listener, listener.getsockname()[1]


def run_agent_background(workdir, line, vision_keys, events_url, timeout_seconds, seconds):
    """Runs the agent in its normal looping mode for a while, then stops it."""
    config = {
        "line": line,
        "visionKeys": vision_keys,
        "modelToken": "MDL",
        "pollIntervalMs": 300,
        "sourceFolderOverride": os.path.join(workdir, "csv"),
        "stateFile": os.path.join(workdir, "state.json"),
        "logFile": os.path.join(workdir, "agent.log"),
        "server": {"eventsUrl": events_url, "heartbeatHost": "127.0.0.1",
                   "heartbeatPort": 6002, "timeoutSeconds": timeout_seconds},
        "dryRun": False,
    }
    path = os.path.join(workdir, "agent.json")
    with open(path, "w", encoding="utf-8") as f:
        json.dump(config, f, indent=2)

    process = subprocess.Popen([EXE, path], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    try:
        time.sleep(seconds)
    finally:
        process.terminate()
        try:
            process.wait(timeout=20)
        except subprocess.TimeoutExpired:
            process.kill()

    with open(os.path.join(workdir, "agent.log"), encoding="utf-8") as f:
        return f.read()


def write_csv(workdir, name, rows, encoding="utf-8"):
    folder = os.path.join(workdir, "csv")
    os.makedirs(folder, exist_ok=True)
    with open(os.path.join(folder, name), "w", encoding=encoding, newline="") as f:
        f.write("\r\n".join(rows) + "\r\n")


def cleanup(agent_ids):
    quoted = ",".join(f"'{a}'" for a in agent_ids)
    sql(f"DELETE FROM lot_history_judgement WHERE lot_history_id IN (SELECT id FROM lot_history WHERE agent_id IN ({quoted}))")
    for table in ["raw_events", "alarms", "defect_occurrences", "lot_history",
                  "vision_rollup_judgement", "vision_rollup", "lot_counter_judgement",
                  "lot_counters", "agent_lot_progress", "agents"]:
        sql(f"DELETE FROM {table} WHERE agent_id IN ({quoted})")


ALL_AGENTS = [f"{LINE}_EXAMPLE_D", f"{LINE}_EXAMPLE_B_CATHODE", f"{LINE}_EXAMPLE_C",
              f"{LINE}_EXAMPLE_A_ANODE", f"{LINE}_EXAMPLE_A_CATHODE"]

if not os.path.exists(EXE):
    print(f"Agent not built: {EXE}")
    sys.exit(1)

print("Cleaning previous test data...")
cleanup(ALL_AGENTS)
work = tempfile.mkdtemp(prefix="visionagent-test-")

try:
    # ---------------------------------------------------------------- pouch align
    print("\n[A] EXAMPLE_D - scan mode, several items on one unit, both image sets")
    a = os.path.join(work, "pouch")
    write_csv(a, f"MDL_{TODAY}.csv", [
        "NO,Result,LOT-ID,CELL-ID,CHECK_A-OK/NG,CHECK_A,CHECK_B-OK/NG,CHECK_B,CHECK_C-OK/NG,CHECK_C,"
        "Image_Path,Overlay_Image_Path,Image_Path_2,Overlay_Image_Path_2",
        "1,OK,LOT-A,C001,OK,1.11,OK,2.22,OK,3.33,,,,",
        "2,NG,LOT-A,C002,NG,14.16007,NG,24.59380,Bypass_NG,9.99,"
        "F:\\img\\2L.jpg,F:\\img\\2L_ov.jpg,F:\\img\\2R.jpg,F:\\img\\2R_ov.jpg",
        "3,OK,LOT-A,C003,OK,1.0,OK,1.0,OK,1.0,,,,",
    ])
    run_agent(a, LINE, ["EXAMPLE_D"])
    P = f"{LINE}_EXAMPLE_D"
    check("inspected", scalar(f"SELECT inspected_count FROM lot_counters WHERE agent_id='{P}'"), 3)
    check("defect units", scalar(f"SELECT defect_unit_count FROM lot_counters WHERE agent_id='{P}'"), 1)
    check("model id read from the filename",
          scalar(f"SELECT model_id FROM defect_occurrences WHERE agent_id='{P}'", ""), "MDL")
    check("Bypass_NG did not become an item", scalar(
        f"SELECT item_summary FROM defect_occurrences WHERE agent_id='{P}'", ""), "CHECK_A + CHECK_B")
    check("raw measurements preserved", scalar(
        f"SELECT GROUP_CONCAT(raw_value ORDER BY item_name) FROM defect_items i "
        f"JOIN defect_occurrences o ON o.id=i.occurrence_id WHERE o.agent_id='{P}'", ""), "14.16007,24.59380")
    check("both image sets, main+overlay each", scalar(
        f"SELECT COUNT(*) FROM defect_images im JOIN defect_occurrences o ON o.id=im.occurrence_id "
        f"WHERE o.agent_id='{P}'"), 4)
    check("image files attached once each, not per item", scalar(
        f"SELECT COUNT(DISTINCT source_path) FROM defect_images im "
        f"JOIN defect_occurrences o ON o.id=im.occurrence_id WHERE o.agent_id='{P}'"), 4)

    # ---------------------------------------------------------------- example_b
    print("\n[B] EXAMPLE_B_CATHODE - named defect, per-side columns, backlight image variant")
    b = os.path.join(work, "example_b")
    header = ("NO,MODEL-ID,LOT-ID,CELL-ID,JUDGE,JUDGE-DEFECT,"
              "LOWER_ITEM_H_L-OK/NG,UPPER_ITEM_H_L-OK/NG,LOWER_ITEM_H_L-JUDGE,UPPER_ITEM_H_L-JUDGE,"
              "LOWER_ITEM_F-OK/NG,UPPER_ITEM_F-OK/NG,"
              "LOWER_IMAGE-PATH-1,LOWER_OVERLAY-IMAGE-PATH-1,LOWER_IMAGE-PATH-3,LOWER_OVERLAY-IMAGE-PATH-3,"
              "UPPER_IMAGE-PATH-1,UPPER_OVERLAY-IMAGE-PATH-1,UPPER_IMAGE-PATH-3,UPPER_OVERLAY-IMAGE-PATH-3")
    img_cols = ("F:\\lo1.jpg,F:\\lo1o.jpg,F:\\lo3.jpg,F:\\lo3o.jpg,"
                "F:\\up1.jpg,F:\\up1o.jpg,F:\\up3.jpg,F:\\up3o.jpg")
    write_csv(b, f"#{LINE} EXAMPLE B VISION(+)_MDL_{TODAY}_001.csv", [
        header,
        f"1,MDL,LOT-W,C001,OK,,OK,OK,OK,OK,OK,OK,{img_cols}",
        # DLNG looks at the -JUDGE columns first; BYPASS_NG on LOWER still names that face.
        f"2,MDL,LOT-W,C002,DLNG,ITEM_H_L,OK,OK,BYPASS_NG,NG,OK,OK,{img_cols}",
        # ITEM_F is a backlight defect, so its images come from the PATH-1 columns.
        f"3,MDL,LOT-W,C003,NG,ITEM_F,OK,OK,OK,OK,NG,OK,{img_cols}",
    ])
    run_agent(b, LINE, ["EXAMPLE_B_CATHODE"])
    W = f"{LINE}_EXAMPLE_B_CATHODE"
    check("inspected", scalar(f"SELECT inspected_count FROM lot_counters WHERE agent_id='{W}'"), 3)
    check("defect units", scalar(f"SELECT defect_unit_count FROM lot_counters WHERE agent_id='{W}'"), 2)
    check("NG and DLNG counted separately", scalar(
        f"SELECT GROUP_CONCAT(CONCAT(judgement,'=',unit_count) ORDER BY judgement) "
        f"FROM lot_counter_judgement WHERE agent_id='{W}'", ""), "DLNG=1,NG=1")
    check("BYPASS_NG counts as a defective face", scalar(
        f"SELECT GROUP_CONCAT(CONCAT(i.side,':',i.raw_value) ORDER BY i.side) FROM defect_items i "
        f"JOIN defect_occurrences o ON o.id=i.occurrence_id WHERE o.agent_id='{W}' AND o.unit_seq=2", ""),
          "LOWER:BYPASS_NG,UPPER:NG")
    check("same defect on two faces is two item rows", scalar(
        f"SELECT COUNT(*) FROM defect_items i JOIN defect_occurrences o ON o.id=i.occurrence_id "
        f"WHERE o.agent_id='{W}' AND o.unit_seq=2 AND i.item_name='ITEM_H_L'"), 2)
    check("DLNG unit takes PATH-3 (default) images", paths(
        f"SELECT GROUP_CONCAT(im.source_path) FROM defect_images im "
        f"JOIN defect_occurrences o ON o.id=im.occurrence_id WHERE o.agent_id='{W}' AND o.unit_seq=2"),
          ["F:\\lo3.jpg", "F:\\lo3o.jpg", "F:\\up3.jpg", "F:\\up3o.jpg"])
    check("backlight defect takes PATH-1 images, LOWER face only", paths(
        f"SELECT GROUP_CONCAT(im.source_path) FROM defect_images im "
        f"JOIN defect_occurrences o ON o.id=im.occurrence_id WHERE o.agent_id='{W}' AND o.unit_seq=3"),
          ["F:\\lo1.jpg", "F:\\lo1o.jpg"])
    check("model id read from the MODEL-ID column",
          scalar(f"SELECT model_id FROM defect_occurrences WHERE agent_id='{W}' AND unit_seq=2", ""), "MDL")

    # ---------------------------------------------------------------- example_c
    print("\n[C] EXAMPLE_C - polarity marker in the item name picks the image set")
    c = os.path.join(work, "example_c")
    write_csv(c, f"MDL_{TODAY}.csv", [
        "NO,Result,LOT-ID,CELL-ID,(+)LeadFilm(Dn)-OK/NG,(+)LeadFilm(Dn),(-)LeadTab-OK/NG,(-)LeadTab,"
        "Image_Path,Overlay_Image_Path,Image_Path_2,Overlay_Image_Path_2",
        "1,NG,LOT-L,C001,NG,3.14,OK,2.71,F:\\p.jpg,F:\\p_ov.jpg,F:\\m.jpg,F:\\m_ov.jpg",
        "2,NG,LOT-L,C002,OK,3.14,NG,2.71,F:\\p.jpg,F:\\p_ov.jpg,F:\\m.jpg,F:\\m_ov.jpg",
    ])
    run_agent(c, LINE, ["EXAMPLE_C"])
    L = f"{LINE}_EXAMPLE_C"
    check("a (+) item pulls only the PLUS set", scalar(
        f"SELECT GROUP_CONCAT(DISTINCT im.set_label) FROM defect_images im "
        f"JOIN defect_occurrences o ON o.id=im.occurrence_id WHERE o.agent_id='{L}' AND o.unit_seq=1", ""), "PLUS")
    check("a (-) item pulls only the MINUS set", scalar(
        f"SELECT GROUP_CONCAT(DISTINCT im.set_label) FROM defect_images im "
        f"JOIN defect_occurrences o ON o.id=im.occurrence_id WHERE o.agent_id='{L}' AND o.unit_seq=2", ""), "MINUS")

    # ---------------------------------------------------------------- example_c align
    print("\n[D] EXAMPLE_A - one process, one PC, two independent agents")
    d = os.path.join(work, "leadalign")
    for side, key in [("ANODE(-)", "ANODE"), ("CATHODE(+)", "CATHODE")]:
        write_csv(d, f"MDL_{side}_{TODAY}.csv", [
            "NO,Result,LOT-ID,CELL-ID,CHECK_F-OK/NG,CHECK_F,Image_Path,Overlay_Image_Path",
            "1,OK,LOT-LA,C001,OK,0.5,,",
            f"2,NG,LOT-LA,C002,NG,7.7,F:\\{key}.jpg,F:\\{key}_ov.jpg",
        ])
    run_agent(d, LINE, ["EXAMPLE_A_ANODE", "EXAMPLE_A_CATHODE"])
    check("both agents registered separately", scalar(
        f"SELECT COUNT(*) FROM agents WHERE agent_id LIKE '{LINE}_EXAMPLE_A_%'"), 2)
    check("anode counted on its own", scalar(
        f"SELECT inspected_count FROM lot_counters WHERE agent_id='{LINE}_EXAMPLE_A_ANODE'"), 2)
    check("cathode counted on its own", scalar(
        f"SELECT inspected_count FROM lot_counters WHERE agent_id='{LINE}_EXAMPLE_A_CATHODE'"), 2)
    check("each read only its own CSV", paths(
        "SELECT GROUP_CONCAT(source_path) FROM defect_images im "
        "JOIN defect_occurrences o ON o.id=im.occurrence_id "
        f"WHERE o.agent_id='{LINE}_EXAMPLE_A_ANODE'"), ["F:\\ANODE.jpg", "F:\\ANODE_ov.jpg"])
    check("example_c align has a single image set", scalar(
        "SELECT GROUP_CONCAT(DISTINCT set_label) FROM defect_images im "
        "JOIN defect_occurrences o ON o.id=im.occurrence_id "
        f"WHERE o.agent_id LIKE '{LINE}_EXAMPLE_A_%'", ""), "MAIN")

    # ---------------------------------------------------------------- restart
    print("\n[E] restarting the agent re-reads nothing")
    before = scalar(f"SELECT inspected_count FROM lot_counters WHERE agent_id='{P}'")
    run_agent(a, LINE, ["EXAMPLE_D"])
    check("counters unchanged after a second pass",
          scalar(f"SELECT inspected_count FROM lot_counters WHERE agent_id='{P}'"), before)

    print("\n[F] a lost state file replays safely instead of double counting")
    os.remove(os.path.join(a, "state.json"))
    run_agent(a, LINE, ["EXAMPLE_D"])
    check("counters still unchanged",
          scalar(f"SELECT inspected_count FROM lot_counters WHERE agent_id='{P}'"), before)
    check("the replay was recorded", scalar(
        f"SELECT replayed_count FROM agent_lot_progress WHERE agent_id='{P}'") > 0, True)

    print("\n[G] new rows appended after a restart are picked up")
    with open(os.path.join(a, "csv", f"MDL_{TODAY}.csv"), "a", encoding="utf-8", newline="") as f:
        f.write("4,NG,LOT-A,C004,NG,5.5,OK,1.0,OK,1.0,F:\\img\\4L.jpg,F:\\img\\4L_ov.jpg,F:\\img\\4R.jpg,F:\\img\\4R_ov.jpg\r\n")
    run_agent(a, LINE, ["EXAMPLE_D"])
    check("inspected advanced by one",
          scalar(f"SELECT inspected_count FROM lot_counters WHERE agent_id='{P}'"), before + 1)
    check("defect units advanced by one",
          scalar(f"SELECT defect_unit_count FROM lot_counters WHERE agent_id='{P}'"), 2)

    print("\n[H] a lot change resets counters and archives the old lot")
    with open(os.path.join(a, "csv", f"MDL_{TODAY}.csv"), "a", encoding="utf-8", newline="") as f:
        # A real lot change restarts the numbering; that restart is the boundary.
        f.write("1,OK,LOT-B,C005,OK,1.0,OK,1.0,OK,1.0,,,,\r\n")
    run_agent(a, LINE, ["EXAMPLE_D"])
    check("current lot", scalar(f"SELECT lot_id FROM lot_counters WHERE agent_id='{P}'", ""), "LOT-B")
    check("counters reset", scalar(f"SELECT inspected_count FROM lot_counters WHERE agent_id='{P}'"), 1)
    check("LOT-A archived with its total", scalar(
        f"SELECT inspected_count FROM lot_history WHERE agent_id='{P}' AND lot_id='LOT-A'"), before + 1)

    print("\n[I] redeploying mid-lot resumes the running lot instead of resetting it")
    # Deploy wipes state.json, so the agent wakes with no saved position in front of a
    # file whose first rows belong to a lot that has already finished. Reading from the
    # top announced that finished lot, which closed the lot actually running and zeroed
    # its counters - a redeploy reset production and defect rate to zero on the floor.
    with open(os.path.join(a, "csv", f"MDL_{TODAY}.csv"), "a", encoding="utf-8", newline="") as f:
        f.write("2,OK,LOT-B,C006,OK,1.0,OK,1.0,OK,1.0,,,,\r\n")
        f.write("3,NG,LOT-B,C007,NG,7.7,OK,1.0,OK,1.0,,,,\r\n")
    run_agent(a, LINE, ["EXAMPLE_D"])
    check("lot B counted before the redeploy",
          scalar(f"SELECT inspected_count FROM lot_counters WHERE agent_id='{P}'"), 3)

    os.remove(os.path.join(a, "state.json"))
    run_agent(a, LINE, ["EXAMPLE_D"])
    check("still on the running lot", scalar(
        f"SELECT lot_id FROM lot_counters WHERE agent_id='{P}'", ""), "LOT-B")
    check("counters survived the redeploy",
          scalar(f"SELECT inspected_count FROM lot_counters WHERE agent_id='{P}'"), 3)
    check("defect count survived the redeploy",
          scalar(f"SELECT defect_unit_count FROM lot_counters WHERE agent_id='{P}'"), 1)
    check("the finished lot was not reopened", scalar(
        f"SELECT COUNT(*) FROM lot_history WHERE agent_id='{P}' AND lot_id='LOT-B'"), 0)

    with open(os.path.join(a, "csv", f"MDL_{TODAY}.csv"), "a", encoding="utf-8", newline="") as f:
        f.write("4,OK,LOT-B,C008,OK,1.0,OK,1.0,OK,1.0,,,,\r\n")
    run_agent(a, LINE, ["EXAMPLE_D"])
    check("and it keeps counting from where it was",
          scalar(f"SELECT inspected_count FROM lot_counters WHERE agent_id='{P}'"), 4)

    print("\n[I2] the lot name changing early does not open the lot at the old numbering")
    # Straight from C-3 Example A. The vision software writes the new LOT-ID a row or two
    # before it restarts NO, so those rows carry the new name and the outgoing lot's
    # numbering:
    #
    #     No 11933   06:01:31   LOTID025KZ     <- new name, old numbering
    #     No     1   06:03:21   LOTID025KZ     <- the lot really starts here
    #
    # Believing the name opened the new lot at 11933, and every real row after it was then
    # refused as already seen. The inspector counted one unit for the rest of the shift.
    k = os.path.join(work, "boundary")
    head = ("NO,Result,LOT-ID,CELL-ID,CHECK_A-OK/NG,CHECK_A,CHECK_B-OK/NG,CHECK_B,"
            "CHECK_C-OK/NG,CHECK_C,Image_Path,Overlay_Image_Path,Image_Path_2,Overlay_Image_Path_2")
    # Read live, the way the agent meets the boundary on the floor: the old lot first,
    # then the renamed row and the restart arriving as the file grows.
    write_csv(k, f"MDL_{TODAY}.csv", [
        head,
        "900,OK,LOT-OLD,C900,OK,1.0,OK,1.0,OK,1.0,,,,",
        "901,OK,LOT-OLD,C901,OK,1.0,OK,1.0,OK,1.0,,,,",
    ])
    run_agent(k, LINE, ["EXAMPLE_D"])
    with open(os.path.join(k, "csv", f"MDL_{TODAY}.csv"), "a", encoding="utf-8", newline="") as f:
        # Renamed early: still LOT-OLD's numbering.
        f.write("902,OK,LOT-NEW,C902,OK,1.0,OK,1.0,OK,1.0,,,,\r\n")
        # The numbering restarts - this is where LOT-NEW actually begins.
        f.write("1,OK,LOT-NEW,D001,OK,1.0,OK,1.0,OK,1.0,,,,\r\n")
        f.write("2,NG,LOT-NEW,D002,NG,7.7,OK,1.0,OK,1.0,,,,\r\n")
        f.write("3,OK,LOT-NEW,D003,OK,1.0,OK,1.0,OK,1.0,,,,\r\n")
    run_agent(k, LINE, ["EXAMPLE_D"])

    check("the new lot is current", scalar(
        f"SELECT lot_id FROM lot_counters WHERE agent_id='{P}'", ""), "LOT-NEW")
    # 3 rows numbered 1..3 - the early-renamed row belongs to the lot whose numbering it
    # carries, so it is counted there rather than opening the new lot at 902.
    check("the new lot counts its own rows", scalar(
        f"SELECT inspected_count FROM lot_counters WHERE agent_id='{P}'"), 3)
    check("and its defect", scalar(
        f"SELECT defect_unit_count FROM lot_counters WHERE agent_id='{P}'"), 1)
    check("the early-renamed row stayed with the old lot", scalar(
        f"SELECT inspected_count FROM lot_history WHERE agent_id='{P}' AND lot_id='LOT-OLD'"), 3)
    # The whole point: the read position must not be parked beyond the lot's own rows.
    check("the read position matches the lot, not the old numbering", scalar(
        f"SELECT last_unit_seq FROM agent_lot_progress WHERE agent_id='{P}' AND lot_id='LOT-NEW'"), 3)

    print("\n[I3] a fresh agent lands on the lot's real first row, not the renamed one")
    # Same file, but read by an agent with no saved position - the deploy case. The seek
    # has to skip the early-renamed row too, or the very first thing it sends parks the
    # read position out of reach again.
    os.remove(os.path.join(k, "state.json"))
    cleanup([P])
    run_agent(k, LINE, ["EXAMPLE_D"])
    check("it starts at the numbering restart", scalar(
        f"SELECT inspected_count FROM lot_counters WHERE agent_id='{P}'"), 3)
    check("with a read position it can pass", scalar(
        f"SELECT last_unit_seq FROM agent_lot_progress WHERE agent_id='{P}' AND lot_id='LOT-NEW'"), 3)

    print("\n[I4] a lot name the inspector never read does not lose the lot change")
    # Straight from A-2 Example A Cathode. The inspector leaves LOT-ID blank whenever it
    # fails to read one, which is routine - and one of those blanks landed on the very row
    # where the numbering restarted:
    #
    #     15380  06:01:04  (blank)      <- still the old lot
    #         1  06:02:38  (blank)      <- the new lot starts here, name not read
    #         2  06:02:42  LOTID053KA   <- and this is what it is called
    #
    # Believing the names, the restart went unnoticed and every row of the new lot was
    # relabelled with the old lot, whose numbering it was far below - so all of it was
    # discarded. The inspector showed yesterday's total all day.
    n = os.path.join(work, "noread")
    head = ("NO,Result,LOT-ID,CELL-ID,CHECK_A-OK/NG,CHECK_A,CHECK_B-OK/NG,CHECK_B,"
            "CHECK_C-OK/NG,CHECK_C,Image_Path,Overlay_Image_Path,Image_Path_2,Overlay_Image_Path_2")
    write_csv(n, f"MDL_{TODAY}.csv", [
        head,
        "500,OK,LOT-E,E500,OK,1.0,OK,1.0,OK,1.0,,,,",
        "501,OK,LOT-E,E501,OK,1.0,OK,1.0,OK,1.0,,,,",
        # A run of rows the inspector could not read a lot for - still the old lot.
        "502,OK,,E502,OK,1.0,OK,1.0,OK,1.0,,,,",
        "503,OK,,E503,OK,1.0,OK,1.0,OK,1.0,,,,",
    ])
    run_agent(n, LINE, ["EXAMPLE_D"])
    check("blank names stay with the running lot", scalar(
        f"SELECT lot_id FROM lot_counters WHERE agent_id='{P}'", ""), "LOT-E")
    check("and are counted, not discarded", scalar(
        f"SELECT inspected_count FROM lot_counters WHERE agent_id='{P}'"), 4)

    with open(os.path.join(n, "csv", f"MDL_{TODAY}.csv"), "a", encoding="utf-8", newline="") as f:
        # The numbering restarts, and this row has no name either.
        f.write("1,OK,,F001,OK,1.0,OK,1.0,OK,1.0,,,,\r\n")
        # The name only turns up here.
        f.write("2,NG,LOT-F,F002,NG,7.7,OK,1.0,OK,1.0,,,,\r\n")
        f.write("3,OK,LOT-F,F003,OK,1.0,OK,1.0,OK,1.0,,,,\r\n")
    run_agent(n, LINE, ["EXAMPLE_D"])

    check("the restart is found even though its row had no name", scalar(
        f"SELECT lot_id FROM lot_counters WHERE agent_id='{P}'", ""), "LOT-F")
    # All three rows of the new lot, including the unnamed one that opened it.
    check("the unnamed first row is counted in the new lot", scalar(
        f"SELECT inspected_count FROM lot_counters WHERE agent_id='{P}'"), 3)
    check("its defect too", scalar(
        f"SELECT defect_unit_count FROM lot_counters WHERE agent_id='{P}'"), 1)
    check("the old lot closed with its own rows", scalar(
        f"SELECT inspected_count FROM lot_history WHERE agent_id='{P}' AND lot_id='LOT-E'"), 4)
    # The read position has to be the new lot's own numbering, not the old lot's 503.
    check("the read position belongs to the new lot", scalar(
        f"SELECT last_unit_seq FROM agent_lot_progress WHERE agent_id='{P}' AND lot_id='LOT-F'"), 3)
    check("nothing landed in a nameless bucket", scalar(
        f"SELECT COUNT(*) FROM agent_lot_progress WHERE agent_id='{P}' AND lot_id=''"), 0)

    print("\n[I5] one misread lot name mid-lot does not move a cold start into the middle")
    # Why A-2 Example A Cathode counted from zero after a resync. A resync deletes the
    # saved position, so the agent seeks to where the running lot begins - and the seek
    # used to call any change of name the start of a new run. This inspector misreads
    # LOT-ID often, so one wrong name partway in looked like the lot restarting there:
    #
    #       1  LOTID053KA   <- the lot really starts here
    #     ...
    #       6  LOTID0       <- one misread row
    #       7  LOTID053KA   <- looked like a fresh run of the same lot
    #
    # The seek landed on that last row and the lot was counted from there on. Both the
    # seek and the collector now read the numbering, and only the numbering.
    m = os.path.join(work, "misread")
    head = ("NO,Result,LOT-ID,CELL-ID,CHECK_A-OK/NG,CHECK_A,CHECK_B-OK/NG,CHECK_B,"
            "CHECK_C-OK/NG,CHECK_C,Image_Path,Overlay_Image_Path,Image_Path_2,Overlay_Image_Path_2")
    rows = [head]
    # The lot before it, which a cold start must not reach back into.
    for i in range(900, 905):
        rows.append(f"{i},OK,LOT-G,C{i},OK,1.0,OK,1.0,OK,1.0,,,,")
    # The lot in progress: eight rows, one of them with a mangled name.
    for i in range(1, 9):
        lot = "LOTID0" if i == 6 else "LOT-H"
        rows.append(f"{i},OK,{lot},D{i:03d},OK,1.0,OK,1.0,OK,1.0,,,,")
    write_csv(m, f"MDL_{TODAY}.csv", rows)
    run_agent(m, LINE, ["EXAMPLE_D"])

    check("the running lot is the one in progress", scalar(
        f"SELECT lot_id FROM lot_counters WHERE agent_id='{P}'", ""), "LOT-H")
    # All eight rows, the misread one included - a row whose name could not be read is
    # still a unit the machine inspected.
    check("counted from the lot's first row, not the misread", scalar(
        f"SELECT inspected_count FROM lot_counters WHERE agent_id='{P}'"), 8)
    check("the lot before it was not replayed", scalar(
        f"SELECT COUNT(*) FROM lot_counters WHERE agent_id='{P}' AND lot_id='LOT-G'"), 0)
    check("and no phantom lot was opened", scalar(
        f"SELECT COUNT(*) FROM lot_history WHERE agent_id='{P}' AND lot_id='LOTID0'"), 0)

    print("\n[I6] the row's own time is kept, and a byte order mark does not shift the seek")
    # Both of these came out of one A-2 log. The real files are written with a byte order
    # mark, and the seek used to work its offsets out by re-encoding text the reader had
    # already decoded - with the mark stripped, so every offset was three bytes short and
    # a cold start opened mid-row:
    #
    #     MDL_ANODE(-)_20260905.csv: starting at lot LOTID053KA (offset 1914040)
    #     MDL_ANODE(-)_20260905.csv line 0: no usable 'NO' value
    #
    # And each unit was stamped with the moment the agent read it rather than the moment
    # the machine inspected it, so a backlog - after a stoppage, a redeploy or a resync -
    # arrived as hundreds of defects at one identical second.
    t = os.path.join(work, "stamped")
    head = ("NO,Time,Result,LOT-ID,CELL-ID,CHECK_A-OK/NG,CHECK_A,CHECK_B-OK/NG,CHECK_B,"
            "CHECK_C-OK/NG,CHECK_C,Image_Path,Overlay_Image_Path,Image_Path_2,Overlay_Image_Path_2")
    write_csv(t, f"MDL_{TODAY}.csv", [
        head,
        # The lot before it, which the cold start must not reach back into.
        "800,5:00:00,OK,LOT-J,C800,OK,1.0,OK,1.0,OK,1.0,,,,",
        "801,5:00:04,OK,LOT-J,C801,OK,1.0,OK,1.0,OK,1.0,,,,",
        # The lot in progress. Its first row is the one an off-by-three seek ate.
        "1,6:01:04,OK,LOT-K,E001,OK,1.0,OK,1.0,OK,1.0,,,,",
        "2,6:01:08,NG,LOT-K,E002,NG,7.7,OK,1.0,OK,1.0,,,,",
        "3,6:44:31,OK,LOT-K,E003,OK,1.0,OK,1.0,OK,1.0,,,,",
        "4,7:12:59,NG,LOT-K,E004,NG,8.8,OK,1.0,OK,1.0,,,,",
    ], encoding="utf-8-sig")
    log = run_agent(t, LINE, ["EXAMPLE_D"])

    check("the seek lands on a row boundary", "no usable" in log, False)
    check("so the lot's first row is not eaten", scalar(
        f"SELECT inspected_count FROM lot_counters WHERE agent_id='{P}'"), 4)
    check("and the lot before it is left alone", scalar(
        f"SELECT COUNT(*) FROM lot_counters WHERE agent_id='{P}' AND lot_id='LOT-J'"), 0)

    # Read in one pass, so every one of these carried the same stamp before.
    stamps = (f"SELECT DATE_FORMAT(occurred_at,'%H:%i:%s') FROM defect_occurrences "
              f"WHERE agent_id='{P}' AND lot_id='LOT-K'")
    check("the defect is stamped with the row's time", scalar(
        stamps + " AND unit_seq=2", ""), "06:01:08")
    check("and the next one with its own, not the reading time", scalar(
        stamps + " AND unit_seq=4", ""), "07:12:59")
    # A time column with no date in it takes the day from the file it is in.
    check("the day comes from the file", scalar(
        f"SELECT DATE_FORMAT(occurred_at,'%Y-%m-%d') FROM defect_occurrences "
        f"WHERE agent_id='{P}' AND lot_id='LOT-K' AND unit_seq=2", ""),
        datetime.now().strftime("%Y-%m-%d"))

    print("\n[J] a server that stops answering does not stop the agent")
    # HttpClient reports its own timeout as a cancellation, and the collector loop used to
    # treat any cancellation as "the service is shutting down" and exit for good. The
    # service stayed up and kept heartbeating, so every inspector on the floor looked
    # alive and merely idle while nothing was being read at all.
    listener, port = black_hole_server()
    try:
        j = os.path.join(work, "timeout")
        write_csv(j, f"MDL_{TODAY}.csv", [
            "NO,Result,LOT-ID,CELL-ID,CHECK_A-OK/NG,CHECK_A,CHECK_B-OK/NG,CHECK_B,"
            "CHECK_C-OK/NG,CHECK_C,Image_Path,Overlay_Image_Path,Image_Path_2,Overlay_Image_Path_2",
            "1,OK,LOT-J,J001,OK,1.0,OK,1.0,OK,1.0,,,,",
            "2,OK,LOT-J,J002,OK,1.0,OK,1.0,OK,1.0,,,,",
        ])
        log = run_agent_background(
            j, LINE, ["EXAMPLE_D"],
            f"http://127.0.0.1:{port}/dashboard/api/ingest/events",
            timeout_seconds=1, seconds=6)
    finally:
        listener.close()

    timeouts = log.count("send timed out")
    # More than one means the loop came back for another pass instead of ending silently.
    check("the timeout is reported", timeouts > 0, True)
    check("and the collector keeps trying", timeouts > 1, True)
    check("the loop did not treat it as shutdown",
          "collector stopped unexpectedly" in log, False)

finally:
    print("\nCleaning up...")
    cleanup(ALL_AGENTS)
    shutil.rmtree(work, ignore_errors=True)

print()
if failures:
    print(f"FAILED: {len(failures)} check(s): {failures}")
    sys.exit(1)
print("All agent checks passed.")
