#!/usr/bin/env python3
"""CPU-only checks: the overlay guard never sends a UI action or launches Java."""
import importlib.util
from pathlib import Path
import subprocess
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("mc_launch", Path(__file__).with_name("launch.py"))
launch = importlib.util.module_from_spec(spec)
spec.loader.exec_module(launch)


class OverlayGateTest(unittest.TestCase):
    def check(self, results, clock, wait):
        with patch.object(launch.subprocess, "run", side_effect=results) as run, \
                patch.object(launch.time, "monotonic", side_effect=clock), \
                patch.object(launch.time, "sleep") as sleep:
            receipt = launch.check_overlays(wait)
        for call in run.call_args_list:
            self.assertEqual(call.args[0], [str(Path(launch.ROOT) / "tools/check-overlays.sh")])
        return receipt, sleep

    def test_clear_immediately(self):
        r, sleep = self.check([subprocess.CompletedProcess([], 0, "", "")], [0, 0.2], 120)
        self.assertEqual(r["exit"], 0)
        self.assertEqual(r["attempts"], 1)
        sleep.assert_not_called()

    def test_wait_then_clear(self):
        r, sleep = self.check([subprocess.CompletedProcess([], 1, "overlay", ""),
                               subprocess.CompletedProcess([], 0, "", "")], [0, 1, 7], 120)
        self.assertEqual(r["exit"], 0)
        self.assertEqual(r["attempts"], 2)
        sleep.assert_called_once_with(5.0)

    def test_timeout_keeps_blocked(self):
        r, sleep = self.check([subprocess.CompletedProcess([], 1, "overlay", "")], [0, 120], 120)
        self.assertNotEqual(r["exit"], 0)
        self.assertEqual(r["output"], "overlay")
        sleep.assert_not_called()

    def test_probe_failure_not_clear(self):
        for failure in (OSError("unavailable"), subprocess.TimeoutExpired("probe", 15)):
            r, _ = self.check([failure], [0, 15], 10)
            self.assertNotEqual(r["exit"], 0)
            self.assertTrue(r["error"])

    def test_zero_wait_retains_single_check(self):
        r, sleep = self.check([subprocess.CompletedProcess([], 1, "overlay", "")], [0, 0], 0)
        self.assertEqual(r["exit"], 1)
        sleep.assert_not_called()


if __name__ == "__main__":
    unittest.main()
