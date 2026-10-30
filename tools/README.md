# Schematch tools

Run the commands below from the repository root, not from this folder.
Use `python tools/<script>.py --help` for all options. The full
[tool guide](../docs/tools.md) explains prerequisites and output formats.

## Prepare and run a dataset

```shell
# Show which sources support downloads, local conversion or generation.
python tools/prepare_datasets.py list

# Generate one synthetic scenario, with ground truth and no download.
python tools/prepare_datasets.py prepare scale --smoke

# Or download, verify and convert the supported T2D-SM benchmark.
python tools/prepare_datasets.py prepare t2dsm --download
```

The last two commands are alternatives producing separate outputs. Preparation
writes tables, ground truth, manifests and a `datasets.yaml` under
`datasets-local/prepared/<source>/`. Copy the generated dataset entries into
`src/main/resources/datasets.yaml`; the command does not change active settings.
Keep `defaultDatasetBasePath: "data"` when using the default generated paths.

Build and run using the [getting-started guide](../docs/getting-started.md).
Preparation does not mine FD/UCC/IND profiles. Use `readDependencies: false`
until compatible profiles have been supplied. See
[dataset preparation](../docs/datasets.md) for local-input layouts, optional
Java validation, ground truth and metadata sharing.
The separate Schema Matching Benchmark Datasets project supplies optional
precomputed annotations/profiles. Use `benchmark_assets.py` to verify and attach
its bundles; see the [benchmark asset guide](../docs/benchmark-assets.md).

## Python matcher servers

Java-only matchers do not need either server. For Python methods, install
`requirements.txt` in a selected environment, then start the server needed by
the chosen Java adapter in another terminal:

| Server | Scope | Start command | Health endpoint |
|---|---|---|---|
| `seed_server.py` | One source/target table pair per request; for example `ComaMatcher`, `CupidMatcher` and `SbertMatcher`. | `python src/main/resources/python_matchers/seed_server.py --port 5101` | `http://127.0.0.1:5101/health` |
| `study_server.py` | One complete scenario per request; for example `DependencyMatcher`, `EntityAlignmentMatcher` and standalone holistic methods. | `python src/main/resources/python_matchers/study_server.py --port 5102` | `http://127.0.0.1:5102/health` |

Both servers bind to localhost. `/health` lists registered method identifiers;
it does not prove that optional models or profiles are installed. Java sends
requests to `/match` through the configured adapter. Run the server and Java
application on the same machine with access to the selected dataset files.
Start only the servers required by the chosen methods.

See [Python installation](../docs/python-matchers.md) for lexical resources,
models and separate environments, and [scenario-server configuration](../docs/scenario-python.md)
for base matrices and second-line matchers. Missing backend resources normally
fail the selected method; method registration alone is not a readiness check.

## Commands in this folder

| Tool | Purpose |
|---|---|
| `run_schematch.py` | Launch the compiled Java application. |
| `check_setup.py` | Check Java, Maven, local libraries and optional evaluation packages. |
| `prepare_datasets.py` | Download, verify, convert or generate supported datasets. |
| `benchmark_assets.py` | Build, verify and attach table-bound annotations and dependency profiles. |
| `dataset_sources.json` | Source IDs, preparation modes, URLs and checksums used by preparation. |
| `transform_dataset.py` | Create recoded, relabelled, perturbed or permuted dataset copies. |
| `audit_datasets.py` | Check tables and ground truth with the normal Java parsers. |
| `evaluate_paper.py` | Evaluate captured raw score matrices and produce metric summaries. |
| `paper_metrics.py` | Shared ranking, assignment and extraction functions. |
| `cluster_units.py` | Group scenarios sharing tables for resampling. |
| `positional_audit.py` | Check ground-truth position, tie-breaking and column-order effects. |
| `learned_combiner.py` | Train combinations of recorded matcher scores on other scenario groups. |
| `check_project.py` | Check repository files for broken links, local paths and generated artifacts. |
| `audit_matchers.py` | Generate/check the class catalog and named Python adapters. |
| `audit_dataset_sources.py` | Generate/check the dataset catalog without downloading its sources. |
| `verify_standalone.py` | Run regression tests, with optional external-directory access blocking. |
| `dataset_support/` | Modules implementing preparation and transformations. |
| `tests/` | Generated examples, reference fixtures and regression tests. |

The `paper` filenames are compatibility names for general evaluation tools.
No paper repository is needed to run these commands. Benchmark-specific case
selections and frozen experiment settings belong with the corresponding study.
