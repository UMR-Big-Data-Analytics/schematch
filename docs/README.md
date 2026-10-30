# Schematch documentation

Schematch compares columns from source and target tables, produces similarity
scores, optionally refines those scores, and evaluates the resulting matches
against known correspondences. Run commands and interpret paths in code blocks
relative to the repository root unless stated otherwise.

## Start here

1. [Getting started](getting-started.md): build and run the bundled synthetic example.
2. [Matchers](matchers.md): select Java methods, combine scores and configure Python backends.
3. [Datasets](datasets.md): acquire public benchmarks, convert files and configure loading.
4. [Evaluation](evaluation.md): choose metrics, handle ties and summarize results.

## Optional Python backends

- [Installation and resources](python-matchers.md): packages, models and external services.
- [Scenario server](scenario-python.md): methods that need all tables in a matching task.

## Reference and development

- [Generated matcher catalog](reference/matcher-catalog.md): classes, packages and backends.
- [Generated dataset catalog](reference/dataset-catalog.md): source IDs and preparation support.
- [Tools](tools.md): launching, dataset preparation, evaluation and development checks.
- [Contributing](CONTRIBUTING.md) and [provenance and licensing](THIRD_PARTY.md).

## Terms used in the guides

| Term | Meaning |
|---|---|
| Scenario | One task with one or more source tables, one or more target tables, and optional known correspondences. |
| First-line matcher | Computes initial column-pair similarity scores from tables or metadata. |
| Second-line matcher / refinement | Adjusts an initial score matrix using another method. |
| Base matrix | Initial scores consumed by a refinement. |
| Ground truth | Known column correspondences used to measure accuracy; not needed for inference. |
| Seed matrix | A stored initial score matrix, unrelated to a random-number seed. |
| Dependency profile | Discovered table metadata such as dependencies and candidate keys. |
| FD | Functional dependency: values in one set of columns determine values in another column or set. |
| UCC | Unique column combination: columns whose joint values uniquely identify rows; a candidate key. |
| IND | Inclusion dependency: values from one column or column set occur in another. |
| PMF | Probability mass function; `PMFMatcher` compares sorted column value frequencies. |
| SF | Similarity Flooding, which propagates scores over a graph representing the schemas. |
| Regime | A dataset variant, such as readable or recoded names and values. |

Names containing `Study`, `study_server.py`, and settings beginning with
`paperEvaluation` remain for configuration compatibility. Their behavior is
documented here; using them requires no original evaluation project or datasets.
