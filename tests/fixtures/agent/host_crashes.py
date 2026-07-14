"""Synthetic host fixture that exits after receiving one request."""

from __future__ import annotations

import sys


sys.stdin.readline()
raise SystemExit(23)
