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
python -m pip install -r requirements-python.txt
python -m unittest discover -s tools/tests -v
```

Two optional learned-model subtests require PyTorch and are explicitly skipped
when it is absent. Their synthetic inputs do not need pretrained model weights.

## Documentation

- [Getting started](QUICKSTART.md): example, build, run and evaluation commands.
- [Matcher guide](MATCHERS.md) and [class inventory](MATCHER_INVENTORY.md).
- [Python systems](PYTHON_MATCHERS.md): dependencies, local resources and services.
- [Dataset preparation](DATASETS.md) and [source/recipe inventory](DATASET_INVENTORY.md).
- [Evaluation and loading](EVALUATION_AND_DATASETS.md): metrics and input contracts.
- [Contributing](CONTRIBUTING.md) and [local release preparation](RELEASE.md).
- [Provenance and licensing review](THIRD_PARTY.md).

The framework uses relative input paths and explicit external-resource settings;
it does not discover adjacent source repositories. Downloaded data, seed
matrices, models and run outputs stay outside the source release. The separate
paper artifact supplies its frozen protocols, configurations and cached results,
and should identify the exact framework commit used.

## Release status

This is a local release candidate. Technical validation is recorded in
[release_validation.json](reports/release_validation.json). Public redistribution
requires the upstream and third-party permissions review described in
[THIRD_PARTY.md](THIRD_PARTY.md); no project-wide license has been assigned by
this preparation. Optional backend availability and published-system parity are
separate from class registration or passing transport tests.
