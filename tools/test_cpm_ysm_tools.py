import json
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))
from cpm_ysm_render import world_transforms


class CpmYsmOfflineContractTest(unittest.TestCase):
    def read(self, name):
        return json.loads((ROOT / "build" / name).read_text(encoding="utf-8"))

    def test_modern_dump_contains_pose_and_mode(self):
        dump = self.read("yukari-fixed3.json")
        self.assertEqual(2, dump["transformMode"])
        self.assertTrue(dump.get("neutralPose"))
        # The pose is emitted by JavaCpmModernPoseSession.  Every returned
        # pose entry has the nine ScriptBone channels plus the visibility bit
        # consumed by the offline renderer's hierarchical hide contract.
        self.assertTrue(all(len(v) == 10 for v in dump["neutralPose"].values()))
        self.assertEqual(len(world_transforms(dump)), len(dump["bones"]))

    def test_modern_renderer_rejects_missing_neutral_pose(self):
        dump = self.read("yukari-fixed3.json")
        dump.pop("neutralPose", None)
        with self.assertRaises(ValueError):
            world_transforms(dump)

    def test_legacy_numeric_gate(self):
        result = self.read("marisa-fixed2-compare.json")
        self.assertEqual("legacy", result["format"])
        self.assertLessEqual(result["maxCornerError"], 2e-6)

    def test_atlas_and_mirror_gates(self):
        for name in ("yukari-fixed3-compare.json", "marisa-fixed2-compare.json"):
            result = self.read(name)
            self.assertEqual("pass", result["stageChecks"]["texture_sampling"])
            self.assertEqual("pass", result["stageChecks"]["mirror_winding_normals"])
            self.assertGreater(result["texture"]["pixels"], 0)
            self.assertEqual("pass", result["texture"]["uvMapping"]["status"])
            self.assertGreater(result["texture"]["uvMapping"]["checkedCorners"], 0)
            self.assertEqual(0, len(result["mirror"]["failures"]))

    def test_baked_uv_and_indices_are_finite(self):
        for name in ("yukari-fixed3.json", "marisa-fixed2.json"):
            dump = self.read(name)
            for bone in dump["bones"]:
                for cube in bone["cubes"]:
                    self.assertEqual(cube["faceCount"] * 4, len(cube["posIndices"]))
                    self.assertEqual(cube["faceCount"] * 4, len(cube["uvIndices"]))
                    self.assertTrue(all(0.0 <= float(v) <= 1.0 for v in cube["uv"]))


if __name__ == "__main__":
    unittest.main()
