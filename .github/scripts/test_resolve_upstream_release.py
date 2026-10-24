"""Behavioral checks for fork release selection and numbering."""
from __future__ import annotations

import importlib.util
import pathlib
import unittest

MODULE_PATH = pathlib.Path(__file__).with_name("resolve_upstream_release.py")
spec = importlib.util.spec_from_file_location("release_resolver", MODULE_PATH)
resolver = importlib.util.module_from_spec(spec)
spec.loader.exec_module(resolver)


class ReleaseResolverTests(unittest.TestCase):
    def test_semver_prerelease_and_stable_order(self):
        self.assertLess(resolver.compare_versions("v15.0.0-beta3.0", "v15.0.0"), 0)
        self.assertLess(resolver.compare_versions("v15.0.0-beta3.0", "v15.0.0-beta3.1"), 0)
        self.assertLess(resolver.compare_versions("v15.0.0-rc2", "v15.0.0-rc10"), 0)

    def test_selects_all_intermediate_versions_without_rolling_back(self):
        candidates = [{"tag_name": tag} for tag in (
            "v15.0.0-beta3.1", "v15.0.0-rc2", "v15.0.0-rc10", "v15.0.0", "v14.9.9"
        )]
        selected = resolver.select_releases("v15.0.0-beta3.0", candidates)
        self.assertEqual(selected["selected"]["tag_name"], "v15.0.0")
        self.assertEqual(len(selected["incorporated"]), 4)
        self.assertIsNone(resolver.select_releases("v15.0.0", [{"tag_name": "v14.9.9"}])["selected"])

    def test_pending_notes_survive_failure_but_not_success(self):
        pending = "## Upstream release v1.2.0\n\nOriginal notes"
        next_release = [{"tag_name": "v1.3.0", "body": "New notes"}]
        failed = resolver.aggregate_release_notes(pending, next_release)
        self.assertIn("Original notes", failed)
        self.assertIn("New notes", failed)
        if "nextcloud-deck" in str(MODULE_PATH):
            asset = "nextcloud-deck-fork.apk"
            marker = "Tracked upstream version"
        else:
            asset = "lawnchair-fork.apk"
            marker = "Upstream release"
        successful = [{"draft": False, "published_at": "2026-01-01", "tag_name": "fork-v1",
                       "body": f"{marker}: [v1.2.0](https://example.invalid/tag)\nFork source commit: `abc`",
                       "assets": [{"name": asset}]}]
        pending_state = {"tag": "v1.2.0", "body": pending}
        self.assertTrue(resolver.has_published_tag_release(successful, "v1.2.0", asset))
        self.assertEqual(resolver.unpublished_pending_notes(pending_state, successful, asset), "")
        missing_asset = [{**successful[0], "assets": []}]
        self.assertEqual(resolver.unpublished_pending_notes(pending_state, missing_asset, asset), pending)
        # A failed integration retains A; a successful A starts B with only B.
        self.assertIn("Original notes", resolver.aggregate_release_notes(
            resolver.unpublished_pending_notes(pending_state, missing_asset, asset), next_release))
        self.assertNotIn("Original notes", resolver.aggregate_release_notes(
            resolver.unpublished_pending_notes(pending_state, successful, asset), next_release))

    def test_schedule_push_retry_and_no_change_paths(self):
        self.assertEqual(resolver.schedule_action("push", {"selected": None}, False), "release-current-source")
        self.assertEqual(resolver.schedule_action("schedule", {"selected": None}, False), "skip-no-change")
        self.assertEqual(resolver.schedule_action("schedule", {"selected": None}, True), "retry-pending")
        self.assertEqual(resolver.schedule_action("schedule", {"selected": {"tag_name": "v1.2.0"}}, False), "integrate-and-release")

    def test_release_dedup_ignores_drafts_and_prefers_existing_asset(self):
        releases = [
            {"draft": True, "published_at": None, "tag_name": "draft", "body": "Fork source commit: `abc`", "assets": [{"name": "app.apk"}]},
            {"draft": False, "published_at": "2026-01-01", "tag_name": "v1-fork.1", "body": "Fork source commit: `abc`", "assets": []},
            {"draft": False, "published_at": "2026-01-02", "tag_name": "v1-fork.2", "body": "Fork source commit: `abc`", "assets": [{"name": "app.apk"}]},
        ]
        self.assertEqual(resolver.find_source_release(releases, "abc", "app.apk")["tag_name"], "v1-fork.2")
        self.assertIsNone(resolver.find_source_release(releases, "other", "app.apk"))

    def test_version_code_and_release_serial_are_monotonic_and_bounded(self):
        releases = [
            {"draft": False, "published_at": "2026-01-01", "tag_name": "v1-fork.8", "body": "Android versionCode: 1008"},
            {"draft": False, "published_at": "2026-01-02", "tag_name": "v2-fork.3", "body": "Android versionCode: 1020"},
            {"draft": True, "published_at": None, "tag_name": "v3-fork.99", "body": "Android versionCode: 1099"},
        ]
        self.assertEqual(resolver.next_release_versions(1000, 4, releases), (21, 1021))
        with self.assertRaises(ValueError):
            resolver.next_release_versions(2_100_000_000, 1, [])

    def test_deck_display_version_does_not_regress_to_older_upstream_tag(self):
        if not hasattr(resolver, "release_display_version"):
            self.skipTest("This resolver does not calculate the Deck app version")
        self.assertEqual(resolver.release_display_version("1.27.0", "1.26.4"), "1.27.0")
        self.assertEqual(resolver.release_display_version("1.26.3", "1.26.4"), "1.26.4")


if __name__ == "__main__":
    unittest.main()
