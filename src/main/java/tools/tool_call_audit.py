#!/usr/bin/env python3
"""
tool_call_audit.py

Parses the AIS app's Tomcat/logback console log (the same text you've been
pasting into chat) and reports, per chat request:
  - every tool called, in order, with its args
  - exact duplicate calls (same tool + same args, called 2+ times)
  - "blind-guessing loops" (same tool called 3+ times with varying args --
    the pattern that wasted ~14s on "Show locations under PSM central")
  - total wall time per request (from "Chat request:" to "Execution Complete")

WHY A LOG PARSER INSTEAD OF ONLY A LIVE HTTP TEST:
Your workflow already produces these logs constantly while you test in the
browser. This lets you point the script at a saved log file (or paste new
output into one) and get an instant redundancy report, without needing the
app to be running or writing extra test-prompt boilerplate. Use
ToolCallAuditTest.java (same folder) when you want to proactively fire a
fixed batch of prompts instead.

USAGE:
    python3 tool_call_audit.py path/to/catalina.out
    python3 tool_call_audit.py path/to/catalina.out --json report.json

    # Or read from stdin (e.g. paste + Ctrl-D, or pipe a live tail):
    tail -f catalina.out | python3 tool_call_audit.py -

WHAT IT LOOKS FOR (matches your actual log format, confirmed against the
logs you pasted):
    INFO ... ChatServlet - [Manual Info]Chat request: session=XXXX, prompt=YYYY
    INFO ... OllamaService - [Manual Info]LLM SELECTED TOOL  : [toolName]
    INFO ... OllamaService - [Manual Info]... LLM GENERATED ARGS : {k=v, k2=v2}
    INFO ... AgentGraph - [Manual Info]=== Graph Execution Complete [NNNNms, N steps] ===

If your log format changes, only the regexes in PATTERNS below need updating.
"""

import re
import sys
import json
import argparse
from collections import OrderedDict, defaultdict

PATTERNS = {
    # session id captured so calls are grouped correctly even if two
    # requests interleave in the log (shouldn't normally happen, but safe)
    "chat_request": re.compile(
        r"Chat request:\s*session=([0-9A-Fa-f]+),\s*prompt=(.*)$"
    ),
    "tool_selected": re.compile(
        r"LLM SELECTED TOOL\s*:\s*\[([^\]]*)\]"
    ),
    # args line appears immediately after tool_selected; tolerant of the
    # emoji prefix (📥) getting mangled/stripped by different terminals
    "tool_args": re.compile(
        r"(?:LLM GENERATED ARGS|GENERATED ARGS)\s*:\s*(\{.*\})\s*$"
    ),
    # also catch the earlier fast-path tool calls that don't go through the
    # "LLM SELECTED TOOL" line, e.g. "Tool: locations_by_psm args: {psm=CENTRAL}"
    "fastpath_tool": re.compile(
        r"Tool:\s*([A-Za-z_]+)\s+args:\s*(\{.*\})\s*$"
    ),
    "exec_complete": re.compile(
        r"Execution Complete \[(\d+)ms,\s*(\d+)\s*steps?\]"
    ),
    "verification_banner": re.compile(r"Verification Note"),
}


def parse_args_str(raw):
    """Best-effort parse of Java's Map.toString() style: {k=v, k2=v2}.
    Not a real parser -- good enough for grouping/dedup purposes, matches
    the exact format seen in your logs (e.g. {psm=CENTRAL, location=Central})."""
    raw = raw.strip()
    if raw.startswith("{") and raw.endswith("}"):
        raw = raw[1:-1]
    if not raw.strip():
        return OrderedDict()
    parts = [p.strip() for p in raw.split(",")]
    result = OrderedDict()
    for p in parts:
        if "=" in p:
            k, v = p.split("=", 1)
            result[k.strip()] = v.strip()
        elif p:
            result[p] = ""
    return result


def args_key(args_dict):
    return json.dumps(args_dict, sort_keys=True)


class RequestSession:
    def __init__(self, session_id, prompt):
        self.session_id = session_id
        self.prompt = prompt
        self.calls = []          # list of (tool_name, args_dict)
        self.total_ms = None
        self.steps = None
        self.has_verification_banner = False

    def add_call(self, tool_name, args_dict):
        self.calls.append((tool_name, args_dict))

    def analyze(self):
        by_name = defaultdict(int)
        by_name_args = defaultdict(int)
        for name, args in self.calls:
            by_name[name] += 1
            by_name_args[(name, args_key(args))] += 1

        issues = []
        for name, count in by_name.items():
            if count > 1:
                issues.append(f"Tool '{name}' called {count} times total")
        for (name, akey), count in by_name_args.items():
            if count > 1:
                issues.append(
                    f"EXACT DUPLICATE: '{name}' called {count}x with identical args {akey}"
                )
        for name, count in by_name.items():
            if count >= 3:
                issues.append(
                    f"POSSIBLE BLIND-GUESSING LOOP: '{name}' called {count} times "
                    f"with varying args (LLM likely guessing variants instead of "
                    f"consulting cached list_psms/similar results)"
                )
        return issues


def parse_log(lines):
    sessions = []
    current = None

    for line in lines:
        m = PATTERNS["chat_request"].search(line)
        if m:
            session_id, prompt = m.group(1), m.group(2).strip()
            current = RequestSession(session_id, prompt)
            sessions.append(current)
            continue

        if current is None:
            continue

        m = PATTERNS["tool_selected"].search(line)
        if m:
            # stash pending tool name; args line usually follows on the next
            # matching line, so store on the session and fill in on tool_args
            current._pending_tool = m.group(1)
            continue

        m = PATTERNS["tool_args"].search(line)
        if m and getattr(current, "_pending_tool", None):
            args = parse_args_str(m.group(1))
            current.add_call(current._pending_tool, args)
            current._pending_tool = None
            continue

        m = PATTERNS["fastpath_tool"].search(line)
        if m:
            name, raw_args = m.group(1), m.group(2)
            current.add_call(name, parse_args_str(raw_args))
            continue

        m = PATTERNS["exec_complete"].search(line)
        if m:
            current.total_ms = int(m.group(1))
            current.steps = int(m.group(2))
            continue

        if PATTERNS["verification_banner"].search(line):
            current.has_verification_banner = True

    return sessions


def print_report(sessions):
    total_flagged = 0
    for s in sessions:
        print(f"--- session={s.session_id[:8]}... ---")
        print(f"Prompt: {s.prompt}")
        if s.total_ms is not None:
            print(f"Total time: {s.total_ms}ms across {s.steps} graph steps")
        print(f"Tool calls ({len(s.calls)}):")
        for name, args in s.calls:
            print(f"    -> {name} {dict(args)}")

        issues = s.analyze()
        if issues:
            total_flagged += 1
            print("[FLAGGED]")
            for issue in issues:
                print(f"    - {issue}")
        else:
            print("[OK] No redundant tool calls detected." if s.calls
                  else "[OK] No tool calls in this request (direct answer / fast path).")

        if s.has_verification_banner:
            print("[NOTE] Response contained 'Verification Note' banner "
                  "(known to fire even when graph.verification=false).")
        print()

    print("=== Summary ===")
    print(f"{total_flagged} of {len(sessions)} requests flagged for redundant tool calls.")
    return total_flagged


def to_json(sessions):
    out = []
    for s in sessions:
        out.append({
            "session": s.session_id,
            "prompt": s.prompt,
            "total_ms": s.total_ms,
            "steps": s.steps,
            "calls": [{"tool": n, "args": dict(a)} for n, a in s.calls],
            "issues": s.analyze(),
            "has_verification_banner": s.has_verification_banner,
        })
    return out


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                      formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("logfile", help="Path to log file, or '-' for stdin")
    parser.add_argument("--json", metavar="PATH",
                         help="Also write a machine-readable JSON report to PATH")
    args = parser.parse_args()

    if args.logfile == "-":
        lines = sys.stdin.readlines()
    else:
        with open(args.logfile, "r", encoding="utf-8", errors="replace") as f:
            lines = f.readlines()

    sessions = parse_log(lines)
    if not sessions:
        print("No 'Chat request:' lines found -- check the log format or path.")
        sys.exit(2)

    flagged = print_report(sessions)

    if args.json:
        with open(args.json, "w", encoding="utf-8") as f:
            json.dump(to_json(sessions), f, indent=2)
        print(f"\nJSON report written to {args.json}")

    sys.exit(1 if flagged else 0)


if __name__ == "__main__":
    main()
