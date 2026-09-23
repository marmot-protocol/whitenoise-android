#!/usr/bin/env python
"""Per-iteration paging report read straight from ConversationPagingBenchmark's Perfetto traces.

Macrobenchmark drops an iteration from its JSON whenever one metric has no value for it, so this
reads the retained traces directly: journey duration, the `WhiteNoise.conversation.page.*` slices,
the three reader-visible edge events, Choreographer frame timing inside the journey, the app main
thread's running time, and how many GPU-memory counter samples the window held.

Usage:
  paging_trace_report.py <trace.perfetto-trace>      one JSON row on stdout
  paging_trace_report.py --summarize <rows.jsonl>     the per-iteration table plus medians

Needs the `perfetto` Python package (it downloads trace_processor on first use); see
`run-paging-trace-report.sh` for the loop over one runner output directory.
"""
import glob
import os
import re
import statistics
import sys

import time

from perfetto.trace_processor import TraceProcessor, TraceProcessorConfig

PKG = "dev.ipf.whitenoise.android.dev"
JOURNEYS = {
    "deepOlderFling": "benchmark:paging-deep-older",
    "returnFlingAfterDeepHistory": "benchmark:paging-return-newer",
    "jumpToNewestAfterSaturation": "benchmark:paging-jump-to-newest",
    "momentumHandoff": "benchmark:paging-momentum",
    "olderFlingWhileEngineCatchesUp": "benchmark:paging-busy-engine",
}


def pct(values, p):
    """Nearest-rank-interpolated percentile `p` (0–1) of `values`; NaN when empty."""
    if not values:
        return float("nan")
    values = sorted(values)
    k = (len(values) - 1) * p
    lo, hi = int(k), min(int(k) + 1, len(values) - 1)
    return values[lo] + (values[hi] - values[lo]) * (k - lo)


def analyse(path):
    """Open one trace, resolve its journey slice, and return the row `--summarize` prints."""
    m = re.search(r"ConversationPagingBenchmark_([A-Za-z]+)_iter(\d+)_", os.path.basename(path))
    test, it = m.group(1), int(m.group(2))
    tp = None
    for attempt in range(5):
        try:
            tp = TraceProcessor(trace=path, config=TraceProcessorConfig(unique_port=True, load_timeout=120, verbose=True))
            break
        except Exception:  # noqa: BLE001 — the previous shell may still hold its port
            time.sleep(1.5)
    if tp is None:
        raise RuntimeError(f"trace processor did not start for {path}")
    def q(sql):
        """Run one SQL query against the open trace and return its rows."""
        return list(tp.query(sql))

    j = q(f"select ts,dur from slice where name='{JOURNEYS[test]}' limit 1")
    if not j:
        tp.close()
        return dict(test=test, it=it, journey_ms=None)
    ts, dur = j[0].ts, j[0].dur
    win = f"ts>={ts} and ts<={ts + dur}"

    def sec(name, agg):
        """Aggregate `agg` over the paging slice `name` inside the journey window, 0 when absent."""
        r = q(f"select {agg} v from slice where name='WhiteNoise.conversation.page.{name}' and {win}")
        return r[0].v if r and r[0].v is not None else 0

    frames = [
        r.ms
        for r in q(
            f"""select s.dur/1e6 ms from slice s join thread_track tt on s.track_id=tt.id
                join thread t using(utid) join process p using(upid)
                where p.name like '{PKG}%' and t.tid=p.pid and s.depth=0
                and s.name like 'Choreographer#doFrame %' and s.{win}"""
        )
    ]
    running = q(
        f"""select sum(x.dur)/1e6 ms from thread_state x join thread t using(utid) join process p using(upid)
            where p.name like '{PKG}%' and t.tid=p.pid and x.state='Running' and x.ts>={ts} and x.ts<={ts + dur}"""
    )
    gpu = q(f"select count(*) n from counter c join process_counter_track k on c.track_id=k.id where k.name like '%gpu%' and c.{win}")
    pages = dict(
        pages=int(sec("window", "count(*)")),
        window_sum=sec("window", "sum(dur)/1e6"),
        window_max=sec("window", "max(dur)/1e6"),
        apply_n=int(sec("apply", "count(*)")),
        apply_sum=sec("apply", "sum(dur)/1e6"),
        apply_max=sec("apply", "max(dur)/1e6"),
        prepare_sum=sec("prepare", "sum(dur)/1e6"),
        edge_stop=int(sec("edgeStop", "count(*)")),
        runway_kept=int(sec("runwayKept", "count(*)")),
        edge_reached=int(sec("edgeReached", "count(*)")),
    )
    tp.close()
    return dict(
        test=test,
        it=it,
        journey_ms=dur / 1e6,
        **pages,
        frames=len(frames),
        f_p50=pct(frames, 0.5),
        f_p90=pct(frames, 0.9),
        f_p99=pct(frames, 0.99),
        f_max=max(frames) if frames else float("nan"),
        jank32=sum(1 for f in frames if f > 32),
        main_running_ms=(running[0].ms or 0) if running else 0,
        gpu_samples=gpu[0].n if gpu else 0,
    )


def main(argv):
    """`<trace>` prints one JSON row; `--summarize <jsonl>` prints the table for a run."""
    import json
    if argv[0] == "--summarize":
        rows = [json.loads(line) for line in open(argv[1]) if line.strip()]
        order = list(JOURNEYS)
        rows.sort(key=lambda r: (order.index(r["test"]), r["it"]))
        hdr = "test                          it  journey  pages  win Σ/max   apply n Σ/max  prep Σ  stop kept reach  frames P50/P90/P99/max  >32  main-run  gpu"
        print(hdr)
        print("-" * len(hdr))
        for r in rows:
            if r["journey_ms"] is None:
                print(f"{r['test']:30s}{r['it']:3d}  (no journey slice)")
                continue
            print(
                f"{r['test']:30s}{r['it']:3d} {r['journey_ms']:8.0f} {r['pages']:6d} {r['window_sum']:6.0f}/{r['window_max']:<4.0f} "
                f"{r['apply_n']:6d} {r['apply_sum']:4.0f}/{r['apply_max']:<4.0f} {r['prepare_sum']:6.0f} "
                f"{r['edge_stop']:5d}{r['runway_kept']:5d}{r['edge_reached']:6d}  "
                f"{r['frames']:5d} {r['f_p50']:4.1f}/{r['f_p90']:4.1f}/{r['f_p99']:5.1f}/{r['f_max']:5.1f} {r['jank32']:4d} {r['main_running_ms']:8.0f} {r['gpu_samples']:5d}"
            )
        print()
        for test in order:
            rs = [r for r in rows if r["test"] == test and r["journey_ms"] is not None]
            if not rs:
                continue
            med = lambda k: statistics.median(r[k] for r in rs)  # noqa: E731
            print(
                f"{test:30s} n={len(rs):2d} pages med={med('pages'):.0f} windowΣ med={med('window_sum'):.0f}ms "
                f"applyΣ med={med('apply_sum'):.0f}ms apply max={max(r['apply_max'] for r in rs):.0f}ms "
                f"edgeStop Σ={sum(r['edge_stop'] for r in rs)} edgeReached Σ={sum(r['edge_reached'] for r in rs)} "
                f"frame P99 med={med('f_p99'):.1f}ms worst frame={max(r['f_max'] for r in rs):.0f}ms >32ms Σ={sum(r['jank32'] for r in rs)}"
            )
        return
    print(json.dumps(analyse(argv[0])))


if __name__ == "__main__":
    main(sys.argv[1:])
