"""Tests for tools/ci/check_no_secrets.sh, run against throw-away git repositories.

Secret-looking values are assembled at runtime so this file itself never contains one.
Run: python3 -m unittest discover -s tools/ci/tests  (or pytest tools/ci/tests)
"""

from __future__ import annotations

import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[1] / "check_no_secrets.sh"
BEGIN = "-----" + "BEGIN"


def fake_token(prefix: str) -> str:
    return prefix + "-" + "Ab3dE5gH7jK9mN1pQ2rS"


class CheckNoSecretsTest(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = Path(tempfile.mkdtemp(prefix="secret-scan-"))
        env = {**os.environ, "GIT_CONFIG_GLOBAL": os.devnull, "GIT_CONFIG_SYSTEM": os.devnull}
        env.pop("GITHUB_ACTIONS", None)
        self.env = env
        self.git("init", "-q")
        self.write("README.md", "hello\n")
        self.git("add", "-A")

    def tearDown(self) -> None:
        shutil.rmtree(self.tmp, ignore_errors=True)

    def git(self, *args: str) -> None:
        subprocess.run(["git", *args], cwd=self.tmp, env=self.env, check=True, capture_output=True)

    def write(self, rel: str, text: str) -> None:
        path = self.tmp / rel
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")

    def scan(self, *args: str) -> subprocess.CompletedProcess[str]:
        return subprocess.run(["bash", str(SCRIPT), "--root", str(self.tmp), *args], env=self.env,
                              capture_output=True, text=True, check=False)

    def add_and_scan(self, rel: str, text: str) -> subprocess.CompletedProcess[str]:
        self.write(rel, text)
        self.git("add", "-A")
        return self.scan()

    # ---- clean cases -------------------------------------------------------------------------------------

    def test_clean_repo_passes(self) -> None:
        res = self.scan()
        self.assertEqual(res.returncode, 0, res.stdout + res.stderr)
        self.assertIn("OK", res.stdout)

    def test_env_example_and_prose_are_allowed(self) -> None:
        self.write(".env.example", "MODAL_TOKEN=\n")
        self.write("docs/a.md", "api_key = wk-<id>.ws-<secret>; a ws-connection-handler; as-IN locale\n")
        self.git("add", "-A")
        res = self.scan()
        self.assertEqual(res.returncode, 0, res.stdout + res.stderr)

    def test_inline_allow_marker(self) -> None:
        res = self.add_and_scan("tests/x.py", f'TOKEN = "{fake_token("wk")}"  # secret-scan: allow\n')
        self.assertEqual(res.returncode, 0, res.stdout + res.stderr)

    def test_allowlist_file(self) -> None:
        self.write("tools/ci/secret_scan_allowlist.txt", "# fixtures\n^fixtures/fake_tokens\\.txt$\n")
        res = self.add_and_scan("fixtures/fake_tokens.txt", fake_token("ws") + "\n")
        self.assertEqual(res.returncode, 0, res.stdout + res.stderr)

    def test_untracked_files_are_ignored_by_default(self) -> None:
        self.write(".env", "SECRET=1\n")
        res = self.scan()
        self.assertEqual(res.returncode, 0, res.stdout + res.stderr)
        res = self.scan("--include-untracked")
        self.assertEqual(res.returncode, 1)

    def test_gitignored_files_are_never_scanned(self) -> None:
        self.write(".gitignore", ".env\n")
        self.write(".env", "SECRET=1\n")
        self.git("add", "-A")
        res = self.scan("--include-untracked")
        self.assertEqual(res.returncode, 0, res.stdout + res.stderr)

    def test_pre_push_positional_args_are_ignored(self) -> None:
        res = self.scan("origin", "https://example.invalid/repo.git")
        self.assertEqual(res.returncode, 0, res.stdout + res.stderr)

    # ---- forbidden paths ---------------------------------------------------------------------------------

    def test_forbidden_paths(self) -> None:
        for rel in (".env", "backend/.env.production", "release.jks", "app/upload.keystore",
                    "service-account-ops.json", "ops-sa-key.json", "keys/web.json", "golden/labels.json",
                    "eval/private/a.png", "eval/golden/labels.json", "eval/golden/images/x.png",
                    "ridemate-golden/generate.py"):
            with self.subTest(rel=rel):
                res = self.add_and_scan(rel, "x\n")
                self.assertEqual(res.returncode, 1, rel)
                self.assertIn(rel, res.stdout)
                self.git("rm", "-q", "--cached", rel)
                (self.tmp / rel).unlink()

    def test_golden_tooling_code_is_allowed(self) -> None:
        for rel in ("eval/golden/generate_golden.py", "eval/golden/README.md"):
            with self.subTest(rel=rel):
                res = self.add_and_scan(rel, "print('synthetic golden generator')\n")
                self.assertEqual(res.returncode, 0, res.stdout + res.stderr)

    # ---- forbidden content -------------------------------------------------------------------------------

    def test_digit_free_mixed_case_token(self) -> None:
        token = "wk-" + "AbCdEfGhJkMnPqRsTu"
        res = self.add_and_scan("cfg.py", f'KEY = "{token}"\n')
        self.assertEqual(res.returncode, 1, "a random base62 token without digits must still be caught")
        self.assertNotIn(token, res.stdout + res.stderr)

    def test_single_case_words_after_prefix_are_not_tokens(self) -> None:
        res = self.add_and_scan("docs/b.md", "see ws-connectionhandlers and as-indianlocalisation notes\n")
        self.assertEqual(res.returncode, 0, res.stdout + res.stderr)

    def test_secret_in_ci_tests_dir_is_caught(self) -> None:
        res = self.add_and_scan("tools/ci/tests/fixture.txt", fake_token("ws") + "\n")
        self.assertEqual(res.returncode, 1, "no directory is exempt from the content scan")

    def test_private_key(self) -> None:
        res = self.add_and_scan("x.txt", f"{BEGIN} OPENSSH PRIVATE KEY-----\nabc\n")
        self.assertEqual(res.returncode, 1)
        self.assertIn("x.txt:1: private key material", res.stdout)
        self.assertNotIn("abc", res.stdout)

    def test_service_account_json(self) -> None:
        body = '{"type": "service_' + 'account", "private_key": "redacted", "client_email": "x"}\n'
        res = self.add_and_scan("config/ops.json", body)
        self.assertEqual(res.returncode, 1)
        self.assertIn("service-account", res.stdout)

    def test_modal_tokens(self) -> None:
        for prefix in ("wk", "ws", "ak", "as"):
            with self.subTest(prefix=prefix):
                token = fake_token(prefix)
                res = self.add_and_scan("cfg.py", f'KEY = "{token}"\n')
                self.assertEqual(res.returncode, 1)
                self.assertIn("Modal token", res.stdout)
                self.assertNotIn(token, res.stdout + res.stderr, "the value must never be printed")

    def test_combined_proxy_key(self) -> None:
        res = self.add_and_scan("cfg.py", f'api_key = "{fake_token("wk")}.{fake_token("ws")}"\n')
        self.assertEqual(res.returncode, 1)

    def test_github_token(self) -> None:
        res = self.add_and_scan("cfg.py", "t = 'gh" + "o_" + "a1B2" * 9 + "'\n")
        self.assertEqual(res.returncode, 1)
        self.assertIn("GitHub token", res.stdout)

    def test_github_actions_annotation(self) -> None:
        self.write("x.txt", f"{BEGIN} RSA PRIVATE KEY-----\n")
        self.git("add", "-A")
        env = {**self.env, "GITHUB_ACTIONS": "true"}
        res = subprocess.run(["bash", str(SCRIPT), "--root", str(self.tmp)], env=env, capture_output=True,
                             text=True, check=False)
        self.assertEqual(res.returncode, 1)
        self.assertIn("::error file=x.txt,line=1::", res.stdout)

    def test_usage_errors(self) -> None:
        res = subprocess.run(["bash", str(SCRIPT), "--bogus"], capture_output=True, text=True, check=False)
        self.assertEqual(res.returncode, 2)
        res = subprocess.run(["bash", str(SCRIPT), "--help"], capture_output=True, text=True, check=False)
        self.assertEqual(res.returncode, 0)
        self.assertIn("Usage", res.stdout)


if __name__ == "__main__":
    unittest.main()
