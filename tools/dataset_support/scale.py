"""Deterministic synthetic scaling scenarios in the portable Valentine interchange layout."""

from __future__ import annotations

import csv
import hashlib
import json
import random
import sys
from pathlib import Path

SIZES = (16, 32, 64, 128, 256)
REPS = (1, 2, 3)


def cells() -> list[tuple[int, int, float, int]]:
    out = [(n, m, 0.3, 2000) for n in SIZES for m in SIZES]                       # grid 1: size
    out += [(64, 64, rho, 2000) for rho in (0.0, 0.15, 0.45, 0.6)]                # grid 2: FD density
    out += [(64, 64, 0.3, r) for r in (500, 8000)]                                # grid 3: rows
    return out


def name(n: int, m: int, rho: float, r: int, rep: int) -> str:
    return f"SCn{n:03d}m{m:03d}d{round(rho * 100):02d}r{r:04d}s{rep}"


def universe(width: int, rho: float, rng: random.Random) -> list[dict]:
    n_der = round(width * rho)
    n_base = width - n_der
    cols = []
    for i in range(n_base):
        cols.append({"kind": "base", "bid": i, "card": rng.randint(3, 20), "type": "int" if i % 2 == 0 else "str"})
    for j in range(n_der):
        arity = 1 if j % 2 == 0 else 2
        parents = rng.sample(range(n_base), arity)
        k = rng.randint(2, 8)
        cols.append({"kind": "derived", "parents": parents, "k": k, "salt": rng.getrandbits(32),
                     "type": "int" if j % 2 == 0 else "str"})
    order = list(range(width))
    rng.shuffle(order)               # derived and base columns interleaved in the universe
    return [cols[i] for i in order], order


def value(col: dict, v: int) -> str:
    return str(v) if col["type"] == "int" else f"v{v:02d}x"


def rows(cols: list[dict], idx: list[int], r: int, rng: random.Random) -> list[list[str]]:
    bases = sorted((c for c in cols if c["kind"] == "base"), key=lambda c: c["bid"])
    out = []
    for _ in range(r):
        base = [rng.randrange(c["card"]) for c in bases]      # every base column, also those not on this side
        row = []
        for p in idx:
            c = cols[p]
            if c["kind"] == "base":
                row.append(value(c, base[c["bid"]]))
            else:
                key = f"{c['salt']}:" + ",".join(str(base[q]) for q in c["parents"])
                row.append(value(c, int(hashlib.md5(key.encode()).hexdigest(), 16) % c["k"]))
        out.append(row)
    return out


def token(rng: random.Random, used: set) -> str:
    while True:
        t = "".join(rng.choice("abcdefghijklmnopqrstuvwxyz") for _ in range(8))
        if t not in used:
            used.add(t)
            return t


def one(out: Path, n: int, m: int, rho: float, r: int, rep: int) -> int:
    stem = name(n, m, rho, r, rep)
    rng = random.Random(f"X51/{stem}")
    o = min(n, m) // 2
    width = n + m - o
    cols, _ = universe(width, rho, rng)
    src_idx = list(range(0, n))
    tgt_idx = list(range(n - o, n - o + m))
    rng_t = random.Random(f"X51/{stem}/target-order")
    rng_t.shuffle(tgt_idx)
    used: set = set()
    names = {p: token(rng, used) for p in range(width)}
    tnames = {p: token(rng, used) for p in range(width)}
    d = out / "Valentine-datasets" / stem / stem
    d.mkdir(parents=True, exist_ok=True)
    for side, idx, nm, seed in (("source", src_idx, names, "s"), ("target", tgt_idx, tnames, "t")):
        data = rows(cols, idx, r, random.Random(f"X51/{stem}/rows/{seed}"))
        with (d / f"{stem}_{side}.csv").open("w", encoding="utf-8", newline="") as fh:
            w = csv.writer(fh)
            w.writerow([nm[p] for p in idx])
            w.writerows(data)
    matches = [{"source_table": f"{stem}_source", "source_column": names[p],
                "target_table": f"{stem}_target", "target_column": tnames[p]}
               for p in src_idx if p in set(tgt_idx)]
    assert len(matches) == o, (stem, len(matches), o)
    (d / f"{stem}_mapping.json").write_text(json.dumps({"matches": matches}, indent=1), encoding="utf-8")
    return o


def main() -> int:
    out = Path(sys.argv[1])
    n = 0
    for (nn, mm, rho, r) in cells():
        for rep in REPS:
            one(out, nn, mm, rho, r, rep)
            n += 1
    print(f"{n} scenarios ({len(cells())} cells x {len(REPS)}) -> {out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
