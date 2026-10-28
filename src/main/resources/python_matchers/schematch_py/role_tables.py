"""Vendored from harness/relational_role_ablation.py (see SOURCES.json); do not edit, re-vendor instead."""
from __future__ import annotations
import csv


def strict_table(path):
    # Some unchanged Valentine values exceed the default CSV field limit.
    csv.field_size_limit(2**31 - 1)
    with path.open(newline="", encoding="utf-8-sig") as handle:
        reader = csv.reader(handle)
        header = next(reader)
        columns = [[] for _ in header]
        if not header or len(set(h.strip() for h in header)) != len(header):
            raise ValueError(f"Empty/duplicate table header: {path}")
        for index, row in enumerate(reader, 2):
            if len(row) != len(header):
                raise ValueError(f"Ragged table at row {index}: {path}")
            for col, value in zip(columns, row):
                col.append(value)
    return [h.strip() for h in header], columns
