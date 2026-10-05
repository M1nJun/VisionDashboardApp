"""Checks that the deployer's output is something the agent actually accepts.

The inspection PCs are not reachable from here, so nothing is copied. What is tested is
the part that used to go wrong silently: the per-PC config. The old deployer rendered a
template per vision type, and a typo in one produced an agent that ran happily and wrote
rows no dashboard cell ever looked up. Here the config is generated from the topology and
then fed to the real agent exe, which either accepts it or fails loudly.

  python deploy/deploy-test.py
"""

import json
import os
import shutil
import subprocess
import sys
import tempfile
from datetime import datetime

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
# The published single-file build, not the Debug one: this test copies the exe on its own
# into a folder laid out like an inspection PC, and a Debug build needs its DLLs beside it.
# It is also what actually ships, so testing anything else proves less.
EXE = os.path.join(REPO, "agent", "bin", "Release", "net8.0", "win-x64", "publish", "VisionAgent.exe")
MYSQL = os.environ.get("VISIONDASH_MYSQL", r"C:\Program Files\MySQL\MySQL Server 8.4\bin\mysql.exe")
DB = "visiondash"
# Credentials come from the environment, never from this file. MYSQL_PWD is the mysql
# client's own variable, so the password reaches it without ever appearing in a command
# line, a process listing, or a commit.
DB_USER = os.environ.get("VISIONDASH_DB_USER", "root")
if "VISIONDASH_DB_PASSWORD" not in os.environ:
    sys.exit("Set VISIONDASH_DB_PASSWORD (your MySQL password) before running this.")
MYSQL_ENV = {**os.environ, "MYSQL_PWD": os.environ["VISIONDASH_DB_PASSWORD"]}

TODAY = datetime.now().strftime("%Y%m%d")

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
    return out.stdout.strip()


def powershell(args):
    out = subprocess.run(["powershell.exe", "-NoProfile", "-NonInteractive",
                          "-ExecutionPolicy", "Bypass", "-File",
                          os.path.join(HERE, "Deploy-Agent.ps1")] + args,
                         capture_output=True, text=True, encoding="utf-8", timeout=180)
    if out.returncode != 0:
        raise RuntimeError((out.stderr or out.stdout)[:800])
    return out.stdout


def load(name):
    with open(os.path.join(REPO, "contracts", name), encoding="utf-8") as f:
        return json.load(f)


topology = load("topology.json")
catalog = load("vision-catalog.json")
known_keys = {v["key"] for v in catalog["visionTypes"]}

emit = tempfile.mkdtemp(prefix="visiondash-deploy-")
work = tempfile.mkdtemp(prefix="visiondash-deploy-run-")

try:
    print("[1] every PC in the topology gets exactly one config")
    powershell(["-Emit", emit])
    written = [f for f in os.listdir(emit) if f.endswith(".json")]
    check("config count", len(written), len(topology["pcs"]))

    configs = {}
    for name in written:
        with open(os.path.join(emit, name), encoding="utf-8") as f:
            configs[name] = json.load(f)

    print("\n[2] each config matches its topology entry")
    by_ip = {pc["ip"]: pc for pc in topology["pcs"]}
    mismatched_line = 0
    mismatched_keys = 0
    unknown = 0
    for name, config in configs.items():
        ip = name[:-5].split("_", 1)[1]
        pc = by_ip[ip]
        if config["line"] != pc["line"]:
            mismatched_line += 1
        if config["visionKeys"] != pc["hosts"]:
            mismatched_keys += 1
        for key in config["visionKeys"]:
            if key not in known_keys:
                unknown += 1
    check("lines match", mismatched_line, 0)
    check("vision keys match", mismatched_keys, 0)
    check("no key outside the catalog", unknown, 0)

    print("\n[3] the one PC that serves two inspectors gets both")
    example_a = [c for c in configs.values() if len(c["visionKeys"]) > 1]
    # One example_c align PC per line, whatever the topology currently lists.
    check("PCs with more than one vision key", len(example_a), len(topology["lines"]))
    check("and it is example_c align both ways", sorted(example_a[0]["visionKeys"]),
          ["EXAMPLE_A_ANODE", "EXAMPLE_A_CATHODE"])

    print("\n[4] every config points at the same server")
    urls = {c["server"]["eventsUrl"] for c in configs.values()}
    check("one ingest url", len(urls), 1)
    check("url shape", urls.pop().endswith("/dashboard/api/ingest/events"), True)

    print("\n[5] the agent accepts a generated config and reports under the right ids")
    # A real Example A config, pointed at synthetic CSVs, run by the real exe.
    target_name = next(n for n, c in configs.items() if len(c["visionKeys"]) > 1)
    config = configs[target_name]
    line = config["line"]
    agents = [f"{line}_{key}" for key in config["visionKeys"]]

    csv_dir = os.path.join(work, "csv")
    os.makedirs(csv_dir, exist_ok=True)
    for side in ("ANODE(-)", "CATHODE(+)"):
        with open(os.path.join(csv_dir, f"MDL_{side}_{TODAY}.csv"), "w",
                  encoding="utf-8", newline="") as f:
            f.write("\r\n".join([
                "NO,Result,LOT-ID,CELL-ID,CHECK_F-OK/NG,CHECK_F,Image_Path,Overlay_Image_Path",
                "1,OK,LOT-DEP,C001,OK,0.4,,",
                "2,NG,LOT-DEP,C002,NG,8.8,F:\\a.jpg,F:\\a_ov.jpg",
            ]) + "\r\n")

    quoted = ",".join(f"'{a}'" for a in agents)
    sql(f"""
        DELETE FROM defect_images WHERE occurrence_id IN
          (SELECT id FROM defect_occurrences WHERE agent_id IN ({quoted}));
        DELETE FROM defect_occurrences WHERE agent_id IN ({quoted});
        DELETE FROM lot_counter_judgement WHERE agent_id IN ({quoted});
        DELETE FROM lot_counters WHERE agent_id IN ({quoted});
        DELETE FROM agent_lot_progress WHERE agent_id IN ({quoted});
        DELETE FROM vision_rollup_judgement WHERE agent_id IN ({quoted});
        DELETE FROM vision_rollup WHERE agent_id IN ({quoted});
        DELETE FROM raw_events WHERE agent_id IN ({quoted});
        DELETE FROM agents WHERE agent_id IN ({quoted});
    """)

    # Only the three things a deployment cannot know are overridden: where the CSVs are on
    # this machine, and where to keep state and logs. Everything the deployer decided -
    # line, visionKeys, server address - is used exactly as generated.
    config["sourceFolderOverride"] = csv_dir
    config["stateFile"] = os.path.join(work, "state.json")
    config["logFile"] = os.path.join(work, "agent.log")
    config["server"]["eventsUrl"] = "http://127.0.0.1:8080/dashboard/api/ingest/events"
    config["server"]["heartbeatHost"] = "127.0.0.1"
    # Laid out exactly as the service will find it: exe, catalog and agent.json side by
    # side, and the agent started with no arguments at all. The service's binPath names
    # only the exe, so if the agent did not fall back to agent.json beside itself, every
    # deployed PC would fail to start and this test would still have passed.
    install = os.path.join(work, "install")
    os.makedirs(install, exist_ok=True)
    shutil.copy2(EXE, os.path.join(install, "VisionAgent.exe"))
    shutil.copy2(os.path.join(os.path.dirname(EXE), "vision-catalog.json"),
                 os.path.join(install, "vision-catalog.json"))
    with open(os.path.join(install, "agent.json"), "w", encoding="utf-8") as f:
        json.dump(config, f, indent=2)

    result = subprocess.run([os.path.join(install, "VisionAgent.exe"), "--once"],
                            capture_output=True, text=True, timeout=120)
    check("agent exit code", result.returncode, 0)
    check("both agents registered", int(sql(
        f"SELECT COUNT(*) FROM agents WHERE agent_id IN ({quoted})")), 2)
    check("each counted its own CSV", sql(
        f"SELECT GROUP_CONCAT(CONCAT(agent_id,'=',inspected_count) ORDER BY agent_id) "
        f"FROM lot_counters WHERE agent_id IN ({quoted})"),
        f"{agents[0]}=2,{agents[1]}=2")
    check("defects recorded", int(sql(
        f"SELECT COUNT(*) FROM defect_occurrences WHERE agent_id IN ({quoted})")), 2)

    sql(f"""
        DELETE FROM defect_images WHERE occurrence_id IN
          (SELECT id FROM defect_occurrences WHERE agent_id IN ({quoted}));
        DELETE FROM defect_occurrences WHERE agent_id IN ({quoted});
        DELETE FROM lot_counter_judgement WHERE agent_id IN ({quoted});
        DELETE FROM lot_counters WHERE agent_id IN ({quoted});
        DELETE FROM agent_lot_progress WHERE agent_id IN ({quoted});
        DELETE FROM vision_rollup_judgement WHERE agent_id IN ({quoted});
        DELETE FROM vision_rollup WHERE agent_id IN ({quoted});
        DELETE FROM raw_events WHERE agent_id IN ({quoted});
        DELETE FROM agents WHERE agent_id IN ({quoted});
    """)

    print("\n[6] filters narrow the target list")
    one_line = tempfile.mkdtemp(prefix="visiondash-deploy-line-")
    powershell(["-Line", "C-2", "-Emit", one_line])
    check("one line", len([f for f in os.listdir(one_line) if f.endswith(".json")]),
          len([pc for pc in topology["pcs"] if pc["line"] == "C-2"]))
    shutil.rmtree(one_line, ignore_errors=True)

    one_key = tempfile.mkdtemp(prefix="visiondash-deploy-key-")
    powershell(["-VisionKey", "EXAMPLE_E", "-Emit", one_key])
    check("one vision key", len([f for f in os.listdir(one_key) if f.endswith(".json")]),
          len([pc for pc in topology["pcs"] if "EXAMPLE_E" in pc["hosts"]]))
    shutil.rmtree(one_key, ignore_errors=True)

    print("\n[7] a resync stops the agent before it clears what the agent writes")
    # Order, not wording. Clearing the server's record of an inspector while its agent is
    # still running gave the agent a few seconds to write its read position straight back
    # - and the re-read that followed the restart was then refused unit by unit, so the
    # inspector counted only what the machine made after the resync.
    script = open(os.path.join(HERE, "Resync-Lot.ps1"), encoding="utf-8").read()
    stop = script.index('"stop", $config.serviceName')
    clear = script.index("DELETE FROM agent_lot_progress")
    state = script.index("Remove-Item $statePath")
    start = script.index('"start", $config.serviceName')
    check("the service is stopped before the database is cleared", stop < clear, True)
    check("and before its state file is deleted", stop < state, True)
    check("and only started again afterwards", clear < start and state < start, True)

finally:
    shutil.rmtree(emit, ignore_errors=True)
    shutil.rmtree(work, ignore_errors=True)

print()
if failures:
    print(f"FAILED: {len(failures)} check(s): {failures}")
    sys.exit(1)
print("All deploy checks passed.")
