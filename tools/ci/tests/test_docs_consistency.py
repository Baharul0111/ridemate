"""The README, setup guide and architecture must state the values the code actually uses.

Each test reads a number or a name from the source of truth (backend settings, the Kotlin pipeline, release.sh,
firebase/tests) and checks the documents quote it, so a later change to the code fails here until the docs follow.
Stdlib only; no network.
Run: python3 -m unittest discover -s tools/ci/tests  (or pytest tools/ci/tests)
"""

from __future__ import annotations

import json
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
README = ROOT / "README.md"
SETUP = ROOT / "setup.md"
ARCHITECTURE = ROOT / "architecture.md"
SLO = ROOT / "docs" / "slo-and-alerts.md"
SPIKES = ROOT / "docs" / "spikes.md"
RELEASE_SH = ROOT / "tools" / "release.sh"
SETTINGS = ROOT / "backend" / "ridemate_api" / "settings.py"
PIPELINE = ROOT / "android/core/src/main/kotlin/app/ridemate/core/watch/RideWatchPipeline.kt"
RULES_TESTS = ROOT / "firebase" / "tests"


def text(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def setting(name: str) -> float:
    match = re.search(rf"^{name} = ([0-9_.]+)", text(SETTINGS), re.MULTILINE)
    assert match, f"{name} not found in {SETTINGS}"
    return float(match.group(1).replace("_", ""))


def seconds(value: float) -> str:
    return str(int(value)) if value == int(value) else str(value)


def section(doc: str, heading: str) -> str:
    """The body of the markdown section whose heading line starts with `heading`, up to the next heading."""
    start = doc.index("\n" + heading)
    level = heading.split(" ", 1)[0]
    rest = doc[start + 1:]
    nxt = re.search(rf"\n#{{1,{len(level)}}} ", rest)
    return rest[: nxt.start()] if nxt else rest


def table_row(doc: str, first_cell: str) -> str:
    rows = [line for line in doc.splitlines() if line.startswith(f"| {first_cell}")]
    assert len(rows) == 1, f"expected one table row starting with {first_cell!r}, found {len(rows)}"
    return rows[0]


def user_docs() -> list[Path]:
    docs = [README, SETUP, ARCHITECTURE, ROOT / "SECURITY.md"]
    for folder in ("docs", "tools", "eval"):
        docs += sorted((ROOT / folder).rglob("*.md"))
    skipped = {"node_modules", "build"}
    return [p for p in docs if p.is_file() and not any(part in skipped or part.startswith(".") for part in
                                                         p.relative_to(ROOT).parts)]


class ChecksumFileNameTest(unittest.TestCase):
    NAME = "SHA256SUMS.txt"

    def test_release_script_writes_and_uploads_the_name_the_docs_use(self) -> None:
        script = text(RELEASE_SH)
        self.assertIn(f'>"$dist/{self.NAME}"', script)
        self.assertIn(f'"dist/{self.NAME}"', re.search(r"gh_cmd=\(gh release create[^)]*\)", script).group(0))
        # The only other spelling allowed is the cleanup of a file left by an older release.sh.
        legacy = [line for line in script.splitlines()
                  if re.search(r"SHA256SUMS(?!\.txt)", line) and not line.lstrip().startswith("rm -f")]
        self.assertEqual(legacy, [])

    def test_every_doc_names_the_checksum_file_the_same_way(self) -> None:
        wrong = []
        for path in user_docs():
            for n, line in enumerate(text(path).splitlines(), 1):
                if re.search(r"SHA256SUMS(?!\.txt)", line):
                    wrong.append(f"{path.relative_to(ROOT)}:{n}: {line.strip()}")
        self.assertEqual(wrong, [], f"use {self.NAME}, the file tools/release.sh publishes")
        self.assertIn(f"sha256sum -c {self.NAME}", text(README))


class FirestoreRulesCommandTest(unittest.TestCase):
    def test_readme_runs_the_rules_test_project_that_exists(self) -> None:
        package = json.loads(text(RULES_TESTS / "package.json"))
        self.assertIn("test", package["scripts"])
        self.assertFalse((ROOT / "firebase" / "package.json").exists())
        row = table_row(text(README), "Firestore rules tests")
        self.assertNotIn("<", row.split("|")[3], "the command cell still has a placeholder")
        self.assertIn("`cd firebase/tests && npm ci && npm test`", row)


class DayOneSpikeValuesTest(unittest.TestCase):
    """docs/spikes.md re-pinned token ceilings and deadlines; the README and architecture must quote the new ones."""

    def test_output_token_ceilings_match_backend_settings(self) -> None:
        read = int(setting("MAX_TOKENS_READ"))
        readme_row = table_row(text(README), "**Fare and status reading**")
        self.assertIn(f"≤ {read:,} output tokens", readme_row)
        arch = text(ARCHITECTURE)
        self.assertIn(f"≤ {read:,} output tokens", table_row(section(arch, "### 4.10"), "Fare/status read"))
        gateway = table_row(section(arch, "### 4.2"), "Model gateway")
        self.assertIn(f"`max_tokens` {read}", gateway)
        for name in ("REREAD", "POINT", "PARSE", "PLACE"):
            self.assertIn(f"{int(setting('MAX_TOKENS_' + name))} tokens", gateway, name)
        self.assertNotIn("800", gateway)

    def test_classifier_deadline_matches_the_pipeline(self) -> None:
        match = re.search(r"CLASSIFIER_DEADLINE_MS = ([0-9_]+)L", text(PIPELINE))
        deadline = seconds(int(match.group(1).replace("_", "")) / 1000)
        arch = text(ARCHITECTURE)
        i9 = next(line for line in section(arch, "### 4.9").splitlines() if line.startswith("- **I9**"))
        self.assertIn(f"classifier deadline ({deadline} s", i9)
        self.assertIn(f"classifier deadline of {deadline} s", section(arch, "### 4.5"))
        self.assertIn(f"the {deadline} s classifier deadline", section(arch, "### 4.12"))
        self.assertIn(f"the {deadline} s classifier deadline", text(SLO))

    def test_server_and_phone_deadlines_match_backend_settings(self) -> None:
        parse, place, point = (setting(n) for n in ("DEADLINE_PARSE_S", "DEADLINE_PLACE_S", "DEADLINE_POINT_S"))
        self.assertEqual(parse, place, "the docs quote one deadline for parse and place-pick")
        arch = text(ARCHITECTURE)
        self.assertIn(f"place-pick and parse {seconds(parse)} s; point {seconds(point)} s", section(arch, "### 4.18"))
        self.assertIn(f"parse/place-pick {seconds(parse + 5)} s, point {seconds(point + 5)} s", section(arch, "### 4.12"))
        slo = text(SLO)
        self.assertIn(f"place-pick and notification parse {seconds(parse)} s; point {seconds(point)} s", slo)
        self.assertIn(f"parse / place-pick {seconds(parse + 5)} s, point {seconds(point + 5)} s", slo)

    def test_the_architecture_changelog_has_the_entry_spikes_md_cites(self) -> None:
        self.assertIn('architecture changelog, "day-1 spike"', text(SPIKES))
        changelog = section(text(ARCHITECTURE), "## 9.")
        entries = [line for line in changelog.splitlines() if "day-1 spike" in line]
        self.assertEqual(len(entries), 1, "architecture.md §9 needs one 'day-1 spike' entry")
        self.assertIn("docs/spikes.md", entries[0])


class SetupGuideTest(unittest.TestCase):
    def test_anonymous_sign_in_is_explained_as_needed_by_try_the_demo(self) -> None:
        step = next(line for line in text(SETUP).splitlines() if "**Anonymous**" in line and "Enable" in line)
        self.assertIn("Try the demo", step)
        self.assertNotIn("only for manual mode", step)


if __name__ == "__main__":
    unittest.main()
