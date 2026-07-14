"""Versioned task registry matching the accepted MVP AI task catalogue."""

from __future__ import annotations

from .errors import ErrorCode, ProcessingError
from .model import TaskSpec


class TaskRegistry:
    def __init__(self) -> None:
        self._tasks: dict[str, TaskSpec] = {}

    def register(self, spec: TaskSpec) -> None:
        if spec.task_id in self._tasks:
            raise ValueError(f"duplicate task id: {spec.task_id}")
        self._tasks[spec.task_id] = spec

    def require(self, task_id: str) -> TaskSpec:
        spec = self._tasks.get(task_id)
        if spec is None:
            raise ProcessingError(ErrorCode.TASK_NOT_REGISTERED)
        if not spec.enabled:
            raise ProcessingError(ErrorCode.TASK_DISABLED)
        return spec

    def snapshot(self) -> tuple[TaskSpec, ...]:
        return tuple(self._tasks[key] for key in sorted(self._tasks))


def default_registry() -> TaskRegistry:
    registry = TaskRegistry()
    rows = (
        ("T01_TIME_NORMALIZE", "TimeInput/v1", "TimeRange/v1", "R0", False, True),
        ("T02_HASH_DEDUP", "SourceDigestSet/v1", "DedupDecision/v1", "R0", False, True),
        ("T03_OCR", "SelectedImage/v1", "ObservationText/v1", "R1", True, True),
        ("T04_TRANSCRIBE", "SelectedAudio/v1", "Transcript/v1", "R1", True, True),
        ("T05_CLASSIFY_OBSERVATION", "Observation/v1", "Classification/v1", "R2", True, True),
        ("T06_EVENT_DRAFT", "ObservationBundle/v1", "EventCandidate/v1", "R2", True, True),
        ("T07_MERGE_CANDIDATES", "EventCandidateSet/v1", "MergeProposal/v1", "R0", True, True),
        ("T08_DESCRIPTION", "EventFacts/v1", "DescriptionProposal/v1", "R2", True, True),
        ("T09_DAY_SUMMARY", "DayLedgerRevision/v1", "Summary/v1", "R3", True, True),
        ("T10_QUERY_REWRITE", "RecallText/v1", "RecallQuery/v1", "R0", True, True),
        ("T11_RANK_CONTEXT", "ContextCandidates/v1", "RankedIds/v1", "R2", True, True),
        ("T12_LONG_TERM_MEMORY", "CrossDayHistory/v1", "Memory/v1", "disabled", False, False),
    )
    for task_id, input_schema, output_schema, tier, cloud_allowed, enabled in rows:
        registry.register(
            TaskSpec(
                task_id=task_id,
                task_version="1",
                input_schema=input_schema,
                output_schema=output_schema,
                default_tier=tier,
                cloud_allowed=cloud_allowed,
                enabled=enabled,
            )
        )
    return registry
