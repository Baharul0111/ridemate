#!/usr/bin/env python3
"""Combined third-party licence report for RideMate (architecture §4.2 CI/CD "license report").

Sources
  Python (backend/, eval/): every installed distribution in the project's uv environment
    (`uv pip list --format json`), with licence data from its installed metadata (License-Expression,
    "License ::" classifiers, License field). Scope comes from the lock file: `uv export --frozen --no-dev`
    lists what ships (runtime); everything else is a dev tool (dev).
  Android (android/, the release APK): the Gradle "dependency-license-report" plugin (jk1), applied to :app in
    android/build.gradle.kts over `releaseRuntimeClasspath`. If the plugin is not applied (or --android-source
    pom-scan is given), the script lists `releaseRuntimeClasspath` with `gradle :app:dependencies` and reads each
    artifact's POM licences (following <parent> POMs) from the Gradle cache.

Outputs (in --out): licenses.json (machine-readable), LICENSES.md (tables + summary), android-html/ (the plugin's
HTML report, when produced). Exit 1 when a shipped package is denied by --deny (or unknown with
--fail-on-unknown); exit 2 on usage or environment errors.

Only package names, versions and licence names are printed. The script needs no secrets and touches no
network itself (Gradle and uv resolve from their caches/repositories as usual).
"""

from __future__ import annotations

import argparse
import datetime as dt
import glob
import json
import os
import re
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET
from dataclasses import asdict, dataclass, field
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
LICENSE_PLUGIN_ID = "com.github.jk1.dependency-license-report"
ANDROID_CONFIGURATION = "releaseRuntimeClasspath"
PLUGIN_JSON = Path("app/build/reports/dependency-license/index.json")
PLUGIN_DIR = Path("app/build/reports/dependency-license")
UNKNOWN = "UNKNOWN"

# Collects licence metadata inside the target environment; runs with that environment's interpreter (-I).
_METADATA_SCRIPT = r"""
import json, importlib.metadata as md
out = []
for d in md.distributions():
    m = d.metadata
    name = m.get("Name")
    if not name:
        continue
    urls = [u.split(",", 1)[-1].strip() for u in (m.get_all("Project-URL") or [])]
    out.append({
        "name": name,
        "version": d.version,
        "license_expression": m.get("License-Expression"),
        "license": m.get("License"),
        "classifiers": [c for c in (m.get_all("Classifier") or []) if c.startswith("License ::")],
        "home_page": m.get("Home-page") or (urls[0] if urls else None),
    })
print(json.dumps(out))
"""


@dataclass
class Package:
    ecosystem: str  # "pypi" | "maven"
    project: str  # "backend", "eval", "android"
    name: str
    version: str
    scope: str  # "runtime" (ships) | "dev" (tooling/tests only)
    licenses: list[str] = field(default_factory=list)
    license_urls: list[str] = field(default_factory=list)
    url: str | None = None
    denied: bool = False

    @property
    def license_text(self) -> str:
        return " / ".join(self.licenses) if self.licenses else UNKNOWN


class ReportError(Exception):
    """An environment problem the user must fix (exit 2)."""


# ---- helpers -------------------------------------------------------------------------------------------------


def normalize_pypi_name(name: str) -> str:
    return re.sub(r"[-_.]+", "-", name).lower()


def run(cmd: list[str], cwd: Path, *, env: dict[str, str] | None = None) -> str:
    try:
        proc = subprocess.run(  # noqa: S603 (fixed argv, no shell)
            cmd, cwd=cwd, env=env, check=False, capture_output=True, text=True
        )
    except FileNotFoundError as exc:
        raise ReportError(f"command not found: {cmd[0]}") from exc
    if proc.returncode != 0:
        tail = "\n".join((proc.stderr or proc.stdout).strip().splitlines()[-25:])
        raise ReportError(f"`{' '.join(cmd)}` failed in {cwd} (exit {proc.returncode}):\n{tail}")
    return proc.stdout


def python_license_strings(meta: dict) -> list[str]:
    """Licence names for one distribution: SPDX expression > classifiers > short License field."""
    expr = (meta.get("license_expression") or "").strip()
    if expr:
        return [expr]
    classifiers = []
    for c in meta.get("classifiers") or []:
        parts = [p.strip() for p in c.split("::")]
        leaf = parts[-1]
        if leaf and leaf not in ("OSI Approved", "License") and leaf not in classifiers:
            classifiers.append(leaf)
    if classifiers:
        return classifiers
    raw = (meta.get("license") or "").strip()
    if raw and raw.upper() != "UNKNOWN":
        first = raw.splitlines()[0].strip()
        # Some packages paste the whole licence text here; keep only a short, single-line name.
        if len(raw) <= 120 and "\n" not in raw:
            return [raw]
        if len(first) <= 80:
            return [first]
    return []


def parse_uv_export(text: str) -> set[str]:
    """Normalized package names from `uv export --format requirements.txt` output."""
    names: set[str] = set()
    for line in text.splitlines():
        line = line.strip()
        if not line or line.startswith(("#", "-")):
            continue
        m = re.match(r"^([A-Za-z0-9][A-Za-z0-9._-]*)", line)
        if m:
            names.add(normalize_pypi_name(m.group(1)))
    return names


def project_name(project_dir: Path) -> str | None:
    pyproject = project_dir / "pyproject.toml"
    if not pyproject.is_file():
        return None
    try:
        import tomllib
    except ModuleNotFoundError:  # Python < 3.11
        m = re.search(r'(?m)^name\s*=\s*"([^"]+)"', pyproject.read_text(encoding="utf-8"))
        return m.group(1) if m else None
    with pyproject.open("rb") as fh:
        return (tomllib.load(fh).get("project") or {}).get("name")


def venv_python(project_dir: Path) -> Path:
    for candidate in (project_dir / ".venv" / "bin" / "python", project_dir / ".venv" / "Scripts" / "python.exe"):
        if candidate.exists():
            return candidate
    raise ReportError(f"{project_dir}/.venv not found: run `uv sync --frozen` there first (or pass --sync)")


def collect_python(project_dir: Path, label: str, *, sync: bool) -> list[Package]:
    if not (project_dir / "pyproject.toml").is_file():
        raise ReportError(f"{project_dir} has no pyproject.toml")
    if sync:
        run(["uv", "sync", "--frozen"], project_dir)
    py = venv_python(project_dir)
    listed = json.loads(run(["uv", "pip", "list", "--format", "json", "--python", str(py)], project_dir))
    meta_by_name = {
        normalize_pypi_name(m["name"]): m
        for m in json.loads(run([str(py), "-I", "-c", _METADATA_SCRIPT], project_dir))
    }
    runtime = parse_uv_export(
        run(
            ["uv", "export", "--frozen", "--no-dev", "--no-hashes", "--no-emit-project", "--no-header",
             "--format", "requirements.txt"],
            project_dir,
        )
    )
    own = normalize_pypi_name(project_name(project_dir) or "")
    packages = []
    for item in listed:
        key = normalize_pypi_name(item["name"])
        if key == own or item.get("editable_project_location"):
            continue
        meta = meta_by_name.get(key, {})
        packages.append(
            Package(
                ecosystem="pypi",
                project=label,
                name=key,
                version=str(item.get("version", meta.get("version", ""))),
                scope="runtime" if key in runtime else "dev",
                licenses=python_license_strings(meta),
                url=meta.get("home_page"),
            )
        )
    return sorted(packages, key=lambda p: p.name)


# ---- Android: jk1 plugin report -----------------------------------------------------------------------------


def parse_plugin_json(data: dict) -> list[Package]:
    """Parse the jk1 JsonReportRenderer output (either the one-licence or the all-licences shape)."""
    packages = []
    for dep in data.get("dependencies") or []:
        module = dep.get("moduleName") or ""
        if ":" not in module:
            continue
        licenses: list[str] = []
        urls: list[str] = []
        for lic in dep.get("moduleLicenses") or []:
            if lic.get("moduleLicense") and lic["moduleLicense"] not in licenses:
                licenses.append(lic["moduleLicense"])
            if lic.get("moduleLicenseUrl") and lic["moduleLicenseUrl"] not in urls:
                urls.append(lic["moduleLicenseUrl"])
        if dep.get("moduleLicense") and dep["moduleLicense"] not in licenses:
            licenses.append(dep["moduleLicense"])
        if dep.get("moduleLicenseUrl") and dep["moduleLicenseUrl"] not in urls:
            urls.append(dep["moduleLicenseUrl"])
        module_urls = dep.get("moduleUrls") or ([dep["moduleUrl"]] if dep.get("moduleUrl") else [])
        packages.append(
            Package(
                ecosystem="maven",
                project="android",
                name=module,
                version=str(dep.get("moduleVersion") or ""),
                scope="runtime",
                licenses=licenses,
                license_urls=urls,
                url=module_urls[0] if module_urls else None,
            )
        )
    return sorted(packages, key=lambda p: p.name)


def plugin_applied(android_dir: Path) -> bool:
    for script in [android_dir / "build.gradle.kts", android_dir / "app" / "build.gradle.kts",
                   android_dir / "build.gradle", android_dir / "app" / "build.gradle"]:
        if script.is_file() and LICENSE_PLUGIN_ID in script.read_text(encoding="utf-8"):
            return True
    return False


def gradle_base_cmd(android_dir: Path) -> list[str]:
    gradlew = android_dir / ("gradlew.bat" if os.name == "nt" else "gradlew")
    if not gradlew.exists():
        raise ReportError(f"{gradlew} not found")
    verification = "strict" if (android_dir / "gradle" / "verification-metadata.xml").is_file() else "lenient"
    return [str(gradlew), "--no-configuration-cache", f"--dependency-verification={verification}"]


def collect_android_plugin(android_dir: Path, *, run_gradle: bool) -> list[Package]:
    if run_gradle:
        # --no-parallel: the plugin resolves configurations across projects (see its README, Gradle 8/9 notes).
        run(gradle_base_cmd(android_dir) + ["--no-parallel", "--no-build-cache", "-q",
                                            ":app:generateLicenseReport", "--rerun-tasks"], android_dir)
    report = android_dir / PLUGIN_JSON
    if not report.is_file():
        raise ReportError(f"{report} not found: run ./gradlew :app:generateLicenseReport in android/")
    return parse_plugin_json(json.loads(report.read_text(encoding="utf-8")))


# ---- Android: POM scan fallback ------------------------------------------------------------------------------

_DEP_LINE = re.compile(
    r"^[\s|+\\-]*(?P<group>[A-Za-z0-9_.\-]+):(?P<name>[A-Za-z0-9_.\-]+)"
    r"(?::(?P<version>[^\s]+))?(?:\s+->\s+(?P<resolved>[^\s]+))?(?P<marker>\s+\([*cn]\))?\s*$"
)


def parse_gradle_dependencies(text: str) -> list[tuple[str, str, str]]:
    """(group, name, resolved version) for every resolved module in `gradle dependencies` tree output."""
    seen: dict[tuple[str, str], str] = {}
    for raw in text.splitlines():
        if raw[:1] not in ("+", "|", "\\", " "):
            continue
        line = re.sub(r"\{[^}]*\}", lambda m: m.group(0).split()[-1].rstrip("}"), raw)
        if "project :" in line or "FAILED" in line:
            continue
        m = _DEP_LINE.match(line)
        if not m:
            continue
        marker = (m.group("marker") or "").strip()
        if marker in ("(c)", "(n)"):
            continue  # constraints and unresolved declarations are not dependencies
        version = m.group("resolved") or m.group("version")
        if not version:
            continue
        seen.setdefault((m.group("group"), m.group("name")), version)
    return sorted((g, n, v) for (g, n), v in seen.items())


def _strip_ns(root: ET.Element) -> ET.Element:
    for el in root.iter():
        if isinstance(el.tag, str) and "}" in el.tag:
            el.tag = el.tag.split("}", 1)[1]
    return root


def find_pom(cache: Path, group: str, name: str, version: str) -> Path | None:
    pattern = str(cache / group / name / version / "*" / f"{name}-{version}.pom")
    hits = sorted(glob.glob(pattern))
    return Path(hits[0]) if hits else None


def pom_licenses(cache: Path, group: str, name: str, version: str, depth: int = 0):
    """(licences, licence urls, project url, packaging) from a POM, inheriting licences from parent POMs."""
    pom = find_pom(cache, group, name, version)
    if pom is None:
        return [], [], None, None
    try:
        root = _strip_ns(ET.parse(pom).getroot())  # noqa: S314 (local Gradle cache, not untrusted input)
    except ET.ParseError:
        return [], [], None, None
    licenses, urls = [], []
    for lic in root.findall("./licenses/license"):
        lname = (lic.findtext("name") or "").strip()
        lurl = (lic.findtext("url") or "").strip()
        if lname and lname not in licenses:
            licenses.append(lname)
        if lurl and lurl not in urls:
            urls.append(lurl)
    project_url = (root.findtext("url") or "").strip() or None
    packaging = (root.findtext("packaging") or "jar").strip()
    if not licenses and depth < 6:
        parent = root.find("parent")
        if parent is not None:
            pg, pn, pv = (parent.findtext(t) or "" for t in ("groupId", "artifactId", "version"))
            if pg and pn and pv:
                licenses, urls, parent_url, _ = pom_licenses(cache, pg.strip(), pn.strip(), pv.strip(), depth + 1)
                project_url = project_url or parent_url
    return licenses, urls, project_url, packaging


def gradle_cache_dir(explicit: str | None) -> Path:
    if explicit:
        return Path(explicit)
    home = Path(os.environ.get("GRADLE_USER_HOME") or Path.home() / ".gradle")
    return home / "caches" / "modules-2" / "files-2.1"


def collect_android_pom_scan(android_dir: Path, cache: Path, *, run_gradle: bool,
                             dependencies_file: Path | None) -> list[Package]:
    if dependencies_file is not None:
        text = dependencies_file.read_text(encoding="utf-8")
    elif run_gradle:
        text = run(gradle_base_cmd(android_dir) + ["-q", ":app:dependencies", "--configuration",
                                                   ANDROID_CONFIGURATION], android_dir)
    else:
        raise ReportError("pom-scan needs Gradle (omit --no-gradle) or --android-dependencies FILE")
    if not cache.is_dir():
        raise ReportError(f"Gradle cache {cache} not found")
    packages = []
    for group, name, version in parse_gradle_dependencies(text):
        licenses, urls, project_url, packaging = pom_licenses(cache, group, name, version)
        if name.endswith("-bom") or (packaging == "pom" and name.endswith(("-platform", "-dependencies"))):
            continue  # BOMs/platforms only align versions; nothing of theirs ships
        packages.append(Package("maven", "android", f"{group}:{name}", version, "runtime", licenses, urls,
                                project_url))
    return packages


# ---- policy + rendering ------------------------------------------------------------------------------------


def apply_policy(packages: list[Package], deny: list[re.Pattern[str]]) -> None:
    """A shipped package is denied when EVERY licence alternative it offers matches a deny pattern."""
    if not deny:
        return
    for pkg in packages:
        if pkg.scope != "runtime" or not pkg.licenses:
            continue
        alternatives = [alt.strip(" ()") for lic in pkg.licenses for alt in re.split(r"\s+OR\s+", lic)]
        pkg.denied = all(any(p.search(alt) for p in deny) for alt in alternatives if alt)


def render_markdown(packages: list[Package], generated: str, sources: dict[str, str]) -> str:
    lines = ["# RideMate third-party licences", "",
             f"Generated {generated} by `tools/license_report.py`.", ""]
    lines += ["| Project | Source |", "|---|---|"]
    lines += [f"| {k} | {v} |" for k, v in sources.items()]
    lines.append("")
    by_license: dict[str, int] = {}
    for p in packages:
        if p.scope == "runtime":
            by_license[p.license_text] = by_license.get(p.license_text, 0) + 1
    lines += ["## Shipped (runtime) packages by licence", "", "| Licence | Packages |", "|---|---|"]
    lines += [f"| {lic} | {n} |" for lic, n in sorted(by_license.items(), key=lambda kv: (-kv[1], kv[0]))]
    review = [p for p in packages if p.scope == "runtime" and (p.denied or not p.licenses)]
    lines += ["", "## Needs review", ""]
    if review:
        lines += ["| Project | Package | Version | Licence | Why |", "|---|---|---|---|---|"]
        lines += [f"| {p.project} | `{p.name}` | {p.version} | {p.license_text} | "
                  f"{'denied by policy' if p.denied else 'no licence metadata'} |" for p in review]
    else:
        lines.append("Nothing: every shipped package has licence metadata and passes the policy.")
    for project in sorted({p.project for p in packages}):
        lines += ["", f"## {project}", "", "| Package | Version | Scope | Licence |", "|---|---|---|---|"]
        for p in sorted((p for p in packages if p.project == project), key=lambda p: (p.scope != "runtime", p.name)):
            lines.append(f"| `{p.name}` | {p.version} | {p.scope} | {p.license_text} |")
    return "\n".join(lines) + "\n"


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(
        description="Write a combined backend + eval + Android third-party licence report.",
        epilog="Example (CI): tools/license_report.py --out build/license-report "
               "--deny '(?i)AGPL|Affero|SSPL|Server Side Public|BUSL|Business Source|Commons Clause'",
    )
    ap.add_argument("--python-project", action="append", metavar="DIR",
                    help="uv project to include (repeatable; default: backend and eval)")
    ap.add_argument("--no-python", action="store_true", help="skip the Python projects (Android only)")
    ap.add_argument("--android", default=str(REPO_ROOT / "android"), metavar="DIR",
                    help="Android Gradle root (default: android/)")
    ap.add_argument("--android-source", choices=("auto", "plugin", "pom-scan", "none"), default="auto",
                    help="auto = plugin when applied in build.gradle.kts, else pom-scan")
    ap.add_argument("--android-dependencies", metavar="FILE",
                    help="pom-scan: read `gradle :app:dependencies` output from FILE instead of running Gradle")
    ap.add_argument("--gradle-cache", metavar="DIR",
                    help="pom-scan: Gradle modules cache (default: $GRADLE_USER_HOME/caches/modules-2/files-2.1)")
    ap.add_argument("--no-gradle", action="store_true",
                    help="do not run Gradle; read an existing plugin report (or --android-dependencies)")
    ap.add_argument("--sync", action="store_true", help="run `uv sync --frozen` in each Python project first")
    ap.add_argument("--out", default=str(REPO_ROOT / "build" / "license-report"), metavar="DIR",
                    help="output directory (default: build/license-report)")
    ap.add_argument("--deny", action="append", default=[], metavar="REGEX",
                    help="licence regex that a shipped package must not be limited to (repeatable)")
    ap.add_argument("--fail-on-unknown", action="store_true",
                    help="exit 1 when a shipped package has no licence metadata")
    args = ap.parse_args(argv)

    try:
        deny = [re.compile(d) for d in args.deny]
    except re.error as exc:
        print(f"license_report: bad --deny regex: {exc}", file=sys.stderr)
        return 2

    projects = [] if args.no_python else [
        Path(p) for p in (args.python_project or [REPO_ROOT / "backend", REPO_ROOT / "eval"])]
    packages: list[Package] = []
    sources: dict[str, str] = {}
    try:
        for proj in projects:
            label = proj.resolve().name
            packages += collect_python(proj.resolve(), label, sync=args.sync)
            sources[label] = "uv pip list + installed metadata; scope from uv export --no-dev"
        android_dir = Path(args.android).resolve()
        mode = args.android_source
        if mode == "auto":
            mode = "plugin" if plugin_applied(android_dir) else "pom-scan"
        if mode == "plugin":
            packages += collect_android_plugin(android_dir, run_gradle=not args.no_gradle)
            sources["android"] = f"{LICENSE_PLUGIN_ID} over :app {ANDROID_CONFIGURATION}"
        elif mode == "pom-scan":
            dep_file = Path(args.android_dependencies) if args.android_dependencies else None
            packages += collect_android_pom_scan(android_dir, gradle_cache_dir(args.gradle_cache),
                                                 run_gradle=not args.no_gradle, dependencies_file=dep_file)
            sources["android"] = f"POM licences from the Gradle cache over :app {ANDROID_CONFIGURATION}"
    except ReportError as exc:
        print(f"license_report: {exc}", file=sys.stderr)
        return 2

    apply_policy(packages, deny)
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    generated = dt.datetime.now(dt.timezone.utc).replace(microsecond=0).isoformat()
    shipped = [p for p in packages if p.scope == "runtime"]
    denied = [p for p in shipped if p.denied]
    unknown = [p for p in shipped if not p.licenses]
    summary = {"packages": len(packages), "shipped": len(shipped), "denied": len(denied), "unknown": len(unknown)}
    (out / "licenses.json").write_text(json.dumps(
        {"generated_at": generated, "sources": sources, "summary": summary,
         "deny": args.deny, "packages": [asdict(p) for p in packages]}, indent=2) + "\n", encoding="utf-8")
    (out / "LICENSES.md").write_text(render_markdown(packages, generated, sources), encoding="utf-8")
    plugin_dir = Path(args.android).resolve() / PLUGIN_DIR
    if sources.get("android", "").startswith(LICENSE_PLUGIN_ID) and plugin_dir.is_dir():
        shutil.copytree(plugin_dir, out / "android-html", dirs_exist_ok=True)

    print(f"license_report: {summary['packages']} packages ({summary['shipped']} shipped), "
          f"{summary['unknown']} shipped without licence metadata, {summary['denied']} denied -> {out}")
    for p in denied:
        print(f"  DENIED  {p.project}: {p.name} {p.version} ({p.license_text})")
    for p in unknown:
        print(f"  UNKNOWN {p.project}: {p.name} {p.version}")
    if denied or (args.fail_on_unknown and unknown):
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
