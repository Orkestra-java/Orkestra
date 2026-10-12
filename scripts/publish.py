#!/usr/bin/env python3
"""Publish the local Orkestra project to its GitHub branch."""

import argparse
import shutil
import subprocess
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
REMOTE = "https://github.com/Orkestra-java/Orkestra.git"
PREPARED = Path("/tmp/orkestra-publish-rq7ue5u_/.git")


def run(*args, capture=False):
    return subprocess.run(
        args, cwd=ROOT, text=True,
        stdout=subprocess.PIPE if capture else None,
        stderr=subprocess.STDOUT if capture else None,
    )


def require(*args):
    result = run(*args)
    if result.returncode:
        raise SystemExit(result.returncode)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--branch", default="chore/initial-import")
    parser.add_argument("--check", action="store_true", help="Check prerequisites without changing files or publishing")
    args = parser.parse_args()
    for command in ("git", "gh"):
        if not shutil.which(command):
            parser.error(f"Required command missing: {command}")
    require("git", "check-ref-format", "--branch", args.branch)
    if args.branch in ("main", "master"):
        parser.error("Choose a publication branch other than main/master")
    if args.check:
        print(f"Project: {ROOT}\nDestination: {REMOTE}\nBranch: {args.branch}")
        return

    # Recover the prepared history so ordinary Git commands work in the project.
    if not (ROOT / ".git").exists():
        if PREPARED.is_dir():
            shutil.copytree(PREPARED, ROOT / ".git")
        else:
            require("git", "init", "-b", args.branch)
    require("git", "rev-parse", "--show-toplevel")
    remote = run("git", "remote", "get-url", "origin", capture=True)
    if remote.returncode:
        require("git", "remote", "add", "origin", REMOTE)
    elif remote.stdout.strip() != REMOTE:
        parser.error(f"Origin differs from the expected destination: {remote.stdout.strip()}")
    branch = run("git", "show-ref", "--verify", f"refs/heads/{args.branch}", capture=True)
    require("git", "switch", *( [args.branch] if branch.returncode == 0 else ["-c", args.branch]))
    require("git", "add", "-A", "--", ".", ":(exclude).agents", ":(exclude).codex", ":(exclude).aws")
    changed = run("git", "diff", "--cached", "--quiet")
    if changed.returncode == 1:
        require("git", "commit", "-m", "Publish Orkestra library")
    elif changed.returncode:
        raise SystemExit(changed.returncode)

    result = run("git", "push", "-u", "origin", args.branch, capture=True)
    print(result.stdout, end="")
    if result.returncode and "workflow" in result.stdout and "scope" in result.stdout:
        print("Authorize GitHub workflow access in the browser to publish GitHub Actions.", flush=True)
        require("gh", "auth", "refresh", "-h", "github.com", "-s", "workflow")
        require("gh", "auth", "setup-git")
        require("git", "push", "-u", "origin", args.branch)
    elif result.returncode:
        raise SystemExit(result.returncode)
    print(f"Published: https://github.com/Orkestra-java/Orkestra/tree/{args.branch}")


if __name__ == "__main__":
    try:
        main()
    except (OSError, KeyboardInterrupt) as error:
        print(f"Publication interrupted: {error}", file=sys.stderr)
        sys.exit(1)
