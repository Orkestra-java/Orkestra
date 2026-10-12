"""Shared Maven layout for repository validation tools."""
import os
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent.parent
JAVA = ROOT / 'java'
NS = {'m': 'http://maven.apache.org/POM/4.0.0'}
MODULES = {
    'core': 'orkestra-core',
    'adapters/logging': 'orkestra-observability',
    'adapters/opentelemetry': 'orkestra-opentelemetry',
    'adapters/spring': 'orkestra-spring',
}


def version():
    return ET.parse(JAVA / 'pom.xml').getroot().findtext('m:version', namespaces=NS)


def artifacts(include_adapters=True):
    return {folder: JAVA / folder / 'target' / f'{name}-{version()}.jar' for folder, name in MODULES.items() if include_adapters or folder == 'core'}


def jdk_tool(name):
    return str(Path(os.environ['JAVA_HOME'], 'bin', name)) if os.environ.get('JAVA_HOME') else name


if __name__ == '__main__':
    print(os.pathsep.join(str(jar) for folder, jar in artifacts(False).items()))
