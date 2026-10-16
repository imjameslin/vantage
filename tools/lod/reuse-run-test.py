#!/usr/bin/env python3
"""Lightweight reuse sidecar tests: no JVM, game, GPU, build, lock or file writes.

Run: python3 -B tools/lod/reuse-run-test.py
Only existing Python/Java source and options text are read. Runtime guards and
owned-child cleanup are tested with mocks; actual GPU/perceptual work is pending.
"""

import ast
import contextlib
import copy
import importlib.util
import io
import json
import math
import os
from pathlib import Path
import signal
import subprocess
import sys
import unittest
from unittest.mock import Mock, mock_open, patch

sys.dont_write_bytecode = True
HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("reuse_run", HERE / "reuse-run.py")
reuse = importlib.util.module_from_spec(spec)
spec.loader.exec_module(reuse)


def args(mode="spot", *extra):
    fixture = [] if "--game-dir" in extra else ["--world-snapshot", str(reuse.ROOT / "run/pinned/reuse-fixture")]
    return reuse.parser().parse_args([
        str(reuse.ROOT / "build/client-reuse-frozen.jar"),
        str(reuse.ROOT / "build/bench-reuse-frozen.jar"),
        "--label", "unit-test", "--mode", mode,
        *fixture, *extra])


def good_result(mode="spot", shared=False, feature="reuse", reset=False, profile=False):
    """Current Bench/Abab schema, synthetic values only (never measured FPS)."""
    extra = ["--game-dir", str(reuse.ROOT / "run/s3-palette-check"), "--world", "bench"] if shared or reset else []
    if reset:
        extra += ["--reset-world", str(reuse.ROOT / "run/pinned/reuse-fixture")]
    if profile:
        extra += ["--item-build-profile"]
    plan = reuse.build_plan(args(mode, *extra, "--feature", feature))
    pinned = bool(plan["snapshot"])
    props = plan["benchProperties"]
    inputs = dict(jars={r: dict(path=plan[r], sha256="fake256-" + r, sha1="fake1-" + r, size=1)
                        for r in ("client", "bench")}, fixture=plan["fixture"],
                  snapshotId="synthetic-id" if pinned else None, snapshotManifestSha256="fake" if pinned else None)
    display = dict(default=True, pixels="3024x1964", points="1512x982", hz=120)
    guards = dict(inputs=inputs, exclusiveLock=True, prismClear=True, command=plan["command"],
                  displayBefore=display, displayAfter=display)
    state = dict(idle=True, tau=2, selected=100, gpu=dict(gpuMB=3000))
    data = dict(label=plan["label"], mode="abab", backend="Metal", framebuffer="3024x1964",
                renderDistance=plan["renderDistance"], lodSettleSeconds=30,
                lod=dict(state=state), launch=dict(display=display, quietGate=dict(ok=True), overlays=False),
                # Deliberately absurd pooled-both-arms values: never use these.
                medianFps=99999, p1LowFps=88888)
    pinned_property = {"bench.pinnedWorld": os.path.relpath(plan["snapshot"], reuse.ROOT) + "@synthetic-id"} if pinned else {}
    data["config"] = dict(properties={**plan["requestedProperties"], **{"bench." + k: v for k, v in props.items()}, **pinned_property},
        jars={r: dict(sha1=inputs["jars"][r]["sha1"]) for r in ("client", "bench")},
        env=reuse.ENV.copy(), native=dict(validation=0, profilePasses=0),
        game=dict(enableVsync="false", framerateLimit="260", ambientOcclusion="true", entityShadows="true",
                  cutoutLeaves="true", mipmapLevels="4", cloudStatus="FANCY", fov="70"),
        toggles=dict(Lod=dict(BUILD=True, draw=True, layerMask=7, RADIUS=512, TAU=2,
                             GPU_BYTES=6144 << 20, secOccl=True, area=False)))
    data["abab"] = dict(preset=props["abab.preset"], spinSeconds=10, order="abba", segments=int(props["abab.segments"]),
        bins=int(props["abab.bins"]), blockSeconds=int(props["abab.blockSeconds"]), minCell=20, discardFrames=int(props["abab.discard"]),
        blocksRun=12, blocksPlanned=12, dropped=0, stop=dict(enabled=False), switches=12,
        toggles=[dict(field=plan["toggleField"], a="false", b="true", readBackA="false", readBackB="true", type="boolean")],
        arms={arm: dict(frames=100, medianFps=500 * factor, median2Fps=480 * factor,
                        avgFps=470 * factor, p1LowFps=200 * factor) for arm, factor in (("A", 1), ("B", 2))},
        effects={metric: dict(logRatio=math.log(0.5), ci=[math.log(0.4), math.log(0.6)], holmP=0.01, call=True, pairs=6)
                 for metric in ("median2", "pairP99")},
        framesRecorded=2, frames=dict(dtNs=[2_000_000, 1_000_000], gpuBusyNs=[-1, -1], bin=[0, 0], block=[0, 1], keep=[1, 1]))
    if profile:
        def snap(n):
            return {"enabled": True, "modes": [{"mode": m, "completed": {"attempts": n if m == 3 else 0}} for m in range(4)]}
        state["gpu"]["itemBuildProfile"] = snap(12)
        data["abab"]["lodCounters"] = {"snapshots": [
            {"lod": {"state": {"gpu": {"itemBuildProfile": snap(n)}}}} for n in (10, 12)]}
    return data, plan, inputs, guards, copy.deepcopy(state)


def good_node_result(mode="flying", active=True):
    fixture = good_result(mode, feature="nodes", shared=True)
    data = fixture[0]
    gpu = dict(itemCache=dict(enabled=True), groupCache=dict(enabled=True, diagnostics=False),
               nodeItems=dict(enabled=False, buildsByMode=[0, 0], buildNsByMode=[0, 0],
                              packetHits=100, replayedClusters=1000, budgetBytes=64 << 20))
    def snapshot(index, arm):
        return dict(boundary="measurement-start" if index < 0 else "block-end", completedBlock=index,
                    activeArm=arm, intervalArm=None if index < 0 else arm,
                    toggleReadbacks=[dict(field=reuse.NODES_FIELD, value="true" if arm == "B" else "false")],
                    lod=dict(state=dict(gpu=copy.deepcopy(gpu))))
    snapshots = [snapshot(-1, "A")]
    blocks = []
    for index, arm in enumerate("ABBA" * 3):
        mode = 0 if arm == "A" else 1
        node = gpu["nodeItems"]
        node["enabled"] = bool(mode)
        if active:
            node["buildsByMode"][mode] += 2
            node["buildNsByMode"][mode] += 20_000
            node["packetHits"] += mode * 5
            node["replayedClusters"] += mode * 50
        blocks.append(dict(index=index, arm=arm))
        snapshots.append(snapshot(index, arm))
    data["abab"]["blocks"] = blocks
    data["abab"]["lodCounters"] = dict(snapshots=snapshots)
    return fixture


def good_prepare_result(mode="flying", measured=True, warmup=True):
    fixture = good_result(mode, feature="prepare", shared=True)
    data, _, _, _, initial = fixture
    packing = dict(enabled=False, active=False, pending=False, failed=False, error="",
                   **{key: 1000 if warmup else 0 for key in reuse.PREPARE_COUNTERS})
    state = dict(meshHandoff=dict(enabled=True), gpu=dict(asyncItems=packing,
                 itemCache=dict(enabled=True), groupCache=dict(enabled=True, diagnostics=False),
                 nodeItems=dict(enabled=False), reuse=dict(enabled=False), asyncConsumer=None))
    initial.update(copy.deepcopy(state))
    initial["gpu"]["gpuMB"] = 3000
    def snapshot(index, arm):
        return dict(boundary="measurement-start" if index < 0 else "block-end", completedBlock=index,
                    frameIndex=(index + 1) * 100, activeArm=arm, intervalArm=None if index < 0 else arm,
                    toggleReadbacks=[dict(field=reuse.PREPARE_FIELD, value="true" if arm == "B" else "false")],
                    lod=dict(state=copy.deepcopy(state)))
    ab = data["abab"]
    snapshots = [snapshot(-1, "A")]
    ab["blocks"] = []
    for index, arm in enumerate("ABBA" * 3):
        if packing["pending"] and arm == "A":
            packing["uploadDefers"] += 1  # upload happens before the OFF frame clears pending work
        packing.update(enabled=arm == "B", active=arm == "B", pending=arm == "B" and measured)
        if measured and arm == "B":
            for key, delta in dict(offers=3, accepted=2, rejected=1, bootstrap=1, fallbacks=1,
                                   uploadDefers=4, captureNs=20, installNs=30, workerNs=40,
                                   acceptedClusters=50).items():
                packing[key] += delta
        ab["blocks"].append(dict(index=index, arm=arm, startFrame=index * 100, frames=100))
        snapshots.append(snapshot(index, arm))
    ab["lodCounters"] = dict(snapshots=snapshots)
    ab["framesRecorded"] = 1200
    ab["frames"] = dict(dtNs=[10_000_000] * 1200, gpuBusyNs=[-1] * 1200, bin=[0] * 1200,
                        block=[i // 100 for i in range(1200)], keep=[int(i % 100 >= 30) for i in range(1200)])
    data["lod"]["state"].update(copy.deepcopy(state))
    data["lod"]["state"]["gpu"]["gpuMB"] = 3000
    data["config"]["toggles"]["LodGpu"] = dict(cacheItems=True, cacheGroups=True, cacheNodeItems=False,
                                              asyncItems=False, GROUP_LOOKUP_DIAGNOSTICS=False)
    return fixture


class Prepare(unittest.TestCase):
    @staticmethod
    def snapshots(fixture):
        return fixture[0]["abab"]["lodCounters"]["snapshots"]

    def test_plan_isolates_default_off_packing_and_preserves_item_rules(self):
        self.assertEqual(args().feature, "reuse")
        for mode in ("spot", "vista", "spin", "flying"):
            with self.subTest(mode=mode):
                plan = reuse.build_plan(args(mode, "--feature", "prepare"))
                items = reuse.build_plan(args(mode, "--feature", "items"))
                props, runtime = plan["benchProperties"], plan["requestedProperties"]
                self.assertEqual(plan["toggleField"], reuse.PREPARE_FIELD)
                self.assertEqual(props["abab.toggles"], reuse.PREPARE_FIELD + "=false|true")
                self.assertEqual({k: v for k, v in props.items() if k != "abab.toggles"},
                                 {k: v for k, v in items["benchProperties"].items() if k != "abab.toggles"})
                for key in ("cacheItems", "cacheGroups", "atomicMeshHandoff"):
                    self.assertEqual(runtime["mc.lod." + key], "true")
                for key in ("asyncItems", "cacheNodeItems", "async", "reuse", "cacheGroups.diagnostics"):
                    self.assertEqual(runtime["mc.lod." + key], "false")
                for key in ("mc.lod.cacheNodeItemsMB", "mc.lod.itemBuildProfile"):
                    self.assertNotIn(key, runtime)
                self.assertEqual({k: runtime[k] for k in reuse.JVM}, reuse.JVM)
                self.assertEqual(plan["options"], items["options"])
                self.assertEqual(plan["environment"], reuse.ENV)
                self.assertEqual(plan["displayRequired"], items["displayRequired"])
                self.assertEqual(props["abab.blocks"], "12")
                self.assertEqual(props["abab.order"], "abba")
                self.assertEqual(props["abab.discard"], "30")
                self.assertEqual(props["abab.warmupSwitch"], "5")
                self.assertEqual(props["abab.lodCounters"], "true")
                self.assertFalse(plan["releaseEligible"])
                self.assertEqual(reuse.build_plan(reuse.parser().parse_args(reuse.worker_command(plan)[3:])), plan)

    def test_profile_and_nondefault_node_cap_stay_with_existing_features(self):
        for extra, message in ((("--item-build-profile",), "profiling requires"),
                               (("--node-cache-mb", "128"), "capacity requires feature nodes")):
            with self.assertRaisesRegex(ValueError, message):
                reuse.build_plan(args("flying", "--feature", "prepare", *extra))
        plan = reuse.build_plan(args("flying", "--feature", "prepare", "--node-cache-mb", "64"))
        self.assertNotIn("mc.lod.cacheNodeItemsMB", plan["requestedProperties"])

    def test_positive_measured_activity_and_diagnostics(self):
        for mode in ("spot", "vista", "spin", "flying"):
            fixture = good_prepare_result(mode)
            self.assertEqual(reuse.validate_result(*fixture), [])
        activity = reuse.prepare_activity(fixture[0])
        self.assertEqual(activity["byArm"]["B"]["acceptedClusters"], 300)
        self.assertEqual(activity["byArm"]["B"]["workerNs"], 240)
        self.assertEqual(activity["byArm"]["A"]["uploadDefers"], 3)
        self.assertEqual(activity["diagnosticDifferenceBMinusA"], dict(rejected=6, fallbacks=6, uploadDefers=21))
        self.assertEqual(activity["blocks"][1]["accepted"], 2)
        self.assertTrue(activity["includesDiscardedFrames"] and activity["warmupExcluded"])
        self.assertFalse(activity["performanceApproved"] or activity["imageApproved"])

    def test_flying_requires_each_measured_counter_not_warmup(self):
        message = "flying prepare comparison did not exercise measured ON worker acceptance of nonempty clusters"
        for warmup in (False, True):
            fixture = good_prepare_result(measured=False, warmup=warmup)
            self.assertIn(message, reuse.validate_result(*fixture))
        for key in ("offers", "accepted", "acceptedClusters", "workerNs"):
            fixture = good_prepare_result()
            for snap in self.snapshots(fixture):
                snap["lod"]["state"]["gpu"]["asyncItems"][key] = 1000
            self.assertIn(message, reuse.validate_result(*fixture), key)

    def test_idle_views_allow_no_worker_activity_but_require_arm_state(self):
        for mode in ("spot", "vista", "spin"):
            for warmup in (False, True):
                fixture = good_prepare_result(mode, measured=False, warmup=warmup)
                self.assertEqual(reuse.validate_result(*fixture), [])
            for index, key, value in ((1, "active", True), (1, "pending", True),
                                      (2, "active", False), (2, "enabled", False), (1, "enabled", True)):
                bad = copy.deepcopy(fixture)
                self.snapshots(bad)[index]["lod"]["state"]["gpu"]["asyncItems"][key] = value
                self.assertTrue(reuse.validate_result(*bad), (mode, index, key))

    def test_start_may_retain_warmup_active_and_pending_until_first_off_frame(self):
        fixture = good_prepare_result()
        packing = self.snapshots(fixture)[0]["lod"]["state"]["gpu"]["asyncItems"]
        packing.update(active=True, pending=True)
        self.assertEqual(reuse.validate_result(*fixture), [])
        packing["active"] = False
        self.assertIn("prepare active/pending state does not match rendered arm", reuse.validate_result(*fixture))

    def test_all_snapshot_fields_are_required_with_strict_types(self):
        fields = ("enabled", "active", "pending", "failed", "error", *reuse.PREPARE_COUNTERS)
        for key in fields:
            for index in (0, 2, 12):
                fixture = good_prepare_result()
                del self.snapshots(fixture)[index]["lod"]["state"]["gpu"]["asyncItems"][key]
                self.assertIn("prepare counters malformed or missing", reuse.validate_result(*fixture), (index, key))
        for key, value in (("offers", True), ("workerNs", 1.5), ("acceptedClusters", -1),
                           ("pending", 0), ("active", "true"), ("error", None)):
            fixture = good_prepare_result()
            self.snapshots(fixture)[2]["lod"]["state"]["gpu"]["asyncItems"][key] = value
            self.assertIn("prepare counters malformed or missing", reuse.validate_result(*fixture), key)

    def test_missing_snapshots_and_counters_rejected(self):
        for key in ("lodCounters", "blocks"):
            fixture = good_prepare_result()
            del fixture[0]["abab"][key]
            self.assertIn("prepare boundary counters missing", reuse.validate_result(*fixture))
        fixture = good_prepare_result()
        del self.snapshots(fixture)[3]["lod"]["state"]["gpu"]["asyncItems"]
        self.assertIn("prepare counters malformed or missing", reuse.validate_result(*fixture))

    def test_failed_or_sticky_error_rejected_at_every_boundary_and_initial_final(self):
        for index in range(13):
            for key, value in (("failed", True), ("error", "packing capacity")):
                fixture = good_prepare_result()
                self.snapshots(fixture)[index]["lod"]["state"]["gpu"]["asyncItems"][key] = value
                self.assertIn("prepare worker failed or reported an error", reuse.validate_result(*fixture))
        for which in ("initial", "final"):
            fixture = good_prepare_result()
            state = fixture[4] if which == "initial" else fixture[0]["lod"]["state"]
            state["gpu"]["asyncItems"]["failed"] = True
            self.assertIn(which + " prepare worker failed or reported an error", reuse.validate_result(*fixture))

    def test_held_flags_tampering_rejected_in_properties_config_and_snapshots(self):
        for key, value in (("cacheItems", "false"), ("cacheGroups", "false"), ("cacheNodeItems", "true"),
                           ("atomicMeshHandoff", "false"), ("async", "true"), ("reuse", "true")):
            fixture = good_prepare_result()
            fixture[0]["config"]["properties"]["mc.lod." + key] = value
            self.assertIn("actual JVM/bench properties differ or are missing", reuse.validate_result(*fixture))
        for key in ("cacheItems", "cacheGroups", "cacheNodeItems", "GROUP_LOOKUP_DIAGNOSTICS", "asyncItems"):
            fixture = good_prepare_result()
            flags = fixture[0]["config"]["toggles"]["LodGpu"]
            flags[key] = not flags[key]
            self.assertIn("effective prepare LodGpu fields not verified", reuse.validate_result(*fixture))
        for index in range(13):
            for path, value in ((("gpu", "itemCache", "enabled"), False),
                                (("gpu", "groupCache", "enabled"), False),
                                (("gpu", "groupCache", "diagnostics"), True),
                                (("gpu", "nodeItems", "enabled"), True),
                                (("meshHandoff", "enabled"), False),
                                (("gpu", "reuse", "enabled"), True), (("gpu", "asyncConsumer"), {})):
                fixture = good_prepare_result()
                state = self.snapshots(fixture)[index]["lod"]["state"]
                for key in path[:-1]:
                    state = state[key]
                state[path[-1]] = value
                self.assertTrue(reuse.validate_result(*fixture), (index, path))

    def test_boundary_identity_readbacks_and_actual_frames_required(self):
        for key, value in (("completedBlock", 7), ("activeArm", "A"), ("intervalArm", "A"),
                           ("boundary", "measurement-start"), ("toggleReadbacks", []), ("frameIndex", 100)):
            fixture = good_prepare_result()
            self.snapshots(fixture)[2][key] = value
            self.assertIn("prepare boundary identity or toggle readback does not match its arm", reuse.validate_result(*fixture))
        for index in range(13):
            fixture = good_prepare_result()
            self.snapshots(fixture)[index]["toggleReadbacks"][0]["value"] = "tampered"
            self.assertTrue(reuse.validate_result(*fixture))
        for key, value in (("frames", 0), ("startFrame", 1), ("index", 0), ("arm", "A")):
            fixture = good_prepare_result()
            fixture[0]["abab"]["blocks"][1][key] = value
            self.assertTrue(reuse.validate_result(*fixture))
        fixture = good_prepare_result()
        fixture[0]["abab"]["blocks"] = fixture[0]["abab"]["blocks"][:2]
        fixture[0]["abab"]["lodCounters"]["snapshots"] = self.snapshots(fixture)[:3]
        self.assertIn("prepare boundary counters missing", reuse.validate_result(*fixture))

    def test_decreased_and_off_arm_worker_counters_rejected(self):
        for key in reuse.PREPARE_COUNTERS:
            fixture = good_prepare_result()
            self.snapshots(fixture)[-1]["lod"]["state"]["gpu"]["asyncItems"][key] = 0
            self.assertIn("prepare counters decreased", reuse.validate_result(*fixture), key)
        for key in reuse.PREPARE_COUNTERS:
            if key == "uploadDefers":
                continue
            fixture = good_prepare_result()
            for snapshot in self.snapshots(fixture)[1:]:
                snapshot["lod"]["state"]["gpu"]["asyncItems"][key] += 1
            self.assertIn("prepare worker activity occurred in the OFF arm", reuse.validate_result(*fixture), key)

    def test_receipt_reports_deltas_without_automatic_performance_or_image_approval(self):
        for measured in (True, False):
            data, plan, inputs, guards, initial = good_prepare_result(measured=measured)
            guards["initialLodState"] = initial
            child = Mock(pid=12345, returncode=0)
            child.poll.return_value = 0
            def digest(path):
                return next((row["sha256"] for row in inputs["jars"].values() if row["path"] == path), "result-hash")
            with patch.object(Path, "exists", return_value=False), patch.object(Path, "mkdir"), \
                    patch.object(Path, "open", mock_open()), patch.object(reuse, "prism_clear"), \
                    patch.object(reuse, "read_json", side_effect=[guards, data]), \
                    patch.object(reuse, "digest", side_effect=digest), patch.object(reuse, "write_json") as write, \
                    patch.object(reuse.subprocess, "Popen", return_value=child), \
                    patch.object(reuse, "stop_owned_group"), contextlib.redirect_stdout(io.StringIO()) as output:
                self.assertEqual(reuse.run(plan), 0 if measured else 1)
            receipt = write.call_args.args[1]
            self.assertEqual(receipt["guardsValid"], measured)
            self.assertEqual(receipt["timingEvidenceValid"], measured)
            self.assertFalse(receipt["performanceEvidence"] or receipt["releaseEligible"])
            self.assertFalse(receipt["prepareActivity"]["imageApproved"])
            self.assertEqual(json.loads(output.getvalue())["prepareActivity"], receipt["prepareActivity"])
            self.assertEqual(receipt["prepareActivity"]["byArm"]["B"]["accepted"], 12 if measured else 0)









class Plans(unittest.TestCase):

    def test_node_capacity_is_held_and_survives_worker(self):
        plan = reuse.build_plan(args("flying", "--feature", "nodes", "--node-cache-mb", "128"))
        self.assertEqual(plan["requestedProperties"]["mc.lod.cacheNodeItemsMB"], "128")
        self.assertEqual(plan["requestedProperties"]["mc.lod.gpuMB"], "6144")
        self.assertEqual(reuse.build_plan(reuse.parser().parse_args(reuse.worker_command(plan)[3:])), plan)
        with self.assertRaisesRegex(ValueError, "capacity requires feature nodes"):
            reuse.build_plan(args("flying", "--feature", "groups", "--node-cache-mb", "128"))

    def test_node_capacity_requires_runtime_counter(self):
        fixture = good_node_result()
        fixture[0]["abab"]["lodCounters"]["snapshots"][1]["lod"]["state"]["gpu"]["nodeItems"]["budgetBytes"] = 128 << 20
        self.assertIn("node packet CPU budget differs from requested capacity", reuse.validate_result(*fixture))

    def test_node_packet_plan_isolates_one_toggle(self):
        for mode in ("spot", "vista", "spin", "flying"):
            plan = reuse.build_plan(args(mode, "--feature", "nodes"))
            runtime = plan["requestedProperties"]
            self.assertEqual(plan["toggleField"], reuse.NODES_FIELD)
            for key in ("mc.lod.cacheItems", "mc.lod.cacheGroups"):
                self.assertEqual(runtime[key], "true")
            for key in ("mc.lod.cacheNodeItems", "mc.lod.async", "mc.lod.reuse", "mc.lod.cacheGroups.diagnostics"):
                self.assertEqual(runtime[key], "false")
            self.assertEqual(reuse.build_plan(reuse.parser().parse_args(reuse.worker_command(plan)[3:])), plan)
            self.assertEqual(reuse.validate_result(*good_node_result(mode)), [])

    def test_node_packets_require_measured_replay_not_warmup(self):
        for mode in ("vista", "spin"):
            self.assertEqual(reuse.validate_result(*good_node_result(mode, active=False)), [])
        fixture = good_node_result(active=False)
        self.assertIn("flying node comparison did not exercise both builds and nonempty ON packet replay",
                      reuse.validate_result(*fixture))
        active = good_node_result()
        snaps = active[0]["abab"]["lodCounters"]["snapshots"]
        for s in snaps:
            s["lod"]["state"]["gpu"]["nodeItems"]["replayedClusters"] = 1000
        self.assertTrue(reuse.validate_result(*active))

    def test_node_packets_reject_wrong_mode_and_held_flags(self):
        for field in ("packetHits", "replayedClusters"):
            fixture = good_node_result()
            fixture[0]["abab"]["lodCounters"]["snapshots"][1]["lod"]["state"]["gpu"]["nodeItems"][field] += 1
            self.assertIn("node packet activity occurred in the wrong arm", reuse.validate_result(*fixture))
        for key in ("itemCache", "groupCache"):
            fixture = good_node_result()
            fixture[0]["abab"]["lodCounters"]["snapshots"][2]["lod"]["state"]["gpu"][key]["enabled"] = False
            self.assertTrue(reuse.validate_result(*fixture))
        fixture = good_node_result()
        del fixture[0]["abab"]["lodCounters"]["snapshots"][3]["lod"]["state"]["gpu"]["nodeItems"]
        self.assertIn("node packet counters malformed or missing", reuse.validate_result(*fixture))
        fixture = good_node_result()
        fixture[0]["abab"]["lodCounters"]["snapshots"][-1]["lod"]["state"]["gpu"]["nodeItems"]["buildNsByMode"] = [0, 0]
        self.assertIn("node packet counters decreased", reuse.validate_result(*fixture))

    def test_node_packets_reject_truncation_and_false_boundary_labels(self):
        fixture = good_node_result()
        ab = fixture[0]["abab"]
        ab["blocks"] = ab["blocks"][:2]
        ab["lodCounters"]["snapshots"] = ab["lodCounters"]["snapshots"][:3]
        self.assertIn("node packet boundary counters missing", reuse.validate_result(*fixture))
        for key, value in (("completedBlock", 7), ("intervalArm", "A"), ("activeArm", "A"),
                           ("boundary", "measurement-start"), ("toggleReadbacks", [])):
            fixture = good_node_result("vista", active=False)
            fixture[0]["abab"]["lodCounters"]["snapshots"][2][key] = value
            self.assertTrue(reuse.validate_result(*fixture), (key, value))
        fixture = good_node_result("spin", active=False)
        fixture[0]["abab"]["lodCounters"]["snapshots"][2]["lod"]["state"]["gpu"]["nodeItems"]["enabled"] = False
        self.assertIn("node packet boundary identity or toggle readback does not match its arm", reuse.validate_result(*fixture))
        fixture = good_node_result()
        fixture[0]["abab"]["blocks"][1]["index"] = 0
        self.assertIn("node packet blocks are not the complete ordered ABBA sequence", reuse.validate_result(*fixture))

    def test_cpu_profile_is_explicit_diagnostic_and_survives_worker_dispatch(self):
        for feature in ("items", "groups", "nodes"):
            plan = reuse.build_plan(args("flying", "--feature", feature, "--item-build-profile"))
            self.assertTrue(plan["diagnosticOnly"])
            self.assertEqual(plan["requestedProperties"]["mc.lod.itemBuildProfile"], "true")
            self.assertEqual(reuse.build_plan(reuse.parser().parse_args(reuse.worker_command(plan)[3:])), plan)
        for mode, feature in (("vista", "groups"), ("spin", "items"), ("flying", "reuse"), ("flying", "async")):
            with self.assertRaisesRegex(ValueError, "profiling requires"):
                reuse.build_plan(args(mode, "--feature", feature, "--item-build-profile"))

    def test_profile_requires_actual_measured_builds(self):
        fixture = good_result("flying", feature="groups", profile=True)
        self.assertEqual(reuse.validate_result(*fixture), [])
        d = fixture[0]
        snaps = d["abab"]["lodCounters"]["snapshots"]
        snaps[-1] = copy.deepcopy(snaps[0])
        self.assertIn("no completed item builds profiled during measurement", reuse.validate_result(*fixture))
        del d["lod"]["state"]["gpu"]["itemBuildProfile"]
        self.assertIn("requested item build profile missing or disabled", reuse.validate_result(*fixture))

    def test_unrequested_profile_cannot_be_fps_evidence(self):
        for through_property in (True, False):
            fixture = good_result("flying", feature="groups")
            if through_property:
                fixture[0]["config"]["properties"]["mc.lod.itemBuildProfile"] = "true"
            else:
                fixture[0]["lod"]["state"]["gpu"]["itemBuildProfile"] = {"enabled": True}
            self.assertIn("unrequested CPU profiling contaminates FPS evidence", reuse.validate_result(*fixture))

    def test_successful_profile_receipt_never_claims_performance_evidence(self):
        for profile in (False, True):
            data, plan, inputs, guards, initial = good_result("flying", feature="groups", profile=profile)
            guards["initialLodState"] = initial
            child = Mock(pid=12345, returncode=0)
            child.poll.return_value = 0
            def digest(path):
                return next((row["sha256"] for row in inputs["jars"].values() if row["path"] == path), "result-hash")
            with patch.object(Path, "exists", return_value=False), patch.object(Path, "mkdir"), \
                    patch.object(Path, "open", mock_open()), patch.object(reuse, "prism_clear"), \
                    patch.object(reuse, "read_json", side_effect=[guards, data]), \
                    patch.object(reuse, "digest", side_effect=digest), patch.object(reuse, "write_json") as write, \
                    patch.object(reuse.subprocess, "Popen", return_value=child), \
                    patch.object(reuse, "stop_owned_group"), contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(reuse.run(plan), 0)
            receipt = write.call_args.args[1]
            self.assertTrue(receipt["guardsValid"])
            self.assertEqual(receipt["performanceEvidence"], not profile)
            self.assertEqual(receipt["timingEvidenceValid"], not profile)
            self.assertEqual(receipt["status"], "profile-diagnostic-only" if profile else "measured-diagnostic-only")
            self.assertFalse(receipt["releaseEligible"])

    def test_group_lookup_holds_item_cache_on_without_diagnostics(self):
        for mode in ("spot", "vista", "spin", "flying"):
            plan = reuse.build_plan(args(mode, "--feature", "groups"))
            props, runtime = plan["benchProperties"], plan["requestedProperties"]
            self.assertEqual(props["abab.toggles"], reuse.GROUPS_FIELD + "=false|true")
            self.assertEqual(runtime["mc.lod.cacheItems"], "true")
            for key in ("mc.lod.reuse", "mc.lod.async", "mc.lod.cacheGroups", "mc.lod.cacheGroups.diagnostics"):
                self.assertEqual(runtime[key], "false")
            self.assertEqual(runtime["mc.lod.tau"], "2")
            self.assertEqual(props["abab.lodCounters"], "true")
            self.assertFalse(plan["releaseEligible"])
            self.assertEqual(reuse.build_plan(reuse.parser().parse_args(reuse.worker_command(plan)[3:])), plan)

    def test_items_modes_keep_async_disabled_and_quality_unchanged(self):
        for mode in ("spot", "vista", "spin", "flying"):
            plan = reuse.build_plan(args(mode, "--feature", "items"))
            props, runtime = plan["benchProperties"], plan["requestedProperties"]
            self.assertEqual(props["abab.toggles"], reuse.ITEMS_FIELD + "=false|true")
            self.assertEqual(runtime["mc.lod.reuse"], "false")
            self.assertEqual(runtime["mc.lod.async"], "false")
            self.assertEqual(runtime["mc.lod.cacheItems"], "false")
            self.assertEqual(runtime["mc.lod.tau"], "2")
            self.assertEqual(props["abab.lodCounters"], "true")
            self.assertFalse(plan["releaseEligible"])
            self.assertEqual(reuse.build_plan(reuse.parser().parse_args(reuse.worker_command(plan)[3:])), plan)

    def test_async_modes_are_separate_default_off_prototype(self):
        for mode in ("spot", "vista", "spin", "flying"):
            plan = reuse.build_plan(args(mode, "--feature", "async"))
            props, runtime = plan["benchProperties"], plan["requestedProperties"]
            self.assertEqual(props["abab.toggles"], reuse.ASYNC_FIELD + "=false|true")
            self.assertEqual(runtime["mc.lod.reuse"], "false")
            self.assertEqual(runtime["mc.lod.async.producerProbe.refreshes"], "0")
            self.assertEqual(runtime["mc.lod.async.shadeDiagnostics"], "false")
            self.assertEqual(runtime["mc.lod.async.shadeAtlasFamily"], "true")
            self.assertEqual(runtime["mc.lod.materialInputs"], "true")
            self.assertEqual(props["abab.discard"], "120")
            self.assertEqual(props["abab.bins"], "4" if mode == "spin" else "1")
            self.assertFalse(plan["releaseEligible"])
            self.assertEqual(reuse.build_plan(reuse.parser().parse_args(reuse.worker_command(plan)[3:])), plan)







    def test_exact_modes_and_quality(self):
        for mode, preset, segments, seconds, rd in (("spot", "heavy", 1, 5, 12), ("vista", "heavy", 1, 5, 12),
                                                   ("spin", "spin", 1, 10, 12), ("flying", "travel", 1, 5, 16)):
            with self.subTest(mode=mode):
                plan = reuse.build_plan(args(mode))
                p, cmd = plan["benchProperties"], plan["command"]
                self.assertEqual(p["mode"], "abab")
                self.assertEqual(p["abab.toggles"], reuse.FIELD + "=false|true")
                self.assertEqual(p["abab.preset"], preset)
                self.assertEqual(p["abab.segments"], str(segments))
                self.assertEqual(p["abab.blockSeconds"], str(seconds))
                self.assertEqual(p["abab.order"], "abba")
                self.assertIn("--jvm=-Dmc.lod.reuse=false", cmd)
                self.assertNotIn("--jvm=-Dmc.lod.reuse=true", cmd)
                for key, value in {"radius": "512", "tau": "2", "gpuMB": "6144", "wave": "false", "hiz": "false",
                                   "solidQuery": "false", "area": "false", "secOccl": "true", "layerMask": "7"}.items():
                    self.assertIn("--jvm=-Dmc.lod." + key + "=" + value, cmd)
                self.assertIn("renderDistance:" + str(rd), plan["options"])
                self.assertIn('renderClouds:"true"', plan["options"])
                self.assertIn("--world-only", cmd)
                self.assertEqual(cmd[cmd.index("--lane") + 1], "timing")
                self.assertEqual(p["measureCmds"], "time set 6000")
                self.assertIn("gamerule advance_time false", p["cmds"])
                self.assertEqual(p["photo"], "0")
                self.assertEqual(p["f3"], "off")
                self.assertEqual(p["abab.stopOn"], "median2,pairP99")
                self.assertFalse(plan["releaseEligible"])
                if preset == "heavy":
                    self.assertEqual(p["spin"], "Infinity")
                    self.assertEqual(p["abab.heavyYaw"], "200")
                    self.assertEqual(p["yaw"], "200")
                if mode == "flying":
                    self.assertEqual(p["world"], "travel")
                    self.assertEqual(p["speed"], "10.9")
                    self.assertNotIn("pos", p)
                else:
                    self.assertEqual(p["pos"], "6.5,180,6.5" if mode == "vista" else "6.5,106,6.5")

    def test_all_launch_flags_exist_without_importing_or_invoking_launch(self):
        source = ast.parse((reuse.ROOT / "tools/launch.py").read_text())
        flags = {arg.value for node in ast.walk(source) if isinstance(node, ast.Call)
                 and isinstance(node.func, ast.Attribute) and node.func.attr == "add_argument"
                 for arg in node.args if isinstance(arg, ast.Constant) and isinstance(arg.value, str)}
        for mode in ("spot", "vista", "spin", "flying"):
            cmd = reuse.build_plan(args(mode))["command"]
            for arg in cmd[3:]:
                if arg.startswith("--"):
                    self.assertIn(arg.split("=", 1)[0], flags)

    def test_bad_labels_worlds_and_bounds(self):
        for extra in (("--label", "../bad"), ("--world", "../bench"), ("--blocks", "6"), ("--blocks", "26"),
                      ("--deadline", "999999"), ("--deadline", "180"), ("--lod-wait", "0")):
            with self.subTest(extra=extra), self.assertRaises(ValueError):
                reuse.build_plan(args("spot", *extra))

    def test_worker_uses_canonical_paths_and_round_trips_plan(self):
        plan = reuse.build_plan(args("spin", "--blocks", "8", "--lod-wait", "30", "--deadline", "240"))
        cmd = reuse.worker_command(plan)
        self.assertEqual(cmd[3:5], [plan["client"], plan["bench"]])
        self.assertEqual(reuse.build_plan(reuse.parser().parse_args(cmd[3:])), plan)

    def test_fast_turn_period_is_explicit_and_survives_worker(self):
        plan = reuse.build_plan(args("spin", "--spin-seconds", "1"))
        self.assertEqual(plan["benchProperties"]["spin"], "1")
        self.assertEqual(plan["benchProperties"]["abab.blockSeconds"], "10")
        self.assertEqual(reuse.build_plan(reuse.parser().parse_args(reuse.worker_command(plan)[3:])), plan)
        with self.assertRaises(ValueError):
            reuse.build_plan(args("flying", "--spin-seconds", "1"))

    def test_existing_fixture_plans_keep_cache_and_flying_bench_world(self):
        for mode in ("spot", "vista", "spin", "flying"):
            with self.subTest(mode=mode):
                plan = reuse.build_plan(args(mode, "--game-dir", str(reuse.ROOT / "run/s3-palette-check"), "--world", "bench"))
                cmd = plan["command"]
                self.assertEqual(plan["gameDir"], str(reuse.ROOT / "run/s3-palette-check"))
                self.assertEqual(cmd[cmd.index("--game-dir") + 1], plan["gameDir"])
                for flag in ("--world-snapshot", "--world-only", "--fresh-world"):
                    self.assertNotIn(flag, cmd)
                self.assertIsNone(plan["snapshot"])
                self.assertEqual(plan["benchProperties"]["world"], "bench")
                self.assertEqual(plan["benchProperties"]["abab.segments"], "1")
                self.assertEqual(plan["fixture"]["kind"], "shared-mutable-project-fixture")
                self.assertFalse(plan["fixture"]["pinned"])
                self.assertFalse(plan["fixture"]["restoredBeforeRun"])
                self.assertEqual(reuse.build_plan(reuse.parser().parse_args(reuse.worker_command(plan)[3:])), plan)
        plan = reuse.build_plan(args("flying", "--game-dir", str(reuse.ROOT / "run/s3-palette-check")))
        self.assertEqual(plan["world"], "bench")

    def test_fixture_arguments_are_required_and_mutually_exclusive(self):
        base = ["client.jar", "bench.jar", "--label", "test", "--mode", "flying"]
        for extra in ([], ["--game-dir", "run/test", "--world-snapshot", "run/pinned/test"]):
            with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
                reuse.parser().parse_args([*base, *extra])

    def test_reset_world_requires_existing_game_dir(self):
        with self.assertRaisesRegex(ValueError, "--reset-world requires --game-dir"):
            reuse.build_plan(args("flying", "--reset-world", "run/pinned/reset"))
        base = ["client.jar", "bench.jar", "--label", "test", "--mode", "flying"]
        with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
            reuse.parser().parse_args([*base, "--reset-world", "run/pinned/reset"])

    def test_reset_world_preserves_cache_plan_and_worker_roundtrip(self):
        for mode in ("spot", "flying"):
            with self.subTest(mode=mode):
                plan = reuse.build_plan(args(mode, "--game-dir", str(reuse.ROOT / "run/s3-palette-check"),
                                             "--reset-world", str(reuse.ROOT / "run/pinned/reuse-fixture")))
                cmd = plan["command"]
                self.assertEqual(plan["world"], "bench")
                self.assertEqual(cmd[cmd.index("--game-dir") + 1], plan["gameDir"])
                self.assertEqual(cmd[cmd.index("--world-snapshot") + 1], plan["resetWorld"])
                self.assertEqual(plan["snapshot"], plan["resetWorld"])
                self.assertIn("--world-only", cmd)
                self.assertNotIn("--fresh-world", cmd)
                self.assertNotIn("--reset-world", cmd)  # sidecar option, not a launch.py option
                fixture = plan["fixture"]
                self.assertEqual(fixture["kind"], "pinned-world-mutable-project-cache")
                self.assertEqual(fixture["pinnedScope"], "world")
                self.assertTrue(fixture["pinned"] and fixture["restoredBeforeRun"])
                self.assertFalse(fixture["lodCachePinned"])
                self.assertTrue(fixture["lodCacheMutable"] and fixture["lodCachePreserved"])
                worker = reuse.worker_command(plan)
                self.assertIn("--game-dir", worker)
                self.assertNotIn("--world-snapshot", worker)
                self.assertEqual(reuse.build_plan(reuse.parser().parse_args(worker[3:])), plan)

    def test_reset_world_overlap_and_prism_rejected(self):
        game = reuse.ROOT / "run/reset-target"
        for snapshot in (game, game / "pin", game.parent, reuse.ROOT / "results/reuse-run/unit-test",
                         reuse.ROOT / "results/reuse-run/unit-test/pin", reuse.ROOT / "run/PrismLauncher/pin"):
            with self.subTest(snapshot=snapshot), self.assertRaises(ValueError):
                reuse.build_plan(args("flying", "--game-dir", str(game), "--reset-world", str(snapshot)))

    def test_existing_fixture_root_prism_and_overlap_guards(self):
        for path in (reuse.ROOT, reuse.ROOT.parent / "other", reuse.ROOT / "run/PrismLauncher/instances/test",
                     reuse.ROOT / "results/reuse-run", reuse.ROOT / "results/reuse-run/unit-test/game"):
            with self.subTest(path=path), self.assertRaises(ValueError):
                reuse.build_plan(args("flying", "--game-dir", str(path)))

    def test_whole_blocks_retain_useful_cells_at_100_fps(self):
        abab = reuse.load_tool("abab")
        for mode in ("spin", "flying"):
            plan = reuse.build_plan(args(mode))
            count = int(plan["benchProperties"]["abab.blockSeconds"]) * 100
            ab = dict(bins=24, segments=1, minCell=20,
                      blocks=[dict(arm="A", startFrame=0, frames=count), dict(arm="B", startFrame=count, frames=count)],
                      frames=dict(dtNs=[10_000_000] * count * 2, gpuBusyNs=[-1] * count * 2,
                                  bin=[i * 24 // count for i in range(count)] * 2,
                                  block=[0] * count + [1] * count, keep=[int(i >= 30) for i in range(count)] * 2))
            cells = abab.cells_from_frames(ab)
            self.assertEqual(sum(ab["frames"]["keep"]), 2 * (count - 30))
            self.assertTrue(all(any(math.isfinite(c["median2"]) for c in b["cells"]) for b in cells))
            self.assertTrue(all(math.isfinite(t[0]) for t in abab.pair_tails_from_frames(ab)[0]))

    def test_prism_and_output_overlap_rejected(self):
        a = args()
        a.client = Path("/unused/PrismLauncher/instances/test/client.jar")
        with self.assertRaisesRegex(ValueError, "Prism"):
            reuse.build_plan(a)
        a = args()
        a.world_snapshot = reuse.ROOT / "results/reuse-run"
        with self.assertRaisesRegex(ValueError, "overlap"):
            reuse.build_plan(a)

    def test_default_and_explicit_dryrun_have_no_side_effects(self):
        for extra in ([], ["--dry-run"]):
            with patch.object(reuse, "worker", side_effect=AssertionError("worker")), \
                    patch.object(reuse, "run", side_effect=AssertionError("run")), \
                    patch.object(reuse, "load_tool", side_effect=AssertionError("lock/pin")), \
                    patch.object(reuse.subprocess, "Popen", side_effect=AssertionError("Popen")), \
                    patch.object(reuse.subprocess, "run", side_effect=AssertionError("subprocess")), \
                    patch.object(Path, "mkdir", side_effect=AssertionError("mkdir")), \
                    patch.object(Path, "write_text", side_effect=AssertionError("write")), \
                    contextlib.redirect_stdout(io.StringIO()) as stdout:
                rc = reuse.main(["missing-client.jar", "missing-bench.jar", "--label", "plan", "--mode", "vista",
                                 "--world-snapshot", "missing-snapshot", *extra])
                self.assertEqual(rc, 0)
                result = json.loads(stdout.getvalue())
                self.assertIn("plan-only", result["status"])
                self.assertFalse(result["releaseEligible"])


class Evidence(unittest.TestCase):
    def test_group_lookup_rejects_changed_background_features(self):
        for mode in ("vista", "spin", "flying"):
            fixture = good_result(mode, feature="groups")
            self.assertEqual(reuse.validate_result(*fixture), [])
            for key, bad_value in (("mc.lod.cacheItems", "false"), ("mc.lod.async", "true"),
                                   ("mc.lod.cacheGroups.diagnostics", "true")):
                bad = copy.deepcopy(fixture)
                bad[0]["config"]["properties"][key] = bad_value
                self.assertIn("actual JVM/bench properties differ or are missing", reuse.validate_result(*bad))

    def test_items_requires_async_disabled_in_loaded_config(self):
        for mode in ("vista", "spin", "flying"):
            fixture = good_result(mode, feature="items")
            self.assertEqual(reuse.validate_result(*fixture), [])
            bad = copy.deepcopy(fixture)
            bad[0]["config"]["properties"]["mc.lod.async"] = "true"
            self.assertIn("actual JVM/bench properties differ or are missing", reuse.validate_result(*bad))

    def test_async_evidence_requires_its_own_flags_and_longer_discard(self):
        for mode in ("vista", "spin", "flying"):
            fixture = good_result(mode, feature="async")
            self.assertEqual(reuse.validate_result(*fixture), [])
            invalid = copy.deepcopy(fixture)
            invalid[0]["abab"]["discardFrames"] = 30
            self.assertIn("ABAB discardFrames differs or missing", reuse.validate_result(*invalid))
            invalid = copy.deepcopy(fixture)
            invalid[0]["config"]["properties"]["mc.lod.async.shadeDiagnostics"] = "true"
            self.assertIn("actual JVM/bench properties differ or are missing", reuse.validate_result(*invalid))

    def test_synthetic_valid_each_mode(self):
        for mode in ("spot", "vista", "spin", "flying"):
            with self.subTest(mode=mode):
                self.assertEqual(reuse.validate_result(*good_result(mode)), [])

    def test_shared_fixture_evidence_valid_without_claiming_pinned_content(self):
        for mode in ("spot", "vista", "spin", "flying"):
            with self.subTest(mode=mode):
                fixture = good_result(mode, shared=True)
                self.assertEqual(reuse.validate_result(*fixture), [])
                fixture[0]["config"]["properties"]["bench.pinnedWorld"] = "false-pinned-claim"
                self.assertTrue(reuse.validate_result(*fixture))

    def test_reset_world_result_requires_world_pin_and_preserves_cache_scope(self):
        fixture = good_result("flying", reset=True)
        self.assertEqual(reuse.validate_result(*fixture), [])
        self.assertFalse(fixture[2]["fixture"]["lodCachePinned"])
        del fixture[0]["config"]["properties"]["bench.pinnedWorld"]
        self.assertIn("pinned scene identity not verified", reuse.validate_result(*fixture))

    def test_reported_spin_period_must_match_the_requested_route(self):
        fixture = good_result("spin")
        self.assertEqual(reuse.validate_result(*fixture), [])
        fixture[0]["abab"]["spinSeconds"] = 1
        self.assertIn("actual spin period differs or missing", reuse.validate_result(*fixture))
        del fixture[0]["abab"]["spinSeconds"]
        self.assertIn("actual spin period differs or missing", reuse.validate_result(*fixture))

    def test_rejects_missing_or_conflicting_evidence(self):
        cases = [
            (("lodSettleSeconds",), -30), (("lod", "state", "tau"), 2.5),
            (("lod", "state", "selected"), 0), (("lod", "state", "idle"), False),
            (("launch", "display", "hz"), 60), (("launch", "display", "pixels"), "3600x2338"),
            (("launch", "quietGate", "ok"), False), (("launch", "overlays"), True),
            (("config", "properties", "mc.lod.reuse"), "true"),
            (("config", "properties", "mc.lod.layerMask"), "0"),
            (("config", "properties", "bench.cmds"), ""),
            (("config", "properties", "bench.pinnedWorld"), "other@id"),
            (("config", "jars", "client", "sha1"), "changed"),
            (("config", "native", "validation"), 1), (("config", "native", "profilePasses"), 1),
            (("config", "env", "MTL_DEBUG_LAYER"), "1"),
            (("config", "env", "JAVA_TOOL_OPTIONS"), "-Dmc.lod.tau=32"),
            (("config", "game", "cloudStatus"), "OFF"),
            (("config", "game", "cloudStatus"), "FAST"),
            (("config", "toggles", "Lod", "draw"), False),
            (("abab", "toggles", 0, "readBackB"), "false"),
            (("abab", "toggles", 0, "field"), "net.minecraft.client.renderer.chunk.Lod#draw"),
            (("abab", "blocksRun"), 4), (("abab", "dropped"), 1),
            (("abab", "stop", "enabled"), True), (("abab", "frames", "keep"), []),
            (("abab", "arms", "B", "p1LowFps"), None),
            (("abab", "effects", "pairP99", "ci"), [None, None]),
            (("abab", "effects", "pairP99", "pairs"), 2),
        ]
        for path, value in cases:
            with self.subTest(path=path):
                fixture = good_result()
                node = fixture[0]
                for part in path[:-1]:
                    node = node[part]
                node[path[-1]] = value
                self.assertTrue(reuse.validate_result(*fixture))

    def test_missing_guards_settle_and_hash_changes_fail(self):
        data, plan, inputs, guards, initial = good_result()
        self.assertTrue(reuse.validate_result(data, plan, inputs, {}, initial))
        self.assertTrue(reuse.validate_result(data, plan, inputs, guards, None))
        self.assertTrue(reuse.validate_result(data, plan, inputs, guards, initial, unchanged=False))
        self.assertTrue(reuse.validate_result(data, plan, inputs, guards, initial, exit_code=124))
        self.assertTrue(reuse.validate_result({}, plan, inputs, guards, initial))
        initial["tau"] = 3.125
        self.assertTrue(reuse.validate_result(data, plan, inputs, guards, initial))

    def test_flying_streaming_is_explicit_not_a_false_unsettled_failure(self):
        fixture = good_result("flying")
        fixture[0]["lod"]["state"]["idle"] = False
        self.assertEqual(reuse.validate_result(*fixture), [])
        fixture[0]["lod"]["state"]["tau"] = 2.5
        self.assertTrue(reuse.validate_result(*fixture))

    def test_current_abab_metrics_not_top_level_or_sector_p99(self):
        data = good_result()[0]
        data["abab"]["effects"]["p99"] = dict(logRatio=123, ci=[122, 124])
        summary = reuse.summarize(data)
        self.assertEqual(summary["arms"]["A"]["medianFps"], 500)
        self.assertEqual(summary["arms"]["B"]["p1LowFps"], 400)
        self.assertAlmostEqual(summary["primaryEffectsFps"]["pairP99"]["percent"], 100)
        self.assertNotIn("p99", summary["primaryEffectsFps"])
        self.assertFalse(summary["topLevelFpsUsed"])
        self.assertFalse(summary["releaseEligible"])
        abab = reuse.load_tool("abab")
        ci = summary["primaryEffectsFps"]["pairP99"]["intervalPercent"]
        expected = sorted(abab.to_pct(v, "pairP99") for v in data["abab"]["effects"]["pairP99"]["ci"])
        for a, b in zip(ci, expected):
            self.assertAlmostEqual(a, b)

    def test_settle_log_parsing(self):
        state = dict(idle=True, tau=2, selected=50)
        log = "prefix [bench] lod settled after 42 s: " + json.dumps(dict(state=state)) + "\n"
        self.assertEqual(reuse.settled_state(log), state)
        self.assertIsNone(reuse.settled_state("[bench] lod NOT settled: {}"))

    def test_nonfinite_json_rejected(self):
        for text in ('{"fps": NaN}', '{"fps": Infinity}'):
            with patch.object(Path, "read_text", return_value=text), self.assertRaises(ValueError):
                reuse.read_json("unused")


class Guards(unittest.TestCase):
    @staticmethod
    @contextlib.contextmanager
    def reset_inputs():
        """Synthetic read-only jars/snapshot; no real fixture access or restore."""
        jar = Mock()
        jar.namelist.return_value = ["net/minecraft/client/renderer/chunk/LodReuse.class", "bench/Bench.class",
                                     "bench/Abab.class", "bench/ConfigBlock.class"]
        pin = Mock()
        pin.verify.return_value = "synthetic-id"
        pin.restore.side_effect = AssertionError("restore belongs to the locked launcher")
        with patch.object(Path, "is_file", return_value=True), patch.object(Path, "is_dir", return_value=True), \
                patch.object(Path, "is_symlink", return_value=False), \
                patch.object(Path, "resolve", new=lambda p: p), \
                patch.object(Path, "stat", return_value=Mock(st_mode=0o100444, st_size=1)), \
                patch.object(reuse.zipfile, "ZipFile") as archive, \
                patch.object(reuse, "digest", return_value="synthetic-hash") as digest, \
                patch.object(reuse, "load_tool", return_value=pin), \
                patch.object(reuse.subprocess, "Popen", side_effect=AssertionError("no launch")):
            archive.return_value.__enter__.return_value = jar
            yield pin, digest

    def test_reset_verifies_pin_manifest_without_hashing_existing_cache(self):
        plan = good_result("flying", reset=True)[1]
        with self.reset_inputs() as (pin, digest):
            inputs = reuse.verify_inputs(plan)
        pin.verify.assert_called_once_with(plan["snapshot"])
        pin.restore.assert_not_called()
        self.assertEqual(inputs["snapshotId"], "synthetic-id")
        self.assertEqual(inputs["snapshotManifestSha256"], "synthetic-hash")
        self.assertEqual(inputs["fixture"], plan["fixture"])
        self.assertEqual({str(c.args[0]) for c in digest.call_args_list},
                         {plan["client"], plan["bench"], str(Path(plan["snapshot"]) / "SHA256SUMS")})

    def test_reset_still_rejects_escaped_existing_fixture_paths(self):
        plan = good_result("flying", reset=True)[1]
        game = Path(plan["gameDir"])
        for relative in ("saves", "saves/bench", "lod", ".natives", "options.txt", "launch.log"):
            with self.subTest(relative=relative), self.reset_inputs() as (pin, _), \
                    patch.object(Path, "resolve", new=lambda p: reuse.ROOT / "outside-fixture" if p == game / relative else p), \
                    self.assertRaisesRegex(ValueError, "fixture path escapes"):
                reuse.verify_inputs(plan)
            pin.verify.assert_not_called()

    def test_reset_rejects_missing_existing_world_and_destination_alias(self):
        plan = good_result("flying", reset=True)[1]
        game = Path(plan["gameDir"])
        with self.reset_inputs(), patch.object(Path, "is_file", new=lambda p: p != game / "saves/bench/level.dat"), \
                self.assertRaisesRegex(ValueError, "existing fixture lacks"):
            reuse.verify_inputs(plan)
        for relative in ("saves", "saves/bench"):
            with self.subTest(relative=relative), self.reset_inputs(), \
                    patch.object(Path, "is_symlink", new=lambda p: p == game / relative), \
                    self.assertRaisesRegex(ValueError, "destination must not be a symlink"):
                reuse.verify_inputs(plan)
        with self.reset_inputs(), \
                patch.object(Path, "resolve", new=lambda p: game / "saves/bench/cache" if p == game / "lod" else p), \
                self.assertRaisesRegex(ValueError, "overlaps the retained LOD cache"):
            reuse.verify_inputs(plan)

    def test_existing_fixture_verification_never_hashes_or_restores_world_cache(self):
        plan = reuse.build_plan(args("flying", "--game-dir", str(reuse.ROOT / "run/s3-palette-check")))
        jar = Mock()
        jar.namelist.return_value = ["net/minecraft/client/renderer/chunk/LodReuse.class", "bench/Bench.class",
                                     "bench/Abab.class", "bench/ConfigBlock.class"]
        with patch.object(Path, "is_file", return_value=True), patch.object(Path, "is_dir", return_value=True), \
                patch.object(Path, "stat", return_value=Mock(st_mode=0o100444, st_size=1)), \
                patch.object(reuse.zipfile, "ZipFile") as archive, patch.object(reuse, "digest", return_value="jar-hash") as digest, \
                patch.object(reuse, "load_tool", side_effect=AssertionError("no pin/restore tools")), \
                patch.object(Path, "mkdir", side_effect=AssertionError("no fixture creation")), \
                patch.object(Path, "write_text", side_effect=AssertionError("no fixture writes")):
            archive.return_value.__enter__.return_value = jar
            inputs = reuse.verify_inputs(plan)
        self.assertIsNone(inputs["snapshotId"])
        self.assertIsNone(inputs["snapshotManifestSha256"])
        self.assertFalse(inputs["fixture"]["pinned"])
        self.assertEqual({str(c.args[0]) for c in digest.call_args_list}, {plan["client"], plan["bench"]})

    def test_environment_is_clean_without_mutating_parent(self):
        source = dict(PATH="/usr/bin", JAVA_TOOL_OPTIONS="injected", MCMTL_SOLID_PROFILE="1", MTL_DEBUG_LAYER="1",
                      MTL_SHADER_VALIDATION="1", DYLD_INSERT_LIBRARIES="injected", MC_GPU_LOCK="private-lock",
                      MC_GPU_LOCK_HELD="timing", PYTHONPATH="injected")
        result = reuse.child_environment(source)
        self.assertEqual(result["MC_GPU_LOCK"], reuse.LOCK)
        self.assertEqual(result["MTL_DEBUG_LAYER"], "0")
        self.assertEqual(result["MTL_SHADER_VALIDATION"], "0")
        for k in ("JAVA_TOOL_OPTIONS", "MCMTL_SOLID_PROFILE", "DYLD_INSERT_LIBRARIES", "MC_GPU_LOCK_HELD", "PYTHONPATH"):
            self.assertNotIn(k, result)
        self.assertEqual(source["MTL_DEBUG_LAYER"], "1")

    def test_prism_fail_closed(self):
        for rc in (0, 2, -9):
            reason = "Prism game process detected" if rc == 0 else "Prism process check unavailable: pgrep exit " + str(rc)
            with self.subTest(rc=rc), patch.object(reuse.subprocess, "run", return_value=Mock(returncode=rc)), \
                    self.assertRaisesRegex(RuntimeError, reason):
                reuse.prism_clear()
        with patch.object(reuse.subprocess, "run", return_value=Mock(returncode=1)) as run:
            reuse.prism_clear()
            self.assertEqual(run.call_args.kwargs["timeout"], 5)
        with patch.object(reuse.subprocess, "run", side_effect=subprocess.TimeoutExpired("pgrep", 5)), \
                self.assertRaises(subprocess.TimeoutExpired):
            reuse.prism_clear()

    def test_absent_display_helper_does_not_compile(self):
        with patch.object(Path, "is_file", return_value=False), \
                patch.object(reuse.subprocess, "run", side_effect=AssertionError("must not compile")), \
                self.assertRaisesRegex(RuntimeError, "prebuilt"):
            reuse.read_display()

    def test_worker_rejects_borrowed_or_shared_lock(self):
        for nested, gpu in ((True, None), (False, "sh")):
            hold = Mock(nested=nested, gpu=gpu, fds=[123])
            gpulock = Mock(EX="ex")
            gpulock.acquire.return_value = hold
            with patch.object(reuse, "load_tool", return_value=gpulock), \
                    patch.object(reuse, "prism_clear"), \
                    patch.object(reuse.subprocess, "Popen", side_effect=AssertionError("no launch")), \
                    self.assertRaisesRegex(RuntimeError, "exclusive"):
                reuse.worker(reuse.build_plan(args()))
            hold.release.assert_called_once()

    def test_frozen_jars_required_before_any_launch(self):
        plan = reuse.build_plan(args())
        with patch.object(Path, "is_file", return_value=True), \
                patch.object(Path, "stat", return_value=Mock(st_mode=0o100644)), \
                patch.object(reuse.subprocess, "Popen", side_effect=AssertionError("no launch")), \
                self.assertRaisesRegex(ValueError, "read-only"):
            reuse.verify_inputs(plan)

    def test_total_deadline_covers_lock_wait_and_preserves_rejected_receipt(self):
        plan = reuse.build_plan(args())
        child = Mock(pid=34567, returncode=None)
        child.poll.return_value = None  # includes a worker still waiting on its lock
        with patch.object(Path, "exists", return_value=False), patch.object(Path, "mkdir"), \
                patch.object(Path, "open", mock_open()), \
                patch.object(reuse, "read_json", side_effect=FileNotFoundError("no result")), \
                patch.object(reuse, "write_json") as write, \
                patch.object(reuse, "prism_clear"), \
                patch.object(reuse.time, "monotonic", side_effect=[0, plan["deadlineSeconds"] + 1]), \
                patch.object(reuse.subprocess, "Popen", return_value=child) as popen, \
                patch.object(reuse, "stop_owned_group") as stop, \
                contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(reuse.run(plan), 124)
        self.assertTrue(popen.call_args.kwargs["start_new_session"])
        stop.assert_called_once_with(child)
        receipt = write.call_args.args[1]
        self.assertFalse(receipt["timingEvidenceValid"])
        self.assertFalse(receipt["releaseEligible"])
        self.assertIn("deadline", receipt["rejections"][0])

    def test_never_overwrites_a_run(self):
        with patch.object(Path, "exists", return_value=True), \
                patch.object(reuse.subprocess, "Popen", side_effect=AssertionError("no launch")), \
                self.assertRaisesRegex(ValueError, "overwrite"):
            reuse.run(reuse.build_plan(args()))

    def test_owned_group_killed_even_if_leader_exits_on_term(self):
        child = Mock(pid=12345)
        with patch.object(reuse.os, "killpg") as kill:
            reuse.stop_owned_group(child)
        self.assertEqual(kill.call_args_list[0].args, (12345, signal.SIGTERM))
        self.assertEqual(kill.call_args_list[1].args, (12345, signal.SIGKILL))
        self.assertEqual(child.wait.call_args_list[0].kwargs, dict(timeout=3))
        self.assertEqual(child.wait.call_args_list[1].kwargs, dict(timeout=2))

    def test_owned_timeout_escalates_only_its_group(self):
        child = Mock(pid=23456)
        child.wait.side_effect = [subprocess.TimeoutExpired("owned-child", 3), 0]
        with patch.object(reuse.os, "killpg") as kill:
            reuse.stop_owned_group(child)
        self.assertEqual([c.args for c in kill.call_args_list], [(23456, signal.SIGTERM), (23456, signal.SIGKILL)])


if __name__ == "__main__":
    unittest.main(verbosity=2)
