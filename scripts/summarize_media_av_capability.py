#!/usr/bin/env python3
"""Validate a bounded schema-3 A/V capture without granting device or release approval.

The JSONL contains instrumentation observations. APK/source provenance and platform
service-denial attribution require the matching run artifacts and platform logs.
"""
import argparse
import hashlib
import json
import re
import sys
from pathlib import Path

MAX_BYTES = 262144
CASES = (
    ("video_construct", "retriever_construct", "video/mp4"),
    ("video_metadata", "retriever_source", "video/mp4"),
    ("video_poster", "probe", "video/mp4"),
    ("video_tracks", "extractor", "video/mp4"),
    ("audio_tracks", "extractor", "audio/wav"),
    ("audio_codec", "codec", "audio/wav"),
    ("audio_waveform", "waveform", "audio/wav"),
    ("truncated_probe", "probe", "video/mp4"),
    ("empty_range", "probe", "empty"),
    ("overflow_range", "extractor", "audio/wav"),
)
PHASES = {
    "retriever_construct", "retriever_source", "retriever_metadata", "probe",
    "extractor_construct", "extractor_source", "extractor_tracks", "codec_create",
    "codec_configure", "codec_start", "waveform", "watchdog", "reply",
}
RECEIPTS = (
    "death_observed", "capability_revoked", "selected_scopes_closed",
    "unbind_succeeded", "cleanup_succeeded",
)


def require(condition, message):
    if not condition:
        raise ValueError(message)


def integer(value, minimum=0):
    return type(value) is int and value >= minimum


def object_pairs(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result, "duplicate JSON field: " + key)
        result[key] = value
    return result


def load(raw):
    require(len(raw) <= MAX_BYTES, "capture exceeds byte bound")
    text = raw.decode("utf-8")
    lines = text.splitlines()
    require(len(lines) == len(CASES) + 1, "capture requires one header and exactly ten cases")
    rows = [json.loads(line, object_pairs_hook=object_pairs) for line in lines]
    require(all(type(row) is dict for row in rows), "JSON objects required")
    return rows


def diagnostics(row, observation, deadline):
    logs = row.get("worker_diagnostics")
    require(row.get("diagnostics_qualified") is True, "diagnostics were not qualified")
    require(type(logs) is list and (1 if deadline else 2) <= len(logs) <= 32,
            "missing or excessive diagnostics")
    terminals = []
    for log in logs:
        require(type(log) is dict, "diagnostic object required")
        for name, expected in (
            ("worker_reported_uid", observation["kernel_ready_uid"]),
            ("ready_kernel_uid", observation["kernel_ready_uid"]),
            ("worker_reported_pid", observation["kernel_ready_pid"]),
            ("ready_kernel_pid", observation["kernel_ready_pid"]),
        ):
            require(type(log.get(name)) is int and log[name] == expected,
                    "diagnostic identity differs from READY")
        require(log.get("phase") in PHASES, "unknown diagnostic phase")
        require(log.get("outcome") in {"begin", "success", "no_result", "exception", "timeout"},
                "unknown diagnostic outcome")
        require(type(log.get("error_class")) is str and
                re.fullmatch(r"[A-Za-z0-9_]{1,64}", log["error_class"]), "invalid error class")
        require(type(log.get("error_code")) is int, "invalid error code")
        require(integer(log.get("worker_elapsed_ns"), 1), "invalid diagnostic time")
        if log["outcome"] != "begin":
            terminals.append(log)
    require(len(terminals) <= 1 if deadline else len(terminals) == 1,
            "ambiguous terminal diagnostics")
    require(any(log["outcome"] == "begin" for log in logs), "missing operation begin")
    return terminals[0] if terminals else None


def observe(row, header):
    observation = row.get("observation")
    require(type(observation) is dict, "worker observation required")
    require(type(observation.get("request")) is int and observation["request"] == row["request"],
            "observation request differs")
    for name in RECEIPTS:
        require(observation.get(name) is True, "missing lifetime receipt: " + name)
    uid, pid = observation.get("kernel_ready_uid"), observation.get("kernel_ready_pid")
    require(integer(uid, 1) and uid != header["app_uid"] and integer(pid, 1), "invalid READY identity")
    for name in ("elapsed_ms", "operation_elapsed_ms", "retirement_elapsed_ms"):
        require(integer(observation.get(name)), "invalid timing: " + name)
    require(observation["operation_elapsed_ms"] <= observation["elapsed_ms"] and
            observation["retirement_elapsed_ms"] <= observation["elapsed_ms"], "inconsistent timing")
    deadline = row.get("outcome") == "reply_deadline_unqualified"
    terminal = diagnostics(row, observation, deadline)
    if deadline:
        require(observation.get("timely_terminal_reply") is False and
                type(observation.get("terminal_reply_seen")) is bool and
                observation.get("output_read") is False, "invalid deadline evidence")
        return "unqualified_timeout", observation
    require(type(observation.get("kernel_reply_uid")) is int and observation["kernel_reply_uid"] == uid,
            "terminal UID differs from READY")
    for name, expected in (
        ("isolated", True), ("internet_granted", False), ("runtime_initialized", False),
        ("private_canary_denied", True), ("death_observed_before_output_read", True),
    ):
        require(observation.get(name) is expected, "invalid isolation receipt: " + name)
    require(type(observation.get("private_canary_errno")) is int and
            observation["private_canary_errno"] in {1, 2, 13}, "invalid canary denial")
    require(type(observation.get("input_write_errno")) is int and
            observation["input_write_errno"] == 9, "selected descriptor was not write-denied")
    for name in ("success", "pixels", "output_read", "worker_soft_timeout"):
        require(type(observation.get(name)) is bool, "boolean required: " + name)
    require(observation["output_read"] == observation["pixels"], "pixel copy receipt differs")
    require(integer(observation.get("duration_ms")) and observation["duration_ms"] <= 86400000 and
            type(observation.get("waveform_bars")) is int and observation["waveform_bars"] in {0, 64},
            "invalid scalar output")
    if row.get("outcome") == "worker_soft_timeout_no_result":
        require(row.get("capability_status") == "unqualified_timeout" and row.get("production_route") is False
                and row.get("soft_refusal_ms") == 5000, "invalid soft-refusal receipt")
        require(observation["worker_soft_timeout"] and not observation["success"] and
                not observation["output_read"] and observation["waveform_bars"] == 0 and
                terminal["phase"] == "watchdog" and terminal["outcome"] == "timeout",
                "soft refusal cannot supply successful output")
        return "unqualified_timeout", observation
    require(not observation["worker_soft_timeout"], "soft refusal labelled as normal result")
    require(row.get("outcome") == ("success" if observation["success"] else "no_result"),
            "result label contradicts terminal reply")
    if observation["success"]:
        require(terminal["outcome"] == "success" and terminal["error_class"] == "none" and
                terminal["error_code"] == 0, "successful reply contradicts diagnostics")
        final_phase = {
            "retriever_construct": "retriever_construct", "retriever_source": "retriever_metadata",
            "probe": "probe", "extractor": "extractor_tracks", "codec": "codec_start",
            "waveform": "waveform",
        }[row["stage"]]
        require(terminal["phase"] == final_phase, "successful reply ended before the required operation")
        require(row["case"] != "truncated_probe", "truncated fixture unexpectedly produced a successful probe")
        if row["case"] == "audio_waveform":
            require(observation["waveform_bars"] == 64, "successful waveform requires 64 bars")
        return "success", observation
    require(terminal["outcome"] in {"exception", "no_result"}, "negative reply contradicts diagnostics")
    if terminal["outcome"] == "exception":
        require(terminal["error_class"] != "none", "exception attribution missing")
        return "attributable_exception", observation
    return "no_result", observation


def summarize(raw):
    rows = load(raw)
    header = rows[0]
    require(header.get("type") == "header" and type(header.get("capture_schema")) is int and
            header["capture_schema"] == 3, "schema 3 required; older captures lack isolation/corpus evidence")
    require(type(header.get("run_token")) is str and re.fullmatch(r"[0-9a-f]{32}", header["run_token"]),
            "invalid run token")
    require(integer(header.get("api"), 30) and integer(header.get("app_uid"), 1), "invalid runtime identity")
    require(header.get("production_route") is False, "capture must declare debug-only routing")
    for name in ("fingerprint", "package"):
        require(type(header.get(name)) is str and 0 < len(header[name]) <= 1024, "missing runtime " + name)
    matrix = []
    corpus = {}
    for index, (row, (case, stage, media_format)) in enumerate(zip(rows[1:], CASES), 1):
        require(row.get("type") == "case" and row.get("case") == case and row.get("stage") == stage and
                type(row.get("request")) is int and row["request"] == index, "case order, stage or request differs")
        require(row.get("run_token") == header["run_token"], "mixed capture token")
        require(row.get("format") == media_format, "unexpected case format")
        require(type(row.get("input_sha256")) is str and re.fullmatch(r"[0-9a-f]{64}", row["input_sha256"]),
                "input digest required")
        require(integer(row.get("input_bytes")) and row["input_bytes"] <= 512 * 1024 * 1024,
                "input size outside selected-descriptor bound")
        if case == "truncated_probe":
            require(row["input_bytes"] == 16, "truncated fixture length differs")
        elif case == "empty_range":
            require(row["input_bytes"] == 0 and row["input_sha256"] == hashlib.sha256(b"").hexdigest(),
                    "empty fixture differs")
        else:
            require(row["input_bytes"] > 16, "healthy corpus length differs")
            identity = (row["input_sha256"], row["input_bytes"])
            require(corpus.setdefault(media_format, identity) == identity, "corpus changes between operations")
        if case in {"empty_range", "overflow_range"}:
            require(row.get("outcome") == "parent_range_rejected" and row.get("worker_started") is False
                    and "observation" not in row and "worker_diagnostics" not in row,
                    "invalid range must be refused before worker admission")
            status, observation = "parent_range_rejected", None
        else:
            status, observation = observe(row, header)
        entry = {"case": case, "stage": stage, "format": media_format, "status": status,
                 "input_sha256": row["input_sha256"], "input_bytes": row["input_bytes"]}
        if observation is not None:
            entry.update({name: observation[name] for name in
                          ("elapsed_ms", "operation_elapsed_ms", "retirement_elapsed_ms")})
        required_output = status == "success"
        if case in {"video_metadata", "video_poster"}:
            required_output = required_output and observation.get("duration_ms", 0) > 0
        if case == "video_poster":
            required_output = required_output and observation.get("pixels") is True
        entry["required_output_present"] = required_output
        matrix.append(entry)
    return {
        "schema": 1, "api": header["api"], "fingerprint": header["fingerprint"],
        "package": header["package"], "capture_sha256": hashlib.sha256(raw).hexdigest(),
        "capture_complete": True,
        "characterization_complete": all(row.get("outcome") != "reply_deadline_unqualified" for row in rows[1:]),
        "all_parsers_supported": all(entry["required_output_present"] for entry in matrix[:7]),
        "production_route": False, "qualification": False, "matrix": matrix,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("capture", type=Path)
    args = parser.parse_args()
    try:
        with args.capture.open("rb") as stream:
            result = summarize(stream.read(MAX_BYTES + 1))
    except (OSError, UnicodeError, ValueError, KeyError, TypeError) as error:
        print("Invalid A/V capture: " + str(error), file=sys.stderr)
        return 1
    print(json.dumps(result, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    sys.exit(main())
