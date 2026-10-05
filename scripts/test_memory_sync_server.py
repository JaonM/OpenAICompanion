import tempfile
import unittest
from pathlib import Path

from memory_sync_server import RelayStore, CapacityError, MAX_BODY, PAGE_BYTES, encode, valid_record


class RelayStoreTest(unittest.TestCase):
    def record(self, index, content=None):
        return {"id": f"{index:032x}", "revision": 1, "author": "b" * 32,
                "memory": None if content is None else {
                    "tier": "long", "topic_id": None, "content": content,
                    "source": "user", "status": "active", "created_at": 1,
                    "updated_at": 1, "expires_at": None}}

    def pull(self, store):
        cursor, records = 0, []
        while True:
            result = store.exchange("alice", [], cursor)
            self.assertLessEqual(len(encode(result)), PAGE_BYTES)
            self.assertLessEqual(len(result["records"]), 256)
            records.extend(result["records"])
            if not result["has_more"]:
                return records, result["cursor"]
            self.assertGreater(result["cursor"], cursor)
            cursor = result["cursor"]

    def test_pages_more_than_ten_thousand_tombstones(self):
        with tempfile.TemporaryDirectory() as directory:
            store = RelayStore(str(Path(directory) / "relay.sqlite"))
            for start in range(0, 10001, 256):
                store.exchange("alice", [self.record(i) for i in range(start, min(start + 256, 10001))], 0)
            records, cursor = self.pull(store)
            self.assertEqual(len(records), 10001)
            self.assertEqual(len({r["id"] for r in records}), 10001)
            self.assertEqual(store.exchange("alice", [], cursor)["records"], [])
            self.assertEqual(store.exchange("bob", [], 0)["records"], [])

    def test_combined_snapshot_larger_than_four_mib_is_readable(self):
        with tempfile.TemporaryDirectory() as directory:
            store = RelayStore(str(Path(directory) / "relay.sqlite"))
            for start in range(0, 270, 15):
                store.exchange("alice", [self.record(i, "x" * 16000) for i in range(start, start + 15)], 0)
            records, cursor = self.pull(store)
            self.assertEqual(len(records), 270)
            self.assertGreater(len(encode({"records": records})), MAX_BODY)
            changed = dict(self.record(0, "updated"), revision=2)
            delta = store.exchange("alice", [changed], cursor)
            self.assertEqual(delta["records"], [changed])
            # Lost responses are safe to replay; they do not generate new sequence numbers.
            self.assertEqual(store.exchange("alice", [changed], delta["cursor"])["records"], [])

    def test_legacy_capacity_failure_rolls_back_before_commit(self):
        with tempfile.TemporaryDirectory() as directory:
            store = RelayStore(str(Path(directory) / "relay.sqlite"))
            store.exchange("alice", [self.record(i) for i in range(10000)])
            with self.assertRaises(CapacityError):
                store.exchange("alice", [self.record(10000)])
            self.assertEqual(len(store.exchange("alice", [])), 10000)
            # The rejected record can subsequently be written via the paged protocol.
            store.exchange("alice", [self.record(10000)], 0)
            self.assertEqual(len(self.pull(store)[0]), 10001)

    def test_utf8_limits_match_device_validation(self):
        self.assertFalse(valid_record(self.record(1, "中" * 6000)))
        self.assertFalse(valid_record(self.record(1, "   ")))
        self.assertTrue(valid_record(self.record(1, "中" * 5000)))

    def test_isolation_versions_and_tombstone(self):
        with tempfile.TemporaryDirectory() as directory:
            store = RelayStore(str(Path(directory) / "relay.sqlite"))
            record = {"id": "a" * 32, "revision": 1, "author": "b" * 32,
                      "memory": {"tier": "long", "topic_id": None, "content": "下班十八点",
                                 "source": "user", "status": "active", "created_at": 1,
                                 "updated_at": 1, "expires_at": None}}
            self.assertEqual(len(store.exchange("alice", [record])), 1)
            self.assertEqual(store.exchange("bob", []), [])
            stale = dict(record, revision=0)
            with self.assertRaises(ValueError):
                store.exchange("alice", [stale])
            deleted = dict(record, revision=2, memory=None)
            self.assertIsNone(store.exchange("alice", [deleted])[0]["memory"])
            self.assertIsNone(store.exchange("alice", [record])[0]["memory"])
            self.assertIsNone(store.exchange("alice", [dict(record, revision=3)])[0]["memory"])


if __name__ == "__main__":
    unittest.main()
