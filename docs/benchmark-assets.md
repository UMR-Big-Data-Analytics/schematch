# Benchmark annotations and dependency profiles

Schema Matching Benchmark Datasets is the companion project for reusable ground
truth and precomputed FD/UCC/IND/DC profiles. Its intended GitHub address is
`UMR-Big-Data-Analytics/schema-matching-benchmark-datasets`. During local
preparation the repository contains a catalog, versioned manifests, curated
annotations and archives under its ignored `dist/` directory. No published
download URL is assumed until it is recorded in the catalog.

Schematch remains independent: normal matching does not require that repository.
The optional `tools/benchmark_assets.py` command reads an explicitly selected
catalog or archive. It uses Python's standard library and never searches sibling
checkouts. Raw data acquisition remains with `prepare_datasets.py`.

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

## Prepare another bundle

```shell
python tools/benchmark_assets.py build --input datasets-local/prepared/example --output datasets-local/example-v1.zip --id example-v1 --version 1 --provenance datasets-local/example-provenance.json
```

Supply provenance describing the raw release, preparation recipe, sampling,
truth origin, profiler version/settings and limitations. The builder validates
managed profile checksums, retains original file hashes and replaces absolute
JSON source/cache locations with portable identifiers. It excludes table rows.
The resulting ZIP contains a manifest and declared truth/profile files only.

Small synthetic fixtures remain with Schematch. Reusable benchmark assets belong
in the companion project; study-specific selections and reported results belong
in a separate paper repository that pins the framework and asset versions.
