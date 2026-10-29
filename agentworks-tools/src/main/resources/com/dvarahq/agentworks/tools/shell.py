"""Structured argv only, empty-by-default environment, bounded binary output, process-group cleanup."""
import base64
import json
import os
import selectors
import signal
import subprocess
import sys
import time


def main(request):
    deadline = time.monotonic() + request["timeout"] / 1000
    process = subprocess.Popen(request["command"], env=request["environment"], cwd="/workspace",
                               stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                               start_new_session=True)
    try:
        content = bytearray()
        with selectors.DefaultSelector() as selector:
            selector.register(process.stdout, selectors.EVENT_READ)
            while True:
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    raise ValueError("SHELL_TIMEOUT")
                if not selector.select(remaining):
                    raise ValueError("SHELL_TIMEOUT")
                chunk = os.read(process.stdout.fileno(), 8192)
                if not chunk:
                    break
                if len(content) + len(chunk) > 65536:
                    raise ValueError("TOOL_OUTPUT_LIMIT")
                content.extend(chunk)
        code = process.wait(timeout=max(0.001, deadline - time.monotonic()))
        return {"exitCode": code, "encoding": "base64", "body": base64.b64encode(content).decode("ascii"),
                "bytes": len(content)}
    finally:
        try:
            os.killpg(process.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
        process.wait()
        process.stdout.close()


try:
    print(json.dumps({"result": main(json.loads(sys.argv[1]))}, separators=(",", ":")))
except subprocess.TimeoutExpired:
    print('{"error":"SHELL_TIMEOUT"}')
except ValueError as failure:
    code = str(failure)
    print(json.dumps({"error": code if code in ("TOOL_OUTPUT_LIMIT", "SHELL_TIMEOUT") else "SHELL_EXECUTION_FAILED"}))
except Exception:
    print('{"error":"SHELL_EXECUTION_FAILED"}')
