from __future__ import annotations

import json
import sys
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "tests" / "harness"))

from contract_harness import AllowListLogger, FixedClock, assert_canaries_absent  # noqa: E402


SYNTHETIC_BODY = "CANARY_BODY_9f53d6c4 synthetic private journal text"
SYNTHETIC_SECRET = "SYNTHETIC_SECRET_CANARY_81d2f9"
SYNTHETIC_QUERY = "CANARY_QUERY_47bd21"
SYNTHETIC_URL = f"https://api.example.invalid/v1/recall?token={SYNTHETIC_SECRET}&q={SYNTHETIC_QUERY}#private"


class LogSafetyTests(unittest.TestCase):
    def setUp(self) -> None:
        clock = FixedClock(datetime(2026, 7, 14, 10, 0, tzinfo=timezone(timedelta(hours=8))))
        self.logger = AllowListLogger(clock)

    def test_sensitive_body_secret_query_and_raw_exception_are_dropped(self) -> None:
        self.logger.emit(
            "contract.validation",
            component="contract_runtime",
            operation="validate_bundle",
            result_code="OK",
            object_type="event",
            object_count_bucket="2-5",
            content=SYNTHETIC_BODY,
            prompt=SYNTHETIC_BODY,
            search_term=SYNTHETIC_QUERY,
            token=SYNTHETIC_SECRET,
            error=RuntimeError(SYNTHETIC_SECRET),
            absolute_path="C:\\Synthetic\\private.txt",
            exact_coordinates="31.2304,121.4737",
        )
        output = self.logger.json_lines()
        assert_canaries_absent(output, [SYNTHETIC_BODY, SYNTHETIC_SECRET, SYNTHETIC_QUERY, "Synthetic\\private.txt", "31.2304"])
        record = json.loads(output)
        self.assertEqual(
            {"timestamp", "event_name", "component", "operation", "result_code", "object_type", "object_count_bucket"},
            set(record),
        )

    def test_full_url_is_reduced_to_scheme_host_and_path(self) -> None:
        self.logger.emit(
            "contract.request",
            component="contract_runtime",
            operation="recall",
            result_code="GRANT_REVOKED",
            endpoint=SYNTHETIC_URL,
        )
        output = self.logger.json_lines()
        assert_canaries_absent(output, [SYNTHETIC_SECRET, SYNTHETIC_QUERY])
        record = json.loads(output)
        self.assertEqual("https://api.example.invalid/v1/recall", record["endpoint"])
        self.assertNotIn("?", record["endpoint"])
        self.assertNotIn("#", record["endpoint"])

    def test_canaries_cannot_hide_inside_allowed_token_fields(self) -> None:
        self.logger.emit(
            SYNTHETIC_BODY,
            component=SYNTHETIC_BODY,
            operation=SYNTHETIC_SECRET,
            result_code=SYNTHETIC_QUERY,
            trace_id=SYNTHETIC_SECRET,
            endpoint=SYNTHETIC_URL,
        )
        output = self.logger.json_lines()
        assert_canaries_absent(output, [SYNTHETIC_BODY, SYNTHETIC_SECRET, SYNTHETIC_QUERY])
        record = json.loads(output)
        self.assertEqual("invalid.event", record["event_name"])
        self.assertNotIn("component", record)
        self.assertNotIn("operation", record)
        self.assertNotIn("result_code", record)
        self.assertNotIn("trace_id", record)


if __name__ == "__main__":
    unittest.main()
