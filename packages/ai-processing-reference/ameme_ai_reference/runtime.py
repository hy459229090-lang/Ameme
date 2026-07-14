"""Scope-bound no-content runtime controls for the offline AI reference."""

from __future__ import annotations

from copy import deepcopy
from dataclasses import dataclass
import hashlib
import hmac
import secrets
from typing import Any, Iterable, Mapping

from .errors import ErrorCode, ProcessingError
from .model import PolicyContext, PromptEnvelope, canonical_json, stable_digest
from .observability import SafeObserver


SYNTHETIC_TEST_SCOPE_HMAC_KEY = b"ameme-ai-reference-synthetic-scope-key-v1"


@dataclass(frozen=True, slots=True)
class BatchTicket:
    batch_id: str
    scope_hash: str
    space_id: str
    task_id: str
    evidence_ids: tuple[str, ...]
    cache_key: str
    policy_generation: str
    revocation_generation: str


@dataclass(slots=True)
class _CacheEntry:
    scope_hash: str
    space_id: str
    evidence_ids: tuple[str, ...]
    policy_generation: str
    revocation_generation: str
    response: dict[str, Any]


class ScopedAIRuntime:
    """Reference-only budget, cache and batch controls bound to authorization scope."""

    def __init__(
        self,
        observer: SafeObserver,
        *,
        scope_hmac_key: bytes | None = None,
    ) -> None:
        if scope_hmac_key is None:
            scope_hmac_key = secrets.token_bytes(32)
        if len(scope_hmac_key) < 16:
            raise ValueError("scope_hmac_key must contain at least 16 bytes")
        self.observer = observer
        self._scope_hmac_key = bytes(scope_hmac_key)
        self._cache: dict[str, _CacheEntry] = {}
        self._batch: dict[str, dict[str, BatchTicket]] = {}
        self._budget_spent: dict[tuple[str, str], int] = {}
        self._deleted: set[tuple[str, str]] = set()
        self._revoked: set[tuple[str, str]] = set()

    def scope_hash(self, context: PolicyContext) -> str:
        payload = canonical_json(
            {"purpose": context.purpose, "space_id": context.space_id}
        ).encode("utf-8")
        return hmac.new(self._scope_hmac_key, payload, hashlib.sha256).hexdigest()

    def cache_key(self, envelope: PromptEnvelope, context: PolicyContext) -> str:
        return stable_digest(
            {
                "adapter_name": envelope.adapter_name,
                "fixture_id": envelope.synthetic_fixture_id,
                "input_hash": envelope.input_hash,
                "input_schema": envelope.input_schema,
                "output_schema": envelope.output_schema,
                "policy_generation": context.policy_generation,
                "policy_version": envelope.policy_version,
                "prompt_template_version": envelope.prompt_template_version,
                "revocation_generation": context.revocation_generation,
                "scope_hash": self.scope_hash(context),
                "task_id": envelope.task_id,
                "task_version": envelope.task_version,
            }
        )

    def apply_context_revocations(self, context: PolicyContext) -> None:
        if context.deleted_evidence_ids or context.revoked_evidence_ids:
            self.invalidate_evidence(
                context,
                deleted_ids=context.deleted_evidence_ids,
                revoked_ids=context.revoked_evidence_ids,
            )

    def ensure_evidence_allowed(
        self, context: PolicyContext, evidence_ids: Iterable[str]
    ) -> None:
        reason = self._forbidden_reason(context, evidence_ids)
        if reason is not None:
            raise ProcessingError(reason)

    def reserve_provider_budget(
        self, envelope: PromptEnvelope, context: PolicyContext
    ) -> bool:
        scope_hash = self.scope_hash(context)
        budget_scope = (scope_hash, context.policy_generation)
        spent = self._budget_spent.get(budget_scope, 0)
        if spent >= context.budget_remaining:
            self._record_runtime(
                envelope,
                context,
                event_kind="budget",
                budget_state="exhausted",
            )
            return False
        self._budget_spent[budget_scope] = spent + 1
        self._record_runtime(
            envelope,
            context,
            event_kind="budget",
            budget_state="consumed",
        )
        return True

    def cache_get(
        self, envelope: PromptEnvelope, context: PolicyContext
    ) -> Mapping[str, Any] | None:
        self.ensure_evidence_allowed(context, envelope.evidence_manifest)
        key = self.cache_key(envelope, context)
        entry = self._cache.get(key)
        if entry is None:
            self._record_runtime(
                envelope,
                context,
                event_kind="cache",
                cache_state="miss",
            )
            return None
        current_scope = self.scope_hash(context)
        valid = (
            entry.scope_hash == current_scope
            and entry.space_id == context.space_id
            and entry.policy_generation == context.policy_generation
            and entry.revocation_generation == context.revocation_generation
            and self._forbidden_reason(context, entry.evidence_ids) is None
        )
        if not valid:
            self._cache.pop(key, None)
            self._record_runtime(
                envelope,
                context,
                event_kind="cache",
                cache_state="invalidated",
            )
            return None
        self._record_runtime(
            envelope,
            context,
            event_kind="cache",
            cache_state="hit",
        )
        return deepcopy(entry.response)

    def cache_put(
        self,
        envelope: PromptEnvelope,
        context: PolicyContext,
        response: Mapping[str, Any],
    ) -> None:
        self.ensure_evidence_allowed(context, envelope.evidence_manifest)
        key = self.cache_key(envelope, context)
        self._cache[key] = _CacheEntry(
            scope_hash=self.scope_hash(context),
            space_id=context.space_id,
            evidence_ids=tuple(sorted(envelope.evidence_manifest)),
            policy_generation=context.policy_generation,
            revocation_generation=context.revocation_generation,
            response=deepcopy(dict(response)),
        )
        self._record_runtime(
            envelope,
            context,
            event_kind="cache",
            cache_state="stored",
        )

    def enqueue_batch(
        self, envelope: PromptEnvelope, context: PolicyContext
    ) -> BatchTicket:
        self.ensure_evidence_allowed(context, envelope.evidence_manifest)
        scope_hash = self.scope_hash(context)
        key = self.cache_key(envelope, context)
        evidence_ids = tuple(sorted(envelope.evidence_manifest))
        batch_seed = {"cache_key": key, "evidence_ids": evidence_ids}
        batch_id = f"batch_{stable_digest(batch_seed)[:24]}"
        ticket = BatchTicket(
            batch_id=batch_id,
            scope_hash=scope_hash,
            space_id=context.space_id,
            task_id=envelope.task_id,
            evidence_ids=evidence_ids,
            cache_key=key,
            policy_generation=context.policy_generation,
            revocation_generation=context.revocation_generation,
        )
        self._batch.setdefault(scope_hash, {})[batch_id] = ticket
        self._record_runtime(
            envelope,
            context,
            event_kind="batch",
            batch_state="queued",
            item_count=1,
        )
        return ticket

    def drain_batch(self, context: PolicyContext) -> tuple[BatchTicket, ...]:
        self.apply_context_revocations(context)
        scope_hash = self.scope_hash(context)
        queued = self._batch.pop(scope_hash, {})
        ready: list[BatchTicket] = []
        dropped = 0
        for ticket in sorted(queued.values(), key=lambda item: item.batch_id):
            valid = (
                ticket.scope_hash == scope_hash
                and ticket.space_id == context.space_id
                and ticket.policy_generation == context.policy_generation
                and ticket.revocation_generation == context.revocation_generation
                and self._forbidden_reason(context, ticket.evidence_ids) is None
            )
            if valid:
                ready.append(ticket)
            else:
                dropped += 1
        self.observer.record(
            event_kind="batch",
            batch_state="drained" if not dropped else "dropped_invalid",
            evidence_count=sum(len(item.evidence_ids) for item in ready),
            item_count=len(ready),
            scope_hash=scope_hash,
        )
        return tuple(ready)

    def invalidate_evidence(
        self,
        context: PolicyContext,
        *,
        deleted_ids: Iterable[str] = (),
        revoked_ids: Iterable[str] = (),
    ) -> None:
        deleted = {str(item) for item in deleted_ids}
        revoked = {str(item) for item in revoked_ids}
        scope_hash = self.scope_hash(context)
        self._deleted.update((context.space_id, item) for item in deleted)
        self._revoked.update((scope_hash, item) for item in revoked)

        removed_cache = 0
        for key, entry in list(self._cache.items()):
            entry_ids = set(entry.evidence_ids)
            deleted_match = (
                entry.space_id == context.space_id and bool(entry_ids & deleted)
            )
            revoked_match = (
                entry.scope_hash == scope_hash and bool(entry_ids & revoked)
            )
            if deleted_match or revoked_match:
                self._cache.pop(key, None)
                removed_cache += 1

        removed_batch = 0
        for queued_scope, queued in list(self._batch.items()):
            for batch_id, ticket in list(queued.items()):
                ticket_ids = set(ticket.evidence_ids)
                deleted_match = (
                    ticket.space_id == context.space_id and bool(ticket_ids & deleted)
                )
                revoked_match = (
                    ticket.scope_hash == scope_hash and bool(ticket_ids & revoked)
                )
                if deleted_match or revoked_match:
                    queued.pop(batch_id, None)
                    removed_batch += 1
            if not queued:
                self._batch.pop(queued_scope, None)

        if removed_cache or removed_batch:
            self.observer.record(
                event_kind="invalidation",
                cache_state="invalidated" if removed_cache else "unchanged",
                batch_state="invalidated" if removed_batch else "unchanged",
                evidence_count=len(deleted | revoked),
                item_count=removed_cache + removed_batch,
                scope_hash=scope_hash,
            )

    def record_route(
        self,
        envelope: PromptEnvelope,
        context: PolicyContext,
        *,
        route: str,
        result_code: str,
    ) -> None:
        self._record_runtime(
            envelope,
            context,
            event_kind="route",
            route=route,
            result_code=result_code,
        )

    def _forbidden_reason(
        self, context: PolicyContext, evidence_ids: Iterable[str]
    ) -> ErrorCode | None:
        ids = {str(item) for item in evidence_ids}
        if ids & set(context.deleted_evidence_ids) or any(
            (context.space_id, item) in self._deleted for item in ids
        ):
            return ErrorCode.DELETED_INPUT
        scope_hash = self.scope_hash(context)
        if ids & set(context.revoked_evidence_ids) or any(
            (scope_hash, item) in self._revoked for item in ids
        ):
            return ErrorCode.REVOKED_INPUT
        return None

    def _record_runtime(
        self,
        envelope: PromptEnvelope,
        context: PolicyContext,
        *,
        event_kind: str,
        result_code: str | None = None,
        route: str | None = None,
        budget_state: str | None = None,
        cache_state: str | None = None,
        batch_state: str | None = None,
        item_count: int | None = None,
    ) -> None:
        fields: dict[str, Any] = {
            "event_kind": event_kind,
            "task_id": envelope.task_id,
            "task_version": envelope.task_version,
            "evidence_count": len(envelope.evidence_manifest),
            "scope_hash": self.scope_hash(context),
        }
        optional = {
            "result_code": result_code,
            "route": route,
            "budget_state": budget_state,
            "cache_state": cache_state,
            "batch_state": batch_state,
            "item_count": item_count,
        }
        fields.update(
            {key: value for key, value in optional.items() if value is not None}
        )
        self.observer.record(**fields)
