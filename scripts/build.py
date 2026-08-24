#!/usr/bin/env python3
"""Assemble and build OpenFusion Android from the pinned Winlator source."""

from __future__ import annotations

import argparse
import os
from pathlib import Path
import shutil
import stat
import subprocess
import sys


REPOSITORY_ROOT = Path(__file__).resolve().parents[1]
WINLATOR_REPOSITORY = "https://github.com/brunodev85/winlator-app.git"
WINLATOR_COMMIT = "4f55d117fff1542944e5b91f433470445160ce08"
PATCH = REPOSITORY_ROOT / "patches" / "winlator-4f55d11.patch"
OVERLAY = REPOSITORY_ROOT / "android-overlay"
MANAGED_MARKER = ".openfusion-android-build"


def run(command: list[str], *, cwd: Path | None = None) -> None:
    location = f" (in {cwd})" if cwd else ""
    print(f"+ {' '.join(command)}{location}", flush=True)
    subprocess.run(command, cwd=cwd, check=True)


def remove_readonly_file(function, path: str, _error) -> None:
    """Allow a managed Git checkout to be replaced on Windows."""
    os.chmod(path, stat.S_IWRITE)
    function(path)


def prepare_managed_directory(work_directory: Path) -> Path:
    work_directory = work_directory.resolve()
    source_directory = work_directory / "winlator-app"
    marker = work_directory / MANAGED_MARKER

    if source_directory.exists():
        if not marker.is_file():
            raise RuntimeError(
                f"Refusing to replace {source_directory}: {marker.name} is missing. "
                "Choose an empty --work-dir or remove it manually."
            )
        shutil.rmtree(source_directory, onerror=remove_readonly_file)

    work_directory.mkdir(parents=True, exist_ok=True)
    marker.write_text(
        "This directory is managed by scripts/build.py.\n",
        encoding="utf-8",
    )
    return source_directory


def assemble_source(source_directory: Path) -> None:
    if not PATCH.is_file():
        raise FileNotFoundError(f"Missing public Winlator patch: {PATCH}")
    if not OVERLAY.is_dir():
        raise FileNotFoundError(f"Missing public Android overlay: {OVERLAY}")

    run(["git", "init", str(source_directory)])
    run(["git", "remote", "add", "origin", WINLATOR_REPOSITORY], cwd=source_directory)
    run(["git", "fetch", "--depth=1", "origin", WINLATOR_COMMIT], cwd=source_directory)
    run(["git", "checkout", "--detach", "FETCH_HEAD"], cwd=source_directory)

    actual_commit = subprocess.check_output(
        ["git", "rev-parse", "HEAD"], cwd=source_directory, text=True
    ).strip()
    if actual_commit != WINLATOR_COMMIT:
        raise RuntimeError(f"Expected Winlator {WINLATOR_COMMIT}, received {actual_commit}")

    patch_from_source = Path(os.path.relpath(PATCH, source_directory))
    run(["git", "apply", "--recount", str(patch_from_source)], cwd=source_directory)
    shutil.copytree(OVERLAY, source_directory / "app", dirs_exist_ok=True)


def build_android(source_directory: Path, gradle_task: str) -> list[Path]:
    if os.name == "nt":
        wrapper = source_directory / "gradlew.bat"
        command = [str(wrapper), "--no-daemon", "--stacktrace", gradle_task]
    else:
        wrapper = source_directory / "gradlew"
        wrapper.chmod(wrapper.stat().st_mode | 0o111)
        command = [str(wrapper), "--no-daemon", "--stacktrace", gradle_task]

    run(command, cwd=source_directory)
    apks = sorted((source_directory / "app" / "build" / "outputs" / "apk").rglob("*.apk"))
    if not apks:
        raise RuntimeError("Gradle completed without producing an APK")
    return apks


def parse_arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "Fetch the pinned Winlator revision, apply the public patch and overlay, "
            "then invoke Gradle using the same process as GitHub Actions."
        )
    )
    parser.add_argument(
        "--work-dir",
        type=Path,
        default=REPOSITORY_ROOT / ".build",
        help="Managed build directory (default: .build)",
    )
    parser.add_argument(
        "--gradle-task",
        default=":app:assembleDebug",
        help="Gradle task to run (default: :app:assembleDebug)",
    )
    parser.add_argument(
        "--prepare-only",
        action="store_true",
        help="Assemble the patched source tree without invoking Gradle",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_arguments()
    source_directory = prepare_managed_directory(args.work_dir)
    assemble_source(source_directory)

    print(f"Assembled source: {source_directory}", flush=True)
    if args.prepare_only:
        return 0

    apks = build_android(source_directory, args.gradle_task)
    for apk in apks:
        print(f"Built APK: {apk}", flush=True)
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, RuntimeError, subprocess.CalledProcessError) as error:
        print(f"BUILD FAILED: {error}", file=sys.stderr)
        raise SystemExit(1)
