#!/usr/bin/env python3
"""Read sent Oko log bundles over FTP and answer the questions the bundle can answer.

Stdlib only, so it runs anywhere Python does. Credentials come from app/upload.properties
(never committed), so nothing here is device- or server-specific.

    python tools/logreport.py list                     fleet table over the whole directory
    python tools/logreport.py show <name>              one bundle
    python tools/logreport.py compare <name> <name>    the diff
    python tools/logreport.py drain <name>             per-run battery drain, by screen/Doze state

Add --dir to point at a different remote folder (default: <remoteDir>/logs, since
upload.properties' remoteDir is the RELEASE directory) and --refresh to bypass the cache.

Two analysis rules are encoded here because getting them wrong has already produced wrong
conclusions twice:

  * Never average a skewed distribution. Brightness and interval figures are MEDIANs; the mean
    of a mostly-dark distribution with a few bright outliers reads as "far brighter when off".
  * Never compute a drain rate across a charge transition. Rates are computed only inside one
    contiguous non-charging run, and a run with fewer than two battery readings reports nothing.
"""

import argparse
import ftplib
import json
import os
import statistics
import sys
import tempfile
from ftplib import FTP

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PROPS = os.path.join(REPO, "app", "upload.properties")
CACHE = os.path.join(tempfile.gettempdir(), "oko_logs")

# A constant-state stretch shorter than this cannot carry a trustworthy drain rate: with
# whole-percent battery readings, a 10-minute window moves at most a few steps.
MIN_SEG_SEC = 900


def load_props(path=PROPS, remote_dir=None):
    if not os.path.exists(path):
        sys.exit(f"missing {path}\nCreate it with host/ftpHost, user, password and remoteDir.")
    props = {}
    with open(path, encoding="utf-8") as fh:
        for line in fh:
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            key, _, value = line.partition("=")
            props[key.strip()] = value.strip()
    props.setdefault("host", props.get("ftpHost", ""))
    props.setdefault("remoteDir", "/")
    props["remoteDir"] = remote_dir or props.get("logsRemoteDir") or (
        props["remoteDir"].rstrip("/") + "/logs"
    )
    return props


def connect(props):
    ftp = FTP(props["host"], timeout=60)
    ftp.login(props["user"], props["password"])
    return ftp


def remote_names(props):
    """Regular files only: the listing also carries `.`-style entries, folders and the release dir."""
    ftp = connect(props)
    try:
        ftp.cwd(props["remoteDir"])
        try:
            return sorted(
                e[0] for e in ftp.mlsd() if e[1]["type"] == "file" and e[0].endswith(".json")
            )
        except (AttributeError, ftplib.error_perm):
            names = []
            for name in sorted(ftp.nlst()):
                if not name.endswith(".json"):
                    continue
                try:
                    if ftp.size(name) is not None:
                        names.append(name)
                except ftplib.error_perm:
                    continue
            return names
    finally:
        ftp.quit()


def fetch(name, props, refresh=False):
    os.makedirs(CACHE, exist_ok=True)
    path = os.path.join(CACHE, name)
    if refresh or not os.path.exists(path):
        ftp = connect(props)
        try:
            ftp.cwd(props["remoteDir"])
            with open(path, "wb") as out:
                ftp.retrbinary(f"RETR {name}", out.write)
        finally:
            ftp.quit()
    with open(path, encoding="utf-8") as fh:
        text = fh.read()
    try:
        return json.loads(text)
    except json.JSONDecodeError as exc:
        sys.exit(f"{name}: not valid JSON ({exc}) - the upload was probably truncated")


def section(bundle, key):
    """Timeline blocks live under `logs`; read them from there, falling back to the root."""
    logs = bundle.get("logs")
    if isinstance(logs, dict) and key in logs:
        return logs[key]
    return bundle.get(key)


def power_summary(bundle):
    return section(bundle, "powerSummary") or {}


def samples(bundle):
    return section(bundle, "power") or []


def run_label(sample):
    state = "screen-on" if sample.get("interactive") else "screen-off"
    if sample.get("deviceIdle"):
        state += " +idle"
    if sample.get("powerSave"):
        state += " +saver"
    if sample.get("thermal"):
        state += " +thermal"
    zones = sample.get("activeZones") or 0
    if zones:
        state += f" zones={zones}"
    return state


def runs(bundle):
    """Contiguous non-charging stretches. A rate is only ever computed inside one of these."""
    out, current = [], None
    for s in samples(bundle):
        if s.get("charging"):
            current = None
            continue
        if current is None:
            current = [s]
            out.append(current)
        else:
            current.append(s)
    return out


def median(values):
    vals = [v for v in values if v is not None]
    return statistics.median(vals) if vals else None


def fmt(value, spec="{:.2f}"):
    return "n/a" if value is None else spec.format(value)


def cell(value):
    if value is None:
        return "n/a"
    return f"{value:.1f}" if isinstance(value, float) else str(value)


def dig(bundle, *path):
    node = bundle
    for step in path:
        if step == "powerSummary":
            node = power_summary(node) if isinstance(node, dict) else None
        else:
            node = (node or {}).get(step) if isinstance(node, dict) else None
    return node


def banner(bundle, name):
    app, dev = bundle.get("app", {}) or {}, bundle.get("device", {}) or {}
    print(f"\n=== {name}")
    print(
        f"{dev.get('model', '?')} / Android {dev.get('androidRelease', '?')} "
        f"(SDK {dev.get('sdkInt', '?')})  tz={dev.get('tz', '?')}"
    )
    print(
        f"build {app.get('versionName', '?')} ({app.get('versionCode', '?')}) "
        f"{app.get('flavor', '?')}  schema={bundle.get('schema', '?')}  "
        f"exported={bundle.get('exportedAt', '?')}"
    )


def cmd_list(args):
    rows = []
    for name in remote_names(args.props):
        try:
            b = fetch(name, args.props, refresh=args.refresh)
        except Exception as exc:  # one unreadable bundle must not sink the fleet table
            print(f"! {name}: {exc}", file=sys.stderr)
            continue
        ps = power_summary(b)
        rows.append(
            {
                "name": name,
                "model": dig(b, "device", "model"),
                "tz": (dig(b, "device", "tz") or "?").split("/")[-1],
                "build": dig(b, "app", "versionName"),
                "schema": b.get("schema"),
                "span_h": round((ps.get("spanSec") or 0) / 3600, 1),
                "samples": ps.get("samples", 0),
                "gap_s": ps.get("longestGapSec"),
                "restarts": ps.get("restartsObserved"),
                "cpu%": ps.get("cpuPctMean"),
                "exits": dig(b, "exitSummary", "total"),
                "screen%": ps.get("screenOnPct"),
                "zones%": ps.get("zonesActivePct"),
            }
        )
    if not rows:
        sys.exit("no bundles")
    cols = list(rows[0])
    widths = {c: max(len(c), max(len(cell(r[c])) for r in rows)) for c in cols}
    print("  ".join(c.ljust(widths[c]) for c in cols))
    print("  ".join("-" * widths[c] for c in cols))
    for r in rows:
        print("  ".join(cell(r[c]).ljust(widths[c]) for c in cols))


def cmd_show(args):
    b = fetch(args.name, args.props, refresh=args.refresh)
    banner(b, args.name)
    ps = power_summary(b)
    if ps:
        print(
            f"samples {ps.get('samples', 0)} over {fmt((ps.get('spanSec') or 0) / 3600, '{:.1f}')}h  "
            f"longest gap {fmt(ps.get('longestGapSec'), '{:.0f}')}s  "
            f"restarts {fmt(ps.get('restartsObserved'), '{:.0f}')}  "
            f"screen-on {fmt(ps.get('screenOnPct'), '{:.0f}')}%  "
            f"zones active {fmt(ps.get('zonesActivePct'), '{:.0f}')}%"
        )
    print(
        f"brightness median {fmt(median([s.get('screenBrightness') for s in samples(b)]), '{:.3f}')}  "
        f"battery {fmt(ps.get('batteryPctMin'), '{:.0f}')}..{fmt(ps.get('batteryPctMax'), '{:.0f}')}%"
    )
    print(
        f"process cpu {fmt(ps.get('cpuPctMean'), '{:.3f}')}% of one core on average, "
        f"peak {fmt(ps.get('cpuPctMax'), '{:.3f}')}% "
        f"({fmt(ps.get('cpuIntervals'), '{:.0f}')} intervals)"
    )
    for key in ("exitSummary", "exits", "decisions", "connections", "reconnects", "gps",
                "threats", "alerts"):
        block = section(b, key)
        if not block:
            continue
        if isinstance(block, list):
            print(f"{key}: {len(block)} records")
        else:
            print(f"{key}: {json.dumps(block, ensure_ascii=False)}")
    drain(b)


def cmd_compare(args):
    a = fetch(args.a, args.props, refresh=args.refresh)
    b = fetch(args.b, args.props, refresh=args.refresh)
    banner(a, args.a)
    banner(b, args.b)
    fields = [
        ("schema", ("schema",)),
        ("version", ("app", "versionName")),
        ("model", ("device", "model")),
        ("sdkInt", ("device", "sdkInt")),
        ("tz", ("device", "tz")),
        ("samples", ("powerSummary", "samples")),
        ("spanSec", ("powerSummary", "spanSec")),
        ("longestGapSec", ("powerSummary", "longestGapSec")),
        ("restartsObserved", ("powerSummary", "restartsObserved")),
        ("maxBlindWindowSec", ("powerSummary", "maxBlindWindowSec")),
        ("screenOnPct", ("powerSummary", "screenOnPct")),
        ("zonesActivePct", ("powerSummary", "zonesActivePct")),
        ("cpuPctMean", ("powerSummary", "cpuPctMean")),
        ("cpuPctMax", ("powerSummary", "cpuPctMax")),
        ("exits.total", ("exitSummary", "total")),
        ("exits.byReason", ("exitSummary", "byReason")),
        ("exits.whileForeground", ("exitSummary", "whileForeground")),
        ("heapMaxMb", ("memory", "heapMaxMb")),
        ("pssKb", ("memory", "pssKb")),
        ("battery.pct", ("battery", "pct")),
    ]
    print("\n" + "field".ljust(20) + args.a[:19].ljust(21) + args.b[:19])
    for label, path in fields:
        va, vb = dig(a, *path), dig(b, *path)
        sa = json.dumps(va, ensure_ascii=False) if isinstance(va, (dict, list)) else cell(va)
        sb = json.dumps(vb, ensure_ascii=False) if isinstance(vb, (dict, list)) else cell(vb)
        flag = "" if sa == sb else "  *"
        print(f"{label.ljust(20)}{sa[:20].ljust(21)}{sb[:19]}{flag}")
    print("\nbrightness median, screen-on vs screen-off:")
    for name, bundle in ((args.a, a), (args.b, b)):
        on = median([s.get("screenBrightness") for s in samples(bundle) if s.get("interactive")])
        off = median([s.get("screenBrightness") for s in samples(bundle) if not s.get("interactive")])
        print(f"  {name[:24]}  on={fmt(on, '{:.3f}')}  off={fmt(off, '{:.3f}')}")
    print("\n(* = differs between the two)")


def cmd_drain(args):
    b = fetch(args.name, args.props, refresh=args.refresh)
    banner(b, args.name)
    drain(b)


def segments(run):
    """Maximal stretches inside a run where the state label never changes."""
    out, current, label = [], None, None
    for s in run:
        tag = run_label(s)
        if tag != label:
            if current:
                out.append(current)
            current, label = [s], tag
        else:
            current.append(s)
    if current:
        out.append(current)
    return out


def segment_rate(seg):
    """(% lost, hours) for one segment, or None when it cannot support a figure.

    A segment needs >= MIN_SEG_SEC of wall clock AND two battery readings that agree the charge
    only fell. Anything else reports nothing rather than a guess.
    """
    readings = [(s["atMillis"], s["batteryPct"]) for s in seg if s.get("batteryPct") is not None]
    if len(readings) < 2:
        return None
    span_h = (seg[-1]["atMillis"] - seg[0]["atMillis"]) / 3_600_000
    if span_h * 3600 < MIN_SEG_SEC:
        return None
    drop = readings[0][1] - readings[-1][1]
    if drop < 0:
        return None  # a charge slipped in; this segment cannot be attributed
    return drop, span_h


def cpu_share(seg):
    """Hours of process CPU per hour of wall clock inside a segment, or None if unavailable.

    This is the attribution the drain column cannot give: the CPU belongs to the app whether or
    not the battery moved, so a high CPU share on a screen-off idle segment is the finding.
    """
    pts = [(s["atMillis"], s["processCpuMs"]) for s in seg if s.get("processCpuMs") is not None]
    if len(pts) < 2:
        return None
    (t0, c0), (t1, c1) = pts[0], pts[-1]
    if t1 <= t0 or c1 < c0:
        return None  # reset across a restart: unusable, not negative
    return (c1 - c0) / (t1 - t0)


def drain(bundle):
    """Battery drain attributed by STATE, not per run.

    The whole-day rate answers nothing: it blends the user's own screen time with the app
    idling. Splitting into constant-state segments and weighting by hours (never averaging
    per-segment rates) is what isolates the app's own contribution.
    """
    found = runs(bundle)
    if not found:
        print("\nno non-charging run in this bundle")
        return
    agg = {}
    drops = []
    for run in found:
        for seg in segments(run):
            rated = segment_rate(seg)
            if rated is None:
                continue
            drop, hours = rated
            # hours, pct dropped, stretch count, cpu hours, cpu share
            entry = agg.setdefault(run_label(seg[0]), [0.0, 0.0, 0, 0.0, 0.0])
            entry[0] += hours
            entry[1] += drop
            entry[2] += 1
            share = cpu_share(seg)
            if share is not None:
                entry[3] += hours
                entry[4] += share * hours
            drops.append(drop)
    if not agg:
        print(f"\nno constant-state stretch of >= {MIN_SEG_SEC // 60}min had two battery readings")
        return
    print(f"\nbattery drain by state (>= {MIN_SEG_SEC // 60}min per stretch, hours-weighted):")
    print("  hours   %/h   pct   stretches  cpu%   state")
    for label, (hours, drop, n, cpu_hours, cpu) in sorted(agg.items(), key=lambda kv: -kv[1][0]):
        rate = drop / hours if hours else None
        print(
            f"  {hours:6.2f}  {fmt(rate, '{:5.2f}')}  {drop:5.0f}  {n:9d}  "
            f"{fmt(cpu / cpu_hours if cpu_hours else None, '{:5.3f}')}  {label}"
        )
    idle = agg.get("screen-off +idle") or agg.get("screen-off")
    if idle and idle[0] >= 1:
        share = idle[4] / idle[3] if idle[3] else None
        print(
            f"\nscreen-off idle: {idle[1] / idle[0]:.2f} %/h over {idle[0]:.1f}h, "
            f"process cpu {fmt(share, '{:.3f}')} of one core. "
            "Compare against a dozing-but-installed baseline, not against zero."
        )
    coarse = median(drops)
    if coarse is not None and coarse <= 2:
        print(
            f"\ncaution: median stretch moved {coarse:.0f} pct, and batteryPct is whole-percent, "
            "so each rate carries roughly +/-100% of one step. These are order-of-magnitude "
            "figures; do not quote a decimal place."
        )


def main():
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    sub = parser.add_subparsers(dest="cmd", required=True)
    for name, fn, one in (("list", cmd_list, False), ("show", cmd_show, True), ("drain", cmd_drain, True)):
        p = sub.add_parser(name)
        if one:
            p.add_argument("name")
        p.set_defaults(fn=fn)
    p = sub.add_parser("compare")
    p.add_argument("a")
    p.add_argument("b")
    p.set_defaults(fn=cmd_compare)
    for p in (parser, *sub.choices.values()):
        p.add_argument("--dir", help="remote log dir (default: <remoteDir>/logs)")
        p.add_argument("--refresh", action="store_true", help="ignore the local cache")
    args = parser.parse_args()
    args.props = load_props(remote_dir=args.dir)
    args.fn(args)


if __name__ == "__main__":
    main()
