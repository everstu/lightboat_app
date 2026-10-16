"""Prevent publishing an APK whose public version differs from its release tag."""
import argparse
import json
from pathlib import Path

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--tag", required=True)
args = parser.parse_args()

metadata = json.loads(Path("app/build/outputs/apk/debug/output-metadata.json").read_text(encoding="utf-8"))
versions = {element["versionName"] for element in metadata["elements"]}
if not versions or {f"v{version}" for version in versions} != {args.tag}:
    raise SystemExit(f"Release tag {args.tag!r} does not match APK versionName: {sorted(versions)}")
print(f"PASS: release tag {args.tag} matches APK versionName")
