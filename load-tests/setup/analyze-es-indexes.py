#!/usr/bin/env python3
"""
analyze-es-indexes.py — Analyze Elasticsearch index usage per Camunda component.

Summarizes index health, document counts, and disk usage for:
  Operate, Tasklist, Optimize, Zeebe (exporter), Camunda (new exporter)

Usage:
  python3 analyze-es-indexes.py
  python3 analyze-es-indexes.py --url http://localhost:9200
  python3 analyze-es-indexes.py --url http://es-host:9200 --user elastic --pass secret
  python3 analyze-es-indexes.py --component operate
  python3 analyze-es-indexes.py --prefix myns- --output json
  python3 analyze-es-indexes.py --output csv

Requires: Python 3.6+ (stdlib only, no pip installs needed)
"""

import argparse
import json
import os
import sys
import urllib.request
import urllib.error
from base64 import b64encode
from datetime import datetime, timezone

# ── Component definitions ──────────────────────────────────────────────────────
COMPONENTS = {
    "operate":  {"pattern": "operate-*",      "color": "\033[0;34m"},  # blue
    "tasklist": {"pattern": "tasklist-*",     "color": "\033[0;32m"},  # green
    "optimize": {"pattern": "optimize-*",     "color": "\033[0;35m"},  # magenta
    "zeebe":    {"pattern": "zeebe-record-*", "color": "\033[0;36m"},  # cyan
    "camunda":  {"pattern": "camunda-*",      "color": "\033[0;33m"},  # yellow
}

# ── ANSI helpers ───────────────────────────────────────────────────────────────
RESET  = "\033[0m"
BOLD   = "\033[1m"
RED    = "\033[0;31m"
YELLOW = "\033[0;33m"
GREEN  = "\033[0;32m"

USE_COLOR = True

def c(text, code):
    return f"{code}{text}{RESET}" if USE_COLOR else text

def bold(text):
    return c(text, BOLD)

def health_str(h):
    colors = {"green": GREEN, "yellow": YELLOW, "red": RED}
    return c(h, colors.get(h, "")) if h else "-"

# ── Size formatting ────────────────────────────────────────────────────────────
def human_size(b):
    try:
        b = int(b)
    except (TypeError, ValueError):
        return "-"
    for unit, threshold in [("GB", 1 << 30), ("MB", 1 << 20), ("KB", 1 << 10)]:
        if b >= threshold:
            return f"{b / threshold:.1f}{unit}"
    return f"{b}B"

# ── HTTP helpers ───────────────────────────────────────────────────────────────
def make_request(url, user=None, password=None):
    req = urllib.request.Request(url)
    if user and password:
        token = b64encode(f"{user}:{password}".encode()).decode()
        req.add_header("Authorization", f"Basic {token}")
    try:
        with urllib.request.urlopen(req, timeout=15) as resp:
            return resp.status, json.loads(resp.read())
    except urllib.error.HTTPError as e:
        body = {}
        try:
            body = json.loads(e.read())
        except Exception:
            pass
        return e.code, body
    except Exception as e:
        print(f"{RED}[ERROR]{RESET} Connection failed: {e}", file=sys.stderr)
        sys.exit(1)

# ── Main analysis ──────────────────────────────────────────────────────────────
def analyze(args):
    global USE_COLOR
    USE_COLOR = args.color

    url    = args.url.rstrip("/")
    user   = args.user or os.environ.get("ES_USER", "")
    passwd = args.pass_ or os.environ.get("ES_PASS", "")
    prefix = args.prefix

    # Verify connectivity
    now = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    print(f"[{now}] Connecting to {url} ...")
    status, info = make_request(f"{url}/", user, passwd)
    if status != 200:
        print(f"{RED}[ERROR]{RESET} HTTP {status} — cannot reach Elasticsearch.", file=sys.stderr)
        print(json.dumps(info, indent=2), file=sys.stderr)
        sys.exit(1)

    cluster = info.get("cluster_name", "unknown")
    version = info.get("version", {}).get("number", "unknown")
    print(f"Cluster: {bold(cluster)}  ES version: {bold(version)}\n")

    # Which components to check
    if args.component == "all":
        selected = list(COMPONENTS.keys())
    else:
        if args.component not in COMPONENTS:
            print(f"{RED}[ERROR]{RESET} Unknown component '{args.component}'. "
                  f"Valid: {', '.join(COMPONENTS)} or 'all'", file=sys.stderr)
            sys.exit(1)
        selected = [args.component]

    # ── CSV header ────────────────────────────────────────────────────────────
    if args.output == "csv":
        print("component,index,health,status,primary_shards,replica_shards,"
              "docs_count,docs_deleted,pri_store_size_bytes,total_store_size_bytes")

    # ── JSON accumulator ──────────────────────────────────────────────────────
    json_result = {}

    # ── Grand totals & per-component storage ──────────────────────────────────
    grand_indices = 0
    grand_docs    = 0
    grand_size    = 0
    summary_rows  = []          # (comp, n_idx, total_docs, total_size)
    comp_store    = {}          # comp -> list of index dicts (for top-30 section)

    TOP_N = 30

    for comp in selected:
        cfg     = COMPONENTS[comp]
        pattern = f"{prefix}{cfg['pattern']}"
        color   = cfg["color"]

        status, data = make_request(
            f"{url}/_cat/indices/{pattern}?format=json&bytes=b&s=index",
            user, passwd
        )

        if status not in (200, 404):
            print(f"{YELLOW}[WARN]{RESET}  Could not fetch indices for {comp} "
                  f"(HTTP {status}) — skipping.")
            continue

        indices = data if isinstance(data, list) else []

        if not indices:
            if args.output == "table":
                print(f"{color}{bold('▶ ' + comp.upper())}{RESET}  "
                      f"(pattern: {pattern})  — no indices found\n")
            summary_rows.append((comp, 0, 0, 0))
            comp_store[comp] = []
            continue

        # Compute totals
        total_docs = sum(int(i.get("docs.count") or 0) for i in indices)
        total_size = sum(int(i.get("pri.store.size") or 0) for i in indices)
        grand_indices += len(indices)
        grand_docs    += total_docs
        grand_size    += total_size
        summary_rows.append((comp, len(indices), total_docs, total_size))
        comp_store[comp] = indices

        # ── TABLE ─────────────────────────────────────────────────────────────
        if args.output == "table":
            col_w = 56
            print(f"{color}{bold('▶ ' + comp.upper())}{RESET}  "
                  f"(pattern: {pattern})")
            print(f"  Indices: {bold(str(len(indices)))}   "
                  f"Docs: {bold(str(total_docs))}   "
                  f"Primary size: {bold(human_size(total_size))}\n")

            # Top-30 by primary size
            top = sorted(indices, key=lambda i: int(i.get("pri.store.size") or 0), reverse=True)[:TOP_N]
            shown = len(top)
            total_shown = len(indices)
            label = f"Top {shown} of {total_shown} indices by size" if total_shown > shown else f"All {total_shown} indices"
            print(f"  {bold(label)}")
            print(f"  {bold(f'%-{col_w}s %-8s %-8s %6s %6s %12s %10s %12s %10s' % ('INDEX','HEALTH','STATUS','PRI','REP','DOCS','DELETED','PRI_SIZE','AVG/DOC'))}")
            print("  " + "─" * 136)

            for idx in top:
                name  = idx.get("index", "")
                hlth  = idx.get("health", "-")
                stat  = idx.get("status", "-")
                pri   = idx.get("pri", "-")
                rep   = idx.get("rep", "-")
                docs  = int(idx.get("docs.count") or 0)
                deld  = idx.get("docs.deleted") or "0"
                psz   = int(idx.get("pri.store.size") or 0)
                avg   = human_size(psz // docs) if docs > 0 else "-"

                display_name = name if len(name) <= col_w else name[:col_w - 1] + "…"
                h_str     = health_str(hlth)
                pad_extra = len(h_str) - len(hlth) if USE_COLOR else 0
                print(f"  {display_name:<{col_w}} {h_str:<{8 + pad_extra}} {stat:<8} "
                      f"{pri:>6} {rep:>6} {docs:>12} {deld:>10} {human_size(psz):>12} {avg:>10}")
            print()

        # ── CSV ───────────────────────────────────────────────────────────────
        elif args.output == "csv":
            for idx in indices:
                docs = int(idx.get("docs.count") or 0)
                psz  = int(idx.get("pri.store.size") or 0)
                tsz  = idx.get("store.size") or "0"
                avg  = psz // docs if docs > 0 else 0
                print(",".join([
                    comp,
                    idx.get("index", ""),
                    idx.get("health", ""),
                    idx.get("status", ""),
                    idx.get("pri", ""),
                    idx.get("rep", ""),
                    str(docs),
                    idx.get("docs.deleted") or "0",
                    str(psz),
                    str(tsz),
                    str(avg),
                ]))

        # ── JSON ──────────────────────────────────────────────────────────────
        elif args.output == "json":
            top = sorted(indices, key=lambda i: int(i.get("pri.store.size") or 0), reverse=True)[:TOP_N]
            json_result[comp] = {
                "component": comp,
                "pattern": pattern,
                "summary": {
                    "index_count":        len(indices),
                    "total_docs":         total_docs,
                    "primary_size_bytes": total_size,
                    "primary_size_human": human_size(total_size),
                },
                f"top_{TOP_N}_by_size": [
                    {
                        "index":                  i.get("index"),
                        "health":                 i.get("health"),
                        "status":                 i.get("status"),
                        "primary_shards":         i.get("pri"),
                        "replica_shards":         i.get("rep"),
                        "docs_count":             int(i.get("docs.count") or 0),
                        "docs_deleted":           int(i.get("docs.deleted") or 0),
                        "pri_store_size_bytes":   int(i.get("pri.store.size") or 0),
                        "total_store_size_bytes": int(i.get("store.size") or 0),
                        "avg_bytes_per_doc":      int(i.get("pri.store.size") or 0) // int(i.get("docs.count") or 1)
                                                  if int(i.get("docs.count") or 0) > 0 else 0,
                    }
                    for i in top
                ],
            }

    # ── Grand summary ──────────────────────────────────────────────────────────
    if args.output == "table":
        W = 75
        print("═" * W)
        print(bold("  GRAND SUMMARY"))
        print("═" * W)
        hdr = f"{'COMPONENT':<15} {'INDEXES':>8} {'TOTAL SIZE':>12} {'% TOTAL':>8} {'DOCS':>15} {'AVG/DOC':>10}"
        print(f"  {bold(hdr)}")
        print("  " + "─" * (W - 2))
        for comp, n_idx, n_docs, n_size in summary_rows:
            pct     = f"{n_size / grand_size * 100:.1f}%" if grand_size > 0 else "-"
            avg_doc = human_size(n_size // n_docs) if n_docs > 0 else "-"
            color   = COMPONENTS[comp]["color"]
            print(f"  {c(comp, color):<{15 + (len(color) + len(RESET) if USE_COLOR else 0)}} "
                  f"{n_idx:>8} {human_size(n_size):>12} {pct:>8} {n_docs:>15} {avg_doc:>10}")
        print("  " + "─" * (W - 2))
        grand_avg = human_size(grand_size // grand_docs) if grand_docs > 0 else "-"
        print(f"  {bold(f'%-15s %8s %12s %8s %15s %10s' % ('TOTAL', str(grand_indices), human_size(grand_size), '100%', str(grand_docs), grand_avg))}")
        print()

    elif args.output == "csv":
        print("\n# SUMMARY")
        print("component,index_count,total_size_bytes,pct_total,total_docs,avg_bytes_per_doc")
        for comp, n_idx, n_docs, n_size in summary_rows:
            pct     = f"{n_size / grand_size * 100:.1f}" if grand_size > 0 else "0"
            avg_doc = n_size // n_docs if n_docs > 0 else 0
            print(f"{comp},{n_idx},{n_size},{pct},{n_docs},{avg_doc}")
        grand_avg = grand_size // grand_docs if grand_docs > 0 else 0
        print(f"TOTAL,{grand_indices},{grand_size},100,{grand_docs},{grand_avg}")

    elif args.output == "json":
        grand_avg = grand_size // grand_docs if grand_docs > 0 else 0
        json_result["_summary"] = {
            "cluster":                    cluster,
            "es_version":                 version,
            "total_indices":              grand_indices,
            "total_docs":                 grand_docs,
            "total_primary_size_bytes":   grand_size,
            "total_primary_size_human":   human_size(grand_size),
            "avg_bytes_per_doc":          grand_avg,
            "components": [
                {
                    "component":          comp,
                    "index_count":        n_idx,
                    "total_docs":         n_docs,
                    "primary_size_bytes": n_size,
                    "primary_size_human": human_size(n_size),
                    "pct_total_size":     round(n_size / grand_size * 100, 1) if grand_size else 0,
                    "avg_bytes_per_doc":  n_size // n_docs if n_docs > 0 else 0,
                }
                for comp, n_idx, n_docs, n_size in summary_rows
            ],
        }
        print(json.dumps(json_result, indent=2))


# ── CLI ────────────────────────────────────────────────────────────────────────
def main():
    parser = argparse.ArgumentParser(
        description="Analyze Elasticsearch index usage per Camunda component.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
Examples:
  python3 analyze-es-indexes.py
  python3 analyze-es-indexes.py --url http://localhost:9200
  python3 analyze-es-indexes.py --url http://es:9200 --user elastic --pass secret
  python3 analyze-es-indexes.py --component operate
  python3 analyze-es-indexes.py --prefix myns- --output json
  python3 analyze-es-indexes.py --output csv > report.csv

Environment variables: ES_URL, ES_USER, ES_PASS
        """,
    )
    parser.add_argument("--url",       default=os.environ.get("ES_URL", "http://localhost:9200"),
                        help="Elasticsearch base URL (default: http://localhost:9200)")
    parser.add_argument("--user",      default="", help="Basic auth username")
    parser.add_argument("--pass",      default="", dest="pass", help="Basic auth password")
    parser.add_argument("--prefix",    default="", help="Index prefix (e.g. myns-)")
    parser.add_argument("--component", default="all",
                        choices=[*COMPONENTS.keys(), "all"],
                        help="Component to analyze (default: all)")
    parser.add_argument("--output",    default="table", choices=["table", "csv", "json"],
                        help="Output format (default: table)")
    parser.add_argument("--no-color",  action="store_false", dest="color",
                        help="Disable colour output")
    parser.set_defaults(color=True)

    args = parser.parse_args()
    # argparse uses dest="pass" which conflicts with keyword; read via vars
    args.pass_ = vars(args).get("pass", "")
    analyze(args)


if __name__ == "__main__":
    main()
