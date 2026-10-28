"""Vendored from harness/class_comparison_conditioned.py (see SOURCES.json); do not edit, re-vendor instead."""
from __future__ import annotations
import csv


def read_columns(path, max_rows=5000):
    with path.open(newline="", encoding="utf-8", errors="replace") as fh:
        r = csv.reader(fh)
        header = next(r, [])
        cols = [[] for _ in header]
        for n, row in enumerate(r):
            if n >= max_rows:
                break
            for i in range(len(header)):
                cols[i].append(row[i] if i < len(row) else "")
    return header, cols
