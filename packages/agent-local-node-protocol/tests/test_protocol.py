from __future__ import annotations

from copy import deepcopy
import json
from pathlib import Path
import sys
import unittest


PACKAGE_ROOT = Path(__file__).resolve().parents[1]
if str(PACKAGE_ROOT) not in sys.path:
    sys.path.insert(0, str(PACKAGE_ROOT))

from ameme_agent_local_node_protocol import (  # noqa: E402
    MAX_REQUEST_BYTES,
    ProtocolViolation,
    ReplayLedger,
    authorize_request,
    build_conformance_document,
    canonical_json_bytes,
    parse_request_line,
    validate_conformance_document,
    validate_request,
    validate_response,
)


class AgentLocalNodeProtocolTests(unittest.TestCase):
    def setUp(self) -> None:
        self.document = build_conformance_document()
        self.request = self.document["positive_requests"][1]["request"]

    def assert_code(self, expected: str, call) -> None:
        with self.assertRaises(ProtocolViolation) as raised:
            call()
        self.assertEqual(expected, raised.exception.code)

    def test_canonical_json_is_utf8_sorted_and_rejects_float(self) -> None:
        self.assertEqual(
            '{"a":"记忆","z":1}'.encode("utf-8"),
            canonical_json_bytes({"z": 1, "a": "记忆"}),
        )
        self.assert_code("INVALID_REQUEST", lambda: canonical_json_bytes({"x": 1.0}))
        self.assert_code("INVALID_REQUEST", lambda: canonical_json_bytes({"x": "a\x00b"}))
        self.assert_code("INVALID_REQUEST", lambda: canonical_json_bytes({"x": "\ud800"}))

    def test_line_parser_rejects_duplicate_keys_bom_nan_and_oversize(self) -> None:
        self.assert_code(
            "INVALID_REQUEST",
            lambda: parse_request_line(b'{"protocol_version":"a","protocol_version":"b"}\n'),
        )
        encoded = json.dumps(self.request, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        self.assert_code("INVALID_REQUEST", lambda: parse_request_line(b"\xef\xbb\xbf" + encoded))
        nan_line = encoded.replace(b'"content":"Synthetic milestone was verified by a tool."', b'"content":NaN')
        self.assert_code("INVALID_REQUEST", lambda: parse_request_line(nan_line))
        escaped_nul = encoded.replace(
            b'"content":"Synthetic milestone was verified by a tool."',
            b'"content":"synthetic\\u0000content"',
        )
        self.assert_code("INVALID_REQUEST", lambda: parse_request_line(escaped_nul))
        self.assert_code(
            "PAYLOAD_TOO_LARGE",
            lambda: parse_request_line(b" " * (MAX_REQUEST_BYTES + 1)),
        )

    def test_committed_vector_semantics_are_self_consistent(self) -> None:
        self.assertEqual([], validate_conformance_document(self.document))
        for item in self.document["positive_requests"]:
            validate_request(item["request"])
        for item in self.document["negative_requests"]:
            self.assert_code(
                item["expected_error"], lambda item=item: validate_request(item["request"])
            )

    def test_grant_superset_allows_request_subset_but_denies_expansion(self) -> None:
        cases = {item["id"]: item for item in self.document["authorization_cases"]}
        allowed = cases["grant_superset_request_subset_allowed"]
        authorize_request(self.request, allowed["grant"])
        for case_id, error in (
            ("space_denied", "SPACE_DENIED"),
            ("type_denied", "DATA_TYPE_DENIED"),
            ("purpose_denied", "PURPOSE_DENIED"),
        ):
            self.assert_code(
                error,
                lambda case=cases[case_id]: authorize_request(self.request, case["grant"]),
            )
        malformed = deepcopy(self.request)
        malformed["control"]["spaces"] = ["space_work", 1]
        self.assert_code("INVALID_REQUEST", lambda: validate_request(malformed))

    def test_same_slot_replays_only_same_semantics(self) -> None:
        ledger = ReplayLedger()
        self.assertEqual("NEW", ledger.accept(self.request))
        replay = deepcopy(self.request)
        replay["request_id"] = "req_same_semantics_retry"
        self.assertEqual("REPLAY", ledger.accept(replay))
        changed = deepcopy(replay)
        changed["payload"]["content"] = "Changed synthetic payload."
        from ameme_agent_local_node_protocol import digest_json

        changed["control"]["payload_digest"] = digest_json(changed["payload"])
        self.assert_code("IDEMPOTENCY_CONFLICT", lambda: ledger.accept(changed))

    def test_response_id_digest_and_not_visible_are_fail_closed(self) -> None:
        positive = self.document["responses"]["positive"]
        validate_response(positive[0]["response"], request=self.document["positive_requests"][1]["request"])
        not_visible = positive[1]["response"]
        validate_response(not_visible, request=self.document["positive_requests"][1]["request"])
        self.assertEqual({"code", "retryable"}, set(not_visible["error"]))
        unsupported = positive[2]["response"]
        validate_response(unsupported, request=self.document["positive_requests"][1]["request"])
        self.assertEqual(
            {"code": "OPERATION_UNSUPPORTED", "retryable": False},
            unsupported["error"],
        )
        wrong_retryability = deepcopy(unsupported)
        wrong_retryability["error"]["retryable"] = True
        self.assert_code(
            "INVALID_REQUEST",
            lambda: validate_response(
                wrong_retryability,
                request=self.document["positive_requests"][1]["request"],
            ),
        )
        negative = self.document["responses"]["negative"]
        for item in negative:
            request = self.document["positive_requests"][1]["request"]
            self.assert_code(
                item["expected_error"],
                lambda item=item, request=request: validate_response(item["response"], request=request),
            )


if __name__ == "__main__":
    unittest.main()
