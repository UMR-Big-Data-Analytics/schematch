"""Vendored from harness/anchors.py (see SOURCES.json); do not edit, re-vendor instead."""
from __future__ import annotations
import csv
from pathlib import Path


NULLS = {"", "null", "none", "nan", "na", "n/a", "-"}


def column_values(table_path: Path):
    """Distinct non-null values per column, keyed by 'Table.column'."""
    name = table_path.stem
    with table_path.open(newline="", encoding="utf-8", errors="ignore") as fh:
        reader = csv.reader(fh)
        try:
            header = next(reader)
        except StopIteration:
            return {}
        sets = [set() for _ in header]
        for row in reader:
            for i, value in enumerate(row[:len(header)]):
                v = value.strip()
                if v.lower() not in NULLS:
                    sets[i].add(v)
    return {f"{name}.{col.strip()}": sets[i] for i, col in enumerate(header)}


def anchor_score(va: set, vb: set, variant: str, background: int = 0) -> float:
    """Inclusion evidence between two value sets.

    The chance baseline is the share of the *scenario's whole value pool* that
    the referenced column covers, not the share of the pair's own union. Using
    the pair's union is wrong in the most important case: two identical columns
    then have a baseline of 1 and score zero, exactly inverting the signal.
    """
    if not va or not vb:
        return 0.0
    shared = len(va & vb)
    if shared == 0:
        return 0.0
    cov_ab = shared / len(va)
    cov_ba = shared / len(vb)

    if variant == "cov":
        return cov_ab
    if variant == "both":
        return min(cov_ab, cov_ba)
    if variant == "both_card":
        # Overlapping on many distinct values is harder to achieve by accident
        # than overlapping on two, so damp small-cardinality agreement.
        return min(cov_ab, cov_ba) * (1.0 - 1.0 / (1.0 + shared))

    n = background if background > 0 else len(va | vb)
    b_ab = min(len(vb) / n, 1.0)
    b_ba = min(len(va) / n, 1.0)
    g_ab = (cov_ab - b_ab) / (1 - b_ab) if b_ab < 1 else 0.0
    g_ba = (cov_ba - b_ba) / (1 - b_ba) if b_ba < 1 else 0.0

    if variant == "gcov":
        return max(0.0, g_ab)
    if variant == "gcov_both":
        return max(0.0, min(g_ab, g_ba))
    raise ValueError(variant)
