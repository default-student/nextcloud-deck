#!/usr/bin/env python3
"""Select published, versioned upstream releases without rolling back the baseline."""
from __future__ import annotations

import argparse
import json
import re
import sys
from functools import cmp_to_key
from pathlib import Path
from typing import Any

VERSION_RE = re.compile(
    r"^v?(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)"
    r"(?:-([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?(?:\+[0-9A-Za-z.-]+)?$"
)


def _prerelease_parts(value: str) -> tuple[tuple[int, object], ...]:
    # Upstream uses labels such as beta3.0 and rc10. Treat embedded digits
    # numerically as well as dot-separated identifiers (rc10 > rc2).
    result: list[tuple[int, object]] = []
    for identifier in value.split("."):
        for part in re.findall(r"[0-9]+|[A-Za-z-]+", identifier):
            if part.isdigit():
                result.append((0, int(part)))
            else:
                result.append((1, part.lower()))
    return tuple(result)


def parse_version(tag: str) -> tuple[tuple[int, int, int], tuple[tuple[int, object], ...] | None] | None:
    match = VERSION_RE.fullmatch(tag)
    if not match:
        return None
    core = tuple(int(match.group(i)) for i in range(1, 4))
    prerelease = _prerelease_parts(match.group(4)) if match.group(4) else None
    return core, prerelease


def compare_versions(left: str, right: str) -> int:
    a = parse_version(left)
    b = parse_version(right)
    if a is None or b is None:
        raise ValueError(f"Not a versioned release tag: {left!r}, {right!r}")
    if a[0] != b[0]:
        return (a[0] > b[0]) - (a[0] < b[0])
    ap, bp = a[1], b[1]
    if ap is None or bp is None:
        if ap is None and bp is None:
            return 0
        return 1 if ap is None else -1
    for x, y in zip(ap, bp):
        if x == y:
            continue
        # SemVer identifiers compare numeric identifiers before text. Our
        # embedded alpha/numeric split makes beta3 and rc10 intuitive too.
        return (x > y) - (x < y)
    return (len(ap) > len(bp)) - (len(ap) < len(bp))


def release_display_version(source_version: str, upstream_tag: str) -> str:
    """Use source app metadata unless the integrated upstream tag is newer."""
    if parse_version(source_version) is None:
        raise ValueError(f"Invalid app versionName: {source_version}")
    if parse_version(upstream_tag) is None:
        raise ValueError(f"Invalid upstream version tag: {upstream_tag}")
    selected = upstream_tag if compare_versions(upstream_tag, source_version) > 0 else source_version
    return selected.removeprefix("v")

def select_releases(baseline: str, candidates: list[dict[str, Any]]) -> dict[str, Any]:
    if parse_version(baseline) is None:
        raise ValueError(f"Invalid baseline release tag: {baseline}")
    eligible: dict[str, dict[str, Any]] = {}
    for candidate in candidates:
        if candidate.get("draft", False):
            continue
        tag = candidate.get("tag_name")
        if not isinstance(tag, str) or parse_version(tag) is None:
            continue
        if "nightly" in tag.lower():
            continue
        if compare_versions(tag, baseline) > 0:
            eligible[tag] = candidate
    ordered = sorted(eligible.values(), key=cmp_to_key(lambda a, b: compare_versions(a["tag_name"], b["tag_name"])))
    return {"baseline": baseline, "selected": ordered[-1] if ordered else None, "incorporated": ordered}


def aggregate_release_notes(pending_body: str, incorporated: list[dict[str, Any]]) -> str:
    """Retain pending notes and append every newly incorporated release once."""
    parts = [pending_body.strip()] if pending_body.strip() else []
    for release in incorporated:
        tag = release["tag_name"]
        body = (release.get("body") or f"No upstream release notes were provided for {tag}.").strip()
        parts.append(f"## Upstream release {tag}\n\n{body}")
    return "\n\n".join(parts) + ("\n" if parts else "")


def has_published_tag_release(releases: list[dict[str, Any]], upstream_tag: str, asset_name: str) -> bool:
    marker = re.compile(r"(?:Tracked upstream version|Upstream release): \[" + re.escape(upstream_tag) + r"\]\(")
    return any(
        not release.get("draft")
        and release.get("published_at")
        and marker.search(release.get("body") or "")
        and any(asset.get("name") == asset_name for asset in (release.get("assets") or []))
        for release in releases
    )


def unpublished_pending_notes(pending: dict[str, Any], releases: list[dict[str, Any]], asset_name: str) -> str:
    if has_published_tag_release(releases, str(pending.get("tag", "")), asset_name):
        return ""
    return str(pending.get("body", ""))



def schedule_action(event: str, selection: dict[str, Any], has_pending: bool) -> str:
    if event != "schedule":
        return "release-current-source"
    if selection.get("selected") is not None:
        return "integrate-and-release"
    return "retry-pending" if has_pending else "skip-no-change"


def find_source_release(releases: list[dict[str, Any]], source_sha: str, asset_name: str) -> dict[str, Any] | None:
    marker = f"Fork source commit: `{source_sha}`"
    matches = [release for release in releases
               if not release.get("draft") and release.get("published_at")
               and marker in (release.get("body") or "")]
    # If a duplicate exists from a previous interrupted run, prefer the one
    # that already has the artifact; otherwise retry the first matching tag.
    matches.sort(key=lambda release: any(
        asset.get("name") == asset_name for asset in (release.get("assets") or [])
    ), reverse=True)
    for release in matches:
        assets = release.get("assets") or []
        return {
            "tag_name": release.get("tag_name"),
            "asset_present": any(a.get("name") == asset_name for a in assets),
            "body": release.get("body") or "",
            "prerelease": bool(release.get("prerelease")),
        }
    return None


def next_release_versions(base_code: int, run_number: int, releases: list[dict[str, Any]], maximum: int = 2100000000) -> tuple[int, int]:
    serials = [run_number]
    existing_codes: list[int] = []
    for release in releases:
        if release.get("draft") or not release.get("published_at"):
            continue
        tag = release.get("tag_name") or ""
        match = re.search(r"-fork\.(\d+)$", tag)
        if match:
            serials.append(int(match.group(1)) + 1)
        body = release.get("body") or ""
        code_match = re.search(r"^Android versionCode: (\d+)\s*$", body, re.M)
        if code_match:
            existing_codes.append(int(code_match.group(1)))
    if existing_codes:
        serials.append(max(existing_codes) - base_code + 1)
    serial = max(serials)
    code = base_code + serial
    if serial < 1 or code > maximum:
        raise ValueError(f"Fork versionCode {code} exceeds Android limit {maximum}")
    return serial, code


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--baseline", required=True)
    parser.add_argument("--candidates", required=True, type=Path)
    args = parser.parse_args()
    try:
        candidates = json.loads(args.candidates.read_text())
        if not isinstance(candidates, list):
            raise ValueError("candidates JSON must be an array")
        result = select_releases(args.baseline, candidates)
    except (OSError, json.JSONDecodeError, ValueError) as exc:
        print(str(exc), file=sys.stderr)
        return 2
    json.dump(result, sys.stdout, ensure_ascii=False)
    print()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
