"""Fails loudly on any drift between vision-catalog.json and topology.json.

Every component reads these two files, so a mismatch here is the class of bug
that used to surface silently as an empty grid cell or an image that never
loads. Run this in CI and at server startup rather than trusting the files.
"""

import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))


def load(name):
    with open(os.path.join(HERE, name), "r", encoding="utf-8") as f:
        return json.load(f)


def main():
    catalog = load("vision-catalog.json")
    topology = load("topology.json")
    errors = []

    types = catalog["visionTypes"]
    keys = [v["key"] for v in types]
    profiles = catalog["thresholdProfiles"]

    if len(set(keys)) != len(keys):
        errors.append("duplicate vision key in catalog")

    orders = sorted(v["gridOrder"] for v in types)
    if orders != list(range(1, len(types) + 1)):
        errors.append(f"gridOrder must be 1..{len(types)} with no gaps, got {orders}")

    # shortName is what the dashboard actually prints in a 240px column. It has to be
    # short enough to never truncate and distinct enough to tell two inspectors apart
    # at a glance, so both are checked here rather than discovered on screen.
    short_names = [v.get("shortName", "") for v in types]
    if len(set(short_names)) != len(short_names):
        errors.append(f"duplicate shortName in catalog: {sorted(short_names)}")

    for v in types:
        key = v["key"]
        if not re.fullmatch(r"[A-Z][A-Z0-9_]*", key):
            errors.append(f"{key}: key must be UPPER_SNAKE")
        if v["thresholdProfile"] not in profiles:
            errors.append(f"{key}: unknown thresholdProfile {v['thresholdProfile']}")

        short = v.get("shortName", "")
        if not short:
            errors.append(f"{key}: shortName is required")
        elif len(short) > 6:
            errors.append(f"{key}: shortName '{short}' is {len(short)} chars, max 6")

        src = v["source"]
        judge_codes = {d["code"] for d in v["judgements"]["defect"]}
        if not any(d["drivesColor"] for d in v["judgements"]["defect"]):
            errors.append(f"{key}: no defect judgement has drivesColor=true")

        disc = src["itemDiscovery"]
        if disc["mode"] == "NAMED_COLUMN":
            missing = judge_codes - set(disc["sideColumnFormats"])
            if missing:
                errors.append(f"{key}: sideColumnFormats missing judgements {sorted(missing)}")
        elif disc["mode"] != "SCAN_SUFFIX":
            errors.append(f"{key}: unknown itemDiscovery mode {disc['mode']}")

        img = src["images"]
        sets = img["sets"]
        if img["selection"] == "BY_ITEM_MARKER":
            unknown = set(img["markers"]) - set(sets)
            if unknown:
                errors.append(f"{key}: markers reference undefined image sets {sorted(unknown)}")
        if img["selection"] == "BY_DEFECT_SIDE":
            missing = set(disc.get("sides", [])) - set(sets)
            if missing:
                errors.append(f"{key}: sides without an image set {sorted(missing)}")

        # Every set is {variant: {main, overlay}} with a "default" variant, even where
        # only one variant exists - the agent then has one code path instead of two.
        declared_variants = set(img.get("variantByItem", {}))
        for label, spec in sets.items():
            if "default" not in spec:
                errors.append(f"{key}/{label}: image set has no 'default' variant")
            for variant, pair in spec.items():
                if variant != "default" and variant not in declared_variants:
                    errors.append(f"{key}/{label}: variant '{variant}' is not listed in variantByItem")
                for field in ("main", "overlay"):
                    if not pair.get(field):
                        errors.append(f"{key}/{label}/{variant}: missing {field} column")
        for variant in declared_variants:
            unbacked = [label for label, spec in sets.items() if variant not in spec]
            if unbacked:
                errors.append(f"{key}: variantByItem '{variant}' has no columns for sets {sorted(unbacked)}")

        if src["modelId"]["from"] == "FILENAME":
            try:
                rx = re.compile(src["modelId"]["regex"].replace("(?<model>", "(?P<model>"))
            except re.error as e:
                errors.append(f"{key}: bad modelId regex: {e}")
            else:
                if "model" not in rx.groupindex:
                    errors.append(f"{key}: modelId regex has no 'model' group")
        elif src["modelId"]["from"] == "COLUMN" and "modelId" not in src["columns"]:
            errors.append(f"{key}: modelId.from=COLUMN but columns.modelId is unset")

    hosted = {k for pc in topology["pcs"] for k in pc["hosts"]}
    for k in sorted(hosted - set(keys)):
        errors.append(f"topology hosts {k}, which is not in the catalog")
    for k in sorted(set(keys) - hosted):
        errors.append(f"catalog defines {k}, which no PC hosts")

    lines = set(topology["lines"])
    for pc in topology["pcs"]:
        if pc["line"] not in lines:
            errors.append(f"pc {pc['ip']} on unlisted line {pc['line']}")

    seen = {}
    for pc in topology["pcs"]:
        for k in pc["hosts"]:
            slot = (pc["line"], k)
            if slot in seen:
                errors.append(f"slot {slot} claimed by both {seen[slot]} and {pc['ip']}")
            seen[slot] = pc["ip"]

    refs = sorted(
        (v["productionRefPriority"], v["key"])
        for v in types
        if v["productionRefPriority"] is not None
    )
    if not refs:
        errors.append("no vision type has productionRefPriority - line production is undefined")
    if len({p for p, _ in refs}) != len(refs):
        errors.append(f"duplicate productionRefPriority: {refs}")

    for v in types:
        for d in v["judgements"]["defect"]:
            warn, crit = d.get("warnPct"), d.get("critPct")
            if d["drivesColor"] and (warn is not None or crit is not None):
                errors.append(
                    f"{v['key']}/{d['code']}: drivesColor judgements take their thresholds "
                    f"from thresholdProfile '{v['thresholdProfile']}', not from the judgement")
            for name, value in (("warnPct", warn), ("critPct", crit)):
                if value is not None and not 0 < value <= 100:
                    errors.append(f"{v['key']}/{d['code']}: {name} {value} is not a percentage")
            if warn is not None and crit is not None and crit <= warn:
                errors.append(f"{v['key']}/{d['code']}: critPct {crit} is not above warnPct {warn}")

    common = catalog.get("commonVisionKeys", [])
    if not common:
        errors.append("commonVisionKeys is empty - the rank panel would open on nothing")
    for key in common:
        if key not in keys:
            errors.append(f"commonVisionKeys names {key}, which is not in the catalog")
    if len(set(common)) != len(common):
        errors.append(f"duplicate commonVisionKeys: {sorted(common)}")

    # Headline rates on the dashboard header. A metric naming a judgement no vision
    # type declares would read 0.00% forever, which on a factory floor looks like good
    # news rather than a broken configuration.
    metrics = catalog.get("fleetMetrics", [])
    metric_keys = [m["key"] for m in metrics]
    if len(set(metric_keys)) != len(metric_keys):
        errors.append(f"duplicate fleetMetric key: {sorted(metric_keys)}")
    for metric in metrics:
        if not metric.get("label"):
            errors.append(f"fleetMetric {metric['key']}: label is required")
        if not metric.get("sources"):
            errors.append(f"fleetMetric {metric['key']}: no sources, the rate would always be 0")
        for source in metric.get("sources", []):
            vision = next((v for v in types if v["key"] == source["visionKey"]), None)
            if vision is None:
                errors.append(
                    f"fleetMetric {metric['key']}: unknown visionKey {source['visionKey']}")
                continue
            codes = [d["code"] for d in vision["judgements"]["defect"]]
            if source["judgement"] not in codes:
                errors.append(
                    f"fleetMetric {metric['key']}: {source['visionKey']} has no judgement "
                    f"{source['judgement']} (has {codes})")

    if errors:
        for e in errors:
            print("ERROR:", e)
        return 1

    print(f"OK  {len(types)} vision types, {len(topology['lines'])} lines, {len(topology['pcs'])} PCs, {len(seen)} slots")
    print("     grid order    :", [v["shortName"] for v in sorted(types, key=lambda x: x["gridOrder"])])
    print("     production ref:", " -> ".join(k for _, k in refs))
    print("     rank common   :", ", ".join(common))
    for metric in metrics:
        sources = ", ".join(f"{s['visionKey']}:{s['judgement']}" for s in metric["sources"])
        print(f"     metric {metric['key']:9} {sources}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
