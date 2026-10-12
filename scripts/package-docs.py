#!/usr/bin/env python3
"""Generate local sources/Javadoc candidates using JDK tools, without Maven plugins."""
import argparse
import hashlib
import os
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET
from zipfile import ZipFile, ZipInfo, ZIP_DEFLATED

from project import ROOT, JAVA, MODULES, artifacts, version, jdk_tool as tool


def archive(destination, files):
    with ZipFile(destination, 'w', compression=ZIP_DEFLATED) as output:
        for name, path in sorted(files):
            entry = ZipInfo(name, date_time=(2026, 10, 12, 0, 0, 0))
            entry.compress_type = ZIP_DEFLATED
            entry.external_attr = 0o100644 << 16
            output.writestr(entry, path.read_bytes())


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--dependency-path', default='', help='Path-separated runtime dependency jars for optional modules')
    args = parser.parse_args()
    release_version = version()
    all_modules = list(artifacts().values())
    if any(not path.exists() for path in all_modules):
        raise SystemExit('Run (cd java && ./mvnw -Padapters verify) before generating candidates')
    module_path = os.pathsep.join(str(path) for path in all_modules)
    if args.dependency_path:
        module_path += os.pathsep + args.dependency_path
    for module, artifact in MODULES.items():
        source = JAVA / module / 'src' / 'main' / 'java'
        target = JAVA / module / 'target'
        # A fresh output directory avoids retaining pages from removed types.
        import tempfile
        with tempfile.TemporaryDirectory(prefix='javadoc-', dir=target) as directory:
            docs = Path(directory)
            command = [tool('javadoc'), '-quiet', '-notimestamp', '-Xdoclint:all,-missing', '--release', '25',
                       '--module-path', module_path, '-d', directory]
            command.extend(str(path) for path in sorted(source.rglob('*.java')))
            subprocess.run(command, check=True)
            legal = [('META-INF/' + name, ROOT / name) for name in ('LICENSE', 'NOTICE')]
            sources_jar = target / f'{artifact}-{release_version}-sources.jar'
            docs_jar = target / f'{artifact}-{release_version}-javadoc.jar'
            archive(sources_jar, [(str(path.relative_to(source)), path) for path in source.rglob('*.java')] + legal)
            archive(docs_jar, [(str(path.relative_to(docs)), path) for path in docs.rglob('*') if path.is_file()] + legal)
            for jar in (sources_jar, docs_jar):
                digest = hashlib.sha256(jar.read_bytes()).hexdigest()
                jar.with_suffix(jar.suffix + '.sha256').write_text(digest + '  ' + jar.name + '\n')
                print(jar.relative_to(ROOT))


if __name__ == '__main__':
    main()
