#!/usr/bin/env python3
"""Enforce inward dependencies using compiled classes and JPMS descriptors."""
import re
import subprocess
import sys
from project import JAVA, artifacts, jdk_tool


def main():
    jars = artifacts()
    classpath = __import__('os').pathsep.join(map(str, jars.values()))
    failures = []
    for layer in ('domain', 'application', 'infrastructure'):
        classes = JAVA / layer / 'target' / 'classes' / 'io'
        if not classes.exists():
            raise RuntimeError('Build the Java reactor before checking architecture')
        output = subprocess.check_output([jdk_tool('jdeps'), '--ignore-missing-deps', '-verbose:class',
                                          '--class-path', classpath, str(classes)], text=True)
        allowed = ('java.', 'jdk.', 'io.orkestra.domain.')
        if layer != 'domain':
            allowed += ('io.orkestra.application.',)
        if layer == 'infrastructure':
            allowed += ('io.orkestra.infrastructure.',)
        for source, dependency in re.findall(r'^\s+(io\.orkestra\.\S+)\s+->\s+(\S+)', output, re.M):
            if not source.startswith(f'io.orkestra.{layer}.'):
                failures.append(f'{layer}: misplaced production type {source}')
            if not dependency.startswith(allowed):
                failures.append(f'{source} depends on forbidden {dependency}')
        descriptor = subprocess.check_output([jdk_tool('jar'), '--describe-module', '--file', str(jars[layer])], text=True)
        expected = {'java.base'}
        if layer != 'domain':
            expected.add('io.orkestra.domain')
        if layer == 'infrastructure':
            expected.add('io.orkestra.application')
        required = set(re.findall(r'^requires (\S+)', descriptor, re.M))
        if required != expected:
            failures.append(f'{layer}: unexpected module dependencies {required - expected} or missing {expected - required}')
        if layer == 'domain' and 'qualified exports io.orkestra.domain.internal to io.orkestra.application' not in descriptor:
            failures.append('Domain implementation bridge must only be exported to application')
    if failures:
        print('\n'.join(sorted(set(failures))), file=sys.stderr)
        return 1
    print('Architecture passed: domain <- application <- infrastructure; inner layers are framework free')
    return 0


if __name__ == '__main__':
    try:
        sys.exit(main())
    except (OSError, RuntimeError, subprocess.CalledProcessError) as error:
        print(error, file=sys.stderr)
        sys.exit(2)
