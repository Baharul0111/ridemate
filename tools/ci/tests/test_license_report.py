"""Tests for tools/license_report.py parsing, POM scan and policy (no Gradle, uv or network needed)."""

from __future__ import annotations

import importlib.util
import json
import shutil
import sys
import tempfile
import unittest
from pathlib import Path

_PATH = Path(__file__).resolve().parents[2] / "license_report.py"
_spec = importlib.util.spec_from_file_location("license_report", _PATH)
assert _spec and _spec.loader
lr = importlib.util.module_from_spec(_spec)
sys.modules["license_report"] = lr
_spec.loader.exec_module(lr)

GRADLE_DEPS = r"""
------------------------------------------------------------
Project ':app'
------------------------------------------------------------

releaseRuntimeClasspath - Resolved configuration for runtime for variant: release
+--- project :core
|    +--- org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2
|    |    \--- org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.10.2
|    |         +--- org.jetbrains:annotations:23.0.0
|    |         \--- org.jetbrains.kotlin:kotlin-stdlib:{strictly 2.2.21} -> 2.2.21 (c)
|    \--- com.google.re2j:re2j:1.8
+--- androidx.core:core-ktx:1.17.0
|    +--- androidx.annotation:annotation:1.8.1 -> 1.9.1
|    \--- org.jetbrains.kotlin:kotlin-stdlib:2.2.21 (*)
+--- androidx.compose:compose-bom:2025.12.01
+--- androidx.compose.ui:ui -> 1.9.0
\--- com.example:not-resolved:1.0 FAILED

(c) - A dependency constraint, not a dependency.
(*) - Indicates repeated occurrences of a transitive dependency subtree.
"""

POM_NS = "http://maven.apache.org/POM/4.0.0"


def pom(group: str, name: str, version: str, *, licenses=(), parent=None, packaging="jar") -> str:
    lic = "".join(f"<license><name>{n}</name><url>{u}</url></license>" for n, u in licenses)
    par = (f"<parent><groupId>{parent[0]}</groupId><artifactId>{parent[1]}</artifactId>"
           f"<version>{parent[2]}</version></parent>") if parent else ""
    return (f'<?xml version="1.0"?><project xmlns="{POM_NS}">{par}<groupId>{group}</groupId>'
            f"<artifactId>{name}</artifactId><version>{version}</version><packaging>{packaging}</packaging>"
            f"<url>https://example.org/{name}</url>"
            + (f"<licenses>{lic}</licenses>" if lic else "") + "</project>")


class ParsingTest(unittest.TestCase):
    def test_parse_gradle_dependencies(self) -> None:
        deps = lr.parse_gradle_dependencies(GRADLE_DEPS)
        self.assertIn(("androidx.annotation", "annotation", "1.9.1"), deps)
        self.assertIn(("androidx.compose.ui", "ui", "1.9.0"), deps)
        self.assertIn(("org.jetbrains.kotlin", "kotlin-stdlib", "2.2.21"), deps)
        self.assertIn(("com.google.re2j", "re2j", "1.8"), deps)
        names = {f"{g}:{n}" for g, n, _ in deps}
        self.assertNotIn("com.example:not-resolved", names)
        self.assertEqual(len(names), len(deps), "one entry per module")

    def test_parse_plugin_json_both_shapes(self) -> None:
        data = {"dependencies": [
            {"moduleName": "androidx.core:core", "moduleVersion": "1.17.0",
             "moduleUrls": ["https://developer.android.com/jetpack/androidx"],
             "moduleLicenses": [{"moduleLicense": "Apache License, Version 2.0",
                                 "moduleLicenseUrl": "https://www.apache.org/licenses/LICENSE-2.0"}]},
            {"moduleName": "com.google.re2j:re2j", "moduleVersion": "1.8", "moduleUrl": "https://github.com/google/re2j",
             "moduleLicense": "Go License"},
            {"moduleName": "no-colon"},
        ]}
        pkgs = lr.parse_plugin_json(data)
        self.assertEqual([p.name for p in pkgs], ["androidx.core:core", "com.google.re2j:re2j"])
        self.assertEqual(pkgs[0].licenses, ["Apache License, Version 2.0"])
        self.assertEqual(pkgs[0].url, "https://developer.android.com/jetpack/androidx")
        self.assertEqual(pkgs[1].licenses, ["Go License"])
        self.assertTrue(all(p.scope == "runtime" and p.project == "android" for p in pkgs))

    def test_python_license_strings(self) -> None:
        self.assertEqual(lr.python_license_strings({"license_expression": "MIT"}), ["MIT"])
        self.assertEqual(lr.python_license_strings(
            {"classifiers": ["License :: OSI Approved :: Apache Software License",
                             "License :: OSI Approved :: BSD License"]}),
            ["Apache Software License", "BSD License"])
        self.assertEqual(lr.python_license_strings({"license": "BSD-3-Clause"}), ["BSD-3-Clause"])
        long_text = "Copyright (c) 2020 Someone\n\nPermission is hereby granted..." * 20
        self.assertEqual(lr.python_license_strings({"license": long_text}), ["Copyright (c) 2020 Someone"])
        self.assertEqual(lr.python_license_strings({"license": "UNKNOWN"}), [])
        self.assertEqual(lr.python_license_strings({}), [])

    def test_parse_uv_export(self) -> None:
        text = "annotated-types==0.8.0\n    # via pydantic\nPyNaCl==1.5.0 ; sys_platform != 'x'\n-e ./local\n"
        self.assertEqual(lr.parse_uv_export(text), {"annotated-types", "pynacl"})


class PomScanTest(unittest.TestCase):
    def setUp(self) -> None:
        self.cache = Path(tempfile.mkdtemp(prefix="gradle-cache-"))

    def tearDown(self) -> None:
        shutil.rmtree(self.cache)

    def put(self, group: str, name: str, version: str, text: str) -> None:
        d = self.cache / group / name / version / "0123abcd"
        d.mkdir(parents=True)
        (d / f"{name}-{version}.pom").write_text(text, encoding="utf-8")

    def test_licenses_inherited_from_parent(self) -> None:
        self.put("org.example", "parent", "3", pom("org.example", "parent", "3", packaging="pom",
                                                   licenses=[("MIT", "https://opensource.org/license/mit")]))
        self.put("org.example", "child", "1.0", pom("org.example", "child", "1.0",
                                                    parent=("org.example", "parent", "3")))
        lic, urls, url, packaging = lr.pom_licenses(self.cache, "org.example", "child", "1.0")
        self.assertEqual(lic, ["MIT"])
        self.assertEqual(urls, ["https://opensource.org/license/mit"])
        self.assertEqual(url, "https://example.org/child")
        self.assertEqual(packaging, "jar")

    def test_collect_pom_scan_from_file(self) -> None:
        self.put("com.google.re2j", "re2j", "1.8", pom("com.google.re2j", "re2j", "1.8",
                                                       licenses=[("Go License", "https://golang.org/LICENSE")]))
        self.put("androidx.compose", "compose-bom", "2025.12.01",
                 pom("androidx.compose", "compose-bom", "2025.12.01", packaging="pom",
                     licenses=[("Apache-2.0", "")]))
        deps_file = self.cache / "deps.txt"
        deps_file.write_text(GRADLE_DEPS, encoding="utf-8")
        pkgs = lr.collect_android_pom_scan(Path("."), self.cache, run_gradle=False, dependencies_file=deps_file)
        by_name = {p.name: p for p in pkgs}
        self.assertEqual(by_name["com.google.re2j:re2j"].licenses, ["Go License"])
        self.assertNotIn("androidx.compose:compose-bom", by_name, "BOMs ship nothing")
        self.assertEqual(by_name["androidx.core:core-ktx"].licenses, [], "missing POM -> unknown")


class PolicyAndMainTest(unittest.TestCase):
    def test_policy_respects_alternatives_and_scope(self) -> None:
        import re
        deny = [re.compile(r"(?i)AGPL|Affero")]
        pkgs = [
            lr.Package("pypi", "backend", "a", "1", "runtime", ["AGPL-3.0-only"]),
            lr.Package("pypi", "backend", "b", "1", "runtime", ["AGPL-3.0-only OR MIT"]),
            lr.Package("pypi", "backend", "c", "1", "dev", ["AGPL-3.0-only"]),
            lr.Package("maven", "android", "d:e", "1", "runtime", ["GNU Affero GPL", "Apache-2.0"]),
            lr.Package("maven", "android", "f:g", "1", "runtime", []),
        ]
        lr.apply_policy(pkgs, deny)
        self.assertEqual([p.name for p in pkgs if p.denied], ["a"])

    def test_main_writes_reports_and_fails_on_denied(self) -> None:
        out = Path(tempfile.mkdtemp(prefix="lic-out-"))
        cache = Path(tempfile.mkdtemp(prefix="lic-cache-"))
        try:
            d = cache / "org.bad" / "lib" / "1.0" / "ff"
            d.mkdir(parents=True)
            (d / "lib-1.0.pom").write_text(pom("org.bad", "lib", "1.0", licenses=[("SSPL-1.0", "")]))
            deps = cache / "deps.txt"
            deps.write_text("+--- org.bad:lib:1.0\n\\--- org.unknown:thing:2.0\n")
            common = ["--no-python", "--android-source", "pom-scan", "--android-dependencies", str(deps),
                      "--gradle-cache", str(cache), "--out", str(out)]
            self.assertEqual(lr.main(common), 0, "no policy -> pass")
            self.assertEqual(lr.main(common + ["--fail-on-unknown"]), 1, "org.unknown:thing has no POM")
            self.assertEqual(lr.main(common + ["--deny", "SSPL"]), 1)
            report = json.loads((out / "licenses.json").read_text())
            self.assertEqual(report["summary"], {"packages": 2, "shipped": 2, "denied": 1, "unknown": 1})
            md = (out / "LICENSES.md").read_text()
            self.assertIn("org.unknown:thing", md)
            self.assertIn("denied by policy", md)
        finally:
            shutil.rmtree(out)
            shutil.rmtree(cache)

    def test_missing_python_project_is_an_environment_error(self) -> None:
        out = Path(tempfile.mkdtemp(prefix="lic-out-"))
        try:
            rc = lr.main(["--python-project", str(out / "nope"), "--android-source", "none", "--out", str(out)])
            self.assertEqual(rc, 2)
        finally:
            shutil.rmtree(out)

    def test_bad_deny_regex(self) -> None:
        self.assertEqual(lr.main(["--deny", "(unclosed", "--android-source", "none"]), 2)


if __name__ == "__main__":
    unittest.main()
