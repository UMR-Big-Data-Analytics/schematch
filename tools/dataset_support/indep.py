"""Independent-sample multi-table scenarios from TPC-H, AdventureWorks and Chinook.

Per database, three schema variants of the same data: N (normalised, one output table per base table) and two
denormalised variants D1/D2, whose tables join referenced tables into their referencing table. A seeded sample of
base rows (orders / sales orders / invoices) is closed under foreign keys; the target side takes an
identical, half-overlapping or disjoint sample of base rows drawn from the same population. Each scenario pairs
variant a's source sample with variant b's target sample (N-D1, N-D2, D1-D2). Truth is schema provenance: a source
and a target column correspond when they hold the same (base table, base column), with agreeing roles where both
carry one; denormalised keys make it n:m. Target labels are snake_case (TPC-H: prefix letter dropped). Ground truth is
derived, not annotated. Recipe and provenance are recorded in SOURCES.json; the grain/profiling checks of the original
driver are not part of the construction and are omitted.

Input layout (semicolon-delimited CSV with a header row; a CSV export policy must be recorded with the data):
    <input>/TPCH/{region,nation,customer,supplier,part,orders,lineitem}.csv
    <input>/AdventureWorks/{Sales.SalesOrderHeader,Sales.SalesOrderDetail,Sales.Customer,Production.Product,
        Production.ProductSubcategory,Production.ProductCategory,Person.Address,Person.StateProvince,
        Person.CountryRegion,Sales.SalesTerritory}.csv
    <input>/Chinook_Sqlite.sqlite            (Chinook v1.4.5; exported to semicolon CSV, or supply <input>/chinook/*.csv)
"""

from __future__ import annotations

import csv
import hashlib
import json
import random
import re
import sqlite3
from pathlib import Path

SEEDS = (20260929, 20260930, 20260931)
CONDITIONS = ("identical", "half", "disjoint")
PAIRINGS = (("N", "D1"), ("N", "D2"), ("D1", "D2"))
DROP = {"rowguid", "ModifiedDate", "SpatialLocation", "AdditionalContactInfo", "Demographics"}
NULLS = {"", "null", "none", "nan", "na", "n/a", "-"}
csv.field_size_limit(2**31 - 1)

# tables: name -> (file, key column); fks: (table, column) -> referenced table;
# variants: output table -> (root table, [(join column as in the wide row, role or None)])
TPCH = {
    "name": "TPCH", "dir": "TPCH", "delimiter": ";", "base": "orders", "sample": 1500,
    "tables": {"region": ("region.csv", "R_REGIONKEY"), "nation": ("nation.csv", "N_NATIONKEY"),
               "customer": ("customer.csv", "C_CUSTKEY"), "supplier": ("supplier.csv", "S_SUPPKEY"),
               "part": ("part.csv", "P_PARTKEY"), "orders": ("orders.csv", "O_ORDERKEY"),
               "lineitem": ("lineitem.csv", None)},
    "fks": {("nation", "N_REGIONKEY"): "region", ("customer", "C_NATIONKEY"): "nation",
            ("supplier", "S_NATIONKEY"): "nation", ("orders", "O_CUSTKEY"): "customer",
            ("lineitem", "L_ORDERKEY"): "orders", ("lineitem", "L_PARTKEY"): "part",
            ("lineitem", "L_SUPPKEY"): "supplier"},
    "variants": {
        "N": {t: (t, []) for t in ("region", "nation", "customer", "supplier", "part", "orders", "lineitem")},
        "D1": {"customer": ("customer", [("C_NATIONKEY", None), ("N_REGIONKEY", None)]),
               "supplier": ("supplier", [("S_NATIONKEY", None), ("N_REGIONKEY", None)]),
               "lineitem": ("lineitem", [("L_PARTKEY", None)]),
               "orders": ("orders", [])},
        "D2": {"orders": ("orders", [("O_CUSTKEY", None)]),
               "lineitem": ("lineitem", [("L_SUPPKEY", None)]),
               "nation": ("nation", [("N_REGIONKEY", None)]),
               "part": ("part", [])}},
}
AW = {
    "name": "AdventureWorks", "dir": "AdventureWorks", "delimiter": ";", "base": "SalesOrderHeader", "sample": 1500,
    "tables": {"SalesOrderHeader": ("Sales.SalesOrderHeader.csv", "SalesOrderID"),
               "SalesOrderDetail": ("Sales.SalesOrderDetail.csv", "SalesOrderDetailID"),
               "Customer": ("Sales.Customer.csv", "CustomerID"),
               "Product": ("Production.Product.csv", "ProductID"),
               "ProductSubcategory": ("Production.ProductSubcategory.csv", "ProductSubcategoryID"),
               "ProductCategory": ("Production.ProductCategory.csv", "ProductCategoryID"),
               "Address": ("Person.Address.csv", "AddressID"),
               "StateProvince": ("Person.StateProvince.csv", "StateProvinceID"),
               "CountryRegion": ("Person.CountryRegion.csv", "CountryRegionCode"),
               "SalesTerritory": ("Sales.SalesTerritory.csv", "TerritoryID")},
    "fks": {("SalesOrderDetail", "SalesOrderID"): "SalesOrderHeader",
            ("SalesOrderDetail", "ProductID"): "Product",
            ("SalesOrderHeader", "CustomerID"): "Customer",
            ("SalesOrderHeader", "TerritoryID"): "SalesTerritory",
            ("SalesOrderHeader", "BillToAddressID"): "Address",
            ("SalesOrderHeader", "ShipToAddressID"): "Address",
            ("Customer", "TerritoryID"): "SalesTerritory",
            ("Product", "ProductSubcategoryID"): "ProductSubcategory",
            ("ProductSubcategory", "ProductCategoryID"): "ProductCategory",
            ("Address", "StateProvinceID"): "StateProvince",
            ("StateProvince", "CountryRegionCode"): "CountryRegion",
            ("StateProvince", "TerritoryID"): "SalesTerritory",
            ("SalesTerritory", "CountryRegionCode"): "CountryRegion"},
    "variants": {
        "N": {t: (t, []) for t in ("SalesOrderHeader", "SalesOrderDetail", "Customer", "Product",
                                   "ProductSubcategory", "ProductCategory", "Address", "StateProvince",
                                   "CountryRegion", "SalesTerritory")},
        "D1": {"sales_line": ("SalesOrderDetail", [("ProductID", None), ("ProductSubcategoryID", None),
                                                   ("ProductCategoryID", None)]),
               "orders": ("SalesOrderHeader", [("CustomerID", None)]),
               "address": ("Address", [("StateProvinceID", None), ("CountryRegionCode", None)]),
               "territory": ("SalesTerritory", [])},
        "D2": {"orders": ("SalesOrderHeader", [("BillToAddressID", "bill"), ("ShipToAddressID", "ship")]),
               "product": ("Product", [("ProductSubcategoryID", None)]),
               "customer": ("Customer", [("TerritoryID", None)]),
               "state": ("StateProvince", [("CountryRegionCode", None)]),
               "sales_detail": ("SalesOrderDetail", []),
               "category": ("ProductCategory", [])}},
}
CHINOOK = {
    "name": "Chinook", "dir": "chinook", "delimiter": ";", "base": "Invoice", "sample": 150,
    "tables": {t: (t + ".csv", k) for t, k in [("Artist", "ArtistId"), ("Album", "AlbumId"), ("Genre", "GenreId"),
                                               ("MediaType", "MediaTypeId"), ("Track", "TrackId"),
                                               ("Customer", "CustomerId"), ("Invoice", "InvoiceId"),
                                               ("InvoiceLine", "InvoiceLineId")]},
    "fks": {("Album", "ArtistId"): "Artist", ("Track", "AlbumId"): "Album", ("Track", "GenreId"): "Genre",
            ("Track", "MediaTypeId"): "MediaType", ("Invoice", "CustomerId"): "Customer",
            ("InvoiceLine", "InvoiceId"): "Invoice", ("InvoiceLine", "TrackId"): "Track"},
    "variants": {
        "N": {t: (t, []) for t in ("Artist", "Album", "Genre", "MediaType", "Track", "Customer", "Invoice", "InvoiceLine")},
        "D1": {"line": ("InvoiceLine", [("TrackId", None), ("AlbumId", None), ("ArtistId", None)]),
               "invoice": ("Invoice", [("CustomerId", None)]), "genre": ("Genre", []), "media": ("MediaType", [])},
        "D2": {"line": ("InvoiceLine", [("InvoiceId", None), ("CustomerId", None)]),
               "track": ("Track", [("GenreId", None), ("MediaTypeId", None)]),
               "album": ("Album", [("ArtistId", None)])}},
}
DATABASES = (TPCH, AW, CHINOOK)


def export_chinook(sqlite_file: Path, folder: Path) -> None:
    """Export the eight Chinook tables to semicolon CSV, as the original driver did (NULL -> empty)."""
    con = sqlite3.connect(f"file:{sqlite_file.as_posix()}?mode=ro", uri=True)
    try:
        for table, (filename, _) in CHINOOK["tables"].items():
            cur = con.execute('SELECT * FROM "' + table + '"')
            folder.mkdir(parents=True, exist_ok=True)
            with (folder / filename).open("w", encoding="utf-8", newline="") as f:
                w = csv.writer(f, delimiter=";")
                w.writerow([c[0] for c in cur.description])
                w.writerows(cur)
    finally:
        con.close()


def read_rows(db, root: Path, table, keep=None):
    path = root / db["dir"] / db["tables"][table][0]
    with path.open(encoding="utf-8-sig", newline="") as fh:
        reader = csv.reader(fh, delimiter=db["delimiter"])
        header = [h.strip() for h in next(reader)]
        cols = [i for i, h in enumerate(header) if h not in DROP]
        for row in reader:
            if len(row) != len(header):
                raise ValueError(f"{path}: ragged row")
            rec = {header[i]: row[i].strip() for i in cols}
            if keep is None or keep(rec):
                yield rec


def choose(keys, n, seed, condition, source=None):
    rng = random.Random(seed)
    if source is None:
        return set(rng.sample(keys, n))
    if condition == "identical":
        return set(source)
    if condition == "independent":
        return set(rng.sample(keys, n))
    shared = n // 2 if condition == "half" else 0
    outside = [x for x in keys if x not in source]
    return set(rng.sample(sorted(source), shared) + rng.sample(outside, n - shared))


def closure(db, root: Path, selected):
    """Selected base rows, the rows referencing them, and every referenced parent row, until closed."""
    base = db["base"]
    bk = db["tables"][base][1]
    rows = {base: list(read_rows(db, root, base, lambda r: r[bk] in selected))}
    keys = {base: set(selected)}
    for (t, c), ref in db["fks"].items():
        if ref == base and t not in rows:
            rows[t] = list(read_rows(db, root, t, lambda r, c=c: r[c] in selected))
    changed = True
    while changed:
        changed = False
        for (t, c), ref in db["fks"].items():
            if t not in rows or ref == base:
                continue
            needed = {r[c] for r in rows[t] if r[c]}
            have = keys.get(ref, set())
            if needed - have:
                k = db["tables"][ref][1]
                want = needed | have
                new = list(read_rows(db, root, ref, lambda r, k=k: r[k] in want))
                got = {r[k] for r in new}
                if not needed <= got:
                    raise ValueError(f"{db['name']}.{t}.{c}: {len(needed - got)} foreign keys without a parent")
                rows[ref], keys[ref], changed = new, got, True
    for t in db["tables"]:
        if not rows.get(t):
            raise ValueError(f"{db['name']}.{t}: empty after closure")
    return rows


def build_variant(db, rows, variant):
    """Output tables as (column provenance, rows, header). Provenance per column: [(base, col, role), ...]."""
    index = {t: {r[k]: r for r in rows[t]} for t, (_, k) in db["tables"].items() if k}
    out = {}
    for name, (root, joins) in db["variants"][variant].items():
        prov = [[(root, c, None)] for c in rows[root][0].keys()] if rows[root] else []
        where = {c: i for i, c in enumerate(rows[root][0].keys())}
        table_rows = [list(r.values()) for r in rows[root]]
        owner = {c: root for c in rows[root][0].keys()}
        for join_col, role in joins:
            ref = db["fks"][(owner[join_col], join_col)]
            ref_key = db["tables"][ref][1]
            ref_cols = [c for c in (rows[ref][0].keys() if rows[ref] else []) if c != ref_key]
            prov[where[join_col]].append((ref, ref_key, role))
            j = where[join_col]
            for c in ref_cols:
                label = f"{role}_{c}" if role else c
                if label in where:
                    label = f"{ref}_{c}"
                where[label] = len(prov)
                prov.append([(ref, c, role)])
                owner[label] = ref
            for r in table_rows:
                hit = index[ref].get(r[j], {})
                r.extend(hit.get(c, "") for c in ref_cols)
        out[name] = (prov, table_rows, list(where))
    return out


def snake(name: str) -> str:
    return re.sub(r"(?<=[a-z0-9])(?=[A-Z])", "_", name).lower()


def target_label(db_name, raw_label):
    if db_name == "TPCH":
        parts = raw_label.split("_", 1)
        return (parts[1] if len(parts) == 2 and len(parts[0]) == 1 else raw_label).lower()
    return snake(raw_label)


def labels_for(db, table_prov, raw_labels, side, table):
    names = [raw if side == "source" else target_label(db["name"], raw) for raw in raw_labels]
    seen = {}
    for i, n in enumerate(names):
        seen.setdefault(n, []).append(i)
    for n, idx in seen.items():
        if len(idx) > 1:
            for i in idx:
                base, _, role = table_prov[i][0]
                names[i] = f"{role or (base if side == 'source' else snake(base))}_{n}"
    if len(set(names)) != len(names):
        raise ValueError(f"{db['name']}/{table}: labels still collide")
    return names


def corresponds(sp, tp) -> bool:
    """Shared (base table, base column); roles must agree only when both sides carry one."""
    return any(b1 == b2 and c1 == c2 and (r1 is None or r2 is None or r1 == r2) for b1, c1, r1 in sp for b2, c2, r2 in tp)


def canonical(v):
    return "" if v.strip().lower() in NULLS else v.strip()


def representation(db, variant, rows, side):
    """{table: (labels, column-major canonical values, provenance)}, tables sorted by name."""
    out = {}
    for table, (prov, trows, raw) in sorted(build_variant(db, rows, variant).items()):
        cols = [[canonical(r[i]) for r in trows] for i in range(len(raw))]
        out[table] = (labels_for(db, prov, raw, side, table), cols, prov)
    return out


def write_csv(path: Path, rows) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", newline="", encoding="utf-8") as fh:
        csv.writer(fh).writerows(rows)


def write_scenario(scen: Path, db, seed, condition, sv, tv, srep, trep) -> int:
    for side, rep in (("source", srep), ("target", trep)):
        for table, (labels, cols, _) in rep.items():
            write_csv(scen / side / f"{table}.csv", [labels] + [list(r) for r in zip(*cols)])
    s_cols = [(t, l) for t, (labels, _, _) in srep.items() for l in labels]
    t_cols = [(t, l) for t, (labels, _, _) in trep.items() for l in labels]
    s_prov = [p for _, (_, _, prov) in srep.items() for p in prov]
    t_prov = [p for _, (_, _, prov) in trep.items() for p in prov]
    gt = [[int(corresponds(a, b)) for b in t_prov] for a in s_prov]
    if not any(map(any, gt)):
        raise ValueError(f"{scen}: no corresponding columns")
    (scen / "ground_truth").mkdir()
    for st in srep:
        rows = [i for i, (t, _) in enumerate(s_cols) if t == st]
        for tt in trep:
            cols = [j for j, (t, _) in enumerate(t_cols) if t == tt]
            write_csv(scen / "ground_truth" / f"{st}___{tt}.csv", [[gt[i][j] for j in cols] for i in rows])
    write_csv(scen / "ground_truth_full.csv", gt)
    (scen / "provenance.json").write_text(json.dumps(
        {"database": db["name"], "seed": str(seed), "condition": condition, "source_variant": sv, "target_variant": tv,
         "source_columns": s_cols, "target_columns": t_cols, "gt_pairs": sum(map(sum, gt))}, indent=1), encoding="utf-8")
    return sum(map(sum, gt))


def prepare(raw: Path, output: Path, seeds=SEEDS, databases=None, work: Path | None = None) -> dict:
    """Write Indep-<db>/s<seed>_<condition>_<a>-<b>/ scenarios below output; returns a manifest of inputs and counts.
    A Chinook export from the SQLite release goes to `work` (default: a temporary folder below output, removed)."""
    export = None
    chosen = [d for d in DATABASES if databases is None or d["name"] in databases]
    if any(d is CHINOOK for d in chosen) and not (raw / "chinook").is_dir():
        sqlite_file = raw / "Chinook_Sqlite.sqlite"
        if not sqlite_file.is_file():
            raise ValueError(f"Missing {raw / 'chinook'} or {sqlite_file}")
        export = work or output / ".chinook-export"
        export_chinook(sqlite_file, export / "chinook")
    manifest = {"seeds": list(seeds), "conditions": list(CONDITIONS), "pairings": ["-".join(p) for p in PAIRINGS],
                "inputs": {}, "scenarios": {}}
    for db in chosen:
        root = export if db is CHINOOK and export is not None else raw
        for filename, _ in db["tables"].values():
            path = root / db["dir"] / filename
            manifest["inputs"][f"{db['dir']}/{filename}"] = hashlib.sha256(path.read_bytes()).hexdigest()
        key = db["tables"][db["base"]][1]
        population = [r[key] for r in read_rows(db, root, db["base"])]
        n = db["sample"]
        if len(set(population)) != len(population) or len(population) < 2 * n:
            raise ValueError(f"{db['name']}: base keys must be unique and at least {2 * n}")
        for seed in seeds:
            src = choose(population, n, seed, "source")
            sr = closure(db, root, src)
            srep = {v: representation(db, v, sr, "source") for v in db["variants"]}
            for condition in CONDITIONS:
                selected = choose(population, n, seed + 100000, condition, src)
                tr = sr if condition == "identical" else closure(db, root, selected)
                trep = {v: representation(db, v, tr, "target") for v in db["variants"]}
                for sv, tv in PAIRINGS:
                    name = f"s{seed}_{condition}_{sv}-{tv}"
                    pairs = write_scenario(output / f"Indep-{db['name']}" / name, db, seed, condition, sv, tv,
                                           srep[sv], trep[tv])
                    manifest["scenarios"][f"Indep-{db['name']}/{name}"] = {
                        "gt_pairs": pairs, "shared_base_rows": len(src & selected)}
    if export is not None and work is None:
        import shutil
        shutil.rmtree(export)
    return manifest
