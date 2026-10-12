#!/usr/bin/env python3
"""Check the public/protected JVM surface of exported packages against a baseline."""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import sys
from zipfile import ZipFile

from project import ROOT, artifacts, jdk_tool


def snapshot(include_adapters=False):
    result = {}
    for module, jar in artifacts(include_adapters).items():
        if not jar.exists():
            raise RuntimeError(f'Missing {jar}; run (cd java && ./mvnw verify) first')
        description = subprocess.check_output([jdk_tool('jar'), '--describe-module', '--file', str(jar)], text=True)
        exports = set(re.findall(r'^exports (\S+)$', description, re.M))
        directives = sorted(line for line in description.splitlines()
                            if line.startswith(('exports ', 'requires ', 'uses ', 'provides ')))
        with ZipFile(jar) as archive:
            names = sorted(name[:-6].replace('/', '.') for name in archive.namelist()
                           if name.endswith('.class') and name.rsplit('/', 1)[0].replace('/', '.') in exports)
        raw = subprocess.check_output([jdk_tool('javap'), '-protected', '-s', '-classpath', str(jar), *names], text=True)
        classes = {}
        for block in re.split(r'^Compiled from .*\n', raw, flags=re.M):
            lines = [line.strip() for line in block.splitlines() if line.strip()]
            if not lines or not lines[0].startswith('public '):
                continue
            header = lines[0]
            match = re.search(r'\b(?:class|interface|enum) ([\w.$]+)', header)
            if not match:
                raise RuntimeError(f'Cannot read javap declaration: {header}')
            members = []
            for index, line in enumerate(lines[1:], 1):
                if line.startswith(('public ', 'protected ')):
                    descriptor = lines[index + 1] if index + 1 < len(lines) and lines[index + 1].startswith('descriptor:') else ''
                    members.append(line + '\n' + descriptor)
            classes[match.group(1)] = {'declaration': header, 'members': sorted(members)}
        result[module] = {'module': directives, 'classes': classes}
    return result


def incompatibilities(old, new):
    failures = []
    for module, before in old.items():
        after = new.get(module)
        if after is None:
            failures.append(f'{module}: module removed')
            continue
        for directive in set(before['module']) - set(after['module']):
            failures.append(f'{module}: directive removed/changed: {directive}')
        for name, original in before['classes'].items():
            current = after['classes'].get(name)
            if current is None:
                failures.append(f'{name}: exported type removed')
                continue
            if original['declaration'] != current['declaration']:
                failures.append(f'{name}: type declaration changed')
            for member in set(original['members']) - set(current['members']):
                failures.append(f'{name}: member removed/changed: {member}')
            for member in set(current['members']) - set(original['members']):
                if ' abstract ' in member.split('\n')[0]:
                    failures.append(f'{name}: abstract obligation added: {member}')
    return failures


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--baseline', type=Path, default=ROOT / 'api' / '0.1.0.json')
    parser.add_argument('--write', action='store_true', help='Explicitly replace baseline; review the resulting diff')
    parser.add_argument("--all", action="store_true", help="Include optional adapter artifacts; build -Padapters first")
    args = parser.parse_args()
    current = snapshot(args.all)
    if args.write:
        args.baseline.parent.mkdir(parents=True, exist_ok=True)
        existing = json.loads(args.baseline.read_text()) if args.baseline.exists() and not args.all else {}
        existing.update(current)
        args.baseline.write_text(json.dumps(existing, indent=2, sort_keys=True) + '\n')
        print(f'API baseline written: {args.baseline}')
        return 0
    baseline = json.loads(args.baseline.read_text())
    if not args.all:
        baseline = {key: value for key, value in baseline.items() if key == 'core'}
    problems = incompatibilities(baseline, current)
    if problems:
        print('\n'.join(problems), file=sys.stderr)
        return 1
    print('API compatibility check passed for ' + ('core and optional adapters' if args.all else 'core'))
    return 0


if __name__ == '__main__':
    try:
        sys.exit(main())
    except (OSError, RuntimeError, subprocess.CalledProcessError) as error:
        print(error, file=sys.stderr)
        sys.exit(2)
