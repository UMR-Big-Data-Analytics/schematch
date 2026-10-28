"""Run the compiled Schematch application with a platform-independent classpath."""
import argparse
import os
from pathlib import Path
import subprocess
import sys

PROJECT = Path(__file__).resolve().parents[1]


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java',default='java',help='Java command from PATH')
    parser.add_argument('--jvm-arg',action='append',default=[],help='Repeatable JVM argument; use --jvm-arg=-Xmx4g')
    args = parser.parse_args(argv)
    compiled = PROJECT/'target/classes/de/uni_marburg/schematch/Main.class'
    dependencies = PROJECT/'target/cp.txt'
    if not compiled.is_file() or not dependencies.is_file():
        print('Build first: mvn compile dependency:build-classpath -Dmdep.outputFile=target/cp.txt',file=sys.stderr)
        return 1
    classpath = str(PROJECT/'target/classes') + os.pathsep + dependencies.read_text(encoding='utf-8-sig').strip()
    try:
        return subprocess.call([args.java,*args.jvm_arg,'-cp',classpath,'de.uni_marburg.schematch.Main'],cwd=PROJECT)
    except OSError as exc:
        print(f'Cannot start Java: {exc.strerror}. Check java on PATH.',file=sys.stderr)
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
