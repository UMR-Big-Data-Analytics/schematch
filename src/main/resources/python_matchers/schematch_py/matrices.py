"""Bundled matcher implementation; see SOURCES.json for provenance."""
from __future__ import annotations
import csv
from pathlib import Path
from typing import Tuple,List,Optional
import numpy as np

def _parse_numeric(token: str) -> float:
    """Parse a plain number or schematch's ``binary / similarity`` cell."""
    value = token.strip()
    if "/" in value:
        parts = value.split("/")
        if len(parts) != 2:
            raise ValueError(f"not a numeric matrix cell: {token!r}")
        # Validate the selected binary match as well, but return the underlying
        # soft score.  Selection is deliberately redone by this harness.
        float(parts[0].strip())
        value = parts[1].strip()
    return float(value)


def _looks_numeric(token: str) -> bool:
    try:
        _parse_numeric(token)
    except ValueError:
        return False
    return True


def read_matrix(
    path: Path, delimiter: str = ","
) -> Tuple[np.ndarray, Optional[List[str]], Optional[List[str]]]:
    """Read a dense matrix, auto-detecting schematch's verbose header/labels.

    Returns (matrix, row_labels, col_labels); labels are None for the plain
    variant.  Ragged rows are rejected loudly rather than silently padded,
    because a truncated matrix would quietly corrupt every downstream metric.
    """
    with open(path, newline="", encoding="utf-8-sig") as fh:
        rows = [r for r in csv.reader(fh, delimiter=delimiter) if r and any(c.strip() for c in r)]
    if not rows:
        raise ValueError(f"{path} is empty")

    col_labels: Optional[List[str]] = None
    # A header row is present iff its first cell is not a number.
    if not _looks_numeric(rows[0][0].strip()):
        col_labels = [c.strip() for c in rows[0][1:]]
        rows = rows[1:]
        if not rows:
            raise ValueError(f"{path} has a header but no data rows")

    row_labels: Optional[List[str]] = None
    # A label column is present iff the first cell of a data row is not a number.
    if not _looks_numeric(rows[0][0].strip()):
        row_labels = [r[0].strip() for r in rows]
        rows = [r[1:] for r in rows]

    widths = {len(r) for r in rows}
    if len(widths) != 1:
        raise ValueError(f"{path} has ragged rows (widths {sorted(widths)})")

    matrix = np.array([[_parse_numeric(c) for c in r] for r in rows], dtype=float)
    return matrix, row_labels, col_labels
