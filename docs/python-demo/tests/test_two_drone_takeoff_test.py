"""双机测试编排回归测试；完全模拟网络，不操作飞机。"""
import sys
from pathlib import Path
import threading
import tempfile
import time
import unittest
from concurrent.futures import ThreadPoolExecutor
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import demo_23_two_drone_takeoff_test as demo


class TakeoffTest(unittest.TestCase):
    def plan(self, number):
        return demo.FlightPlan(demo.AircraftIdentity(number, f"rc{number}", f"uav{number}"),
                               30, 120, 2, 1)

    def test_two_requests_overlap_and_are_sent_once(self):
        in_request = threading.Barrier(2)
        def api(*args, **kwargs):
            in_request.wait(timeout=2)
            return {"code": 0}
        states = {"kind": "takeoff", "flight_id": "task", "status": "wayline_ok", "result": 0}
        records = [{}, {}]
        start = threading.Barrier(2)
        with patch.object(demo.common, "api_call", side_effect=api) as call, \
                patch.object(demo, "wait_for_new_state", return_value=states):
            with ThreadPoolExecutor(max_workers=2) as pool:
                jobs = [pool.submit(demo.dispatch, "token", self.plan(i + 1), 0,
                                    start, 1, records[i]) for i in range(2)]
                for job in jobs:
                    job.result(timeout=5)
        self.assertEqual(call.call_count, 2)
        self.assertEqual(len({c.args[2] for c in call.call_args_list}), 2)
        self.assertTrue(all(r["result"] == "device_reported_success" for r in records))

    def test_timeout_never_replays_post(self):
        record = {}
        with patch.object(demo.common, "api_call", side_effect=demo.common.DemoApiError(
                "takeoff", "timeout", ambiguous=True)) as call, \
                patch.object(demo, "wait_for_new_state", return_value=None):
            demo.dispatch("token", self.plan(1), 0, threading.Barrier(1), 1, record)
        self.assertEqual(call.call_count, 1)
        self.assertEqual(record["result"], "unknown")

    def test_idle_cleanup_is_not_takeoff_success(self):
        record = {}
        state = {"kind": "takeoff", "flight_id": "task", "status": "task_finish", "result": 0}
        with patch.object(demo.common, "api_call", return_value={"code": 0}), \
                patch.object(demo, "wait_for_new_state", return_value=state):
            demo.dispatch("token", self.plan(1), 0, threading.Barrier(1), 1, record)
        self.assertEqual(record["result"], "ended_unverified")

    def test_observation_timeout_preserves_latest_matching_state(self):
        initial = {"kind": "takeoff", "flight_id": "task", "status": "command_accepted"}
        latest = {**initial, "status": "wayline_progress"}
        record = {}
        with patch.object(demo.common, "api_call", return_value={"code": 0}), \
                patch.object(demo, "wait_for_new_state", return_value=initial), \
                patch.object(demo, "wait_for_terminal", return_value=None), \
                patch.object(demo.common, "get_point_flight_state", return_value=latest):
            demo.dispatch("token", self.plan(1), 0, threading.Barrier(1), 1, record)
        self.assertEqual(record["result"], "unknown")
        self.assertEqual(record["state"], latest)

    def test_default_preflight_does_not_seize_or_dispatch(self):
        identities = [self.plan(1).identity, self.plan(2).identity]
        with tempfile.TemporaryDirectory() as directory, \
                patch.object(sys, "argv", ["demo", "--props-removed", "--report",
                                            str(Path(directory) / "result.json")]), \
                patch.object(demo.common, "login", return_value="token"), \
                patch.object(demo, "discover", return_value=identities), \
                patch.object(demo, "verify_online_topology"), \
                patch.object(demo, "read_baselines"), \
                patch.object(demo, "read_osd", return_value={}), \
                patch.object(demo, "make_plans", return_value=[self.plan(1), self.plan(2)]), \
                patch.object(demo, "seize_all") as seize, \
                patch.object(demo, "dispatch") as dispatch:
            self.assertEqual(demo.main(), 0)
        seize.assert_not_called()
        dispatch.assert_not_called()

    def test_stale_airborne_or_invalid_position_blocks_plans(self):
        identity = self.plan(1).identity
        good = {"received_monotonic": time.monotonic(), "mode_code": 0,
                "latitude": 30, "longitude": 120}
        for bad in ({"received_monotonic": time.monotonic() - 6},
                    {"mode_code": 3}, {"latitude": None}, {"latitude": float("nan")}):
            with self.subTest(bad=bad), self.assertRaises(demo.common.DemoError):
                demo.make_plans([identity], {identity.aircraft_sn: {**good, **bad}}, 2)

    def test_more_than_two_online_devices_is_not_arbitrary_selection(self):
        devices = [{"status": True, "children": {"status": True}}] * 3
        with patch.object(demo.common, "fetch_gateway_devices", return_value=devices), \
                self.assertRaises(demo.common.DemoError):
            demo.discover("token")


if __name__ == "__main__":
    unittest.main()
