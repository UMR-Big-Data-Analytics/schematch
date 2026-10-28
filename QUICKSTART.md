# Getting started

Run commands from the Schematch project directory. Use JDK 17 or newer, Maven,
and Python 3.10 or newer for the preparation tools. Java-only matchers do not need
a Python matcher server. Required local profiler libraries are listed in `pom.xml`.

## Check the installation

```shell
python tools/check_setup.py
```

This checks Java, the Java compiler, Maven, configuration files and the libraries
referenced by the build. It reports missing prerequisites without downloading or
changing anything. Use `--json` for structured output.

## Run the existing example

The active configuration already selects the synthetic `examples/data/demo/`
pair and `PMFMatcher`, with profiling and optional evaluation disabled. Build
and run it with the commands below before changing any configuration.

## Prepare a small example

```shell
python tools/prepare_datasets.py prepare scale --smoke
```

This creates one small deterministic dataset with known correspondences. It uses
no external download. Files are written under the ignored `datasets-local/`
directory. Copy `datasets-local/prepared/scale/datasets.yaml` to
`src/main/resources/datasets.yaml`. Its paths are relative to
`defaultDatasetBasePath`, which should remain `data` for this example.

Replace the entries in `src/main/resources/first_line_matchers.yaml` with:

```yaml
---
name: "PMFMatcher"
packageName: "similarity.instance"
params:
  distance: ["L2"]
```

In `src/main/resources/general.yaml`, retain the other settings and set:

```yaml
defaultDatasetBasePath: "data"
readDependencies: false
saveOutputFirstLineMatchers: true
evaluateTablePairGeneration: false
evaluateFirstLineMatchers: false
evaluateSecondLineMatchers: false
evaluateSimMatrixBoostingOnFirstLineMatchers: false
evaluateSimMatrixBoostingOnSecondLineMatchers: false
runSimMatrixBoostingOnFirstLineMatchers: false
runSimMatrixBoostingOnSecondLineMatchers: false
runSecondLineMatchers: false
readCacheFirstLineMatchers: false
paperEvaluation: false
```

## Build and run

```shell
mvn compile dependency:build-classpath -Dmdep.outputFile=target/cp.txt
python tools/run_schematch.py
```

The launcher chooses the correct classpath separator on Windows, Linux and macOS.
It uses the project's active configuration and writes the run output under
`results/`. Optional memory settings can be supplied as
`python tools/run_schematch.py --jvm-arg=-Xmx4g`.

## Evaluate the result

For the paper's raw-score metrics, install the tested evaluation dependencies in
your selected environment:

```shell
python -m pip install -r requirements-evaluation.txt
python tools/check_setup.py --paper-evaluation
```

Set `paperEvaluation: true` and `paperEvaluationPython: "python"` in
`general.yaml`, rebuild with the same Maven command, then run the launcher again.
Recall@GT, MRR, Hungarian F1, mutual matching and extraction reports are written to
the run's `paper_evaluation/` directory. Keep the legacy binary evaluation flags
disabled when sending raw scores to this evaluator.

For public datasets and conversion policies see [DATASETS.md](DATASETS.md).
For matcher choices and optional resources see [MATCHERS.md](MATCHERS.md).
For metric definitions and aggregation settings see
[EVALUATION_AND_DATASETS.md](EVALUATION_AND_DATASETS.md).
