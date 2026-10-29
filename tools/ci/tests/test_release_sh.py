"""Tests for the pure helpers in tools/release.sh and its CI guard.

release.sh is sourced (its main only runs when executed), so each helper is exercised in isolation.
No keystore, SDK, build or network is involved.
"""

from __future__ import annotations

import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[2] / "release.sh"
EXPECTED = "94:F2:DD:5F:DB:02:AD:ED:46:31:CB:0A:09:4A:0B:EE:81:05:32:C1:C8:AE:22:23:C1:7D:1C:7A:A4:22:7A:A0"
EXPECTED_HEX = EXPECTED.replace(":", "").lower()

APKSIGNER_OK = f"""Verifies
Verified using v1 scheme (JAR signing): false
Verified using v2 scheme (APK Signature Scheme v2): true
Verified using v3 scheme (APK Signature Scheme v3): true
Verified using v3.1 scheme (APK Signature Scheme v3.1): false
Verified using v4 scheme (APK Signature Scheme v4): false
Verified for SourceStamp: false
Number of signers: 1
Signer #1 certificate DN: CN=RideMate, O=RideMate, C=IN
Signer #1 certificate SHA-256 digest: {EXPECTED_HEX}
Signer #1 certificate SHA-1 digest: 0123456789abcdef0123456789abcdef01234567
Signer #1 certificate MD5 digest: 0123456789abcdef0123456789abcdef
"""

BADGING_RELEASE = """package: name='in.ridemate.app' versionCode='7' versionName='1.0.3-hackathon' platformBuildVersionName='15' compileSdkVersion='36'
sdkVersion:'29'
targetSdkVersion:'35'
uses-permission: name='android.permission.SYSTEM_ALERT_WINDOW'
application-label:'RideMate'
"""

XMLTREE_SAFE = """N: android=http://schemas.android.com/apk/res/android (line=2)
  E: manifest (line=2)
    E: application (line=10)
      A: http://schemas.android.com/apk/res/android:debuggable(0x0101000f)=false
      E: service (line=20)
        A: http://schemas.android.com/apk/res/android:permission(0x01010006)="android.permission.BIND_NOTIFICATION_LISTENER_SERVICE"
"""


def call(func: str, *args: str) -> subprocess.CompletedProcess[str]:
    script = f'source "{SCRIPT}"; {func} "$@"'
    return subprocess.run(["bash", "-c", script, "bash", *args], capture_output=True, text=True, check=False)


class ReleaseHelpersTest(unittest.TestCase):
    def test_normalize_and_format_fingerprint(self) -> None:
        self.assertEqual(call("normalize_hex", EXPECTED).stdout, EXPECTED_HEX)
        self.assertEqual(call("format_fingerprint", EXPECTED_HEX).stdout, EXPECTED)

    def test_signer_digests(self) -> None:
        out = call("signer_sha256_digests", APKSIGNER_OK).stdout.split()
        self.assertEqual(out, [EXPECTED_HEX])
        two = APKSIGNER_OK + "Signer #2 certificate SHA-256 digest: " + "ab" * 32 + "\n"
        self.assertEqual(len(call("signer_sha256_digests", two).stdout.split()), 2)

    def test_scheme_check(self) -> None:
        self.assertEqual(call("apksigner_scheme_ok", APKSIGNER_OK).returncode, 0)
        v1_only = APKSIGNER_OK.replace("v2): true", "v2): false").replace("v3): true", "v3): false")
        self.assertNotEqual(call("apksigner_scheme_ok", v1_only).returncode, 0)

    def test_badging(self) -> None:
        self.assertEqual(call("badging_value", BADGING_RELEASE, "versionName").stdout.strip(), "1.0.3-hackathon")
        self.assertEqual(call("badging_value", BADGING_RELEASE, "versionCode").stdout.strip(), "7")
        self.assertEqual(call("badging_value", BADGING_RELEASE, "name").stdout.strip(), "in.ridemate.app")
        self.assertNotEqual(call("badging_is_debuggable", BADGING_RELEASE).returncode, 0)
        self.assertEqual(call("badging_is_debuggable", BADGING_RELEASE + "application-debuggable\n").returncode, 0)
        self.assertNotEqual(call("badging_is_test_only", BADGING_RELEASE).returncode, 0)
        self.assertEqual(call("badging_is_test_only", BADGING_RELEASE + "testOnly='-1'\n").returncode, 0)

    def test_manifest_debuggable(self) -> None:
        self.assertNotEqual(call("manifest_is_debuggable", XMLTREE_SAFE).returncode, 0)
        for variant in ("android:debuggable(0x0101000f)=true", "android:debuggable(0x0101000f)=(type 0x12)0xffffffff",
                        'android:debuggable="true"'):
            with self.subTest(variant=variant):
                self.assertEqual(call("manifest_is_debuggable", f"A: {variant}\n").returncode, 0)

    def test_manifest_accessibility(self) -> None:
        self.assertNotEqual(call("manifest_has_accessibility_service", XMLTREE_SAFE).returncode, 0)
        for line in ('A: android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE"',
                     'A: android:name="android.accessibilityservice.AccessibilityService"',
                     'A: android:name="android.accessibilityservice"'):
            with self.subTest(line=line):
                self.assertEqual(call("manifest_has_accessibility_service", XMLTREE_SAFE + line).returncode, 0)

    def test_newest_build_tools_prefers_stable(self) -> None:
        sdk = Path(tempfile.mkdtemp(prefix="sdk-"))
        try:
            for v in ("34.0.0", "35.0.1", "36.0.0-rc5", "35.0.0", "9.0.0"):
                (sdk / "build-tools" / v).mkdir(parents=True)
            self.assertEqual(call("newest_build_tools", str(sdk)).stdout, f"{sdk}/build-tools/35.0.1")
        finally:
            shutil.rmtree(sdk)

    def test_refuses_to_run_in_ci(self) -> None:
        env = {**os.environ, "CI": "true"}
        res = subprocess.run(["bash", str(SCRIPT)], env=env, capture_output=True, text=True, check=False)
        self.assertEqual(res.returncode, 1)
        self.assertIn("local-only", res.stderr)

    def test_help_mentions_go_ahead(self) -> None:
        res = subprocess.run(["bash", str(SCRIPT), "--help"], capture_output=True, text=True, check=False)
        self.assertEqual(res.returncode, 0)
        self.assertIn("GO-AHEAD", res.stdout)
        self.assertIn("--skip-build", res.stdout)
        self.assertNotIn("set -euo", res.stdout, "help prints only the comment header, no code")
        self.assertNotIn("readonly", res.stdout)

    def test_stamp_matches(self) -> None:
        sha = "ab" * 32
        stamp = f"commit=c0ffee\ndirty=false\napk_sha256={sha}\n"
        self.assertEqual(call("stamp_value", stamp, "apk_sha256").stdout.strip(), sha)
        self.assertEqual(call("stamp_matches", stamp, "c0ffee", sha).returncode, 0)
        for label, args in (("other commit", (stamp, "beef", sha)),
                            ("other APK", (stamp, "c0ffee", "cd" * 32)),
                            ("dirty build", (stamp.replace("dirty=false", "dirty=true"), "c0ffee", sha)),
                            ("no stamp", ("", "c0ffee", sha)),
                            ("unknown commit", (stamp.replace("c0ffee", "unknown"), "unknown", sha))):
            with self.subTest(label):
                self.assertNotEqual(call("stamp_matches", *args).returncode, 0)


class ReleaseFlowTest(unittest.TestCase):
    """End to end against a throw-away repo: fake gradlew, fake SDK (apksigner/aapt2 print canned output)."""

    PASSWORD = "not-a-real-password-123"

    def setUp(self) -> None:
        self.tmp = Path(tempfile.mkdtemp(prefix="release-flow-"))
        self.repo = self.tmp / "repo"
        self.home = self.tmp / "home"
        self.sdk = self.tmp / "sdk"
        (self.repo / "tools").mkdir(parents=True)
        shutil.copy(SCRIPT, self.repo / "tools" / "release.sh")
        (self.repo / ".gitignore").write_text("build/\ndist/\n", encoding="utf-8")
        gradlew = self.repo / "android" / "gradlew"
        gradlew.parent.mkdir(parents=True)
        gradlew.write_text(
            "#!/usr/bin/env bash\nset -e\nout=app/build/outputs/apk/release\nmkdir -p \"$out\"\n"
            "printf 'signed-apk-%s' \"$(date +%s%N)\" > \"$out/app-release.apk\"\n", encoding="utf-8")
        gradlew.chmod(0o755)
        (self.home / ".ridemate").mkdir(parents=True)
        (self.home / ".ridemate" / "release.jks").write_bytes(b"fake keystore")
        tools = self.sdk / "build-tools" / "35.0.0"
        tools.mkdir(parents=True)
        self.canned = self.tmp / "canned"
        self.canned.mkdir()
        self.set_canned(APKSIGNER_OK, BADGING_RELEASE, XMLTREE_SAFE)
        for name, body in (("apksigner", 'cat "$CANNED/apksigner.txt"\n'),
                           ("aapt2", 'case "$2" in badging) cat "$CANNED/badging.txt" ;; '
                                     'xmltree) cat "$CANNED/xmltree.txt" ;; *) exit 2 ;; esac\n')):
            (tools / name).write_text("#!/usr/bin/env bash\n" + body, encoding="utf-8")
            (tools / name).chmod(0o755)
        self.env = {k: v for k, v in os.environ.items()
                    if k not in ("CI", "GITHUB_ACTIONS", "ANDROID_SDK_ROOT", "RIDEMATE_KEYSTORE_PASSWORD")}
        self.env.update({"HOME": str(self.home), "ANDROID_HOME": str(self.sdk), "CANNED": str(self.canned),
                         "GIT_CONFIG_GLOBAL": os.devnull, "GIT_CONFIG_SYSTEM": os.devnull,
                         "RIDEMATE_KEYSTORE_PASSWORD": self.PASSWORD})
        self.git("init", "-q")
        self.git("add", "-A")
        self.git("-c", "user.name=t", "-c", "user.email=t@example.invalid", "commit", "-q", "-m", "init")

    def tearDown(self) -> None:
        shutil.rmtree(self.tmp, ignore_errors=True)

    def set_canned(self, apksigner: str, badging: str, xmltree: str) -> None:
        for name, text in (("apksigner", apksigner), ("badging", badging), ("xmltree", xmltree)):
            (self.canned / f"{name}.txt").write_text(text, encoding="utf-8")

    def git(self, *args: str) -> str:
        return subprocess.run(["git", *args], cwd=self.repo, env=self.env, check=True, capture_output=True,
                              text=True).stdout.strip()

    def release(self, *args: str) -> subprocess.CompletedProcess[str]:
        return subprocess.run(["bash", str(self.repo / "tools" / "release.sh"), *args], cwd=self.repo,
                              env=self.env, capture_output=True, text=True, check=False, stdin=subprocess.DEVNULL)

    def test_full_build_writes_verified_dist_and_only_prints_the_publish_command(self) -> None:
        res = self.release()
        self.assertEqual(res.returncode, 0, res.stdout + res.stderr)
        dist = self.repo / "dist"
        apk = dist / "RideMate-1.0.3-hackathon.apk"
        self.assertTrue(apk.is_file())
        # The README and runbooks tell users to download and check SHA256SUMS.txt: the release must ship that name.
        self.assertTrue((dist / "SHA256SUMS.txt").is_file())
        self.assertFalse((dist / "SHA256SUMS").exists())
        check = subprocess.run(["shasum", "-a", "256", "-c", "SHA256SUMS.txt"] if shutil.which("shasum")
                               else ["sha256sum", "-c", "SHA256SUMS.txt"], cwd=dist, capture_output=True, text=True)
        self.assertEqual(check.returncode, 0, check.stdout + check.stderr)
        notes = (dist / "RELEASE_NOTES.md").read_text(encoding="utf-8")
        self.assertIn(self.git("rev-parse", "HEAD"), notes)
        self.assertIn(EXPECTED, notes)
        self.assertIn("sha256sum -c SHA256SUMS.txt", notes)
        self.assertIn("gh release create v1.0.3-hackathon", res.stdout)
        self.assertIn("dist/SHA256SUMS.txt", res.stdout)
        self.assertIn("GO-AHEAD", res.stdout)
        self.assertNotIn(self.PASSWORD, res.stdout + res.stderr)
        stamp = (self.repo / "android/app/build/outputs/apk/release/ridemate-release.stamp").read_text()
        self.assertIn("dirty=false", stamp)
        # The stamped APK may be re-verified and packaged without rebuilding.
        res = self.release("--skip-build")
        self.assertEqual(res.returncode, 0, res.stdout + res.stderr)
        self.assertNotIn("WARNING", res.stdout)

    def test_skip_build_publish_refuses_an_apk_this_script_did_not_build_from_head(self) -> None:
        self.assertEqual(self.release().returncode, 0)
        (self.repo / "android/app/build/outputs/apk/release/app-release.apk").write_bytes(b"swapped")
        res = self.release("--skip-build", "--publish", "--yes")
        self.assertEqual(res.returncode, 1)
        self.assertIn("was not built by release.sh", res.stderr)
        res = self.release("--skip-build")
        self.assertEqual(res.returncode, 0, res.stdout + res.stderr)
        self.assertIn("WARNING", res.stdout)
        self.assertIn("built from commit `unknown`", (self.repo / "dist/RELEASE_NOTES.md").read_text())

    def test_untracked_android_files_make_the_tree_dirty(self) -> None:
        asset = self.repo / "android/app/src/main/assets/new.json"
        asset.parent.mkdir(parents=True)
        asset.write_text("{}", encoding="utf-8")
        res = self.release()
        self.assertEqual(res.returncode, 1)
        self.assertIn("uncommitted changes", res.stderr)
        res = self.release("--allow-dirty", "--publish", "--yes")
        self.assertEqual(res.returncode, 1)
        self.assertIn("refusing to publish", res.stderr)

    def test_wrong_certificate_fails(self) -> None:
        self.set_canned(APKSIGNER_OK.replace(EXPECTED_HEX, "ab" * 32), BADGING_RELEASE, XMLTREE_SAFE)
        res = self.release()
        self.assertEqual(res.returncode, 1)
        self.assertIn("does not match the published", res.stderr)
        self.assertFalse((self.repo / "dist").exists(), "nothing is packaged when verification fails")

    def test_debuggable_or_accessibility_manifest_fails(self) -> None:
        for label, badging, xmltree, message in (
                ("debuggable", BADGING_RELEASE + "application-debuggable\n", XMLTREE_SAFE, "debuggable"),
                ("accessibility", BADGING_RELEASE,
                 XMLTREE_SAFE + 'A: android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE"\n',
                 "AccessibilityService")):
            with self.subTest(label):
                self.set_canned(APKSIGNER_OK, badging, xmltree)
                res = self.release()
                self.assertEqual(res.returncode, 1)
                self.assertIn(message, res.stderr)

    def test_missing_password_fails_before_building(self) -> None:
        del self.env["RIDEMATE_KEYSTORE_PASSWORD"]
        res = self.release()
        self.assertEqual(res.returncode, 1)
        self.assertIn("RIDEMATE_KEYSTORE_PASSWORD is not set", res.stderr)
        self.assertFalse((self.repo / "android/app/build").exists())

    def test_yes_requires_publish(self) -> None:
        env = {k: v for k, v in os.environ.items() if k not in ("CI", "GITHUB_ACTIONS")}
        res = subprocess.run(["bash", str(SCRIPT), "--yes"], env=env, capture_output=True, text=True, check=False)
        self.assertEqual(res.returncode, 1)
        self.assertIn("--yes only makes sense with --publish", res.stderr)


if __name__ == "__main__":
    unittest.main()
