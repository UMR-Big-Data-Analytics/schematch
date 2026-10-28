"""Run the bundled scenario/pair regression checks without external datasets."""
from pathlib import Path
import runpy
import sys

if __name__ == "__main__":
    tests=Path(__file__).resolve().parents[4]/"tools/tests"
    sys.path.insert(0,str(tests))
    runpy.run_path(str(tests/"test_python_matchers.py"),run_name="__main__")
