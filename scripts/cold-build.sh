#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
project_root=$(pwd)
# Deliberately do not copy caches, targets, or the normal wrapper distribution.
cold_root=$(mktemp -d "${TMPDIR:-/tmp}/orkestra-cold.XXXXXX")
trap 'rm -rf "$cold_root"' EXIT
python3 - "$project_root" "$cold_root/project" <<'PY'
from pathlib import Path
import shutil
import sys
source, target = map(Path, sys.argv[1:])
target.mkdir()
items = ['LICENSE', 'NOTICE', 'java', 'scripts', 'api', 'examples']
for name in items:
    path = source / name
    if path.is_dir():
        shutil.copytree(path, target / name, ignore=shutil.ignore_patterns('target', '__pycache__'))
    else:
        shutil.copy2(path, target / name)
PY
cd "$cold_root/project/java"
export MAVEN_USER_HOME="$cold_root/maven-user"
./mvnw --batch-mode --no-transfer-progress -Dmaven.repo.local="$cold_root/repository" -Prelease,adapters verify
cd ..
python3 scripts/test_check_api.py
python3 scripts/check-api.py --all
scripts/run-examples.sh
# Retain candidates for the calling CI job; caches and work copy are removed.
python3 - "$project_root" <<'PY'
from pathlib import Path
import shutil
import sys
root = Path(sys.argv[1])
for candidate in Path('java').rglob('target/*.jar'):
    destination = root / candidate
    destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(candidate, destination)
PY
