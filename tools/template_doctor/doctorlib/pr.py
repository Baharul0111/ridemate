"""Turning an approved bundle into a pull request on the repository the owner chooses.

By default nothing is executed: the exact commands are printed for the owner. `--execute` creates the branch and
the local commit; only `--execute --push` also pushes the branch and runs `gh pr create`. The tool never pushes
on its own.
"""

from __future__ import annotations

import re
import shlex
import subprocess
from collections.abc import Callable, Sequence
from dataclasses import dataclass, field
from pathlib import Path

from . import paths

REPO_RE = re.compile(r"^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")
BUNDLED_PATHS = (
    "android/app/src/main/assets/config",
    "android/app/src/main/res/xml/remote_config_defaults.xml",
)

Runner = Callable[[Sequence[str], Path], int]
RemoteReader = Callable[[str, Path], "str | None"]
_GITHUB_REMOTE_RE = re.compile(r"github\.com[:/]+(?P<repo>[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+?)(?:\.git)?/*$")


class PrError(RuntimeError):
    pass


@dataclass(frozen=True)
class PrPlan:
    branch: str
    repo: str
    local: tuple[tuple[str, ...], ...]
    push: tuple[tuple[str, ...], ...]
    preconditions: tuple[tuple[str, ...], ...] = field(default_factory=tuple)
    remote: str = "origin"

    def render(self) -> str:
        lines = ["# Review the bundle first, then from the repository root:"]
        lines += [shlex.join(cmd) for cmd in self.local]
        lines.append(
            f"# Push and open the pull request (owner's call; `{self.remote}` must point at {self.repo}):"
        )
        lines += [shlex.join(cmd) for cmd in self.push]
        return "\n".join(lines) + "\n"


def plan_pr(
    bundle: Path,
    patched_keys: Sequence[str],
    *,
    repo: str,
    base: str = "main",
    remote: str = "origin",
    title: str | None = None,
) -> PrPlan:
    if not REPO_RE.match(repo):
        raise PrError("--repo must look like OWNER/NAME")
    if not patched_keys:
        raise PrError("the bundle has no patched config to propose")
    branch = f"template-doctor/{bundle.name.lower()}"
    title = title or f"Template Doctor: {', '.join(patched_keys)} ({bundle.name})"
    targets = [f"config/remote_config/{key}.json" for key in patched_keys]
    local: list[tuple[str, ...]] = [("git", "switch", "-c", branch)]
    local += [
        ("cp", str(bundle / f"{key}.json"), target) for key, target in zip(patched_keys, targets, strict=True)
    ]
    local += [
        ("uv", "run", "--project", "eval", "python", "config/build_template.py", "--bundled-only"),
        ("uv", "run", "--project", "eval", "python", "config/validate.py"),
        ("git", "add", *targets, *BUNDLED_PATHS),
        ("git", "commit", "-m", title),
    ]
    push = (
        ("git", "push", "-u", remote, branch),
        (
            "gh", "pr", "create", "--repo", repo, "--base", base, "--head", branch,
            "--title", title, "--body-file", str(bundle / "report.md"),
        ),
    )  # fmt: skip
    preconditions = (("git", "diff", "--quiet", "--", "config/remote_config", *BUNDLED_PATHS),)
    return PrPlan(
        branch=branch, repo=repo, local=tuple(local), push=push, preconditions=preconditions, remote=remote
    )


def subprocess_runner(cmd: Sequence[str], cwd: Path) -> int:
    return subprocess.run(list(cmd), cwd=cwd, check=False).returncode


def git_remote_url(remote: str, cwd: Path) -> str | None:
    proc = subprocess.run(
        ["git", "remote", "get-url", remote], cwd=cwd, capture_output=True, text=True, check=False
    )
    return proc.stdout.strip() or None if proc.returncode == 0 else None


def remote_points_at(url: str | None, repo: str) -> bool:
    """True when a git remote URL (https or ssh) is the GitHub repository OWNER/NAME."""
    match = _GITHUB_REMOTE_RE.search(url or "")
    return match is not None and match.group("repo").lower() == repo.lower()


def execute(
    plan: PrPlan,
    *,
    push: bool,
    runner: Runner = subprocess_runner,
    cwd: Path | None = None,
    remote_url: RemoteReader = git_remote_url,
) -> list[str]:
    """Run the plan (local branch + commit; push + gh only when `push`). Stops at the first failure. With `push`,
    nothing runs unless the push remote is the repository the owner chose (the branch must reach that repo)."""
    root = cwd or paths.REPO_ROOT
    done: list[str] = []
    if push:
        url = remote_url(plan.remote, root)
        if not remote_points_at(url, plan.repo):
            shown = re.sub(r"//[^/@]+@", "//", url) if url else "not configured"  # never echo credentials
            raise PrError(
                f"git remote `{plan.remote}` is {shown}, not github.com/{plan.repo}; "
                "pass --remote with a remote that points at --repo"
            )
    for cmd in plan.preconditions:
        if runner(cmd, root) != 0:
            raise PrError(
                "config/ or the bundled defaults have uncommitted changes; commit or stash them first"
            )
    for cmd in [*plan.local, *(plan.push if push else ())]:
        code = runner(cmd, root)
        if code != 0:
            raise PrError(f"`{shlex.join(cmd)}` exited {code}; stopped (completed: {len(done)} steps)")
        done.append(shlex.join(cmd))
    return done
