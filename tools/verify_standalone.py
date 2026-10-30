"""Run Python regression checks with optional filesystem isolation from another directory.

Run from a detached copy with --blocked-root pointing to the unavailable source
workspace. Runtime imports and file reads into that directory fail immediately.
"""
import argparse
import os
from pathlib import Path
import sys
import unittest


def main(argv=None):
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--blocked-root',type=Path,help='Optional directory that this isolated copy must not read')
    args=parser.parse_args(argv)
    project=Path(__file__).resolve().parents[1]
    if args.blocked_root:
        blocked=args.blocked_root.resolve()
        if project.is_relative_to(blocked):
            parser.error('Run this check from a separate copy outside --blocked-root')
        def guard(event,values):
            if event in {'open','os.listdir','os.scandir'} and values and isinstance(values[0],(str,bytes,os.PathLike)):
                path=Path(os.fsdecode(values[0])).resolve()
                if path.is_relative_to(blocked):
                    raise AssertionError('Unexpected access outside the isolated checkout: '+str(path))
        sys.addaudithook(guard)
    suite=unittest.defaultTestLoader.discover(str(project/'tools/tests'))
    result=unittest.TextTestRunner(verbosity=2).run(suite)
    return 0 if result.wasSuccessful() else 1


if __name__=='__main__':
    raise SystemExit(main())
