# Matchers in Schematch

[Complete class inventory](MATCHER_INVENTORY.md) · [Machine-readable catalog](src/main/resources/matcher_catalog.json)

See [evaluation and dataset support](EVALUATION_AND_DATASETS.md) for paper metrics and input validation.

The inventory distinguishes Java implementations, Java combinations with Python components, table-pair Python methods, scenario Python methods, and external HTTP services. Concrete classes include presets, controls and generic transports; they are not a count of independent algorithms. The 92 configured study seeds are parameter variants and file inputs, not an additional algorithm count. The generated inventory provides current counts. Class presence does not imply that optional models, lexical resources, or Python environments are installed.

## Native algorithms and the straightforward additions

The standard study seed panel was audited against the source: label/string, lexical, set/value, distribution, unary statistics, metadata, composite, and probabilistic-assignment classes are present. Lexical resources remain optional inputs. The audit also checks the existing Python methods and optional seed configurations.

The following names make previously implicit methods directly visible:

| Method | Class / package | Meaning |
|---|---|---|
| Sorted PMF | `PMFMatcher` / `similarity.instance` | L2 rank-frequency similarity; `distance: HELLINGER` is an explicit alternative |
| KS | `KolmogorovSmirnovMatcher` / `similarity.instance` | Preset of DistributionTestMatcher; AUTO domain defaults |
| Wasserstein | `WassersteinMatcher` / `similarity.instance` | Normalized empirical distance; no Valentine clustering |
| Value JSD | `JensenShannonValueMatcher` / `similarity.instance` | Preset comparing shared value identities |
| Table-qualified names Q | `TableQualifiedNameMatcher` / `similarity.label` | Raw Q term, including table names |
| Base + 0.5 Q | `QualifiedNameRefinementMatcher` / `refinement` | Native additive Q refinement; weight configurable |
| Sinkhorn | `SinkhornMatcher` / `refinement` | X103 soft selection; tau 0.1, 20 iterations by default |
| Hungarian bonus | `HungarianBonusMatcher` / `refinement` | X103 unit(base) + (1 + 1e-6) assignment mask |
| Value-shape role V | `ValueShapeRoleMatcher` / `similarity.instance` | Within-table character-distribution role descriptors |
| Null role N | `NullStructureMatcher` / `similarity.instance` | X101 co-missingness role; experimental, not a recommended accuracy improvement |
| VAL | `ValueAverageMatcher` / `ensemble` | Unit mean of invariant unary statistics, PMF, and unary statistics |
| (iv) | `IVMatcher` / `ensemble` | Unit mean of COMA, char3_soft, Cupid, and SBERT MPNet; needs Python seeds |
| (iv) + 0.1 VAL | `IVPlusValueMatcher` / `ensemble` | Additive recipe; preserves absolute score scale |
| C3 | `C3Matcher` / `ensemble` | mean(unit(char3_soft), unit(jsd_values), VAL); needs Python char3_soft |

`DistributionMatcher` remains available for compatibility with existing configurations and its `smx.pmf.distance` JVM property. New configurations should use `PMFMatcher`, whose default L2 metric is independent of that property. PMF retains the old null policy: empty strings count as a value category. It does not perform Hungarian assignment and is not a full JMM implementation.

Composite MINMAX operates on each complete scenario component matrix. SUM honors component weights and does not divide by the sum of weights. The named study recipes preserve the nested normalization order; they should not be flattened into an equal-weight average.

```yaml
# first_line_matchers.yaml: choose one base if a refinement uses baseMatcherIndex 0.
---
name: "PMFMatcher"
packageName: "similarity.instance"
params:
  distance: ["L2"]
```

To use the all-native value ensemble, replace that entry with `ValueAverageMatcher`, package `ensemble`. To use the study's hybrid ensemble, choose `IVPlusValueMatcher`, package `ensemble`, and start the seed server.

## Named Python table-pair matchers

All classes below are in `matching.external` and inherit `PythonServerMatcher` through `NamedPythonMatcher`. They execute the existing backend rather than duplicate it. Default port: 5101. Common inherited YAML parameters: `serverPort`, `options` (a JSON string), `timeoutSeconds`, and `failOnError`.

| Class | Server method | Prerequisites / provenance |
|---|---|---|
| `ComaMatcher` | `coma` | Existing Valentine COMA adapter |
| `ComaInstancesMatcher` | `coma_instances` | Existing COMA schema+instances adapter |
| `CupidMatcher` | `cupid` | Existing Valentine Cupid adapter |
| `SbertMatcher` | `sbert` | Sentence-transformer model; options select model/text serialization |
| `MagnetoMatcher` | `magneto` | Compatible model environment |
| `IsresmatMatcher` | `isresmat` | Existing separate ISResMat environment/subprocess |
| `KangNaughtonMatcher` | `kang_naughton` | Project's MI-graph reconstruction |
| `KangNaughtonProfileMatcher` | `kang_naughton_profile` | MI-profile variant of that reconstruction |
| `Char3SoftMatcher` | `char3_soft` | Value 3-gram overlap, not column-name q-grams |
| `TokenSoftMatcher` | `token_soft` | Value-token overlap |
| `RoleGcovMatcher` | `role_gcov` | Existing within-table inclusion roles |
| `RoleGcovSignificanceMatcher` | `role_gcov_sig` | Inclusion roles with significance |
| `RoleUncertaintyMatcher` | `role_uncertainty` | Conditional-uncertainty roles |
| `ValentineDistributionMatcher` | `valentine_distribution` | Full existing Valentine adapter; differs from PMF and simple Wasserstein |
| `ValentineJaccardMatcher` | `valentine_jaccard` | Existing Valentine adapter |
| `WordVectorsMatcher` | `word_vectors` | Explicit vector resource in options |
| `SemanticTypesMatcher` | `semantic_types` | Configured external type adapter |
| `LlmMatcher` | `llm` | Existing opt-in/service gates remain in force |
| `EmbeddingRandomMatcher` | `embedding_random` | Random control, not a substantive algorithm |
| `BdikitContrastiveLearningMatcher` | `bdikit_cl` | bdi-kit's `ct_learning` (RoBERTa-base + SimCLR checkpoint bdi-cl-v0.2, 15-row column samples, cosine); identical embeddings to bdi-kit 0.6.0 (max abs difference 0.0 on three Sakila tables) without importing bdi-kit; the checkpoint is read with `weights_only=True`. Needs `checkpoint` and `roberta` options or `SCHEMATCH_BDIKIT_CL_CHECKPOINT` / `SCHEMATCH_ROBERTA`. Trained for GDC harmonisation: treat GDC benchmarks as seen |
| `SnoopyMatcher` | `snoopy` | Snoopy's released proxy columns (90 x 50 x 300; WikiTable checkpoint by default) over fastText cc.en.300 cell vectors, cosine of the projected columns; a semantic-join signal. The repository has no licence: internal comparison only. Needs `checkpoint_dir` and `vectors` options or `SCHEMATCH_SNOOPY` / `SCHEMATCH_FASTTEXT` |
| `BdikitMaxValSimMatcher` | `bdikit_max_val_sim` | bdikit MaxValSim: zero-shot Magneto top-20, re-scored 0.5 x TF-IDF value similarity + 0.5 x Magneto; Magneto environment |
| `BdikitTwoPhaseMatcher` | `bdikit_two_phase` | bdikit TwoPhase: zero-shot Magneto top-20, then SimFlood per source column (1:1 output); Magneto environment |
| `BdikitTwoPhaseComaMatcher` | `bdikit_two_phase_coma` | The same with COMA as second stage |

### Simple measures (added 2026-10-15)

All in `schematch_py/simple_matchers.py`; no model or download. Labels are split on camelCase / snake_case / digits and lower-cased, and the table prefix is dropped. Every output is in [0, 1]. Numeric-only measures give 0 when either column is not numeric (at least 90% of non-empty values parse).

| Class | Server method | Measure |
|---|---|---|
| `RatcliffObershelpMatcher` | `ratcliff_obershelp` | difflib Ratcliff–Obershelp ratio of labels |
| `TokenSortRatioMatcher` | `token_sort_ratio` | rapidfuzz token_sort_ratio of labels |
| `TokenSetRatioMatcher` | `token_set_ratio` | rapidfuzz token_set_ratio of labels |
| `PartialRatioMatcher` | `partial_ratio` | rapidfuzz partial_ratio (best-aligned substring) |
| `EditexMatcher` | `editex` | Editex phonetic edit similarity (Zobel and Dart 1996) |
| `BagDistanceMatcher` | `bag_distance` | Bag (character multiset) distance similarity |
| `TokenTverskyMatcher` | `tversky` | Tversky index of label token sets (alpha = beta = 0.5) |
| `TokenOverlapCoefficientMatcher` | `overlap_coefficient` | Overlap coefficient of label token sets (the native `OverlapLabelMatcher` uses q-grams) |
| `GeneralizedJaccardMatcher` | `generalized_jaccard` | Generalized Jaccard of tokens, Jaro–Winkler >= 0.8, one-to-one |
| `AbbreviationMatcher` | `abbreviation` | Abbreviation-aware names: common-prefix stripping, ordered token prefixes, consonant skeletons |
| `TfIdfValuesMatcher` | `tfidf_values` | TF-IDF cosine of whole-column value documents |
| `RangeOverlapMatcher` | `range_overlap` | IoU of numeric or date [min, max] intervals |
| `ValueLengthMatcher` | `value_length` | 1 - total variation distance of value-length histograms |
| `HistogramIntersectionMatcher` | `histogram_intersection` | Histogram intersection on a shared 20-bin grid (numeric) |
| `BhattacharyyaMatcher` | `bhattacharyya` | Bhattacharyya coefficient on the same grid (numeric) |
| `EnergyDistanceMatcher` | `energy_distance` | exp(-energy distance / pooled std) (numeric) |
| `TopKValuesMatcher` | `topk_values` | Jaccard of the 10 most frequent values |
| `BenfordMatcher` | `benford` | 1 - Jensen–Shannon distance of leading-digit distributions |
| `DigitCountMatcher` | `digit_count` | 1 - Jensen–Shannon distance of digit-count distributions |
| `DumasMatcher` | `dumas` | DUMAS (Bilke and Naumann 2005), simplified: the 20 most similar record pairs by TF-IDF row cosine, one-to-one, then the mean normalised Levenshtein similarity per column pair |

```yaml
---
name: "ComaMatcher"
packageName: "external"
params:
  serverPort: [5101]
---
name: "SbertMatcher"
packageName: "external"
params:
  options: ['{"model":"all-mpnet-base-v2"}']
```

These example entries run separate first-line matchers. For a combined base, use a named ensemble instead. The generic `PythonServerMatcher` and its `method` parameter remain available for advanced/custom use. Named adapters fix their algorithm: changing `method` to a different algorithm is rejected. Different variants of one algorithm are configured through their existing options.

Server code and numerical helpers are bundled with Schematch. Optional third-party methods use installed packages, cached models, or explicitly configured interpreters. See [Python installation](PYTHON_MATCHERS.md). No sibling checkout is discovered automatically.

## EmbDI service adapter

`EmbdiMatcher` in package `sota` is a Java HTTP adapter to a separately installed EmbDI service, using port 5001 by default. The EmbDI algorithm and service are not bundled or launched by Schematch. This adapter sends the scenario path and expects a complete scenario matrix; the service must have access to the same dataset files. Its presence in the Java source tree does not make EmbDI a native Java implementation.

## Scenario methods and named presets

The original `ScenarioPythonServerMatcher` is a transport class. Its `method` selected one of four implementations in `study_server.py`. They were already executable matchers, but had no dedicated algorithm classes. They now have named adapters, all in package `external` (the standalone matchers added later are listed below the table):

| Class | Backend | Default behavior |
|---|---|---|
| `StudyBaseMatcher` | `base` | Passed base, or study-file composition in SERVER mode |
| `PythonQualifiedNameMatcher` | `q` | Base + 0.5 Q, with server ordering/tie options |
| `DependencyMatcher` | `dep` | X105 M1/M3/M4 refinement; `q_weight` adds Q |
| `EntityAlignmentMatcher` | `entity_alignment` | Greedy, canonical order, seeded 1e-7 tie noise |
| `ExactEntityAlignmentMatcher` | `entity_alignment` | Exact SciPy MILP; canonical/tie-noise defaults retained |
| `StudyReplayEntityAlignmentMatcher` | `entity_alignment` | Exact solver, canonical=false, tie_noise=0 |

Four standalone scenario matchers were added on 2026-10-15. They read the scenario's tables themselves, need no base (a sent base is ignored) and default to `initialMatrix: SERVER`, so they also run without a first-line matcher. ALITE and RACT follow their published code (read, not copied); OmniMatch is described below the table.

| Class | Backend | Method |
|---|---|---|
| `PythonAliteMatcher` | `alite` | ALITE holistic matching (Khatiwada et al., VLDB 2023; northeastern-datalab/alite): values-only serialisation (unique lower-cased alphanumeric tokens, top 512 by IDF), RoBERTa-base `pooler_output` (`roberta_serialized`), sklearn agglomerative clustering of the string columns of tables with >= 3 rows (Euclidean, complete linkage, connectivity 0 inside a table), k from the widest table to n - 1 by silhouette. ALITE outputs clusters only; the score (cos + 0.5 same-cluster) / 1.5 is ours |
| `PythonRactMatcher` | `ract` | RACT (Traeger et al.; leotraeg/RACT, variant b.I): mpnet embedding of the SerReference serialisation (column, table, type, PK/FK, 5 values, weak and strong tables), a 768-512-256-#tables sigmoid classifier (dropout 0.2, L2 0.001, focal loss alpha 0.9 gamma 2, Adam 1e-3, 100 epochs, batch 16, 5-fold stratified ensemble) trained on the target columns plus 1-hop join-path view copies labelled exp(-0.5) for the other table; cosine inside each source column's top-2 tables, 0.01 x cosine outside |
| `PythonOmniMatchReleaseMatcher` | `omnimatch_release` | OmniMatch ported from the authors' release (amazon-science/omnimatch 515f8ce, CC BY-NC 4.0): join / non-join pairs generated from the scenario's own tables (splits, overlaps, typos), five relations (infrequent-token Jaccard, fastText cosine, header-token JS, Jaccard, containment), top-3 graph, 987 node features, 2-layer RGCN (dgl RelGraphConv semantics) with a triplet loss, 30 epochs, 256-d; score 1 - d / 2. Needs fastText cc.en.300 (`vectors` or `SCHEMATCH_FASTTEXT`; cut to 50-d by PCA as the release's cc.en.50; `vectors_cache` / `SCHEMATCH_FASTTEXT_CACHE` stores the reduction). Results vary strongly with `seed` and with the column order the generator sees (top-1 on IMDB D1-D2 0.63 to 0.83): average several seeds |
| `PythonOmniMatchMatcher` | `omnimatch` | Our earlier paper-level OmniMatch reimplementation (five relations, mpnet value embeddings, split-and-perturbed views, plain-torch RGCN); kept for comparison, prefer `omnimatch_release` |

Deviations to keep in mind: RACT reads declared foreign keys, which the scenarios do not have, so its references are profiled or partial (>= 0.9) INDs into keys; it drops columns outside the top-k tables, which would leave the matrix incomplete; and the original sweeps k (`top_tables`). RoBERTa-base has no trained pooler, so ALITE's `pooler_output` comes from a randomly initialised layer, in the original as here; the initialisation is seeded (`seed`) and `pooling` can be set to `cls` or `mean`. ALITE needs a local roberta-base (`options.roberta` or `SCHEMATCH_ROBERTA`); `encoder: "mpnet"` uses the sentence encoder instead. The OmniMatch port documents its own deviations in `schematch_py/omnimatch_release.py` (placeholder generator settings, seeded RNG, guards for tables of 1-2 rows, NFKD instead of unidecode, a wordninja-style splitter over the vector vocabulary).

Options: `seed`; ALITE `encoder`, `roberta`, `pooling`, `linkage`, `bonus`, `strings_only`, `max_tokens`; RACT `model`, `top_tables`, `decay`, `folds`, `epochs`, `batch_size`, `alpha`, `gamma`, `l2`, `dropout`, `lr`; OmniMatch `epochs`, `hidden`, `dim`, `margin`, `top_k`, `lr`. With a single target table RACT reduces to the cosine.

The other scenario methods send **one request containing all tables' column labels and a scenario-wide base matrix**, rather than running independently on table pairs. Default port: 5102. Use them on the second line. Common inherited parameters also include `initialMatrix` and `baseMatcherIndex`.

```yaml
# second_line_matchers.yaml; the only first-line matcher is the desired base ensemble.
---
name: "DependencyMatcher"
packageName: "external"
params:
  initialMatrix: ["RAW_FIRST_LINE"]
  options: ['{"q_weight":0.5}']
---
name: "EntityAlignmentMatcher"
packageName: "external"
params:
  initialMatrix: ["RAW_FIRST_LINE"]
```

Enable `runSecondLineMatchers` in general.yaml. These two entries compare two refinements of the same first-line base; they do **not** chain dep into entity alignment. Names are visible in the source tree, YAML, logs, and result identities. Use one base matcher/ensemble when relying on index 0.

RAW_FIRST_LINE uses the original score matrix; PREVIOUS may use threshold-boosted scores. SERVER loads archived study seed files and needs `base_recipe` and `seed_root`. The native `QualifiedNameRefinementMatcher` implements base+Q without Python or artificial tie noise; for direct numerical comparison with the Python Q adapter set `tie_noise: 0` there.

`options` replaces the whole JSON object, including a named preset's defaults. When overriding study replay options, retain all three settings: `greedy:false`, `canonical:false`, `tie_noise:0`. Exact optimization and benchmark accuracy are different properties.

See [STUDY_SERVER.md](src/main/resources/python_matchers/STUDY_SERVER.md) for profile requirements, startup, and existing study checks. Its computational modules are bundled with Schematch and require only the declared numerical dependencies.

## Native refinements as second-line or nested first-line methods

`QualifiedNameRefinementMatcher`, `SinkhornMatcher`, and `HungarianBonusMatcher` accept the same RAW_FIRST_LINE/PREVIOUS base choice and index. Alternatively, their `base` parameter accepts a JSON component and computes that base directly, making nesting possible without a pipeline redesign.

```yaml
---
name: "QualifiedNameRefinementMatcher"
packageName: "refinement"
params:
  initialMatrix: ["RAW_FIRST_LINE"]
  weight: [0.5]
```

Or an independent first-line refinement:

```yaml
---
name: "HungarianBonusMatcher"
packageName: "refinement"
params:
  base: ['{"name":"ValueAverageMatcher","package":"ensemble"}']
```

## Methods without a bundled live backend

- **PDED** has a live study-server adapter (`pded`, `PdedMatcher`) and requires an explicitly configured compatible build through `options.pded_home` or `SCHEMATCH_PDED`. The PDED runner is not bundled or discovered in a neighboring checkout.
- **PRISMA, SiMa, SemStruct-reimpl, ReMa, and the learned combiner:** the catalog lists available archived FILE seed names separately. Schematch can consume supplied matrices through SF FILE input; it cannot compute these methods through the bundled servers. Live integration still needs each system's preprocessing, execution and output contract.
- **R13, H, M5, X102–X104, the graph literal term L, and X52/F_std:** these depend on additional profile, self-matrix, graph, or pipeline semantics. V and N were small enough to port in this pass. The remaining terms require a separate parity-backed port/backend rather than an alias to an unrelated existing method.
- **JMM-2:** no second-order implementation is bundled. `PMFMatcher` supplies the sorted marginal-distribution similarity; joint column-pair distributions and second-order assignment search remain separate work.
- **Candidate pruning:** no table/column blocking redesign is part of this change.

## Repeating the audit and checks

```powershell
python tools/audit_matchers.py
python tools/audit_matchers.py --check
python tools/audit_dataset_sources.py --check
python -m unittest discover -s tools/tests -v
mvn -o test
```

The matcher audit follows the Matcher superclass chain, checks the seed panel's classes, and verifies complete named-adapter coverage of both server registries. `--check` fails on stale generated files without changing them. `MatcherCatalogTest` configures every audited seed through MatcherFactory, including inherited parameters. Offline native tests additionally check explicit PMF semantics, nested study recipes, native role terms, Python-reference Q/Sinkhorn/Hungarian values, matrix input selection, and table-pair/scenario HTTP dispatch. Transport tests use a local stub; they do not assert model availability or trained-model accuracy.

The [2026-10-15 inventory validation](reports/inventory_validation.json) records
139 passing Java tests and 29 passing Python tests, including the separate-copy
Python run with the original research workspace unavailable.
