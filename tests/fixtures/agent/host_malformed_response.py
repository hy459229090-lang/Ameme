"""Synthetic host fixture that emits one malformed response."""

from __future__ import annotations

import sys


sys.stdin.readline()
sys.stdout.write("not-json\n")
sys.stdout.flush()
