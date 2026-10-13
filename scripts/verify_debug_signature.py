"""Check the APK certificate against the fixed repository debug signing key."""
import os
from pathlib import Path
import re
import subprocess

EXPECTED_DIGEST = "0575d7998df338fe231f05f3fe85b876a064305fc4db2b39ab937f474ed7fe29"
sdk = Path(os.environ["ANDROID_HOME"])
apksigner = sdk / "build-tools" / "35.0.0" / ("apksigner.bat" if os.name == "nt" else "apksigner")
result = subprocess.run(
    [str(apksigner), "verify", "--print-certs", "app/build/outputs/apk/debug/app-debug.apk"],
    check=True, capture_output=True, text=True,
)
digests = re.findall(r"Signer #\d+ certificate SHA-256 digest:\s*([0-9a-fA-F]+)", result.stdout)
if len(digests) != 1 or digests[0].lower() != EXPECTED_DIGEST:
    raise SystemExit("APK does not use the fixed repository debug certificate")
print("PASS: APK uses the fixed debug certificate " + EXPECTED_DIGEST)
