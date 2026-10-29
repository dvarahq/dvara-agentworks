#!/usr/bin/env python3
"""Deterministic SPIKE-007 NDJSON runner. Standard library only; no framework dependency."""

import json
import sys

PROTOCOL = "agentworks-executor/v1"
CAPABILITIES = ["EVENT_STREAMING", "CANCELLATION", "TOOL_MEDIATION", "USAGE_REPORTING"]


def emit(value):
    print(json.dumps(value, separators=(",", ":")), flush=True)


def event(start, sequence, event_type, payload=""):
    emit({"type": "event", "runId": start["runId"], "traceId": start["traceId"],
          "sequence": sequence, "eventType": event_type, "payload": payload})


def read_message():
    line = sys.stdin.readline()
    if not line:
        raise EOFError("controller closed input")
    return json.loads(line)


def main():
    handshake = read_message()
    if handshake.get("type") != "handshake":
        return 2
    version = "unsupported/v9" if "VERSION_MISMATCH" in handshake.get("requiredCapabilities", []) else PROTOCOL
    emit({"type": "handshake_ack", "protocolVersion": version, "executorId": "fake-python/1",
          "capabilities": CAPABILITIES, "assurance": "INSTRUMENTED",
          "accessMode": handshake.get("accessMode")})
    start = read_message()
    mode = start.get("input", "success")
    if mode == "crash":
        return 17
    if mode == "out_of_order":
        event(start, 2, "RUN_ACTIVATED", "accepted")
        return 0
    if mode == "duplicate":
        event(start, 1, "RUN_ACTIVATED", "accepted")
        event(start, 1, "PROGRESS", "duplicate-sequence")
        return 0
    if mode == "correlation_mismatch":
        bad = dict(start)
        bad["traceId"] = "wrong-trace"
        event(bad, 1, "RUN_ACTIVATED", "accepted")
        return 0
    event(start, 1, "RUN_ACTIVATED", "accepted")
    if mode == "failure":
        event(start, 2, "RUN_FAILED", "RUNNER_FAILURE")
        return 0
    if mode in ("slow", "cancel"):
        command = read_message()
        terminal = "RUN_TIMED_OUT" if command.get("reason") == "timeout" else "RUN_CANCELLED"
        event(start, 2, terminal, command.get("reason", "cancelled"))
        return 0
    event(start, 2, "TOOL_REQUESTED", "calendar.lookup")
    tool = read_message()
    if tool.get("type") == "tool_error":
        event(start, 3, "RUN_FAILED", "TOOL_FAILURE")
        return 0
    event(start, 3, "TOOL_COMPLETED", "mediated")
    event(start, 4, "USAGE_REPORTED", "input=3,output=2")
    event(start, 5, "RUN_SUCCEEDED", "completed")
    if mode == "late_event":
        event(start, 6, "PROGRESS", "too-late")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (EOFError, json.JSONDecodeError):
        raise SystemExit(3)
