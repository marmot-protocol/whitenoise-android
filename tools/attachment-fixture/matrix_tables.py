"""Render pooled matrix runs as the markdown tables used in the performance reports."""

import argparse
import json
from pathlib import Path

from matrix_report import aggregate, compare

MIB = 1024 * 1024


def size_label(size):
    """Human size of one cell."""
    return f"{size / MIB:g} MiB" if size >= MIB else f"{size // 1024} KiB"


def fmt(cell, key, quantile="p50"):
    """One distribution value, or a dash when it was not measured."""
    stats = cell.get(key)
    if not stats or stats.get(quantile) is None:
        return "—"
    value = stats[quantile]
    return f"{value:.0f}" if value >= 100 else f"{value:.1f}"


def mib_pair(cell, key):
    """Median and maximum of a byte peak, in MiB."""
    stats = cell.get(key)
    return "—" if not stats else f"{stats['p50'] / MIB:.0f} / {stats['max'] / MIB:.0f}"


def tables(agg):
    """Upload, download, memory and attribution tables for one pooled aggregate."""
    out = ["### Upload (median ms)", "",
           "| Link | Size | n | Preparation visible | MDK upload | Server upload | Upload and publish |",
           "| --- | ---: | ---: | ---: | ---: | ---: | ---: |"]
    for p in agg["profiles"]:
        for c in p["cells"]:
            out.append(f"| {p['name']} | {size_label(c['size'])} | {c['samples']} | {fmt(c, 'prep_visible_ms')} | "
                       f"{fmt(c, 'mdk_upload_ms')} | {fmt(c, 'server_upload_ms')} | {fmt(c, 'upload_publish_ms')} |")
    out += ["", "### Cold download (median ms)", "",
            "| Link | Size | n | Admission | First progress | Transport | Authoritative READY | Subscription delay | "
            "Materialize | Open-ready lease |",
            "| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |"]
    for p in agg["profiles"]:
        for c in p["cells"]:
            out.append(f"| {p['name']} | {size_label(c['size'])} | {c['samples']} | {fmt(c, 'admission_ms')} | "
                       f"{fmt(c, 'first_progress_ms')} | {fmt(c, 'transport_ms')} | {fmt(c, 'ready_ms')} | "
                       f"{fmt(c, 'subscription_delay_ms')} | {fmt(c, 'materialize_ms')} | {fmt(c, 'lease_ms')} |")
    out += ["", "### Warm, restart and memory", "",
            "| Link | Size | Warm lease ms | Restart native lease ms | Upload Java peak MiB | Upload native peak MiB | "
            "Cold Java peak MiB | Cold native peak MiB | Uploads | GETs | Retries |",
            "| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |"]
    for p in agg["profiles"]:
        for c in p["cells"]:
            restart = p["recreated_native_lease_ms"].get(c["size"])
            restart = f"{restart['p50']:.1f}" if restart else "—"
            out.append(f"| {p['name']} | {size_label(c['size'])} | {fmt(c, 'warm_lease_ms')} | {restart} | "
                       f"{mib_pair(c, 'upload_java_peak_bytes')} | {mib_pair(c, 'upload_native_peak_bytes')} | "
                       f"{mib_pair(c, 'cold_java_peak_bytes')} | {mib_pair(c, 'cold_native_peak_bytes')} | "
                       f"{c['requests']['uploads']} | {c['requests']['gets']} | {c['requests']['retries']} |")
    out += ["", "Memory columns are median / maximum of the sampled peaks.", "",
            "### Dominant delay", "", "| Link | Size | Upload | Download |", "| --- | ---: | --- | --- |"]
    for p in agg["profiles"]:
        for c in p["cells"]:
            def named(part):
                return "—" if not part else f"{part['dominant']} ({part['layer']}, {part['shares'][part['dominant']] * 100:.0f}%)"
            out.append(f"| {p['name']} | {size_label(c['size'])} | {named(c['attribution']['upload'])} | "
                       f"{named(c['attribution']['download'])} |")
    return "\n".join(out)


def comparison(base, cand, metrics):
    """Median change per cell for the metrics a candidate is expected to move."""
    verdicts = compare(base, cand)
    index = {(c["profile"], c["size"], c["metric"]): c for c in verdicts["cells"]}
    out = ["| Link | Size | Metric | Baseline p50 | Candidate p50 | Change | Verdict |",
           "| --- | ---: | --- | ---: | ---: | ---: | --- |"]
    for p in base["profiles"]:
        for c in p["cells"]:
            for metric in metrics:
                row = index.get((p["name"], c["size"], metric))
                if row:
                    pct = 100 * row["delta_ms"] / row["baseline_p50"] if row["baseline_p50"] else 0
                    out.append(f"| {p['name']} | {size_label(c['size'])} | {metric} | {row['baseline_p50']:.1f} | "
                               f"{row['candidate_p50']:.1f} | {row['delta_ms']:+.1f} ms ({pct:+.0f}%) | {row['verdict']} |")
    return "\n".join(out), verdicts


def load(paths):
    """Pool the raw matrices of several preserved runs."""
    return aggregate([json.loads(Path(p).read_text())["raw"] for p in paths])


def main():
    """Print the baseline tables, or the comparison when a candidate is given."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--baseline", nargs="+", required=True)
    parser.add_argument("--candidate", nargs="*")
    args = parser.parse_args()
    base = load(args.baseline)
    print(tables(base))
    if args.candidate:
        cand = load(args.candidate)
        print("\n## Candidate\n")
        print(tables(cand))
        text, verdicts = comparison(base, cand, ("feed_ready_ms", "lease_ms", "subscription_delay_ms", "ready_ms",
                                                  "upload_publish_ms", "warm_lease_ms"))
        print("\n## Comparison\n")
        print(text)
        print(f"\naccepted={verdicts['accepted']} correctness_diffs={len(verdicts['correctness_diffs'])}")


if __name__ == "__main__":
    main()
