#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PLATFORM_DIR="${PLATFORM_DIR:-$(cd "$SCRIPT_DIR/.." && pwd)}"
OUTPUT_DIR="${OUTPUT_DIR:-$(cd "$PLATFORM_DIR/.." && pwd)/code-zips}"

mkdir -p "$OUTPUT_DIR"

timestamp="${PACKAGE_TIMESTAMP:-$(date +%Y%m%d-%H%M%S)}"
member_admin_zip="$OUTPUT_DIR/member-admin-code-$timestamp.zip"
registration_kiosk_zip="$OUTPUT_DIR/registration-kiosk-code-$timestamp.zip"

PLATFORM_DIR="$PLATFORM_DIR" \
MEMBER_ADMIN_ZIP="$member_admin_zip" \
REGISTRATION_KIOSK_ZIP="$registration_kiosk_zip" \
python - <<'PY'
import os
from pathlib import Path
from zipfile import ZIP_DEFLATED, ZipFile

SKIP_SUFFIXES = {
    ".md",
    ".db",
    ".zip",
    ".jar",
    ".png",
    ".jpg",
    ".jpeg",
    ".gif",
    ".webp",
    ".mp3",
    ".wav",
    ".mp4",
    ".avi",
    ".mov",
}

SKIP_DIR_NAMES = {
    ".git",
    "build-resources",
    "dist",
    "node_modules",
    "release",
    "target",
    "test-results",
    "__pycache__",
}


def should_skip_file(path):
    return path.suffix.lower() in SKIP_SUFFIXES


def is_under_skipped_directory(path, repo_dir):
    relative = path.relative_to(repo_dir)
    return any(part in SKIP_DIR_NAMES for part in relative.parts[:-1])


def add_path(zip_file, repo_dir, source, archive_root):
    path = repo_dir / source
    if not path.exists():
        return

    if path.is_file():
        if not should_skip_file(path):
            zip_file.write(path, Path(archive_root) / source)
        return

    for child in sorted(path.rglob("*")):
        if child.is_file() and not is_under_skipped_directory(child, repo_dir) and not should_skip_file(child):
            zip_file.write(child, Path(archive_root) / child.relative_to(repo_dir))


def build_zip(repo_dir, output_path, archive_root, includes):
    output_path = Path(output_path)
    if output_path.exists():
        output_path.unlink()

    with ZipFile(output_path, "w", ZIP_DEFLATED) as zip_file:
        for source in includes:
            add_path(zip_file, repo_dir, source, archive_root)


platform_dir = Path(os.environ["PLATFORM_DIR"]).resolve()
common_includes = [
    ".npmrc",
    "package.json",
    "pnpm-lock.yaml",
    "pnpm-workspace.yaml",
    "tsconfig.json",
    "i18n",
    "packages",
    "scripts",
    "desktop/shared",
]
member_admin_includes = common_includes + [
    "apps/member-admin",
    "desktop/member-admin",
    "desktop/electron-builder.member-admin.json",
    "server/pom.xml",
    "server/src",
]
registration_kiosk_includes = common_includes + [
    "apps/registration-kiosk",
    "desktop/registration-kiosk",
    "desktop/electron-builder.registration-kiosk.json",
]

build_zip(
    platform_dir,
    os.environ["MEMBER_ADMIN_ZIP"],
    "ledGame-platform",
    member_admin_includes,
)
build_zip(
    platform_dir,
    os.environ["REGISTRATION_KIOSK_ZIP"],
    "ledGame-platform",
    registration_kiosk_includes,
)

print(f"Member admin code zip:       {os.environ['MEMBER_ADMIN_ZIP']}")
print(f"Registration kiosk code zip: {os.environ['REGISTRATION_KIOSK_ZIP']}")
PY

echo
echo "Included common paths: root package configs, i18n, packages, scripts, desktop/shared"
echo "Included member-admin paths: apps/member-admin desktop/member-admin server/pom.xml server/src"
echo "Included registration paths: apps/registration-kiosk desktop/registration-kiosk"
echo "Excluded generated/runtime/assets/docs by default: .git node_modules dist target build-resources release databases media markdown docs"
