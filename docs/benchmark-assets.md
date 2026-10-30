# Benchmark annotations and dependency profiles

The [Schema Matching Benchmark Datasets repository](https://github.com/UMR-Big-Data-Analytics/schema-matching-benchmark-datasets)
is the companion project for reusable ground truth and precomputed FD/UCC/IND/DC
profiles. The link identifies its intended publication address; the repository
and archive downloads have not yet been published. During local
preparation the repository contains a catalog, versioned manifests, curated
annotations and archives under its ignored `dist/` directory. No published
download URL is assumed until it is recorded in the catalog.

Schematch remains independent: normal matching does not require that repository.
The optional `tools/benchmark_assets.py` command reads an explicitly selected
catalog or archive. It uses Python's standard library and never searches sibling
checkouts. Raw data acquisition remains with `prepare_datasets.py`.

## Repository relationship

Keep the three responsibilities separate:

| Project | Responsibility |
|---|---|
| Schematch | Matching, data preparation, metadata installation and evaluation software. |
| Schema Matching Benchmark Datasets | Reusable annotations, profile manifests, provenance and versioned profile archives. |
| A paper's reproduction repository | Exact framework/asset versions, scenario selections, experiment settings, analyses and results. |

The benchmark project is an independent repository, not a Git submodule of
Schematch. Normal framework use does not need a metadata checkout. Users can
select a local catalog or, after publication, a fixed-version HTTPS catalog and
download only the required archive. Adding a submodule would not distribute
the ZIPs under ignored `dist/`; those still need release publication.

The next usability improvement is a combined preparation command in Schematch
that calls its existing data preparation and asset installation steps. That
entry point is not implemented yet. It belongs with the framework's preparation
tools and does not require another repository. Keep matcher selection and run
configuration explicit, and report missing profiles or manual source inputs.

## What is automated now?

| Step | Command or action | Current behavior |
|---|---|---|
| Acquire and prepare raw data | `prepare_datasets.py prepare <source> --download` | Downloads declared files for supported sources, checks them and writes tables, truth and a relative-path dataset configuration. Other sources require local/manual inputs. |
| Obtain metadata | `benchmark_assets.py install` with an explicit catalog | Uses an existing local ZIP, or downloads its declared HTTPS URL. Current catalog URLs are unset, so local ZIPs are required until release publication. |
| Attach metadata | The same `install` command | Checks archive contents, prepared-table hashes and conflicts, then places the declared ground truth and profiles into the normal scenario directories. |
| Configure a run | Select generated dataset entries and complete-profile scenarios | Still explicit; the tools preserve active Java configuration. |
| Run matchers | `run_schematch.py` after build/configuration | Java methods run locally; optional Python matchers need their configured server/resources. |

`prepare_datasets.py` does not invoke the metadata installer. Ground truth
imported by preparation is already available without a metadata bundle; profiles
are a separate optional input. Preparing data and metadata currently requires
two commands. Neither changes the active configuration or starts matchers.

The FD/UCC/IND files are inside the metadata ZIPs, below paths such as:

```text
payload/<dataset>/<scenario>/metadata/source/<table>/FD_results.txt
payload/<dataset>/<scenario>/metadata/source/<table>/UCC_results.txt
payload/<dataset>/<scenario>/metadata/source/inds.txt
```

Target-side files use `metadata/target/`. The installer removes the archive's
`payload/` prefix when placing files under the prepared dataset root. Catalogs
and manifests describe those files but do not embed their profile contents.

## Select and inspect a bundle

Run from the Schematch root. These examples assume the metadata checkout is a
sibling; replace the catalog argument for any other location:

```shell
python tools/benchmark_assets.py list --catalog ../Schema-Matching-Benchmark-Datasets/catalog.json
python tools/benchmark_assets.py verify --catalog ../Schema-Matching-Benchmark-Datasets/catalog.json --id t2dsm-readable-v1
```

The location is not hard-coded in the tool. A fixed-version HTTPS catalog URL
can also be supplied after publication. Each archive requires a SHA-256 checksum
in the catalog; archives absent locally are fetched only from its explicit HTTPS URL.

## Attach assets to prepared tables

First acquire and prepare the publisher's data. Check any invalid mappings in
the import report; preparation errors should be resolved explicitly.

```shell
python tools/prepare_datasets.py prepare t2dsm --download
python tools/benchmark_assets.py install --catalog ../Schema-Matching-Benchmark-Datasets/catalog.json --id t2dsm-readable-v1 --prepared datasets-local/prepared/t2dsm --dry-run
python tools/benchmark_assets.py install --catalog ../Schema-Matching-Benchmark-Datasets/catalog.json --id t2dsm-readable-v1 --prepared datasets-local/prepared/t2dsm
```

Before writing, the installer verifies archive integrity, every required local
table's filename and SHA-256 hash, and all annotation/profile conflicts. It adds
only the declared metadata and truth files. Matching files are left in place;
conflicting truth or profiles cause an error. A fresh preparation's
`not_profiled` marker can be replaced with the supplied status record.

A bundle may cover a subset of a prepared release. All scenarios named by that
bundle must exist; additional local scenarios are left untouched. The example
prepares 178 T2D-SM pairs and attaches profiles to the archived subset of 175;
the other three remain unprofiled. A table hash
mismatch requires the correct input snapshot or a new profile bundle, rather
than bypassing verification. Raw tables are never downloaded by the asset tool.

You can also inspect or attach a separately obtained archive:

```shell
python tools/benchmark_assets.py verify --archive datasets-local/example-v1.zip --sha256 <archive-sha256>
python tools/benchmark_assets.py install --archive datasets-local/example-v1.zip --sha256 <archive-sha256> --prepared datasets-local/prepared/example --dry-run
```

Successful attachment preserves the supplied completeness statuses. Check them
before enabling `readDependencies`: some tables are domain catalogs, some
profiles are partial or missing, and arity/threshold limits can apply even when
a configured profiling run completed. The installer verifies compatibility, not
the scientific correctness of a dependency or an annotation.

## Configure and run

Preparation writes `datasets-local/prepared/<source>/datasets.yaml`. Copy the
desired entries into `src/main/resources/datasets.yaml` and follow the
[getting-started guide](getting-started.md). Leave `readDependencies: false` for
unprofiled inputs. Enable it only for selected scenarios whose required profiles
are complete; attaching a subset bundle does not profile the remaining cases.
The tools do not change the active configuration or start a matcher automatically.

## Prepare another bundle

```shell
python tools/benchmark_assets.py build --input datasets-local/prepared/example --output datasets-local/example-v1.zip --id example-v1 --version 1 --provenance datasets-local/example-provenance.json
```

Supply provenance describing the raw release, preparation recipe, sampling,
truth origin, profiler version/settings and limitations. The builder validates
managed profile checksums, retains original file hashes and replaces absolute
JSON source/cache locations with portable identifiers. It excludes table rows.
The resulting ZIP contains a manifest and declared truth/profile files only.
For legacy exports without recorded profiling-input hashes, the new table binding
describes the archived snapshot. It does not independently prove the original
profiler used that snapshot; retain that provenance limitation in the release.

Small synthetic fixtures remain with Schematch. Reusable benchmark assets belong
in the companion project; study-specific selections and reported results belong
in a separate paper repository that pins the framework and asset versions.
