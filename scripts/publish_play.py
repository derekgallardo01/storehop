#!/usr/bin/env python3
"""
Automated Google Play Console AAB Publisher for StoreHop (`com.storehop.app`).

Uploads the signed release bundle (`app/build/outputs/bundle/release/app-release.aab`),
attaches release notes (from `--notes` or the top release section in `CHANGELOG.md`),
assigns the release to the target track (`production` by default), and commits the edit
via the Google Play Android Developer API v3.

Usage:
    python scripts/publish_play.py
    python scripts/publish_play.py --build
    python scripts/publish_play.py --track internal --notes "Custom release notes"
"""

from __future__ import annotations

import argparse
import os
import re
import subprocess
import sys
from pathlib import Path

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")
if hasattr(sys.stderr, "reconfigure"):
    sys.stderr.reconfigure(encoding="utf-8")

from google.oauth2 import service_account
from googleapiclient.discovery import build
from googleapiclient.errors import HttpError
from googleapiclient.http import MediaFileUpload

PACKAGE_NAME = "com.storehop.app"
SCOPES = ["https://www.googleapis.com/auth/androidpublisher"]
REPO_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_AAB_PATH = REPO_ROOT / "app" / "build" / "outputs" / "bundle" / "release" / "app-release.aab"
BUILD_GRADLE_PATH = REPO_ROOT / "app" / "build.gradle.kts"
CHANGELOG_PATH = REPO_ROOT / "CHANGELOG.md"

DEFAULT_CREDENTIAL_CANDIDATES = [
    REPO_ROOT / ".secrets" / "play-credentials.json",
    REPO_ROOT / "play-service-account.json",
    Path(r"I:\Projects\music-genie\.secrets\credentials.json"),
]


def resolve_credentials_path(explicit_path: str | None) -> Path:
    if explicit_path:
        p = Path(explicit_path).expanduser().resolve()
        if not p.exists():
            raise FileNotFoundError(f"Credentials file not found at: {p}")
        return p

    env_path = os.environ.get("PLAY_CREDENTIALS_PATH")
    if env_path:
        p = Path(env_path).expanduser().resolve()
        if not p.exists():
            raise FileNotFoundError(f"PLAY_CREDENTIALS_PATH does not exist: {p}")
        return p

    for candidate in DEFAULT_CREDENTIAL_CANDIDATES:
        if candidate.exists():
            return candidate

    raise FileNotFoundError(
        "No Play Console service account JSON found. Set PLAY_CREDENTIALS_PATH or place "
        "credentials at .secrets/play-credentials.json."
    )


def parse_gradle_version(gradle_file: Path) -> tuple[int, str]:
    content = gradle_file.read_text(encoding="utf-8")
    code_match = re.search(r"versionCode\s*=\s*(\d+)", content)
    name_match = re.search(r'versionName\s*=\s*"([^"]+)"', content)
    if not code_match or not name_match:
        raise ValueError(f"Could not parse versionCode / versionName from {gradle_file}")
    return int(code_match.group(1)), name_match.group(1)


def extract_latest_changelog_notes(changelog_file: Path, max_chars: int = 490) -> str:
    """Extract bullet points from the topmost release section in CHANGELOG.md."""
    if not changelog_file.exists():
        return "Bug fixes and improvements."

    content = changelog_file.read_text(encoding="utf-8")
    sections = re.split(r"^## \[", content, flags=re.MULTILINE)
    if len(sections) < 2:
        return "Bug fixes and improvements."

    latest_section = sections[1]
    # Strip the header line and the ### Versions subsection
    latest_body = "\n".join(latest_section.splitlines()[1:])
    latest_body = re.split(r"^### Versions", latest_body, flags=re.MULTILINE)[0]

    bullets: list[str] = []
    for line in latest_body.splitlines():
        stripped = line.strip()
        if stripped.startswith("- "):
            # Remove markdown bold/code formatting for clean plain-text Play release notes
            clean = re.sub(r"\*\*([^*]+)\*\*", r"\1", stripped[2:])
            clean = clean.replace("`", "")
            bullets.append(f"• {clean}")

    if not bullets:
        return "Bug fixes and improvements."

    joined = "\n".join(bullets)
    if len(joined) <= max_chars:
        return joined

    # Keep complete bullets up to max_chars
    kept: list[str] = []
    current_len = 0
    for b in bullets:
        addition = len(b) + (1 if kept else 0)
        if current_len + addition > max_chars:
            break
        kept.append(b)
        current_len += addition

    return "\n".join(kept) if kept else joined[:max_chars]


def run_gradle_bundle() -> None:
    gradle_cmd = str(REPO_ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew"))
    print(f"[*] Building signed release AAB via {gradle_cmd} :app:bundleRelease ...")
    subprocess.run([gradle_cmd, ":app:bundleRelease"], cwd=str(REPO_ROOT), check=True)


def publish_bundle(
    aab_path: Path,
    credentials_path: Path,
    track: str,
    status: str,
    release_notes: str,
    release_name: str,
) -> None:
    if not aab_path.exists():
        raise FileNotFoundError(
            f"AAB not found at {aab_path}. Run with --build or run './gradlew :app:bundleRelease' first."
        )

    size_mb = aab_path.stat().st_size / (1024 * 1024)
    print(f"[*] Package:      {PACKAGE_NAME}")
    print(f"[*] Artifact:     {aab_path} ({size_mb:.2f} MB)")
    print(f"[*] Release name: {release_name}")
    print(f"[*] Target track: {track} (status={status})")
    print(f"[*] Credentials:  {credentials_path}")
    print(f"[*] Release notes (en-US):\n{release_notes}\n")

    creds = service_account.Credentials.from_service_account_file(
        str(credentials_path), scopes=SCOPES
    )
    service = build("androidpublisher", "v3", credentials=creds)

    edit = service.edits().insert(body={}, packageName=PACKAGE_NAME).execute()
    edit_id = edit["id"]
    print(f"[*] Created Play Console edit session: {edit_id}")

    try:
        media = MediaFileUpload(
            str(aab_path),
            mimetype="application/octet-stream",
            resumable=True,
        )
        print("[*] Uploading AAB bundle to Google Play ...")
        bundle_response = (
            service.edits()
            .bundles()
            .upload(packageName=PACKAGE_NAME, editId=edit_id, media_body=media)
            .execute()
        )
        version_code = bundle_response["versionCode"]
        sha256 = bundle_response.get("sha256", "")
        print(f"[+] Uploaded bundle versionCode={version_code} (sha256={sha256[:16]}...)")

        release_body = {
            "name": release_name,
            "versionCodes": [str(version_code)],
            "status": status,
            "releaseNotes": [
                {
                    "language": "en-US",
                    "text": release_notes[:500],
                }
            ],
        }

        print(f"[*] Updating track '{track}' ...")
        service.edits().tracks().update(
            packageName=PACKAGE_NAME,
            editId=edit_id,
            track=track,
            body={"track": track, "releases": [release_body]},
        ).execute()

        print("[*] Committing edit to Google Play Console ...")
        commit_resp = (
            service.edits()
            .commit(packageName=PACKAGE_NAME, editId=edit_id)
            .execute()
        )
        print(
            f"[+] SUCCESS! Published {release_name} (versionCode {version_code}) "
            f"to '{track}' track (edit {commit_resp['id']})."
        )
    except HttpError as err:
        print(f"[!] Play API error: {err}", file=sys.stderr)
        try:
            service.edits().delete(packageName=PACKAGE_NAME, editId=edit_id).execute()
        except Exception:
            pass
        sys.exit(1)


def main() -> None:
    parser = argparse.ArgumentParser(description="Publish StoreHop AAB to Google Play Console.")
    parser.add_argument(
        "--track",
        default="production",
        help="Play track to publish to (default: production; options: internal, alpha, beta, production)",
    )
    parser.add_argument(
        "--status",
        default="completed",
        choices=["completed", "draft", "inProgress", "halted"],
        help="Release status on the track (default: completed)",
    )
    parser.add_argument(
        "--build",
        action="store_true",
        help="Run './gradlew :app:bundleRelease' before uploading",
    )
    parser.add_argument(
        "--aab",
        default=str(DEFAULT_AAB_PATH),
        help="Path to the signed .aab file",
    )
    parser.add_argument(
        "--notes",
        default=None,
        help="Explicit release notes for en-US (defaults to latest section in CHANGELOG.md)",
    )
    parser.add_argument(
        "--credentials",
        default=None,
        help="Path to service account JSON (or set PLAY_CREDENTIALS_PATH)",
    )
    args = parser.parse_args()

    if args.build:
        run_gradle_bundle()

    version_code, version_name = parse_gradle_version(BUILD_GRADLE_PATH)
    release_name = f"{version_code} ({version_name})"
    notes = args.notes.strip() if args.notes else extract_latest_changelog_notes(CHANGELOG_PATH)
    credentials_path = resolve_credentials_path(args.credentials)

    publish_bundle(
        aab_path=Path(args.aab).resolve(),
        credentials_path=credentials_path,
        track=args.track,
        status=args.status,
        release_notes=notes,
        release_name=release_name,
    )


if __name__ == "__main__":
    main()
