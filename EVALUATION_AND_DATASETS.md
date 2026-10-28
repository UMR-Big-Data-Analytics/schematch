# Paper evaluation and dataset loading

Schematch now has an optional raw-score evaluation path using the paper's standalone Python functions. It complements the original binary Precision/Recall/F1/Overall evaluator. It needs NumPy and SciPy, not a matcher server or the surrounding research repository.

## Metrics and extraction

| Paper measurement | Supported behavior |
|---|---|
| Global Recall@GT | Exact expected, pessimistic, and optimistic tie treatments |
| Row MRR | Exact expected and pessimistic primary metrics; optimistic diagnostics |
| F1_H | Maximum-score Hungarian assignment, dropping scores <= 1e-9 |
| F1_Hr | Mean over the same 50 seeded random row/column permutations as `score_screen_metrics.py` |
| F1_M | Mutual top-1, retaining tied positive maxima |
| J_tf | (R_exp + F1_Hr) / 2; paired differences give the paper's delta objective |
| Earlier objective J | (R_pess + F1_H) / 2; separately named `J_preregistered` |
| Extraction diagnostics | Precision, recall, F1, Overall, TP/FP/FN, number of matches, match/GT ratio |
| Ranking/tie diagnostics | Rank bounds, margins, entropy, zero-score share, ties at maxima |
| Comparisons | Per-scenario paired differences, family-macro summaries, descriptive wins/losses/ties |
| Uncertainty | Optional 95% percentile bootstrap, resampling explicit units within families |

All nine existing extraction policies are available: source top-1, mutual top-1, relative thresholds 1.0 and 0.95, global threshold 0.5, Hungarian, and partial Hungarian with penalties 0.3/0.5/0.7.

`tools/paper_metrics.py` is a standalone snapshot of the project's original evaluation module and the six reusable functions from the original paper scorer. Source hashes are in its header. It has no imports from those folders. The validation checks all seven primary metrics and every permutation against the original implementation on 30 matrices, including ties, negatives, rectangular shapes and many-to-many truth.

## Enable evaluation during a normal run

Merge these settings into your existing `general.yaml`; retain its other required settings:

```yaml
paperEvaluation: true
paperEvaluationPython: "python"
paperEvaluationScript: "tools/evaluate_paper.py"

# Paper evaluation receives raw matrices and performs extraction itself.
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

The primary metrics use scenario key `dataset/scenario` for the same deterministic permutations as the paper. A grouping manifest can override `permutation_key` if the configured dataset uses a different alias. The evaluator records NumPy/SciPy versions because solver behavior can matter for tied assignments.

## Match the paper's aggregation and selected cases explicitly

Without a grouping manifest the report groups by configured dataset, with a minimum size of one. This is descriptive, not an automatic reproduction of a particular preregistered analysis. To reproduce a paper table, supply that analysis's cases, families, independent units, regimes and splits. For example:

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
    "reason": "Outside the registered case set"
  }
}
```

Every evaluated case must occur in an explicitly supplied manifest. There is no guess for the source table or an independent unit. Summaries keep regime, split, corpus and group strata separate. Family means are averaged equally, after excluding families below the chosen size.

```yaml
paperEvaluationGroups: "configs/paper-groups.json"
paperEvaluationMinFamilySize: 5
paperEvaluationBootstrap: 10000
paperEvaluationBootstrapSeed: 20261014
paperEvaluationTimeoutSeconds: 3600
```

The SF paper's primary family minimum is five; other analyses use their own registered rules. Bootstrap requires the grouping manifest, resamples complete units within families, and keeps all scenarios in each sampled unit together. To reproduce a scenario-stratified bootstrap, use each scenario as its unit. Use the frozen analysis scripts for the exact original experiment contrasts, sample selection, preregistered seeds and figure checks; a generic summary cannot infer those decisions from filenames.

Matcher `wall_ms` is a measured wall duration, with cached results marked. It is not peak RSS, CPU time, or the paper's dedicated timing protocol. The existing timing and cluster experiment tools and SF diagnostic exports remain the route for those experiments; ordinary report generation is not a substitute timing benchmark.

## Supported dataset input

See [dataset acquisition and preparation](DATASETS.md) for source links, checked download
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

Raw Valentine JSON mappings, GDC releases and other external formats still need their explicit existing import adapters. See [dataset preparation](DATASETS.md); importing preserves strings and records corrections rather than guessing column identities. Pubs/ORIG_/ENC_ layouts require conversion or supplied truth before evaluation.

The importer's SHA-256 implementation now works in the existing Python 3.10 matcher environment as well as newer Python versions; hashes and cache identities remain byte-for-byte compatible.

## Repeat a dataset audit

Build Schematch and create a dependency classpath with Maven if `target/cp.txt` is absent, then:

```powershell
python tools/audit_datasets.py --dataset <dataset-directory>
# Audit an explicit JSON list of local dataset directories:
python tools/audit_datasets.py --manifest configs/dataset-inputs.json
```

`DatasetAudit` streams every CSV record through the same Java parser as normal loading, without retaining table values. It validates truth against discovered columns and table blocks, never mines profiles, and never rewrites inputs. Reports and discovered manifests go into `target/` by default. Unreadable directories, invalid data and inference-only datasets remain visible in the reports.

For this pass's complete counts, see [the dataset compatibility audit](reports/DATASET_COMPATIBILITY.md). Repeated/regime/self-matching variants count as separate scenarios, not independent datasets for statistical inference.
