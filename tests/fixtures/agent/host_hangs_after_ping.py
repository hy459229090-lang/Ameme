"""Synthetic host fixture that acknowledges ping and then hangs."""

from __future__ import annotations

import json
import sys
import time


first = json.loads(sys.stdin.readline())
sys.stdout.write(
    json.dumps(
        {
            "protocol": "ameme.core-oracle-host.v1",
            "request_id": first["request_id"],
            "ok": True,
            "result": {
                "backend": "core-oracle-reference",
                "production_ready": False,
            },
        }
    )
    + "\n"
)
sys.stdout.flush()
sys.stdin.readline()
time.sleep(60)
