"""Tests for tools/ci/check_base_image.py with a fake registry (no network)."""

from __future__ import annotations

import importlib.util
import io
import json
import shutil
import sys
import tempfile
import unittest
import urllib.error
import urllib.request
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[1] / "check_base_image.py"
_spec = importlib.util.spec_from_file_location("check_base_image", SCRIPT)
cbi = importlib.util.module_from_spec(_spec)
assert _spec.loader is not None
sys.modules["check_base_image"] = cbi
_spec.loader.exec_module(cbi)

OLD = "sha256:" + "a" * 64
NEW = "sha256:" + "b" * 64
MODAL_APP = f'''BASE_IMAGE = "python:3.12-slim@{OLD}"
image = modal.Image.from_registry(BASE_IMAGE)
'''


class FakeRegistry:
    def __init__(self, digests: dict[tuple[str, str], str]) -> None:
        self.digests = digests
        self.requests: list[urllib.request.Request] = []

    def __call__(self, req: urllib.request.Request) -> tuple[dict[str, str], bytes]:
        self.requests.append(req)
        url = req.full_url
        if url.startswith(cbi.AUTH_URL):
            return {}, json.dumps({"token": "anon"}).encode()
        assert req.get_method() == "HEAD"
        assert req.get_header("Authorization") == "Bearer anon"
        repo, tag = url.removeprefix(cbi.REGISTRY_URL + "/v2/").split("/manifests/")
        if (repo, tag) not in self.digests:
            raise urllib.error.URLError("404")
        return {"docker-content-digest": self.digests[(repo, tag)]}, b""


class CheckBaseImageTest(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = Path(tempfile.mkdtemp(prefix="base-image-"))
        self.file = self.tmp / "modal_app.py"

    def tearDown(self) -> None:
        shutil.rmtree(self.tmp, ignore_errors=True)

    def run_main(self, text: str, fetch, *args: str) -> tuple[int, str, str]:
        self.file.write_text(text, encoding="utf-8")
        out, err = io.StringIO(), io.StringIO()
        with redirect_stdout(out), redirect_stderr(err):
            code = cbi.main([*args, str(self.file)], fetch=fetch)
        return code, out.getvalue(), err.getvalue()

    def test_find_pins(self) -> None:
        pins, unpinned = cbi.find_pins(MODAL_APP + 'x = from_registry("debian:12-slim")\n', "m.py")
        self.assertEqual([(p.name, p.tag, p.digest, p.line) for p in pins], [("python", "3.12-slim", OLD, 1)])
        self.assertEqual(unpinned, [(3, "debian:12-slim")])

    def test_docker_hub_repository(self) -> None:
        self.assertEqual(cbi.docker_hub_repository("python"), "library/python")
        self.assertEqual(cbi.docker_hub_repository("org/img"), "org/img")
        self.assertEqual(cbi.docker_hub_repository("docker.io/library/python"), "library/python")
        with self.assertRaises(cbi.CheckError):
            cbi.docker_hub_repository("ghcr.io/org/img")

    def test_current_pin_passes(self) -> None:
        reg = FakeRegistry({("library/python", "3.12-slim"): OLD})
        code, out, _ = self.run_main(MODAL_APP, reg)
        self.assertEqual(code, 0, out)
        self.assertIn("current", out)
        self.assertEqual(len(reg.requests), 2)

    def test_stale_pin_fails_and_prints_the_new_digest(self) -> None:
        code, out, _ = self.run_main(MODAL_APP, FakeRegistry({("library/python", "3.12-slim"): NEW}))
        self.assertEqual(code, 1)
        self.assertIn(f"python:3.12-slim@{NEW}", out)

    def test_allow_stale_warns_only(self) -> None:
        code, out, _ = self.run_main(MODAL_APP, FakeRegistry({("library/python", "3.12-slim"): NEW}),
                                     "--allow-stale")
        self.assertEqual(code, 0)
        self.assertIn("STALE", out)

    def test_unpinned_image_is_an_error(self) -> None:
        code, _, err = self.run_main('image = modal.Image.from_registry("python:3.12-slim")\n', FakeRegistry({}))
        self.assertEqual(code, 2)
        self.assertIn("not pinned by digest", err)

    def test_no_pin_is_an_error(self) -> None:
        code, _, err = self.run_main("image = modal.Image.debian_slim()\n", FakeRegistry({}), "--allow-stale")
        self.assertEqual(code, 2)
        self.assertIn("no digest-pinned base image", err)

    def test_registry_failure_is_an_error(self) -> None:
        code, _, err = self.run_main(MODAL_APP, FakeRegistry({}))
        self.assertEqual(code, 2)
        self.assertIn("registry lookup failed", err)

    def test_only_docker_hosts_are_contacted(self) -> None:
        with self.assertRaises(cbi.CheckError):
            cbi._urlopen(urllib.request.Request("https://example.invalid/x"))


if __name__ == "__main__":
    unittest.main()
