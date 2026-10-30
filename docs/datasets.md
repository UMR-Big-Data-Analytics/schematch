# Dataset acquisition and preparation

[Complete source/recipe inventory](reference/dataset-catalog.md) · [Machine-readable source catalog](../tools/dataset_sources.json)

The catalog has **23 source/recipe IDs**: five checked download-and-prepare
recipes, five converters for manually acquired inputs, two generators using local
MusicBrainz/Lahman or TPC-H/AdventureWorks/Chinook inputs, one standalone synthetic
generator, and ten sources
with acquisition guidance but no direct converter. These counts describe
preparation support, not the number of independent datasets or matching tasks.

Keep dataset bytes outside Git. Commit this guide, the scripts, source URLs,
checksums, conversion rules and analysis manifests. The default working directory
`datasets-local/` is ignored by Git; `--work` can point to another drive. Raw dataset files and derived matrices are not included in this source release.

The tool runs from a **standalone Schematch checkout**, using Python 3.10+ and only
the standard library. It does not require Valentine, pandas, NumPy, a matcher
server or the surrounding research workspace. Java is optional for preparation,
and required for `--validate-java` and matching. No data is downloaded by `list`
or by preparation from `--input`.

## Quick start

Run these commands from the Schematch project directory:

```powershell
python tools/prepare_datasets.py list

# Small public benchmark: download, verify, convert and write datasets.yaml.
python tools/prepare_datasets.py prepare t2dsm --download

# Or use an already downloaded/extracted release, entirely offline.
python tools/prepare_datasets.py prepare t2dsm --input datasets-local/inputs/t2d_sm

# One generated smoke scenario; no external files or network required.
python tools/prepare_datasets.py prepare scale --smoke
```

The two T2D examples are alternatives: existing outputs are never overwritten.
Choose a new `--output` or `--work` for a second preparation. A failed conversion
does not publish a partial prepared dataset. Fetch verifies cached downloads on
reuse; a partial extraction without a completion marker requires a fresh work
directory. No source download is executed as Python code.

Output is `datasets-local/prepared/<source>/`, containing normal Schematch datasets,
`datasets.yaml`, `preparation_manifest.json`, and applicable import/conversion
reports. Copy the generated YAML documents into `src/main/resources/datasets.yaml`
(or your run's configuration). Paths in those documents are relative to `defaultDatasetBasePath` (default: `data/`).
Use `--dataset-base` if your configuration uses another base directory. Keep the
project and data directories in the same relative arrangement when moving them. The tool
does not replace the active configuration. It streams every exported table to
check its headers and row widths; the importer additionally validates mapping
identities. For full Java ground-truth/block validation add `--validate-java`:

```powershell
mvn -q -DskipTests compile dependency:build-classpath -Dmdep.outputFile=target/cp.txt
python tools/prepare_datasets.py prepare t2dsm --download --validate-java --work datasets-local
```

When storing data on another drive, choose `--dataset-base` on that drive as well
and configure the same base for Schematch. Relative paths cannot span Windows
drive roots. Exported provenance paths are relative to the prepared dataset root;
`conversion-input/` denotes a temporary conversion stage identified by its hashes.

Downloads use the URLs and checksums in [dataset_sources.json](../tools/dataset_sources.json).
All supplied downloads have recorded SHA-256 checksums except Valentine's fixed
Zenodo release, which is checked against its published MD5; prepared CSV files
always receive SHA-256 hashes. URL/checksum changes require a deliberate catalog
update, never accepting changed bytes silently. Google Drive and publisher sites
can return interstitial pages or deny automated access; those fail verification.
Obtain the same file through the official page and use `--input` instead.

## Public matching benchmarks

The names in the first column are the script's source IDs. A source family can
produce many datasets and transformed corpora; the 111 audited corpora are not
111 separately downloadable public releases.

| Source ID | Official acquisition source | Preparation and limitations |
|---|---|---|
| `valentine` | [Valentine v1, Zenodo 5084605](https://zenodo.org/records/5084605), approximately 602 MB ZIP | Download + import. Includes ChEMBL, TPC-DI, OpenData, Magellan and Wikidata. Unionable, View-Unionable, Joinable and Semantically-Joinable are scenario types within the release. |
| `t2dsm` | [WDC Schema Matching Benchmark](https://webdatacommons.org/structureddata/smb/), WH archive linked by [wdc-smb](https://github.com/wbsg-uni-mannheim/wdc-smb) | Download + convert all 178 WH pairs. Original train/valid/test membership is retained in `conversion_manifest.json`. No model is trained by this tool. |
| `hdxsm` | [SMUTF/HDXSM authors' repository](https://github.com/MAGICS-LAB/SMUTF); recorded `hdx_datasets_0.2.zip` Drive URL in catalog | Download + convert 204 release pairs. A historical 199-case subset requires a separate selection manifest; full conversion includes 204 pairs. |
| `gdcsm` | [Magneto GDC benchmark, Zenodo 14963588](https://zenodo.org/records/14963588); [Magneto repository](https://github.com/VIDA-NYU/magneto-matcher) | Download target/mappings and ten original supplementary XLSX files, then convert all ten studies. Supplement URLs and hashes are pinned individually. |
| `gdc` | Same Magneto release, already prepared CSV variant | Offline import from `<input>/gdc/{source-tables,target-tables,ground-truth}`. This retains the existing CSV-adapter behavior; use `gdcsm` for the original-supplement conversion from the original supplementary files. |
| `madi` | [MaDI-Bench](https://github.com/wbsg-uni-mannheim/MaDI-Bench), pinned commit `655faa751536b0c33150f681adc1a305c570a114` | Obtain the checkout manually, then convert five **base** tasks to 18 unordered source pairs through their unified-schema mappings. Its whole pipeline need not be installed. |
| `goby` | [GOBY official download page](https://goby-benchmark.github.io/), approximately 1.53 GB ZIP, password published there: `GOBY2025` | Acquire/extract manually, then prepare the frozen seeded 200-wrapper-pair sample. The catalog pins the input archive hash. |
| `mimic` | [MIMIC-IV demo 2.2](https://physionet.org/content/mimic-iv-demo/2.2/), [OMOP demo 0.9](https://physionet.org/content/mimic-iv-demo-omop/0.9/), [Parciak ground truth](https://github.com/UHasselt-DSI-Data-Systems-Lab/code-schema-matching-LLMs-artefacs/blob/main/benchmark/ground_truth.csv) | Download + convert one multi-table scenario, 7 source/6 target tables and 49 positive correspondences. Uses public demos. The OMOP rows derive from the same patients; this is not independent-instance evidence. |
| `synthea` | [OMAP/SMAT truth](https://github.com/JZCS2018/SMAT), [Synthea sample data](https://synthetichealth.github.io/synthea-sample-data/), [OHDSI Eunomia](https://github.com/OHDSI/Eunomia) GiBleed 5.3 | Acquire three inputs manually; portable converter produces 39 full/pair scenarios across three seeds. Historical archive hashes are below. |

Examples:

```powershell
python tools/prepare_datasets.py prepare valentine --download --skip-invalid
python tools/prepare_datasets.py prepare hdxsm --download
python tools/prepare_datasets.py prepare gdcsm --download
python tools/prepare_datasets.py prepare mimic --download

# Local MaDI checkout root, containing "use cases/".
python tools/prepare_datasets.py prepare madi --input datasets-local/inputs/MaDI-Bench
python tools/prepare_datasets.py prepare goby --input datasets-local/inputs/goby
python tools/prepare_datasets.py prepare synthea --input datasets-local/inputs/synthea_raw
```

`--skip-invalid` is an explicit choice, not a default. The original Valentine/GDC
snapshot contains missing files or unresolved mapping labels; a historical
bulk import selected 390 of 547 discovered candidates. Counts depend on the exact
input release and corrections. The tool writes an external audit report even if
it refuses import. Select individual cases with repeatable scenario-ID globs:

```powershell
python tools/prepare_datasets.py prepare valentine --input datasets-local/inputs/raw `
  --select 'valentine/TPC-DI/Joinable/*' --output datasets-local/inputs/ready/tpcdi-joinable
```

`--input` for Valentine must contain `Valentine-datasets/`, not be that folder
itself. See the generated import report for exact scenario IDs. To import a newly
generated Valentine-layout corpus use source ID `valentine-layout`.

Corrections can be supplied with `--corrections fixes.json`; for example the older
GDC CSV snapshot has a documented `Gillette`/`Gilette` filename mismatch:

```json
{"gdc/Gillette":{"files":{"source":"gdc/source-tables/Gilette.csv"}}}
```

Use `--trim-header-whitespace` only when that correction is intended. Unresolved
semantic aliases are errors. Corrections, exclusions and many-to-many truth are
recorded, never inferred from matcher scores.

### Additional local-input layouts

GOBY's input directory must contain `wrappers/goby_<id>.csv`,
`goby_schema_mappings.csv`, `unified_schema.csv`, and
`goby_data_csv_files.tar.gz`. Extract the latter to `wrappers/` with your archive
tool. Eligibility is 20-50,000 rows and at least three mapped displayed columns;
unknown/`NO_DISPLAY` fields are excluded. Pair selection uses seed `20261006`.
Pairs with no shared unified attribute remain all-negative cases, under the converter's sampling policy. Derived pairwise truth is not a new manual annotation.

Synthea's input layout is:

```text
synthea_raw/
  omop_synthea_data.xlsx
  synthea/*.csv
  omop/GiBleed_5.3/*.csv
```

Historical archive SHA-256 values (a moving `latest` URL is insufficient for an
exact rerun; obtain the matching archive or record a new dataset version):

| Input | SHA-256 |
|---|---|
| `synthea_sample_data_csv_latest.zip` | `d61417b551e5b0997c33851b339c157421751f0ea68c18ea686ceb1850907c35` |
| `GiBleed_5.3.zip` | `081b82e84c90e2904e02c26546e46412b01cce566071c77e0c4a355c2d0612f9` |
| `omop_synthea_data.xlsx` | `a915aafa779f5d1ca0ee0e57edbc9ee0defadbf660d5e58d057bff5d87cff7de` |

The Synthea adapter records predefined header aliases, unavailable attributes,
lost truth, the 5,000-row child-table cap, whole parent tables and seeds
20261005/20261006/20261007 in its conversion report. GDC conversion reproduces
the supplementary-table cleaning rules and marks its target as a **domain catalogue**,
whose rows do not represent joint observations. HDXSM conversion records removed
empty columns, header normalization and truth references absent from a table;
these conversions are not advertised as byte-preserving raw releases.

## Relational sources used to generate matching tasks

Raw databases do not include schema-matching ground truth. Generating tasks
requires creating normalized or denormalized views, sampling rows and deriving
correspondences from schema provenance. Preserve the exact source snapshot and generation recipe.

| Source | Official acquisition | Required preparation |
|---|---|---|
| MusicBrainz | [Dated database dumps](https://musicbrainz.org/doc/MusicBrainz_Database/Download) | Headerless PostgreSQL TSV with the recorded schema version. `controlled` uses `medium`, `release`, `release_group`. |
| Lahman | [SABR Lahman database](https://sabr.org/lahman-database/) | Versioned CSV export. `controlled` uses `Batting.csv`, `People.csv`, `Teams.csv`. |
| AdventureWorks | [Microsoft SQL Server samples](https://github.com/microsoft/sql-server-samples/tree/master/samples/databases/adventure-works) | Pin the release and CSV-export policy; preserve table-qualified names such as `Sales.*` and `Person.*`. |
| IMDb | [IMDb dataset download](https://datasets.imdbws.com/); [usage terms and format](https://data.imdb.com/non-commercial-datasets/) | Retain a dated TSV snapshot, including `title.basics`, `name.basics`, `title.principals` and the other tables named by the recipe. |
| Sakila | [MySQL Sakila documentation/download](https://dev.mysql.com/doc/sakila/en/) | Load the sample SQL database and export the declared tables with a fixed null/CSV policy. |
| SCOPe | [SCOPe downloads](https://scop.berkeley.edu/downloads/) | The historical converter input was a relational snapshot with `scop_node`, `scop_comment`, `scop_level`; current flat files need conversion and are not that snapshot. |
| TPC-H | [TPC-H specification/tools](https://www.tpc.org/tpch/) | Record dbgen build, scale factor and seed, then export the required tables. |
| Chinook | [Chinook v1.4.5 SQLite release](https://github.com/lerocha/chinook-database/releases/tag/v1.4.5) | Keep `Chinook_Sqlite.sqlite`; the independent-sampling recipe constructs matching schemas from it. |
| Amalgam bibliography | [Original Amalgam suite](https://www.cs.toronto.edu/~miller/amalgam/) | Acquire instances and the exact correspondence annotation separately. The schema page alone supplies neither a separately curated correspondence set nor an interchangeable mapping. |
| EFES bibliography/music | [HPI repeatability page, Integration scenarios](https://hpi.de/naumann/projects/repeatability/data-integration/estimating-data-integration-and-cleaning-effort.html) | Retain its published mappings. Separately curated `final_gt` correspondence annotations are a separately versioned input; do not silently substitute EFES truth. |

The portable controlled generator handles MusicBrainz + Lahman:

```text
<input>/Musicbrainz/{medium,release,release_group}
<input>/Lahman/{Batting.csv,People.csv,Teams.csv}
```

```powershell
python tools/prepare_datasets.py prepare controlled --input datasets-local/inputs/relational `
  --rows 5000 --seed 20260904
```

This generates 16 scenarios: two domains × four relationship types × plain and
the domain's structural regime. Hash-ranked sampling, joins, truth and transforms
are preserved from the research generator; invariant checks run before import.
It is the controlled v2 construction, not a claim to recreate every later v3 or
final-controlled experiment with the same command.

The independent-sample recipe builds multi-table scenarios from TPC-H,
AdventureWorks and Chinook:

```text
<input>/TPCH/{region,nation,customer,supplier,part,orders,lineitem}.csv
<input>/AdventureWorks/Sales.SalesOrderHeader.csv, Sales.SalesOrderDetail.csv, Sales.Customer.csv,
    Production.Product.csv, Production.ProductSubcategory.csv, Production.ProductCategory.csv,
    Person.Address.csv, Person.StateProvince.csv, Person.CountryRegion.csv, Sales.SalesTerritory.csv
<input>/Chinook_Sqlite.sqlite          # or an existing export in <input>/chinook/
```

```powershell
python tools/prepare_datasets.py prepare indep --input datasets-local/inputs/indep
```

The TPC-H and AdventureWorks inputs are semicolon-delimited CSV exports with a
header row; record their export policy with the data. Each database has a
normalized variant N and two denormalized variants D1/D2 that join referenced
tables into the referencing table. A seeded sample of base rows (1,500 orders,
1,500 sales orders or 150 invoices) is closed under foreign keys. The target side
uses the same, a half-overlapping or a disjoint sample. For three seeds, three
overlap conditions and the pairings N-D1, N-D2 and D1-D2, this gives 27 scenarios
per database. Truth is derived from schema provenance: columns correspond when they
hold the same base column, with roles agreeing where both carry one. It is n:m,
because denormalized keys occur in several tables. Each scenario includes
`provenance.json` with the column provenance. `indep-construction.json` records
input hashes and the number of shared base rows. On the original inputs, all 81
scenarios (3,438 files) match the historical construction byte for byte. Its
profiling and grain checks are not part of the recipe.

## Transformed variants of prepared datasets

`tools/transform_dataset.py` writes recoded, relabelled or permuted copies of a
prepared corpus root, which contains `<dataset>/<scenario>/` directories. It uses
the standard library only, never overwrites output, selects scenarios with
repeatable `--select <dataset>/<scenario>` globs, and records settings, per-scenario
results and script hashes in `transformation_manifest.json`. Truth must be an
unlabeled 0/1 matrix; labeled truth is rejected.

```powershell
python tools/transform_dataset.py md5 --input prepared/valentine --output prepared/valentine-md5
python tools/transform_dataset.py opaque-labels --input prepared/valentine --output prepared/valentine-opaque --fraction 1.0
python tools/transform_dataset.py opaque-tables --variant on --input prepared/synthea --output prepared/synthea-on
python tools/transform_dataset.py permute --input prepared/synthea-on --output prepared/synthea-on-perm
python tools/transform_dataset.py knobs --input prepared/valentine --output prepared/valentine-knobs --select "ChEMBL-*/*"
```

| Command | Transformation | Scope and caveats |
|---|---|---|
| `md5` | Labels and non-null values become MD5 digests with a per-side salt derived from `--seed`, dataset, scenario and side. Values in `NULLS` (empty, `null`, `none`, `nan`, `na`, `n/a`, `-`) are written empty, not hashed. FD/UCC/IND/DC label references and JSON column specs are relabelled; profile-status hashes are refreshed. | Single-table scenarios. Each side stays internally bijective, so null and distinct counts per column are verified. Cross-side value identity is removed by design. |
| `opaque-labels` | Header names become unsalted tokens (`c` + 12 hex digits of MD5), so a label shared by both sides stays shared; values are unchanged. `--fraction` obscures a seeded share of labels. | FD/UCC files are emptied and `profile-status.properties` is set to `not_profiled`. IND, scored JSON and `manifest.json` still name the original columns. |
| `opaque-tables` | Multi-table. Table and column names become salted digests; `--variant md5` also recodes values as `md5`. Label references in profile text, JSON and `provenance.json` are relabelled; no original qualified label may remain. | Table names keep their sorted order and truth files are unchanged, so every column keeps its readable position. Apply `permute` afterwards before relying on tie-breaking. |
| `permute` | Per side and table, a column order from `random.Random(SHA-256(key))`, redrawn until it differs from the original. Truth blocks and `ground_truth_full.csv` are permuted; label-addressed profiles stay valid; JSON column specs, `manifest.json` and table hashes are updated. | `--key` defaults to `{dataset}/{scenario}/{side}/{table}`; the permutations are stored in the manifest for `positional_audit.py rerun`. |
| `knobs` | Target-side controls: `base`, `K1` (seeded column order, truth follows), `K2p25..100` (a nested share of distinct values recoded, one mapping per side so dependencies hold), `K3p25..100` (a nested share of labels tokenized, profiles relabelled), `K4f50/25` (FD files thinned). Output folders are `<scenario>__<knob>`. | Single-table. `--key` defaults to `x79\|{dataset}\|{scenario}`, the case key of the original knob corpus. K1 does not renumber JSON column specs. |

Parity with the original research scripts was checked on prepared Valentine
scenarios and one multi-table bibliography scenario. Outputs of `md5`,
`opaque-tables` `on`/`md5` and all twelve `knobs` were byte-identical, including
the md5 cohort tables. `opaque-labels` differed only in the deliberately rewritten
`profile-status.properties`. With key `X87/madi/{scenario}/{side}`,
`permutation()` reproduced all 36 permuted MaDI tables and all 1,512 permuted
initial matrices of the column-order study. The original applied it to raw
Valentine-layout files rather than prepared scenarios.

## Repeating a published experiment

Downloading a source and reproducing an analysis are separate steps. An exact
rerun needs its selected cases, snapshots, row samples, transformations,
annotations, profiles and evaluation settings. Record these inputs in a versioned analysis manifest; dataset names alone do
not define an experiment.

## Dependency profiles and evaluation

Preparation produces table/truth inputs, not mined FD/UCC/IND profiles. Managed
metadata is marked `not_profiled`. Use `readDependencies: false` for matchers that
do not need profiles. Structural methods consume profiles supplied in the
standard `metadata/<side>/<table>/` layout. The preparation command does not mine
these profiles, and never starts a profiler from another checkout.

For managed single-table inputs, `metadata/profile-status.properties` must mark
`source.fd` and `target.fd` as `complete` and supply each side's `table.sha256`
and `fd.sha256`. The latter identifies the corresponding `FD_results.txt` bytes.
Keep the miner version and settings with the input manifest; the loader rejects
changed files. Legacy multi-table metadata retains its documented layout and
must be prepared explicitly by the dataset provider. A missing profile is not a
successfully empty profile. Domain catalogues such as the GDC target must not
receive invented tuple-level FDs.

For Recall@GT, MRR, Hungarian F1 (`F1_H`), permutation-averaged Hungarian F1
(`F1_Hr`), mutual matching and the other extraction rules, enable the optional
raw-score evaluator described in [evaluation](evaluation.md).
Evaluation needs NumPy/SciPy even though dataset preparation does not. Reproducing a published
summary also requires its selected cases, grouping, independent units and
permutation keys; generated dataset names alone do not define those choices.

Source-specific terms and attribution remain with their publishers; availability
for download does not by itself grant permission to redistribute every data file.
No data bytes or third-party model weights are added by this implementation.

## Sharing ground truth and dependency profiles

Ground truth and precomputed FD/UCC/IND profiles are useful reusable benchmark
assets. Distribute them for the exact prepared tables to which they apply.
Ground truth identifies columns; dependencies additionally depend on row samples,
null handling, normalization and the profiling settings. A database name alone
does not identify a compatible set of profiles.

### Current preparation support

| Step | Current behavior |
|---|---|
| Acquire raw data | `--download` fetches and checks the declared files for supported sources; local/manual sources need supplied inputs. |
| Convert or generate tables | The registered adapter writes the normal source/target layout and records conversion choices. |
| Ground truth | Converters import upstream correspondences or derive them from an explicit mapping/generation recipe. Ambiguous or unavailable truth is not inferred from matcher scores. |
| Dataset configuration | Preparation writes relative-path `datasets.yaml`; the user selects those entries for the active run. |
| FD/UCC/IND profiles | Preparation does not mine these. Optional `benchmark_assets.py` installation verifies a separately supplied bundle against prepared tables. Managed inputs remain `not_profiled` until compatible profiles are supplied. |
| Validation | Table checks and optional `--validate-java` validate CSV/truth loading. They do not establish that all dependency profiles have been mined or are semantically appropriate. |

`prepare_datasets.py --input` selects an acquired raw release, not a metadata
overlay. Use the separate [benchmark asset command](benchmark-assets.md) to
verify and attach a compatible bundle from Schema Matching Benchmark Datasets.
A prepared matching dataset with truth is not automatically ready for
dependency-based matchers.

### Recommended distribution

Keep the framework repository focused on software, its source catalog and small
synthetic test fixtures. Keep study-specific selections and reported results
with the paper's reproduction repository. The separate Schema Matching Benchmark
Datasets project contains reusable curated annotations, profile provenance and
versioned asset manifests. Its bundles are independently selectable by benchmark
and preparation variant. Small annotation files can be ordinary versioned files
there; large profile collections fit release archives. A paper should pin the
asset version and its own scenario selection.

[GitHub release assets](https://docs.github.com/en/repositories/working-with-files/managing-large-files/about-large-files-on-github)
can distribute files without tracking them in the code repository. A
[versioned Zenodo record](https://help.zenodo.org/docs/deposit/manage-versions/)
is another option for an archived dataset/profile release. Use a fixed version
and an archive checksum rather than a moving latest URL.

A Git submodule is useful when another source-code repository must be developed
as a dependency. It is not necessary to distribute these metadata files: optional
archives preserve a normal framework checkout and avoid making every user fetch
every benchmark. Source-specific permissions and attribution also apply to
annotations and profiles; distinguish upstream truth from new curated mappings.

### What an asset archive should record

An archive should include a manifest describing:

- the benchmark release and preparation recipe/version;
- the selected scenarios, sampling seeds and transformation settings;
- SHA-256 hashes of every required prepared source and target CSV;
- hashes of the supplied ground-truth and profile files;
- profiler name, version, parameters, null policy and completeness of each profile type;
- ground-truth origin, annotation version, corrections and attribution;
- the compatible Schematch format and any limitations of the supplied metadata.

Use paths relative to the prepared dataset root. For example, an archive can
mirror the following layout without containing the raw source/target CSVs:

```text
bundle_manifest.json
<dataset>/<scenario>/
  ground_truth_full.csv
  metadata/
    profile-status.properties
    source/
      <source-table-name>/FD_results.txt
      <source-table-name>/UCC_results.txt
      inds.txt
    target/
      <target-table-name>/FD_results.txt
      <target-table-name>/UCC_results.txt
      inds.txt
    source-to-target-inds.txt
    target-to-source-inds.txt
```

The asset command packages this layout below `payload/` in each archive and
records its exact files and required table hashes in `bundle_manifest.json`.
Pair-block truth under `ground_truth/` can be supplied instead of the full
matrix. Include only files corresponding to actual annotations and profiles;
an absent profile must not be represented as a successfully empty result.
For managed single-table FD inputs, retain the runtime status/checksum contract
described above. Other profile types also need provenance and explicit hash
verification; the FD guard alone does not validate them all.

### Download-and-attach workflow

The intended workflow for published assets is:

1. Download or acquire the pinned raw release.
2. Run the registered preparation recipe, producing tables, truth and file hashes.
3. Fetch the optional asset archive and verify its checksum.
4. Require its prepared-table hashes and scenario selection to match the local outputs.
5. Attach the matching annotations/profiles without silently replacing conflicting truth.
6. Validate loading, then enable `readDependencies` for methods requiring profiles.

Steps 1-2 use the preparation commands. Steps 3-5 use
`benchmark_assets.py verify/install`, with an explicitly supplied catalog or
archive; downloads require a published HTTPS URL and archive checksum in that
catalog. The companion project's v1.0.0 archives are published; its updated
catalog supplies their fixed URLs and SHA-256 checksums. Newly prepared bundles
remain local until they receive their own published URLs.
Do not present successful attachment as a complete reproduction of a study whose
selected cases, annotations or profiles are missing.

## Validation of this tool

To refresh the source list or check it without changing files:

```powershell
python tools/audit_dataset_sources.py
python tools/audit_dataset_sources.py --check
```

The audit checks registered preparation modes, download destinations and checksum
formats against the local catalog. It does not contact publishers. The generated
inventory is also checked by the normal Python test suite.
