# Dataset acquisition and preparation

[Complete source/recipe inventory](DATASET_INVENTORY.md) · [Machine-readable source catalog](tools/dataset_sources.json)

The catalog has **22 source/recipe IDs**: five checked download-and-prepare
recipes, five converters for manually acquired inputs, one generator using local
MusicBrainz/Lahman inputs, one standalone synthetic generator, and ten sources
with acquisition guidance but no direct converter. These counts describe
preparation support, not the number of independent datasets or paper scenarios.

Keep dataset bytes outside Git. Commit this guide, the scripts, source URLs,
checksums, conversion rules and analysis manifests. The default working directory
`datasets-local/` is ignored by Git; `--work` can point to another drive. Existing
tracked datasets are not removed or untracked by this change.

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

Downloads use the URLs and checksums in [dataset_sources.json](tools/dataset_sources.json).
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

| Source ID | Official acquisition source | Preparation and study relationship |
|---|---|---|
| `valentine` | [Valentine v1, Zenodo 5084605](https://zenodo.org/records/5084605), approximately 602 MB ZIP | Download + import. Includes ChEMBL, TPC-DI, OpenData, Magellan and Wikidata. Unionable, View-Unionable, Joinable and Semantically-Joinable are scenario types within the release. |
| `t2dsm` | [WDC Schema Matching Benchmark](https://webdatacommons.org/structureddata/smb/), WH archive linked by [wdc-smb](https://github.com/wbsg-uni-mannheim/wdc-smb) | Download + convert all 178 WH pairs. Original train/valid/test membership is retained in `conversion_manifest.json`. No model is trained by this tool. |
| `hdxsm` | [SMUTF/HDXSM authors' repository](https://github.com/MAGICS-LAB/SMUTF); recorded `hdx_datasets_0.2.zip` Drive URL in catalog | Download + convert 204 release pairs. The study's `hdxsm-usable` subset has 199 cases and requires its separate selection manifest. |
| `gdcsm` | [Magneto GDC benchmark, Zenodo 14963588](https://zenodo.org/records/14963588); [Magneto repository](https://github.com/VIDA-NYU/magneto-matcher) | Download target/mappings and ten original supplementary XLSX files, then convert all ten studies. Supplement URLs and hashes are pinned individually. |
| `gdc` | Same Magneto release, already prepared CSV variant | Offline import from `<input>/gdc/{source-tables,target-tables,ground-truth}`. This retains the existing CSV-adapter behavior; use `gdcsm` for the original-supplement conversion used in later study runs. |
| `madi` | [MaDI-Bench](https://github.com/wbsg-uni-mannheim/MaDI-Bench), study commit `655faa751536b0c33150f681adc1a305c570a114` | Obtain the checkout manually, then convert five **base** tasks to 18 unordered source pairs through their unified-schema mappings. Its whole pipeline need not be installed. |
| `goby` | [GOBY official download page](https://goby-benchmark.github.io/), approximately 1.53 GB ZIP, password published there: `GOBY2025` | Acquire/extract manually, then prepare the frozen seeded 200-wrapper-pair sample. The catalog retains the study archive hash. |
| `mimic` | [MIMIC-IV demo 2.2](https://physionet.org/content/mimic-iv-demo/2.2/), [OMOP demo 0.9](https://physionet.org/content/mimic-iv-demo-omop/0.9/), [Parciak ground truth](https://github.com/UHasselt-DSI-Data-Systems-Lab/code-schema-matching-LLMs-artefacs/blob/main/benchmark/ground_truth.csv) | Download + convert one multi-table scenario, 7 source/6 target tables and 49 positive correspondences. Uses public demos. The OMOP rows derive from the same patients; this is not independent-instance evidence. |
| `synthea` | [OMAP/SMAT truth](https://github.com/JZCS2018/SMAT), [Synthea sample data](https://synthetichealth.github.io/synthea-sample-data/), [OHDSI Eunomia](https://github.com/OHDSI/Eunomia) GiBleed 5.3 | Acquire three inputs manually; portable converter produces the study's 39 full/pair scenarios across three seeds. Historical archive hashes are below. |

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
snapshot contains missing files or unresolved mapping labels; the older research
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
tool. Eligibility is 20â€“50,000 rows and at least three mapped displayed columns;
unknown/`NO_DISPLAY` fields are excluded. Pair selection uses seed `20261006`.
Pairs with no shared unified attribute remain all-negative cases, as in the
original design. Derived pairwise truth is not a new manual annotation.

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
the study supplement cleaning and marks its target as a **domain catalogue**,
whose rows do not represent joint observations. HDXSM conversion records removed
empty columns, header normalization and truth references absent from a table;
these conversions are not advertised as byte-preserving raw releases.

## Relational sources used to generate matching tasks

Downloading these databases does not provide the paper's matching truth. The
study constructs normalized/denormalized views, samples rows and derives truth
from schema provenance. Preserve the exact source snapshot and generation recipe.

| Source | Official acquisition | Required preparation |
|---|---|---|
| MusicBrainz | [Dated database dumps](https://musicbrainz.org/doc/MusicBrainz_Database/Download) | Headerless PostgreSQL TSV with the recorded schema version. `controlled` uses `medium`, `release`, `release_group`. |
| Lahman | [SABR Lahman database](https://sabr.org/lahman-database/) | Versioned CSV export. `controlled` uses `Batting.csv`, `People.csv`, `Teams.csv`. |
| AdventureWorks | [Microsoft SQL Server samples](https://github.com/microsoft/sql-server-samples/tree/master/samples/databases/adventure-works) | Pin the release and CSV-export policy; preserve the study's `Sales.*` and `Person.*` names. |
| IMDb | [IMDb dataset download](https://datasets.imdbws.com/); [usage terms and format](https://developer.imdb.com/non-commercial-datasets/) | Retain a dated TSV snapshot, including `title.basics`, `name.basics`, `title.principals` and the other tables named by the recipe. |
| Sakila | [MySQL Sakila documentation/download](https://dev.mysql.com/doc/sakila/en/) | Load the sample SQL database and export the declared tables with a fixed null/CSV policy. |
| SCOPe | [SCOPe downloads](https://scop.berkeley.edu/downloads/) | The study used a local relational snapshot with `scop_node`, `scop_comment`, `scop_level`; current flat files need conversion and are not that snapshot. |
| TPC-H | [TPC-H specification/tools](https://www.tpc.org/tpch/) | Record dbgen build, scale factor and seed, then export the required tables. |
| Chinook | [Chinook v1.4.5 SQLite release](https://github.com/lerocha/chinook-database/releases/tag/v1.4.5) | Keep `Chinook_Sqlite.sqlite`; the independent-sampling recipe constructs matching schemas from it. |
| Amalgam bibliography | [Original Amalgam suite](https://www.cs.toronto.edu/~miller/amalgam/) | Acquire instances and the exact correspondence annotation separately. The schema page alone supplies neither our final C+L truth nor an interchangeable mapping. |
| EFES bibliography/music | [HPI repeatability page, Integration scenarios](https://hpi.de/naumann/projects/repeatability/data-integration/estimating-data-integration-and-cleaning-effort.html) | Retain its published mappings. The study's curated `final_gt` C+L annotations are a separately versioned input; do not silently substitute EFES truth. |

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

## Generated paper variants and remaining research recipes

These are generated artifacts, not additional public downloads. The authoritative
workspace inventory is retained in [the compatibility audit](reports/DATASET_COMPATIBILITY.md).
These historical variants require the corresponding frozen input snapshots,
selection manifests and annotations. The portable preparation commands above
cover the listed release adapters; they do not infer unpublished annotations or
load construction code from another repository. Acquisition-only sources are
marked `manual preparation` by the `list` command.

| Indexed corpus/variant | Base inputs and original recipe |
|---|---|
| `benchmark-full`, `benchmark-smoke` | Valentine/GDC import; portable `valentine`/`gdc` plus frozen selected cases/corrections. |
| `controlled-imported*` | MusicBrainz/Lahman joined views; `tools/dataset_support/controlled.py` and the version's design manifest. Portable `controlled` reproduces v2. |
| `final-controlled` | AdventureWorks/IMDb/Sakila/SCOPe, 96 tasks; the frozen joined-view design, source hashes, 5,000-row samples, three seeds. |
| `indep_plain`, `indep_prof*` | TPC-H/AdventureWorks/Chinook; normalized/denormalized schema provenance and frozen independent samples; 81 tasks. Profiles are a separate step. |
| `indep2_*` | Lahman/MusicBrainz; normalized/denormalized schema provenance; 54 tasks across seeds/overlap/schema views. |
| `test_*` | Sakila/IMDb/SCOPe; frozen schema-view and sampling manifests; 27 disjoint-instance multi-table tasks. |
| `final_indep*` | Amalgam/music instances plus the curated correspondence CSVs; use C+L truth and the declared MusicBrainz table restriction. Nine tasks. |
| `synthea_*`, `mimic_*` | Portable `synthea`/`mimic` for readable bases; original hashes, sampling and conversion reports define the exact variants. |
| `t2d-usable`, `hdxsm-usable` | Frozen subsets of 175/199 pairs; importing all 178/204 is not the same case selection. Keep exclusions and analysis manifests as code/data-free JSON. |
| `*_md5`, `*_md5_encrypted`, `*_on` | Original readable inputs plus their frozen transformation manifests, salts/seeds, null/strip policies and label maps. `_on` hashes names; `_md5` also recodes values. |
| `*_opaque*`, `*_partial*`, `*_corrupt*`, `*_revalue*` | Original bases plus the corresponding frozen perturbation recipe/settings. Preserve the manifest identifying affected cells/columns and truth. |
| `knobs` | Frozen perturbation settings; derived perturbation grid, not a separately acquired dataset. |
| `self_*` | Construct two within-side self-matches per original case, identity/provenance truth. |
| `x87-*-perm*` | Apply recorded column permutations, truth/profile remapping. |
| `scale-imported` | Portable `scale`; 31 size/density/row settings × three repetitions = 93 cases. `--smoke` creates one case only. |
| `_ref_clean_exact`, `DeNorm`, thesis examples | Preserve their generation recipe or small original fixture if redistribution permits; their names alone are insufficient provenance. |

The historical opaque multi-table builder preserves readable table/column order.
That order can affect tied assignment results; use the paper's permutation/tie
protocol for replication. Changing order or null policies creates a new dataset
version. These recipes and curated annotations must be published alongside the
paper for a complete exact rerun; downloading current raw databases cannot
recover them. This guide makes that remaining release work visible.

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
paper evaluator as described in [EVALUATION_AND_DATASETS.md](EVALUATION_AND_DATASETS.md).
Evaluation needs NumPy/SciPy even though dataset preparation does not. Exact paper
summaries additionally require their frozen selected cases/families/units and
permutation keys; generated dataset names are not automatically paper aliases.

Source-specific terms and attribution remain with their publishers; availability
for download does not by itself grant permission to redistribute every data file.
No data bytes or third-party model weights are added by this implementation.

## Validation of this tool

To refresh the source list or check it without changing files:

```powershell
python tools/audit_dataset_sources.py
python tools/audit_dataset_sources.py --check
```

The audit checks registered preparation modes, download destinations and checksum
formats against the local catalog. It does not contact publishers. The generated
inventory is also checked by the normal Python test suite.

On 2026-10-15, ten preparation tests and four evaluator tests passed. A real public
T2D-SM download passed checksum verification and Java validation of all 178 pairs.
Local-release conversion checks covered **700 scenarios** across T2D-SM, HDXSM,
GDC-SM, MaDI, GOBY, MIMIC, Synthea, a selected Valentine subset, controlled smoke
generation and scaling smoke generation, with zero Java parsing/truth errors.
See [the validation record](reports/dataset_preparation_validation.json) for exact
counts and settings; these checks did not run matcher accuracy experiments or
mine dependency profiles.
