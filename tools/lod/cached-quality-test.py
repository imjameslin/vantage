#!/usr/bin/env python3
"""Bounded standalone quality check. Default: CPU only, no Metal device creation.

After obtaining the parent's serialized GPU slot:
  python3 tools/lod/cached-quality-test.py --gpu
No game, production build, benchmark or committed/generated repo artifacts.
"""
import argparse
import os
from pathlib import Path
import subprocess
import tempfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--gpu', action='store_true', help='run GPU comparisons; requires prior parent clearance')
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    java_home = Path(os.environ.get('CACHED_QUALITY_JAVA_HOME',
        str(root / 'mc/runtime/java-runtime-epsilon/jre.bundle/Contents/Home')))
    with tempfile.TemporaryDirectory(prefix='lod-cached-quality-') as tmp:
        work = Path(tmp)
        def run(argv):
            subprocess.run([str(x) for x in argv], cwd=root, check=True)
        run(['xcrun', 'clang++', '-std=c++17', '-O2', '-fobjc-arc', '-ffp-contract=off',
             '-Wall', '-Wextra', root / 'tools/lod/cached-quality-test.mm',
             '-framework', 'Foundation', '-framework', 'Metal', '-o', work / 'runner'])
        run([work / 'runner', '--cpu-only'])
        run([java_home / 'bin/javac', '-d', work, root / 'tools/PerceptualDiff.java',
             root / 'tools/lod/cached-quality-test.java'])
        # selftest() calls System.exit, so run it in its own JVM.
        run([java_home / 'bin/java', '-XX:ActiveProcessorCount=2', '-cp', work,
             'PerceptualDiff', '--selftest'])
        run([java_home / 'bin/java', '-XX:ActiveProcessorCount=2', '-cp', work,
             'CachedQualityReferenceTest', work / 'fixtures'])
        if args.gpu:
            run([work / 'runner', '--gpu', root / 'native/tests/lod_cached_quality.metal', work / 'fixtures'])
        else:
            print('GPU comparisons NOT RUN; rerun with --gpu only after parent clearance.', flush=True)


if __name__ == '__main__':
    main()
