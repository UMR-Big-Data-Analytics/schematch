# Schematch

Schematch is a framework for schema matching and evaluation. This fork builds on
[Alexander Vielhauer's original project](https://github.com/avielhauer/schematch)
and [Julian Trösser's development fork](https://github.com/juliantroesser/schematch)
at Marburg University, retaining their history and attribution.

It includes Similarity Flooding and dependency-aware graph variants, native
label/value/distribution matchers, compositions and refinements, and named
adapters for optional Python systems. The generated inventory identifies
implementations, presets, controls and service requirements separately.

## Run the bundled example

Install JDK 17 or newer and Maven. From this directory:

```shell
mvn test compile dependency:build-classpath -Dmdep.outputFile=target/cp.txt
python tools/run_schematch.py
```

Python 3.10 or newer runs the launcher and preparation tools. The default PMF
example uses small synthetic tables included under `examples/data/` and writes
its matrix below `results/`. It needs no model, server, benchmark download or
dependency profiling. Normal Maven dependencies are downloaded on the first
build. Legacy profiling snapshots unavailable from their old remote are
preserved with checksums under `src/lib/maven/`.

For numerical Python methods and regression checks, use a dedicated environment:

```shell
python -m venv .venv
# Activate .venv with the command appropriate for your shell.
python -m pip install -r requirements.txt
python -m unittest discover -s tools/tests -v
```

Two optional learned-model subtests require PyTorch and are explicitly skipped
when it is absent. Their synthetic inputs do not need pretrained model weights.

## Python dependencies

[requirements.txt](requirements.txt) pins the numerical packages and Valentine
adapters used by the Python tools and CI. Install them with:

```shell
python -m pip install -r requirements.txt
```

Java-only matching and dataset preparation do not require these Python packages.
Neural models, vector files and external services remain optional; see
[Python installation](docs/python-matchers.md) for their additional setup.

## Python matcher servers

Python-backed matchers are called through local servers: `seed_server.py`
handles table pairs on port 5101, and `study_server.py` handles complete scenarios
on port 5102. Java-only methods need neither server. See
[server installation](docs/python-matchers.md),
[scenario-server configuration](docs/scenario-python.md) and the
[tools README](tools/README.md#python-matcher-servers) for startup commands,
resource requirements and configuration examples.

## Download or generate data

The [dataset guide](docs/datasets.md) explains acquisition, conversion,
generation and local input layouts. The [dataset catalog](docs/reference/dataset-catalog.md)
lists the preparation support for each source. From the repository root:

```shell
python tools/prepare_datasets.py list

# Generate one small synthetic scenario, including ground truth; no download.
python tools/prepare_datasets.py prepare scale --smoke

# Or download, verify and convert a supported public benchmark.
python tools/prepare_datasets.py prepare t2dsm --download
```

Prepared data and a relative-path `datasets.yaml` are written under
`datasets-local/prepared/<source>/`. Copy the generated entries into
`src/main/resources/datasets.yaml` and follow the [run instructions](docs/getting-started.md).
Preparation preserves the active configuration. Sources marked for local or
manual preparation need the named inputs; every catalog entry is not an
automatic download.

The converters import or derive ground truth. They do not mine FD/UCC/IND
profiles; use `readDependencies: false` until those are supplied. The companion
**Schema Matching Benchmark Datasets** project collects reusable annotations and
precomputed profiles separately. The [benchmark asset guide](docs/benchmark-assets.md)
explains how to select its catalog, verify table hashes and attach compatible
files with `tools/benchmark_assets.py`. See the
[metadata-sharing guide](docs/datasets.md#sharing-ground-truth-and-dependency-profiles)
for provenance and distribution requirements.

## Documentation

Start with the [documentation index](docs/README.md), or go directly to:

- [Getting started](docs/getting-started.md): build and run the bundled example.
- [Matchers](docs/matchers.md): Java methods, compositions and optional Python systems.
- [Datasets](docs/datasets.md): acquisition, preparation and input layouts.
- [Evaluation](docs/evaluation.md): metrics, extraction and result summaries.

Generated catalogs, advanced server configuration and maintainer instructions
are grouped in `docs/`. The [tools README](tools/README.md) provides a local
index and examples; the [full tool guide](docs/tools.md) explains prerequisites
for running, evaluating and developing Schematch.

- [CONTRIBUTING.md](docs/CONTRIBUTING.md) explains how to propose changes and
  which checks to run.
- [THIRD_PARTY.md](docs/THIRD_PARTY.md) records the origins of bundled components
  and the licensing and attribution work required for distribution.

## Scope

Schematch contains reusable matching, data preparation and evaluation capabilities.
It uses relative input paths and explicitly configured external resources; it
builds and runs without a paper repository or an adjacent research checkout.

Study-specific configurations, registrations, selected cohorts, result snapshots,
analyses and manuscript sources belong in separate experiment repositories.
Those repositories identify the exact Schematch commit used. Downloaded data,
model weights and generated run outputs stay outside the framework source tree.
