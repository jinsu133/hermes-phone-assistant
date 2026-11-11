"""Offline profile-store contract: validation, atomicity, version invalidation."""
import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import phone_profiles as profiles


PKG = "com.skt.tmap.ku"

TMAP = {
    "package": PKG,
    "version_code": 1234,
    "entry": {"deeplink": None, "fallback": "launch"},
    "anchors": {
        "home": {"id_suffix": "tmap_main_search_bar_layout"},
        "search": {"role": "search_field"},
        "results": {"text_contains": "정렬결과"},
        "popup": {"text_contains": "닫기"},
    },
    "routes": {
        "search": [
            {"op": "click", "id_suffix": "tmap_main_search_bar_layout"},
            {"op": "await", "role": "search_field", "timeout_ms": 3000},
            {"op": "input", "role": "search_field", "value": "{q}"},
            {"op": "submit", "role": "search_field", "timeout_ms": 2500},
            {"op": "await", "text_contains": "정렬결과", "timeout_ms": 5000},
        ]
    },
    "dismiss": ["닫기", "오늘은 그만보기"],
}


class ProfileStore(unittest.TestCase):
    def setUp(self):
        self.home = Path(tempfile.mkdtemp(prefix="profiles-"))

    def test_round_trip_is_data_only(self):
        stored = profiles.put(self.home, PKG, dict(TMAP))
        self.assertEqual(stored["package"], PKG)
        again = profiles.get(self.home, PKG)
        self.assertEqual(again["anchors"]["results"], {"text_contains": "정렬결과"})
        raw = (self.home / "phone-profiles.json").read_text(encoding="utf-8")
        self.assertIn("정렬결과", raw)

    def test_missing_store_reads_as_empty_not_an_error(self):
        self.assertIsNone(profiles.get(self.home, PKG))
        self.assertEqual(profiles.load(self.home)["apps"], {})

    def test_version_change_invalidates_a_stored_anchor(self):
        profiles.put(self.home, PKG, dict(TMAP), version_code=1234)
        self.assertEqual(profiles.anchor(self.home, PKG, "results", 1234),
                         {"text_contains": "정렬결과"})
        with self.assertRaises(profiles.ProfileError) as caught:
            profiles.anchor(self.home, PKG, "results", 1240)
        self.assertEqual(caught.exception.code, "profile_version_changed")

    def test_a_profile_cannot_inject_an_unknown_operation(self):
        broken = json.loads(json.dumps(TMAP))
        broken["routes"]["search"].append({"op": "confirm_payment"})
        with self.assertRaises(profiles.ProfileError) as caught:
            profiles.put(self.home, PKG, broken)
        self.assertIn("profile_bad_steps", caught.exception.code)

    def test_a_profile_cannot_smuggle_confirmation_arguments(self):
        broken = json.loads(json.dumps(TMAP))
        broken["routes"]["search"][0]["confirmation"] = {"kind": "payment", "amount": "1"}
        # The step op is legal, so rejection happens at the snapshot step limit check:
        # an unknown key makes the value fail the string type check.
        with self.assertRaises(profiles.ProfileError):
            profiles.put(self.home, PKG, broken)

    def test_a_profile_cannot_route_to_an_undefined_anchor(self):
        broken = json.loads(json.dumps(TMAP))
        broken["routes"]["ghost"] = [{"op": "home"}]
        with self.assertRaises(profiles.ProfileError) as caught:
            profiles.put(self.home, PKG, broken)
        self.assertIn("profile_unknown_anchor", caught.exception.code)

    def test_selectors_reject_arbitrary_keys(self):
        broken = json.loads(json.dumps(TMAP))
        broken["anchors"]["home"] = {"xpath": "//*"}
        with self.assertRaises(profiles.ProfileError):
            profiles.put(self.home, PKG, broken)

    def test_package_must_match(self):
        broken = json.loads(json.dumps(TMAP))
        broken["package"] = "com.example.other"
        with self.assertRaises(profiles.ProfileError) as caught:
            profiles.put(self.home, PKG, broken)
        self.assertEqual(caught.exception.code, "profile_package_mismatch")

    def test_oversized_plan_is_refused(self):
        broken = json.loads(json.dumps(TMAP))
        broken["routes"]["search"] = [{"op": "back"} for _ in range(30)]
        with self.assertRaises(profiles.ProfileError):
            profiles.put(self.home, PKG, broken)

    def test_plan_for_prepends_only_launch(self):
        profiles.put(self.home, PKG, dict(TMAP))
        steps = profiles.plan_for(self.home, PKG, "search", 1234)
        self.assertEqual(steps[0], {"op": "launch", "package": PKG})
        self.assertEqual(steps[1]["op"], "click")
        # An overlay must be dismissed by an explicit route step: a fixed dismiss-first
        # prefix ran before the asynchronously shown popup and missed it.
        self.assertNotIn("dismiss", [s["op"] for s in steps if s["op"] != "launch"][:1])

    def test_route_may_not_dismiss_an_undeclared_label(self):
        broken = json.loads(json.dumps(TMAP))
        broken["routes"]["search"].insert(0, {"op": "dismiss", "labels": ["광고닫기"]})
        with self.assertRaises(profiles.ProfileError) as caught:
            profiles.put(self.home, PKG, broken)
        self.assertIn("profile_undeclared_dismiss", caught.exception.code)

    def test_declared_dismiss_label_is_allowed_in_a_route(self):
        shaped = json.loads(json.dumps(TMAP))
        shaped["routes"]["search"].insert(0, {"op": "dismiss", "labels": ["닫기"]})
        profiles.put(self.home, PKG, shaped)
        steps = profiles.plan_for(self.home, PKG, "search", 1234)
        self.assertEqual(steps[1], {"op": "dismiss", "labels": ["닫기"]})

    def test_unknown_route_is_reported_not_guessed(self):
        profiles.put(self.home, PKG, dict(TMAP))
        with self.assertRaises(profiles.ProfileError) as caught:
            profiles.plan_for(self.home, PKG, "navigation", 1234)
        self.assertIn("profile_unknown_route", caught.exception.code)

    def test_corrupt_store_is_refused_instead_of_silently_reset(self):
        profiles.put(self.home, PKG, dict(TMAP))
        (self.home / "phone-profiles.json").write_text("{not json", encoding="utf-8")
        with self.assertRaises(profiles.ProfileError) as caught:
            profiles.load(self.home)
        self.assertEqual(caught.exception.code, "profile_store_unreadable")

    def test_write_is_atomic_and_leaves_no_temp_files(self):
        profiles.put(self.home, PKG, dict(TMAP))
        leftovers = [p.name for p in self.home.iterdir() if p.name.startswith(".phone-profiles-")]
        self.assertEqual(leftovers, [])
        self.assertTrue((self.home / "phone-profiles.json").exists())


class Resolve(unittest.TestCase):
    def setUp(self):
        self.home = Path(tempfile.mkdtemp(prefix="profiles-"))
        profiles.put(self.home, PKG, dict(TMAP))

    def test_resolve_binds_the_query_into_the_input_step(self):
        plan = profiles.resolve(self.home, PKG, "search", query="광화문")
        inputs = [s for s in plan["steps"] if s["op"] == "input"]
        self.assertEqual(inputs[0]["value"], "광화문")
        self.assertEqual(plan["budget_ms"], 15000)

    def test_resolve_refuses_a_query_route_without_a_query(self):
        with self.assertRaises(profiles.ProfileError) as caught:
            profiles.resolve(self.home, PKG, "search")
        self.assertEqual(caught.exception.code, "profile_query_required")

    def test_resolve_rejects_empty_or_oversized_queries(self):
        for bad in ("", "   ", "x" * 201):
            with self.assertRaises(profiles.ProfileError) as caught:
                profiles.resolve(self.home, PKG, "search", query=bad)
            self.assertEqual(caught.exception.code, "profile_bad_query")

    def test_resolve_rejects_a_query_that_is_not_text(self):
        with self.assertRaises(profiles.ProfileError):
            profiles.resolve(self.home, PKG, "search", query=["a"])

    def test_resolve_keeps_the_device_budget_ceiling(self):
        for bad in (499, 15001, "15000", True):
            with self.assertRaises(profiles.ProfileError) as caught:
                profiles.resolve(self.home, PKG, "search", query="a", budget_ms=bad)
            self.assertEqual(caught.exception.code, "profile_bad_budget")

    def test_resolve_does_not_mutate_the_stored_profile(self):
        profiles.resolve(self.home, PKG, "search", query="광화문")
        stored = profiles.plan_for(self.home, PKG, "search", 1234)
        self.assertEqual([s for s in stored if s["op"] == "input"][0]["value"], "{q}")

    def test_list_apps_exposes_routes_without_anchors(self):
        listed = profiles.list_apps(self.home)
        self.assertEqual(listed[0]["package"], PKG)
        self.assertEqual(listed[0]["routes"], ["search"])
        self.assertNotIn("anchors", listed[0])

    def test_resolve_pins_the_recorded_version_into_the_plan(self):
        # The device refuses the plan when this differs from the installed app, so every
        # resolved plan must carry it rather than relying on the caller to pass one.
        plan = profiles.resolve(self.home, PKG, "search", query="강남역")
        self.assertEqual(plan["package"], PKG)
        self.assertEqual(plan["version_code"], 1234)
        self.assertEqual(plan["steps"][0], {"op": "launch", "package": PKG})

    def test_resolve_omits_the_pin_when_the_profile_records_no_version(self):
        stored = profiles.get(self.home, PKG)
        stored.pop("version_code", None)
        profiles.put(self.home, PKG, stored)
        plan = profiles.resolve(self.home, PKG, "search", query="강남역")
        self.assertNotIn("package", plan)
        self.assertNotIn("version_code", plan)

    def test_resolve_refuses_an_explicit_version_that_disagrees(self):
        with self.assertRaises(profiles.ProfileError) as caught:
            profiles.resolve(self.home, PKG, "search", query="강남역", version_code=9999)
        self.assertIn("profile_version_changed", caught.exception.code)


class KeyAllowList(unittest.TestCase):
    """A profile must fail at save time, not at run time as INVALID_ARGUMENTS on device."""

    def setUp(self):
        self.home = Path(tempfile.mkdtemp(prefix="profiles-"))

    def _save(self, step):
        shaped = {
            "package": PKG, "version_code": 1234,
            "entry": {"deeplink": None, "fallback": "launch"},
            "anchors": {"home": {"id_suffix": "tmap_main_search_bar_layout"}},
            "routes": {"home": [step]},
            "dismiss": ["닫기"],
        }
        profiles.put(self.home, PKG, shaped)

    def test_unknown_key_is_refused(self):
        with self.assertRaises(profiles.ProfileError):
            self._save({"op": "home", "evil": "x"})

    def test_confirmation_key_cannot_be_smuggled_into_a_step(self):
        for op_keys in ({"op": "click", "id_suffix": "x", "confirmation": "payment"},
                        {"op": "home", "confirmation": {"kind": "payment"}},
                        {"op": "click", "id_suffix": "x", "approval": "yes"}):
            with self.assertRaises(profiles.ProfileError):
                self._save(op_keys)

    def test_fields_belonging_to_another_op_are_refused(self):
        for step in ({"op": "home", "index": 1},
                     {"op": "home", "timeout_ms": 900},
                     {"op": "click", "id_suffix": "x", "labels": ["닫기"]},
                     {"op": "click", "id_suffix": "x", "package": "com.evil"},
                     {"op": "back", "role": "search_field"}):
            with self.assertRaises(profiles.ProfileError):
                self._save(step)

    def test_legal_steps_still_save(self):
        for step in ({"op": "home"}, {"op": "back"},
                     {"op": "click", "id_suffix": "x", "index": 1},
                     {"op": "await", "role": "search_field", "timeout_ms": 2000},
                     {"op": "label", "name": "home"},
                     {"op": "dismiss", "labels": ["닫기"]},
                     {"op": "input", "role": "search_field", "value": "{q}"}):
            profiles.put(self.home, PKG, {
                "package": PKG, "version_code": 1234,
                "entry": {"deeplink": None, "fallback": "launch"},
                "anchors": {"home": {"id_suffix": "s"}},
                "routes": {"home": [step]}, "dismiss": ["닫기"]})

    def test_value_is_capped_at_2000_and_other_strings_at_200(self):
        self._save({"op": "input", "role": "search_field", "value": "x" * 2000})
        with self.assertRaises(profiles.ProfileError):
            self._save({"op": "input", "role": "search_field", "value": "x" * 2001})
        with self.assertRaises(profiles.ProfileError):
            self._save({"op": "click", "id_suffix": "x" * 201})

    def test_a_branch_must_carry_a_selector_not_just_a_target(self):
        with self.assertRaises(profiles.ProfileError) as caught:
            self._save({"op": "route", "to": "home", "branches": [{"to": "home"}]})
        self.assertIn("profile_bad_selector", caught.exception.code)

    def test_labels_must_be_short_non_empty_strings(self):
        for bad in ([""], ["x" * 61], [None], [1]):
            with self.assertRaises(profiles.ProfileError):
                self._save({"op": "dismiss", "labels": bad})

    def test_profile_validator_agrees_with_the_device_validator(self):
        """Both allow-lists must name the same ops with the same keys and the same minimum."""
        import re
        actions = (Path(__file__).resolve().parents[1] / "actions.py").read_text(encoding="utf-8")
        block = re.search(r"allowed = \{(.*?)\n    \}", actions, re.S).group(1)
        device, device_required = {}, {}
        for line in block.splitlines():
            line = line.strip()
            if not line or ":" not in line:
                continue
            op = line.split(":")[0].strip().strip('"')
            left, right = line.split(":", 1)[1].split("},", 1)
            device[op] = {k for k in re.findall(r'"([a-z_]+)"', left) if k != op}
            device_required[op] = {k for k in re.findall(r'"([a-z_]+)"', right.replace(")", ""))}
        self.assertEqual(set(device), set(profiles._STEP_KEYS))
        self.assertEqual(set(device_required), set(profiles._STEP_REQUIRED))
        for op, keys in device.items():
            self.assertEqual(keys, profiles._STEP_KEYS[op], op)
        for op, keys in device_required.items():
            self.assertEqual(keys, profiles._STEP_REQUIRED[op], op)

    def test_a_step_missing_a_required_field_is_refused(self):
        # Allowed-but-incomplete passed the old upper-bound check and died on the device.
        for step in ({"op": "launch"}, {"op": "input", "role": "search_field"},
                     {"op": "label"}, {"op": "route", "to": "home"},
                     {"op": "dismiss"}):
            with self.assertRaises(profiles.ProfileError) as caught:
                self._save(step)
            self.assertIn("profile_missing_field", caught.exception.code, step)

    def test_a_dismiss_label_that_agrees_to_something_is_refused(self):
        for label in ("허용", "확인", "동의", "결제하기", "구매", "삭제"):
            with self.assertRaises(profiles.ProfileError) as caught:
                self._save({"op": "dismiss", "labels": [label]})
            self.assertIn("profile_forbidden_dismiss_label", caught.exception.code)

    def test_a_closing_dismiss_label_is_still_accepted(self):
        labels = ["닫기", "나중에", "건너뛰기", "오늘은 그만보기"]
        profiles.put(self.home, PKG, {
            "package": PKG, "anchors": {"home": {"id_suffix": "s"}},
            "routes": {"home": [{"op": "dismiss", "labels": labels}]},
            "dismiss": labels})

    def test_load_revalidates_a_hand_edited_store(self):
        # Semantic damage must fail closed, not feed an unvalidated plan to the device.
        path = self.home / "phone-profiles.json"
        path.write_text(json.dumps({"schema": 1, "apps": {PKG: {
            "package": PKG, "anchors": {"home": {"id_suffix": "s"}},
            "routes": {"home": [{"op": "dismiss", "labels": ["결제"]}]},
        }}}, ensure_ascii=False), encoding="utf-8")
        with self.assertRaises(profiles.ProfileError):
            profiles.load(self.home)

    def test_load_refuses_a_profile_whose_package_disagrees_with_its_key(self):
        path = self.home / "phone-profiles.json"
        path.write_text(json.dumps({"schema": 1, "apps": {PKG: {
            "package": "com.evil", "anchors": {"home": {"id_suffix": "s"}},
            "routes": {},
        }}}), encoding="utf-8")
        with self.assertRaises(profiles.ProfileError):
            profiles.load(self.home)

    def test_put_does_not_lose_a_concurrent_app(self):
        # Two writers each doing read-modify-write must both land.
        import threading
        other = "com.other.app"
        profiles.put(self.home, PKG, {
            "package": PKG, "anchors": {"home": {"id_suffix": "s"}}, "routes": {}})
        errors = []

        def writer(pkg):
            try:
                profiles.put(self.home, pkg, {
                    "package": pkg, "anchors": {"home": {"id_suffix": "s"}}, "routes": {}})
            except Exception as exc:  # pragma: no cover
                errors.append(exc)

        threads = [threading.Thread(target=writer, args=(p,)) for p in (other, PKG)]
        for t in threads:
            t.start()
        for t in threads:
            t.join()
        self.assertEqual(errors, [])
        self.assertEqual(set(profiles.load(self.home)["apps"]), {PKG, other})

    def test_list_apps_counts_the_prefix_only_when_launch_is_used(self):
        base = {"package": PKG, "anchors": {"home": {"id_suffix": "s"}},
                "routes": {"home": [{"op": "home"}]}}
        profiles.put(self.home, PKG, dict(base, entry={"fallback": "launch"}))
        self.assertEqual(profiles.list_apps(self.home)[0]["steps"]["home"], 2)
        profiles.put(self.home, PKG, dict(base, entry={"deeplink": "x", "fallback": None}))
        self.assertEqual(profiles.list_apps(self.home)[0]["steps"]["home"], 1)


class Reliability(unittest.TestCase):
    """A profile must not be able to rot silently."""

    def setUp(self):
        self.home = Path(tempfile.mkdtemp(prefix="profiles-"))
        profiles.put(self.home, PKG, TMAP)

    def test_a_fresh_route_is_a_candidate(self):
        self.assertEqual(profiles.standing(self.home, PKG, "search"), "candidate")
        self.assertEqual(profiles.list_apps(self.home)[0]["standing"], {"search": "candidate"})

    def test_three_consecutive_successes_promote(self):
        for _ in range(3):
            profiles.record(self.home, PKG, "search", True)
        self.assertEqual(profiles.standing(self.home, PKG, "search"), "trusted")

    def test_a_failure_resets_the_streak_without_demoting(self):
        for _ in range(3):
            profiles.record(self.home, PKG, "search", True)
        stats = profiles.record(self.home, PKG, "search", False)
        self.assertEqual(stats["streak"], 0)
        # One failure is usually a popup, not a bad anchor: still usable, just unproven.
        self.assertEqual(profiles.standing(self.home, PKG, "search"), "candidate")
        self.assertEqual(stats["best"], 3)

    def test_a_mostly_failing_route_decays(self):
        for _ in range(3):
            profiles.record(self.home, PKG, "search", True)
        for _ in range(6):
            profiles.record(self.home, PKG, "search", False)
        self.assertEqual(profiles.standing(self.home, PKG, "search"), "decayed")

    def test_decay_waits_for_enough_evidence(self):
        # One failure out of three is noise; the window has not been reached yet.
        for ok in (True, False, True):
            profiles.record(self.home, PKG, "search", ok)
        self.assertNotEqual(profiles.standing(self.home, PKG, "search"), "decayed")

    def test_counters_survive_a_reload_and_stay_valid(self):
        profiles.record(self.home, PKG, "search", True)
        reloaded = profiles.get(self.home, PKG)
        self.assertEqual(reloaded["stats"]["search"]["runs"], 1)
        self.assertEqual(profiles.standing(self.home, PKG, "search"), "candidate")

    def test_recording_an_unknown_route_is_refused(self):
        with self.assertRaises(profiles.ProfileError):
            profiles.record(self.home, PKG, "navigation", True)

    def test_recording_a_non_boolean_outcome_is_refused(self):
        # A caller passing "ok" must not be counted as a success.
        with self.assertRaises(profiles.ProfileError):
            profiles.record(self.home, PKG, "search", "ok")

    def test_a_malformed_stats_block_is_refused_on_read(self):
        path = self.home / "phone-profiles.json"
        data = json.loads(path.read_text(encoding="utf-8"))
        data["apps"][PKG]["stats"] = {"search": {"runs": -1, "fails": 0, "streak": 0,
                                                 "best": 0, "last_ok": None}}
        path.write_text(json.dumps(data), encoding="utf-8")
        with self.assertRaises(profiles.ProfileError):
            profiles.load(self.home)

    def test_stats_for_an_undeclared_route_is_refused(self):
        path = self.home / "phone-profiles.json"
        data = json.loads(path.read_text(encoding="utf-8"))
        data["apps"][PKG]["stats"] = {"ghost": {"runs": 1, "fails": 0, "streak": 1,
                                                "best": 1, "last_ok": True}}
        path.write_text(json.dumps(data), encoding="utf-8")
        with self.assertRaises(profiles.ProfileError):
            profiles.load(self.home)

    def test_a_run_is_appended_to_the_device_log(self):
        profiles._append_log(self.home, {"package": PKG, "route": "search", "ok": True,
                                         "code": "ok", "elapsed_ms": 1234,
                                         "steps": [{"op": "launch", "ok": True, "ms": 20}]})
        profiles._append_log(self.home, {"package": PKG, "route": "search", "ok": False,
                                         "code": "target_not_found", "failed_op": "click"})
        lines = (self.home / "phone-plans.log").read_text(encoding="utf-8").splitlines()
        self.assertEqual(len(lines), 2)
        first = json.loads(lines[0])
        self.assertTrue(first["ok"])
        self.assertEqual(first["elapsed_ms"], 1234)
        self.assertEqual(json.loads(lines[1])["code"], "target_not_found")

    def test_the_log_rotates_instead_of_growing(self):
        with open(self.home / "phone-plans.log", "wb") as handle:
            handle.write(b"x" * (profiles._MAX_LOG_BYTES + 1))
        profiles._append_log(self.home, {"package": PKG, "route": "search", "ok": True})
        self.assertTrue((self.home / "phone-plans.log.1").exists())
        lines = (self.home / "phone-plans.log").read_text(encoding="utf-8").splitlines()
        self.assertEqual(len(lines), 1)
        self.assertTrue(json.loads(lines[0])["ok"])


if __name__ == "__main__":
    unittest.main(verbosity=2)
