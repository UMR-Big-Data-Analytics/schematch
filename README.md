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

## Documentation

Start with the [documentation index](docs/README.md), or go directly to:

- [Getting started](docs/getting-started.md): build and run the bundled example.
- [Matchers](docs/matchers.md): Java methods, compositions and optional Python systems.
- [Datasets](docs/datasets.md): acquisition, preparation and input layouts.
- [Evaluation](docs/evaluation.md): metrics, extraction and result summaries.

Generated catalogs, advanced server configuration and maintainer instructions
are grouped in `docs/`. The [tool guide](docs/tools.md) explains which commands
are useful for running, evaluating and developing Schematch.

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
