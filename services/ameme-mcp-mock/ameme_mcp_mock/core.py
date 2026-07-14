"""Policy-enforcing local implementation behind the MCP transport."""

from __future__ import annotations

import hashlib
import json
import re
import uuid
from copy import deepcopy
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from typing import Any, Callable, Iterable

from .store import JsonStore


AUTONOMOUS_PURPOSE = "autonomous_memory"
MAX_GRANT_DAYS = 30
DEFAULT_CONTEXT_DAYS = 30
DEFAULT_CONTEXT_ITEMS = 12
DEFAULT_CONTEXT_TOKENS = 2_000
CONTEXT_TTL_MINUTES = 15
UNDO_TTL_MINUTES = 10
INJECTION_PATTERN = re.compile(
    r"ignore (?:all |previous |system )?(?:instructions?|requirements?)|"
    r"忽略(?:系统|之前|以上).{0,12}(?:要求|指令)|"
    r"(?:export|reveal|print|显示|导出).{0,12}(?:secret|token|key|密钥)",
    re.IGNORECASE,
)


@dataclass
class MockError(Exception):
    code: str
    message_key: str
    retryable: bool = False

    def as_dict(self) -> dict[str, Any]:
        return {
            "code": self.code,
            "message_key": self.message_key,
            "retryable": self.retryable,
            "trace_id": f"trace_{uuid.uuid4().hex[:16]}",
        }


def _utcnow() -> datetime:
    return datetime.now(timezone.utc)


def _iso(value: datetime) -> str:
    return value.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")


def _parse(value: str) -> datetime:
    return datetime.fromisoformat(value.replace("Z", "+00:00")).astimezone(timezone.utc)


def _digest(value: Any) -> str:
    payload = json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(payload.encode("utf-8")).hexdigest()


def _identifier(prefix: str) -> str:
    return f"{prefix}_{uuid.uuid4().hex}"


def _required(arguments: dict[str, Any], *names: str) -> None:
    missing = [name for name in names if name not in arguments or arguments[name] in (None, "", [])]
    if missing:
        raise MockError("INVALID_ARGUMENT", "missing_required_fields")


class AmemeMock:
    """Implements the six Agent tools with strict local policy checks."""

    def __init__(
        self,
        store: JsonStore,
        *,
        clock: Callable[[], datetime] | None = None,
        offline: bool = False,
    ) -> None:
        self.store = store
        self.clock = clock or _utcnow
        self.offline = offline

    def call(self, tool: str, arguments: dict[str, Any]) -> dict[str, Any]:
        handlers = {
            "pair": self.pair,
            "capture": self.capture,
            "recall": self.recall,
            "get_context": self.get_context,
            "feedback": self.feedback,
            "status": self.status,
        }
        if tool not in handlers:
            raise MockError("METHOD_NOT_FOUND", "unknown_tool")
        activity_count = len(self.store.state["activity"])
        try:
            return handlers[tool](arguments)
        except MockError as error:
            if len(self.store.state["activity"]) == activity_count:
                spaces = arguments.get("spaces") or ([arguments["space"]] if arguments.get("space") else [])
                data_types = arguments.get("memory_types") or (
                    [arguments["memory_type"]] if arguments.get("memory_type") else arguments.get("data_types", [])
                )
                self._activity(
                    arguments.get("caller_id", "caller_unknown"),
                    arguments.get("purpose", tool),
                    spaces,
                    data_types,
                    error.code,
                )
                self.store.save()
            raise

    def pair(self, arguments: dict[str, Any]) -> dict[str, Any]:
        action = arguments.get("action", "begin")
        if action == "begin":
            return self._begin_pair(arguments)
        if action == "approve":
            return self._approve_pair(arguments)
        if action == "revoke":
            return self._revoke_pair(arguments)
        raise MockError("INVALID_ARGUMENT", "unknown_pair_action")

    def _begin_pair(self, arguments: dict[str, Any]) -> dict[str, Any]:
        _required(arguments, "caller_id", "purposes", "spaces", "data_types", "expires_at")
        terms = {
            "caller_id": arguments["caller_id"],
            "purposes": self._bounded_values(arguments["purposes"]),
            "spaces": self._bounded_values(arguments["spaces"]),
            "data_types": self._bounded_values(arguments["data_types"]),
            "not_before": arguments.get("not_before", _iso(self.clock())),
            "expires_at": arguments["expires_at"],
        }
        if any("*" in value for values in (terms["purposes"], terms["spaces"], terms["data_types"]) for value in values):
            raise MockError("SCOPE_DENIED", "wildcards_are_not_grants")
        not_before = _parse(terms["not_before"])
        expires_at = _parse(terms["expires_at"])
        if expires_at <= max(self.clock(), not_before):
            raise MockError("GRANT_EXPIRED", "grant_expiry_must_be_future")
        if expires_at - not_before > timedelta(days=MAX_GRANT_DAYS):
            raise MockError("CONSENT_REQUIRED", "grant_duration_exceeds_mock_default")

        challenge_id = _identifier("pair")
        challenge = {
            "challenge_id": challenge_id,
            "terms": terms,
            "terms_digest": _digest(terms),
            "status": "pending",
            "created_at": _iso(self.clock()),
            "expires_at": _iso(self.clock() + timedelta(minutes=5)),
        }
        self.store.state["challenges"][challenge_id] = challenge
        self.store.save()
        self._activity(terms["caller_id"], "pair", terms["spaces"], terms["data_types"], "PAIRING_PENDING")
        return {
            "state": "pairing_pending",
            "challenge_id": challenge_id,
            "terms_digest": challenge["terms_digest"],
            "terms": terms,
            "confirmation_required": True,
            "revoke_route": "pair(action=revoke)",
        }

    def _approve_pair(self, arguments: dict[str, Any]) -> dict[str, Any]:
        _required(arguments, "caller_id", "challenge_id", "confirmation")
        challenge = self.store.state["challenges"].get(arguments["challenge_id"])
        if not challenge:
            raise MockError("AUTH_REQUIRED", "pairing_challenge_unknown")
        if challenge["status"] != "pending":
            raise MockError("PAIRING_REPLAY", "pairing_challenge_already_used")
        if challenge["terms"]["caller_id"] != arguments["caller_id"]:
            raise MockError("AUTH_REQUIRED", "pairing_caller_mismatch")
        if _parse(challenge["expires_at"]) <= self.clock():
            challenge["status"] = "expired"
            self.store.save()
            raise MockError("GRANT_EXPIRED", "pairing_challenge_expired")
        confirmation = arguments["confirmation"]
        if not isinstance(confirmation, dict) or confirmation.get("confirmed") is not True:
            raise MockError("CONSENT_REQUIRED", "pairing_needs_explicit_confirmation")
        if confirmation.get("terms_digest") != challenge["terms_digest"]:
            raise MockError("SCOPE_DENIED", "confirmed_terms_do_not_match")

        terms = challenge["terms"]
        grant_id = _identifier("grt")
        grant = {
            "schema_version": 1,
            "grant_id": grant_id,
            "owner_id": "user_synthetic",
            **deepcopy(terms),
            "status": "active",
            "created_at": _iso(self.clock()),
        }
        self.store.state["grants"][grant_id] = grant
        challenge["status"] = "used"
        challenge["grant_id"] = grant_id
        self.store.save()
        self._activity(terms["caller_id"], "pair", terms["spaces"], terms["data_types"], "OK")
        return {
            "state": "ready",
            "grant_id": grant_id,
            "expires_at": grant["expires_at"],
            "scope": self._scope_summary(grant),
            "revoke_route": "pair(action=revoke)",
        }

    def _revoke_pair(self, arguments: dict[str, Any]) -> dict[str, Any]:
        _required(arguments, "caller_id", "grant_id", "confirmation")
        confirmation = arguments["confirmation"]
        if not isinstance(confirmation, dict) or confirmation.get("confirmed") is not True:
            raise MockError("CONSENT_REQUIRED", "revocation_needs_explicit_confirmation")
        grant = self.store.state["grants"].get(arguments["grant_id"])
        if not grant or grant["caller_id"] != arguments["caller_id"]:
            raise MockError("AUTH_REQUIRED", "grant_not_visible")
        if grant["status"] == "revoked":
            return {"state": "revoked", "grant_id": grant["grant_id"], "replayed": True}
        grant["status"] = "revoked"
        grant["revoked_at"] = _iso(self.clock())
        for context_pack in self.store.state["context_packs"].values():
            if context_pack["grant_id"] == grant["grant_id"]:
                context_pack["state"] = "expired"
        self.store.save()
        self._activity(grant["caller_id"], "revoke", grant["spaces"], grant["data_types"], "GRANT_REVOKED")
        return {"state": "revoked", "grant_id": grant["grant_id"], "new_access_blocked": True}

    def capture(self, arguments: dict[str, Any]) -> dict[str, Any]:
        if arguments.get("operation", "create") == "undo":
            return self._undo_capture(arguments)
        if arguments.get("operation", "create") != "create":
            raise MockError("INVALID_ARGUMENT", "unknown_capture_operation")
        _required(
            arguments,
            "caller_id",
            "grant_id",
            "purpose",
            "space",
            "memory_type",
            "content",
            "evidence_kind",
            "idempotency_key",
        )
        if arguments.get("requested_action") in {"delete", "erase", "purge"}:
            raise MockError("DELETION_CONFIRMATION_REQUIRED", "use_dedicated_deletion_flow")
        if len(arguments["idempotency_key"]) < 8:
            raise MockError("INVALID_ARGUMENT", "idempotency_key_too_short")
        memory_type = arguments["memory_type"]
        self._authorize(arguments, [arguments["space"]], [memory_type], autonomous=True)
        self._high_risk_gate(arguments, autonomous=True)

        payload_hash = _digest(
            {
                key: arguments.get(key)
                for key in (
                    "caller_id",
                    "grant_id",
                    "purpose",
                    "space",
                    "memory_type",
                    "content",
                    "evidence_kind",
                    "target_event_id",
                    "event_time",
                )
            }
        )
        prior = self.store.state["idempotency"].get(arguments["idempotency_key"])
        if prior:
            if prior["payload_hash"] != payload_hash:
                self._activity(arguments["caller_id"], arguments["purpose"], [arguments["space"]], [memory_type], "IDEMPOTENCY_CONFLICT")
                raise MockError("IDEMPOTENCY_CONFLICT", "idempotency_key_payload_changed")
            replay = deepcopy(prior["result"])
            replay["replayed"] = True
            return replay

        evidence_state, fact_status = self._evidence(arguments["evidence_kind"])
        now = _iso(self.clock())
        if memory_type == "revision":
            result = self._create_revision(arguments, evidence_state, now)
        elif memory_type == "event":
            event_id = _identifier("evt")
            event = {
                "schema_version": 1,
                "event_id": event_id,
                "owner_id": "user_synthetic",
                "space_id": arguments["space"],
                "event_type": arguments.get("event_type", "result"),
                "time_range": {
                    "start": arguments.get("event_time", now),
                    "precision": "minute",
                },
                "title": str(arguments["content"])[:200],
                "description": str(arguments["content"])[:4000],
                "fact_status": fact_status,
                "field_evidence": [
                    {
                        "field": "description",
                        "observation_ids": [f"obs_{event_id[4:]}"],
                        "confidence": 1.0 if evidence_state in {"observed", "user_asserted"} else 0.5,
                        "status": evidence_state,
                    }
                ],
                "evidence_state": evidence_state,
                "source_object_ids": [f"src_{event_id[4:]}"],
                "revision": 1,
                "sensitivity": arguments.get("sensitivity", "personal"),
                "data_class": arguments.get("data_class", "structured"),
                "memory_type": "event",
                "state": "active",
                "created_at": now,
                "updated_at": now,
            }
            self.store.state["events"][event_id] = event
            result = {"object_type": "event", "event_id": event_id, "revision": 1}
        else:
            raise MockError("DATA_TYPE_DENIED", "capture_only_creates_event_or_revision")

        undo_token = _identifier("undo")
        undo_target = result.get("event_id") or result["target_event_id"]
        self.store.state["undo"][undo_token] = {
            "caller_id": arguments["caller_id"],
            "grant_id": arguments["grant_id"],
            "target_event_id": undo_target,
            "created_object_type": result["object_type"],
            "created_object_id": result.get("event_revision_id", undo_target),
            "expires_at": _iso(self.clock() + timedelta(minutes=UNDO_TTL_MINUTES)),
            "used": False,
        }
        delivery_state = "queued" if self.offline or arguments.get("simulate_offline") else "local_only"
        if delivery_state == "queued":
            self.store.state["queue"].append(
                {
                    "queue_id": _identifier("que"),
                    "object_type": result["object_type"],
                    "object_id": result.get("event_id") or result.get("event_revision_id"),
                    "state": "queued",
                    "created_at": now,
                }
            )
        response = {
            **result,
            "evidence_state": evidence_state,
            "persistence_state": "durable",
            "delivery_state": delivery_state,
            "synced": False,
            "activity_visible": True,
            "undo_token": undo_token,
            "undo_expires_at": self.store.state["undo"][undo_token]["expires_at"],
        }
        self.store.state["idempotency"][arguments["idempotency_key"]] = {
            "payload_hash": payload_hash,
            "result": deepcopy(response),
            "created_at": now,
        }
        self._activity(arguments["caller_id"], arguments["purpose"], [arguments["space"]], [memory_type], "OK", 1)
        self.store.save()
        return response

    def _create_revision(self, arguments: dict[str, Any], evidence_state: str, now: str) -> dict[str, Any]:
        _required(arguments, "target_event_id")
        event = self.store.state["events"].get(arguments["target_event_id"])
        if not event or event["state"] != "active" or event["space_id"] != arguments["space"]:
            raise MockError("AUTH_REQUIRED", "target_not_visible")
        revision_number = int(event["revision"]) + 1
        revision_id = _identifier("evr")
        revision = {
            "schema_version": 1,
            "event_revision_id": revision_id,
            "event_id": event["event_id"],
            "revision": revision_number,
            "actor": "agent",
            "reason": "user_addendum" if evidence_state == "user_asserted" else "model_recompute",
            "changes": {"description": str(arguments["content"])[:4000], "evidence_state": evidence_state},
            "created_at": now,
        }
        self.store.state["revisions"][revision_id] = revision
        event["description"] = str(arguments["content"])[:4000]
        event["revision"] = revision_number
        event["revision_head_id"] = revision_id
        event["evidence_state"] = evidence_state
        event["updated_at"] = now
        return {
            "object_type": "revision",
            "event_revision_id": revision_id,
            "target_event_id": event["event_id"],
            "revision": revision_number,
        }

    def _undo_capture(self, arguments: dict[str, Any]) -> dict[str, Any]:
        _required(arguments, "caller_id", "grant_id", "purpose", "space", "memory_type", "undo_token")
        self._authorize(arguments, [arguments["space"]], [arguments["memory_type"]], autonomous=True)
        undo = self.store.state["undo"].get(arguments["undo_token"])
        if not undo or undo["caller_id"] != arguments["caller_id"] or undo["grant_id"] != arguments["grant_id"]:
            raise MockError("AUTH_REQUIRED", "undo_not_visible")
        if undo["used"]:
            return {"state": "undone", "replayed": True, "target_event_id": undo["target_event_id"]}
        if _parse(undo["expires_at"]) <= self.clock():
            raise MockError("CONTEXT_EXPIRED", "undo_window_expired")
        event = self.store.state["events"].get(undo["target_event_id"])
        if event:
            event["state"] = "deleted"
            event["updated_at"] = _iso(self.clock())
        undo["used"] = True
        self._activity(arguments["caller_id"], arguments["purpose"], [arguments["space"]], [arguments["memory_type"]], "UNDONE", 1)
        self.store.save()
        return {"state": "undone", "target_event_id": undo["target_event_id"], "activity_visible": True}

    def recall(self, arguments: dict[str, Any]) -> dict[str, Any]:
        _required(arguments, "caller_id", "grant_id", "purpose", "spaces", "memory_types")
        spaces = self._bounded_values(arguments["spaces"])
        memory_types = self._bounded_values(arguments["memory_types"])
        autonomous = arguments.get("invocation", "explicit") == "autonomous"
        self._authorize(arguments, spaces, memory_types, autonomous=autonomous)
        allow_high_risk = self._high_risk_gate(arguments, autonomous=autonomous)
        start_at, end_at = self._requested_time_range(arguments.get("time_range"))
        events, risk_filtered = self._visible_events(
            spaces,
            memory_types,
            arguments.get("query"),
            allow_high_risk=allow_high_risk,
            start_at=start_at,
            end_at=end_at,
        )
        limit = min(max(int(arguments.get("limit", 20)), 1), 100)
        results = [
            {
                "object_type": event["memory_type"],
                "object_id": event["event_id"],
                "revision": event["revision"],
                "space_id": event["space_id"],
                "fact_status": event["fact_status"],
                "lineage_refs": event.get("source_object_ids", []),
            }
            for event in events[:limit]
        ]
        partial_reasons = ["device_not_synced"] if self.offline else []
        if risk_filtered:
            partial_reasons.append("policy_filtered")
        state = "partial" if partial_reasons else ("empty" if not results else "complete_for_requested_scope")
        self._activity(arguments["caller_id"], arguments["purpose"], spaces, memory_types, "OK", len(results))
        self.store.save()
        return {
            "range_state": state,
            "partial_reasons": partial_reasons,
            "results": results,
            "requested_scope": {"spaces": spaces, "memory_types": memory_types},
        }

    def get_context(self, arguments: dict[str, Any]) -> dict[str, Any]:
        _required(arguments, "caller_id", "grant_id", "purpose", "space", "memory_types")
        space = arguments["space"]
        memory_types = self._bounded_values(arguments["memory_types"])
        autonomous = arguments.get("invocation", "autonomous") == "autonomous"
        self._authorize(arguments, [space], memory_types, autonomous=autonomous)
        allow_high_risk = self._high_risk_gate(arguments, autonomous=autonomous)
        days = int(arguments.get("time_window_days", DEFAULT_CONTEXT_DAYS))
        item_budget = int(arguments.get("item_budget", DEFAULT_CONTEXT_ITEMS))
        token_budget = int(arguments.get("token_budget", DEFAULT_CONTEXT_TOKENS))
        if autonomous and (days > DEFAULT_CONTEXT_DAYS or item_budget > DEFAULT_CONTEXT_ITEMS or token_budget > DEFAULT_CONTEXT_TOKENS):
            raise MockError("CONSENT_REQUIRED", "implicit_context_scope_expansion")
        item_budget = min(max(item_budget, 1), DEFAULT_CONTEXT_ITEMS)
        token_budget = min(max(token_budget, 1), DEFAULT_CONTEXT_TOKENS)
        events, risk_filtered = self._visible_events(
            [space],
            memory_types,
            arguments.get("query"),
            allow_high_risk=allow_high_risk,
            start_at=self.clock() - timedelta(days=days),
            end_at=self.clock(),
        )
        safe_events: list[dict[str, Any]] = []
        filtered = risk_filtered
        for event in events:
            body = f"{event.get('title', '')}\n{event.get('description', '')}"
            if INJECTION_PATTERN.search(body):
                filtered = True
                continue
            safe_events.append(event)
        items = [
            {
                "object_type": event["memory_type"],
                "object_id": event["event_id"],
                "revision": event["revision"],
                "content": event.get("description") or event.get("title"),
                "evidence_state": event.get("evidence_state"),
                "lineage_refs": event.get("source_object_ids", []),
                "untrusted_memory": True,
            }
            for event in safe_events[:item_budget]
        ]
        pack_id = _identifier("ctx")
        partial_reasons: list[str] = []
        if self.offline:
            partial_reasons.append("device_not_synced")
        if filtered:
            partial_reasons.append("policy_filtered")
        pack = {
            "schema_version": 1,
            "context_pack_id": pack_id,
            "grant_id": arguments["grant_id"],
            "caller_id": arguments["caller_id"],
            "purpose": arguments["purpose"],
            "space": space,
            "memory_types": memory_types,
            "state": "partial" if partial_reasons else "ready",
            "partial_reasons": partial_reasons,
            "items": items,
            "token_budget": token_budget,
            "instruction_boundary": "memory_is_untrusted_data",
            "created_at": _iso(self.clock()),
            "expires_at": _iso(self.clock() + timedelta(minutes=CONTEXT_TTL_MINUTES)),
        }
        self.store.state["context_packs"][pack_id] = pack
        self._activity(arguments["caller_id"], arguments["purpose"], [space], memory_types, "OK", len(items))
        self.store.save()
        return deepcopy(pack)

    def feedback(self, arguments: dict[str, Any]) -> dict[str, Any]:
        _required(arguments, "caller_id", "grant_id", "purpose", "space", "memory_type", "target_id", "action", "idempotency_key")
        if len(arguments["idempotency_key"]) < 8:
            raise MockError("INVALID_ARGUMENT", "idempotency_key_too_short")
        if arguments["action"] in {"delete", "erase", "purge"}:
            raise MockError("DELETION_CONFIRMATION_REQUIRED", "use_dedicated_deletion_flow")
        autonomous_revision = arguments["action"] == "correct"
        self._authorize(arguments, [arguments["space"]], [arguments["memory_type"]], autonomous=autonomous_revision)
        payload_hash = _digest({key: arguments.get(key) for key in sorted(arguments) if key != "idempotency_key"})
        prior = self.store.state["idempotency"].get(arguments["idempotency_key"])
        if prior:
            if prior["payload_hash"] != payload_hash:
                raise MockError("IDEMPOTENCY_CONFLICT", "idempotency_key_payload_changed")
            result = deepcopy(prior["result"])
            result["replayed"] = True
            return result
        feedback_id = _identifier("fbk")
        feedback = {
            "feedback_id": feedback_id,
            "space_id": arguments["space"],
            "target_id": arguments["target_id"],
            "action": arguments["action"],
            "user_statement": arguments.get("user_statement"),
            "created_at": _iso(self.clock()),
        }
        self.store.state["feedback"][feedback_id] = feedback
        delivery_state = "queued" if self.offline or arguments.get("simulate_offline") else "local_only"
        result: dict[str, Any] = {
            "feedback_id": feedback_id,
            "state": "queued" if delivery_state == "queued" else "accepted",
            "persistence_state": "durable",
            "delivery_state": delivery_state,
            "synced": False,
            "activity_visible": True,
        }
        if arguments["action"] == "correct":
            revision_arguments = {
                **arguments,
                "target_event_id": arguments["target_id"],
                "content": arguments.get("user_statement", "User correction"),
            }
            result.update(self._create_revision(revision_arguments, "user_asserted", _iso(self.clock())))
        if arguments["action"] == "policy_violation":
            event = self.store.state["events"].get(arguments["target_id"])
            if event:
                event["policy_blocked"] = True
        if delivery_state == "queued":
            self.store.state["queue"].append(
                {
                    "queue_id": _identifier("que"),
                    "object_type": "feedback",
                    "object_id": feedback_id,
                    "state": "queued",
                    "created_at": _iso(self.clock()),
                }
            )
        self.store.state["idempotency"][arguments["idempotency_key"]] = {
            "payload_hash": payload_hash,
            "result": deepcopy(result),
            "created_at": _iso(self.clock()),
        }
        self._activity(arguments["caller_id"], arguments["purpose"], [arguments["space"]], [arguments["memory_type"]], "OK", 1)
        self.store.save()
        return result

    def status(self, arguments: dict[str, Any]) -> dict[str, Any]:
        _required(arguments, "caller_id")
        caller_id = arguments["caller_id"]
        grants = [grant for grant in self.store.state["grants"].values() if grant["caller_id"] == caller_id]
        for grant in grants:
            self._expire_if_needed(grant)
        selected = arguments.get("grant_id")
        if selected:
            grants = [grant for grant in grants if grant["grant_id"] == selected]
        visible_grants = [
            {
                "grant_id": grant["grant_id"],
                "state": grant["status"],
                "expires_at": grant["expires_at"],
                "scope": self._scope_summary(grant),
                "revoke_route": "pair(action=revoke)",
            }
            for grant in grants
        ]
        activities = [activity for activity in self.store.state["activity"] if activity["caller_id"] == caller_id]
        self.store.save()
        return {
            "connection_state": "offline" if self.offline else ("ready" if any(item["state"] == "active" for item in visible_grants) else "unpaired"),
            "grants": visible_grants,
            "queued_count": len(self.store.state["queue"]),
            "recent_activity": activities[-20:],
            "recovery_action": "retry_when_online" if self.offline else None,
        }

    def _authorize(
        self,
        arguments: dict[str, Any],
        spaces: list[str],
        memory_types: list[str],
        *,
        autonomous: bool,
    ) -> dict[str, Any]:
        grant = self.store.state["grants"].get(arguments.get("grant_id"))
        if not grant:
            self._deny(arguments, spaces, memory_types, "GRANT_MISSING", "grant_missing")
        assert grant is not None
        if grant["caller_id"] != arguments.get("caller_id"):
            self._deny(arguments, spaces, memory_types, "AUTH_REQUIRED", "caller_mismatch")
        self._expire_if_needed(grant)
        if grant["status"] == "revoked":
            self._deny(arguments, spaces, memory_types, "GRANT_REVOKED", "grant_revoked")
        if grant["status"] == "expired":
            self._deny(arguments, spaces, memory_types, "GRANT_EXPIRED", "grant_expired")
        purpose = arguments.get("purpose")
        if purpose not in grant["purposes"]:
            self._deny(arguments, spaces, memory_types, "PURPOSE_DENIED", "purpose_denied")
        if not set(spaces).issubset(set(grant["spaces"])):
            self._deny(arguments, spaces, memory_types, "SPACE_DENIED", "space_denied")
        if not set(memory_types).issubset(set(grant["data_types"])):
            self._deny(arguments, spaces, memory_types, "DATA_TYPE_DENIED", "data_type_denied")
        if autonomous:
            exact = (
                purpose == AUTONOMOUS_PURPOSE
                and set(grant["purposes"]) == {AUTONOMOUS_PURPOSE}
                and set(grant["spaces"]) == set(spaces)
                and set(grant["data_types"]) == set(memory_types)
            )
            if not exact:
                self._deny(arguments, spaces, memory_types, "CONSENT_REQUIRED", "autonomous_grant_not_exact")
        return grant

    def _high_risk_gate(self, arguments: dict[str, Any], *, autonomous: bool) -> bool:
        sensitivity = str(arguments.get("sensitivity", "personal")).lower()
        data_class = str(arguments.get("data_class", "structured")).lower()
        risky = sensitivity == "restricted" or data_class == "raw"
        if not risky:
            return False
        if autonomous:
            raise MockError("POLICY_BLOCKED", "autonomous_raw_or_restricted_blocked")
        confirmation = arguments.get("high_risk_confirmation")
        if not isinstance(confirmation, dict) or confirmation.get("confirmed") is not True:
            raise MockError("CONSENT_REQUIRED", "raw_or_restricted_needs_confirmation")
        return True

    def _visible_events(
        self,
        spaces: Iterable[str],
        memory_types: Iterable[str],
        query: str | None,
        *,
        allow_high_risk: bool,
        start_at: datetime | None = None,
        end_at: datetime | None = None,
    ) -> tuple[list[dict[str, Any]], bool]:
        allowed_spaces = set(spaces)
        allowed_types = set(memory_types)
        query_folded = (query or "").casefold()
        result = []
        risk_filtered = False
        for event in self.store.state["events"].values():
            if event.get("state") != "active" or event.get("policy_blocked"):
                continue
            if event.get("space_id") not in allowed_spaces or event.get("memory_type", "event") not in allowed_types:
                continue
            if not allow_high_risk and (
                event.get("sensitivity") == "restricted" or event.get("data_class") == "raw"
            ):
                risk_filtered = True
                continue
            event_time_value = event.get("time_range", {}).get("start") or event.get("created_at")
            if event_time_value:
                event_time = _parse(event_time_value)
                if start_at and event_time < start_at:
                    continue
                if end_at and event_time > end_at:
                    continue
            body = f"{event.get('title', '')}\n{event.get('description', '')}".casefold()
            if query_folded and query_folded not in body:
                continue
            result.append(event)
        result.sort(key=lambda item: item.get("updated_at") or item.get("created_at") or "", reverse=True)
        return result, risk_filtered

    @staticmethod
    def _requested_time_range(value: Any) -> tuple[datetime | None, datetime | None]:
        if value is None:
            return None, None
        if not isinstance(value, dict) or not value.get("start"):
            raise MockError("INVALID_ARGUMENT", "time_range_requires_start")
        start = _parse(value["start"])
        end = _parse(value["end"]) if value.get("end") else None
        if end and end < start:
            raise MockError("INVALID_ARGUMENT", "time_range_end_before_start")
        return start, end

    def _expire_if_needed(self, grant: dict[str, Any]) -> None:
        if grant["status"] == "active" and _parse(grant["expires_at"]) <= self.clock():
            grant["status"] = "expired"
            for context_pack in self.store.state["context_packs"].values():
                if context_pack["grant_id"] == grant["grant_id"]:
                    context_pack["state"] = "expired"

    def _deny(
        self,
        arguments: dict[str, Any],
        spaces: list[str],
        memory_types: list[str],
        code: str,
        message_key: str,
    ) -> None:
        self._activity(
            arguments.get("caller_id", "caller_unknown"),
            arguments.get("purpose", "unknown"),
            spaces,
            memory_types,
            code,
        )
        self.store.save()
        raise MockError(code, message_key)

    def _activity(
        self,
        caller_id: str,
        purpose: str,
        spaces: Iterable[str],
        data_types: Iterable[str],
        result_code: str,
        object_count: int = 0,
    ) -> None:
        count_bucket = "0" if object_count == 0 else ("1" if object_count == 1 else "2-10" if object_count <= 10 else "11+")
        self.store.state["activity"].append(
            {
                "activity_id": _identifier("act"),
                "caller_id": caller_id,
                "purpose": purpose,
                "spaces": sorted(set(spaces)),
                "data_types": sorted(set(data_types)),
                "result_code": result_code,
                "object_count_bucket": count_bucket,
                "occurred_at": _iso(self.clock()),
            }
        )

    @staticmethod
    def _bounded_values(values: Any) -> list[str]:
        if not isinstance(values, list) or not values or len(values) > 16:
            raise MockError("INVALID_ARGUMENT", "scope_must_be_nonempty_list")
        normalized = [str(value) for value in values]
        if len(set(normalized)) != len(normalized):
            raise MockError("INVALID_ARGUMENT", "scope_values_must_be_unique")
        return normalized

    @staticmethod
    def _scope_summary(grant: dict[str, Any]) -> dict[str, Any]:
        return {
            "caller_id": grant["caller_id"],
            "purposes": grant["purposes"],
            "spaces": grant["spaces"],
            "data_types": grant["data_types"],
        }

    @staticmethod
    def _evidence(kind: str) -> tuple[str, str]:
        mapping = {
            "user_statement": ("user_asserted", "user_asserted"),
            "inference": ("inferred", "low_confidence_candidate"),
            "direct_evidence": ("observed", "confirmed"),
        }
        if kind not in mapping:
            raise MockError("INVALID_ARGUMENT", "unknown_evidence_kind")
        return mapping[kind]
