import unittest
from service_check import error_delta


class ServiceCheckTests(unittest.TestCase):
    def test_interval_counts_do_not_alert_on_old_failures(self):
        self.assertEqual(error_delta({'rpc:500': 10, 'rpc:200': 100}, {'rpc:500': 10, 'rpc:200': 120}), (0, 20))
        self.assertEqual(error_delta({'rpc:500': 10}, {'rpc:500': 12, 'ready:503': 1, 'rpc:200': 20}), (3, 23))

    def test_service_restart_resets_baseline(self):
        self.assertEqual(error_delta({'rpc:500': 10, 'rpc:200': 100}, {'rpc:500': 1, 'rpc:200': 2}), (0, 0))
