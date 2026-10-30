# Evaluation and dataset loading

Schematch evaluates raw similarity matrices with ranking and assignment metrics. This optional Python evaluator complements the Java binary Precision/Recall/F1/Overall evaluator. It requires NumPy and SciPy and works independently of matcher servers.

The prefix `paperEvaluation`, script names `paper_metrics.py` and `evaluate_paper.py`, and output directory `paper_evaluation/` are legacy names kept for compatibility. They work with any supported dataset and require no particular paper or experiment project. See the [glossary](README.md#terms-used-in-the-guides) for the basic terms.

## Metrics and extraction

| Measurement | Supported behavior |
|---|---|
| Global Recall@GT | Exact expected, pessimistic, and optimistic tie treatments |
| Row MRR | Exact expected and pessimistic primary metrics; optimistic diagnostics |
| F1_H | Maximum-score Hungarian assignment, dropping scores <= 1e-9 |
| F1_Hr | Mean Hungarian F1 over 50 seeded random row/column permutations, reducing positional tie-breaking effects |
| F1_M | Mutual top-1, retaining tied positive maxima |
| J_tf | (R_exp + F1_Hr) / 2; an equal-weight ranking/assignment summary |
| Legacy objective J | (R_pess + F1_H) / 2; separately named `J_preregistered` |
| Extraction diagnostics | Precision, recall, F1, Overall, TP/FP/FN, number of matches, match/GT ratio |
| Ranking/tie diagnostics | Rank bounds, margins, entropy, zero-score share, ties at maxima |
| Comparisons | Per-scenario paired differences, family-macro summaries, descriptive wins/losses/ties |
| Uncertainty | Optional 95% percentile bootstrap, resampling explicit units within families |

All nine existing extraction policies are available: source top-1, mutual top-1, relative thresholds 1.0 and 0.95, global threshold 0.5, Hungarian, and partial Hungarian with penalties 0.3/0.5/0.7.

`tools/paper_metrics.py` contains the bundled numerical implementation and source-provenance hashes. Regression checks cover all seven primary metrics and every seeded permutation on 30 reference matrices, including ties, negatives, rectangular shapes and many-to-many truth. No external scorer checkout is imported.

Global Recall@GT selects as many cells from the complete scenario matrix as there are true correspondences. It differs from a per-source-column Recall@GT definition. `R_exp` averages over ties at that cutoff; pessimistic and optimistic variants use worst and best tied selections. Row MRR evaluates each source column's ranking. Report the definition when comparing tools.

## Enable evaluation during a normal run

Merge these settings into your existing `general.yaml`; retain its other required settings:

```yaml
paperEvaluation: true
paperEvaluationPython: "python"
paperEvaluationScript: "tools/evaluate_paper.py"

# Raw-score evaluation performs extraction itself.
evaluateFirstLineMatchers: false
evaluateSecondLineMatchers: false
evaluateSimMatrixBoostingOnFirstLineMatchers: false
evaluateSimMatrixBoostingOnSecondLineMatchers: false
runSimMatrixBoostingOnFirstLineMatchers: false
runSimMatrixBoostingOnSecondLineMatchers: false
```

Activate your NumPy/SciPy environment before starting Schematch, or configure a project-relative interpreter path. `paperEvaluation` is off by default. Its Python/script prerequisites are checked before matching. If evaluation fails, the run fails and retains its inputs for diagnosis.

No extra entries are needed in `metrics.yaml` for this mode. The existing metric classes remain binary metrics; enabling them directly on raw non-binary scores still requires an explicit extraction step. A matcher with structural dependencies may require `readDependencies: true`; a value/name-only run can explicitly set it false. That explicit setting is now honored instead of always being inferred from boosting flags.

Raw matrices are captured immediately after each first-/second-line matcher, before boosting. Output under `<run>/paper_evaluation/` includes:

- `input.jsonl`: raw scores, aligned ground truth, labels, method/stage identity, declared baseline, elapsed time and cache status;
- `metrics.csv`: the seven primary metrics and both objectives per matrix;
- `extractions.csv`: all nine extraction policies and diagnostics;
- `paired.csv`, `paired_extractions.csv`: differences against an identified raw first-line base;
- `summary.csv`, `selection_summary.csv`, `paired_summary.csv`, `paired_selection_summary.csv`;
- `excluded.csv` and `unpaired.csv`, including explicit exclusions or comparisons without a proven base;
- `evaluation_manifest.json`: library versions, epsilon, permutation count, grouping and bootstrap settings.

RAW_FIRST_LINE refinements identify their actual base. SERVER/FILE inputs, PREVIOUS inputs, and inline bases that were not separately recorded are not silently paired with an unrelated first-line placeholder. Scores for these methods are still evaluated; their missing comparison is listed in `unpaired.csv`.

You can rescore the captured matrices without rerunning the matcher:

```powershell
python tools/evaluate_paper.py --records <run>/paper_evaluation/input.jsonl --out <report>
```

The primary metrics use scenario key `dataset/scenario` to seed deterministic permutations. A grouping manifest can override `permutation_key` if the configured dataset uses a different alias. The evaluator records NumPy/SciPy versions because solver behavior can matter for tied assignments.

## Configure grouping and resampling

Without a grouping manifest the report groups by configured dataset, with a minimum size of one. A family is a named collection of related scenarios; a unit identifies the independent source kept together during resampling. Dataset variants and train/test splits can be separated. Repeating a published analysis requires its exact case selection and grouping. For example:

```json
{
  "ChEMBL-Unionable/case1": {
    "family": "ChEMBL",
    "unit": "actual-source-table-id",
    "regime": "readable",
    "split": "test"
  },
  "ChEMBL-Unionable/excluded-case": {
    "include": false,
    "reason": "Excluded from this comparison"
  }
}
```

Every evaluated case must occur in an explicitly supplied manifest. There is no guess for the source table or an independent unit. Summaries keep regime, split, corpus and group strata separate. Family means are averaged equally, after excluding families below the chosen size.

```yaml
paperEvaluationGroups: "configs/evaluation-groups.json"
paperEvaluationMinFamilySize: 5
paperEvaluationBootstrap: 10000
paperEvaluationBootstrapSeed: 20261014
paperEvaluationTimeoutSeconds: 3600
```

Choose a minimum group size for the analysis; this example requires five scenarios per family. Bootstrap requires the grouping manifest, resamples complete units within families, and keeps all scenarios in each sampled unit together. To reproduce a scenario-stratified bootstrap, use each scenario as its unit. Record case selections, contrasts, grouping and random seeds with the experiment. Generic summaries cannot infer those choices from filenames.

### Derive resampling units from shared tables

Scenarios built from the same table are not independent. `tools/cluster_units.py`
links each scenario to the SHA-256 of each table and takes connected components.
Linking ignores roles: a table used as a source in one scenario and as a target in
another is the same node. It writes a grouping manifest with one `unit` per
component and the dataset name as `family`. Hashes come from each import
manifest's `raw_sha256` (the original release file) when present; otherwise, the
prepared CSV files are hashed. Use `--sides source` when every scenario shares one
target, such as a domain catalogue; the units are then conditional on that target.

```powershell
python tools/cluster_units.py --input datasets-local/prepared/goby --out configs/goby-groups.json --family GOBY
```

On the 200 prepared GOBY pairs, this gives the 124 table-sharing clusters used
historically with either hash source and exactly the same partition. Edit the
manifest to add regimes, splits or exclusions before using it with
`paperEvaluationGroups`.

### Positional audits

Matrices can collect correctness from column order rather than from evidence.
`linear_sum_assignment` resolves ties toward the identity, and many splits keep
schema order. `tools/positional_audit.py` reads a run's `input.jsonl`:

```powershell
python tools/positional_audit.py alignment --records <run>/paper_evaluation/input.jsonl --out <report>
python tools/positional_audit.py extractor --records <run>/paper_evaluation/input.jsonl --out <report>
python tools/positional_audit.py rerun --original <run>/paper_evaluation/input.jsonl `
  --permuted <run-on-permuted-copy>/paper_evaluation/input.jsonl `
  --permutations <permuted-copy>/transformation_manifest.json --out <report>
```

`alignment` reports truth pairs off the diagonal and the F1 of an all-ones matrix.
`extractor` permutes target columns of every matrix and its truth by the same
permutations: 20 per scenario from seed 20260909, shared by all matchers and drawn
in first-seen order. It reports Hungarian F1 before and after, the difference
("subsidy"), tie share and spread, with a constant-matrix reference.
Mutual top-1 is equivariant and must show zero subsidy. This mode measures the
extractor's tie-breaking and cannot detect a matcher that reads column order.
`rerun` does: run the matcher on a copy from `transform_dataset.py permute`, and
require `S_permuted == S[rows, cols]`. Deviations above 1e-12 and 1e-9 are
counted separately. On the 382-scenario Valentine protocol, the port reproduced the
historical report exactly. COMA (schema, readable) has F1_H 0.697793 and permuted
0.674236. The constant matrix has 0.722903 and permuted 0.036412. The alignment
survey has 6,432 truth pairs, 10 off the diagonal.

### Learned combination of first-line matchers

`tools/learned_combiner.py` trains a class-balanced logistic regression on base
matrices from other families. Its features are each base matcher's score and
margins to the row and column best. It writes the held-out family's probabilities
as `<out>/<dataset>/<scenario>/seed.csv`. `FileSeedMatcher` can replay them with
`seedRoot` set to the parent of `<out>` and `seed` set to its name; Similarity
Flooding FILE input can also read them. Families come from `--groups` or,
explicitly, `--family-from-dataset`. With one family, it falls back to
leave-one-scenario-out and says so. Control matchers are rejected as features.
It requires scikit-learn.

```powershell
python tools/learned_combiner.py --records <run>/paper_evaluation/input.jsonl `
  --bases LevenshteinMatcher,PMFMatcher --groups configs/evaluation-groups.json --out seeds/learned_lofo
```

Rebuilt from the original eight base matrices of the 382 Valentine scenarios, all
382 learned matrices and every per-family coefficient were bit-identical to the
historical leave-one-family-out output.

Matcher `wall_ms` is a measured wall duration, with cached results marked. It is not peak RSS, CPU time, or a controlled timing benchmark. The existing timing and cluster experiment tools and SF diagnostic exports remain the route for those experiments; ordinary report generation is not a substitute timing benchmark.

## Supported dataset input

See [dataset acquisition and preparation](datasets.md) for source links, checked download
manifests, standalone import commands and preparation of local releases.

Normal layout, for both single- and multi-table scenarios:

```text
<dataset>/<scenario>/
  source/*.csv
  target/*.csv
  ground_truth/<source-table>___<target-table>.csv
  ground_truth_full.csv                  # alternative/full scenario truth
  metadata/                             # optional; required by methods that use profiles
```

Configure a dataset directory in `datasets.yaml`, not the enclosing corpus root. For example:

```yaml
---
name: "Test-IMDB"
path: "../datasets-local/prepared/test/Test-IMDB"
```

Dataset paths should be relative to `defaultDatasetBasePath`, normally `data/`. The generated dataset configuration uses this convention. Scenario discovery is sorted and ignores output/metadata folders; a folder with only one input side is an error. Side folders contain CSV tables only; unrelated sidecar files are ignored.

The loader:

- uses UTF-8, accepting a BOM, quoted delimiters and quoted multiline cells;
- preserves strings, leading zeros, literal `NA`, whitespace and empty cells;
- validates every row width and rejects duplicate headers with an actionable import/rename error;
- preserves a blank single-column CSV record as an empty value instead of silently dropping the row;
- accepts binary 0/1 or 0.0/1.0 ground truth, plain or labeled;
- aligns labeled truth to actual table/column order, including the legacy `table.csv.column` label form;
- supports pair-block truth, full-only truth, or both, validating consistency when both exist;
- allows absent pair blocks as negatives when scenario truth is explicitly supplied;
- rejects missing evaluation truth, malformed dimensions, unknown/duplicate truth labels and inconsistent blocks.

Matrix exports now quote column labels correctly and use UTF-8. Plain caches retain their first data row, use the same safe filenames as the writer, and reject truncated, mis-sized or non-finite cached matrices. These checks prevent a readable dataset from producing a silently corrupted evaluation matrix downstream.

An unlabeled full matrix has only a positional contract: sorted table filenames, with each table's CSV header order. Labeled truth can be reordered. If a full unlabeled matrix and explicit pair blocks disagree, the loader fails instead of guessing which order was intended.

Datasets without truth can be used for inference with all evaluation disabled. They cannot produce accuracy metrics. CSV/GT readability does not certify semantic ground-truth correctness or profile readiness. Existing managed-profile checks still reject unavailable/changed FD profiles; domain catalogues do not become ordinary row tables merely because their CSV is readable.

Raw Valentine JSON mappings, GDC releases and other external formats still need their explicit existing import adapters. See [dataset preparation](datasets.md); importing preserves strings and records corrections rather than guessing column identities. Pubs/ORIG_/ENC_ layouts require conversion or supplied truth before evaluation.

The importer's SHA-256 implementation now works in the existing Python 3.10 matcher environment as well as newer Python versions; hashes and cache identities remain byte-for-byte compatible.

## Repeat a dataset audit

Build Schematch and create a dependency classpath with Maven if `target/cp.txt` is absent, then:

```powershell
python tools/audit_datasets.py --dataset <dataset-directory>
# Audit an explicit JSON list of local dataset directories:
python tools/audit_datasets.py --manifest configs/dataset-inputs.json
```

`DatasetAudit` streams every CSV record through the same Java parser as normal loading, without retaining table values. It validates truth against discovered columns and table blocks, never mines profiles, and never rewrites inputs. Reports and discovered manifests go into `target/` by default. Unreadable directories, invalid data and inference-only datasets remain visible in the reports.

Repeated, transformed and self-matching variants are separate scenarios, not automatically independent data sources for statistical inference.
