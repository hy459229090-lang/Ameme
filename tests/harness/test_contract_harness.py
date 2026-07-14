from __future__ import annotations

import unittest
from datetime import datetime, timedelta, timezone

from contract_harness import DeterministicIdFactory, FaultInjector, FixedClock, InjectedFault


class DeterministicHarnessTests(unittest.TestCase):
    def test_fixed_clock_is_repeatable_and_only_moves_forward_explicitly(self) -> None:
        start = datetime(2026, 7, 14, 9, 30, tzinfo=timezone(timedelta(hours=8)))
        clock = FixedClock(start)
        self.assertEqual(start, clock.now())
        self.assertEqual(start, clock.now())
        self.assertEqual(start + timedelta(seconds=5), clock.advance(timedelta(seconds=5)))
        with self.assertRaises(ValueError):
            clock.advance(timedelta(microseconds=-1))

    def test_fixed_clock_rejects_ambiguous_naive_time(self) -> None:
        with self.assertRaises(ValueError):
            FixedClock(datetime(2026, 7, 14, 9, 30))

    def test_deterministic_ids_repeat_across_factory_instances(self) -> None:
        first = DeterministicIdFactory("contract")
        second = DeterministicIdFactory("contract")
        sequence_one = [first.next("evt"), first.next("evt"), first.next("src")]
        sequence_two = [second.next("evt"), second.next("evt"), second.next("src")]
        self.assertEqual(sequence_one, sequence_two)
        self.assertEqual(["evt_contract_0001", "evt_contract_0002", "src_contract_0001"], sequence_one)

    def test_fault_injection_occurs_exactly_as_scheduled(self) -> None:
        faults = FaultInjector()
        faults.fail_next("store.commit", "DISK_FULL", times=2)
        for _ in range(2):
            with self.assertRaises(InjectedFault) as raised:
                faults.checkpoint("store.commit")
            self.assertEqual("DISK_FULL", raised.exception.code)
        faults.checkpoint("store.commit")
        self.assertEqual(3, faults.call_count("store.commit"))
        self.assertEqual(0, faults.pending_count("store.commit"))


if __name__ == "__main__":
    unittest.main()
