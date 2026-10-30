# Matchers in Schematch

[Complete class inventory](reference/matcher-catalog.md) · [Machine-readable catalog](../src/main/resources/matcher_catalog.json)

See [evaluation and dataset support](evaluation.md) for raw-score metrics and input validation.

The inventory distinguishes Java implementations, Java combinations with Python components, table-pair Python methods, scenario Python methods, and external HTTP services. Concrete classes include presets, controls and generic transports; they are not a count of independent algorithms. The generated inventory provides current counts. Class presence does not imply that optional models, lexical resources, or Python environments are installed.

## Native algorithms and compositions

Java methods cover label/string, lexical, value-set, distribution, column-statistics, metadata, composite and assignment-based matching. Lexical resources remain optional. The generated catalog lists every concrete class and checks named Python adapters.

PMF compares sorted value frequencies; KS is the Kolmogorov-Smirnov test and JSD is Jensen-Shannon divergence. In these formulas, `unit(matrix)` means min-max normalization of a complete scenario matrix to [0, 1]; a constant matrix becomes zero.

| Method | Class / package | Meaning |
|---|---|---|
| Sorted PMF | `PMFMatcher` / `similarity.instance` | L2 rank-frequency similarity; `distance: HELLINGER` is an explicit alternative |
| KS | `KolmogorovSmirnovMatcher` / `similarity.instance` | Preset of DistributionTestMatcher; AUTO domain defaults |
| Wasserstein | `WassersteinMatcher` / `similarity.instance` | Normalized empirical distance; no Valentine clustering |
| Value JSD | `JensenShannonValueMatcher` / `similarity.instance` | Preset comparing shared value identities |
| Table-qualified names Q | `TableQualifiedNameMatcher` / `similarity.label` | Raw Q term, including table names |
| Base + 0.5 Q | `QualifiedNameRefinementMatcher` / `refinement` | Native additive Q refinement; weight configurable |
| Sinkhorn | `SinkhornMatcher` / `refinement` | Soft doubly stochastic normalization; tau 0.1, 20 iterations by default |
| Hungarian bonus | `HungarianBonusMatcher` / `refinement` | unit(base) + (1 + 1e-6) assignment mask |
| Value-shape role V | `ValueShapeRoleMatcher` / `similarity.instance` | Within-table character-distribution role descriptors |
| Null role N | `NullStructureMatcher` / `similarity.instance` | Co-missingness descriptor; experimental, not a recommended accuracy improvement |
| Value ensemble (legacy `VAL`) | `ValueAverageMatcher` / `ensemble` | Mean of separately normalized column-profile, PMF and recoding-invariant column-profile scores |
| Hybrid ensemble (legacy `(iv)`) | `IVMatcher` / `ensemble` | Mean of separately normalized COMA, value-trigram overlap, Cupid and SBERT MPNet scores; needs the Python table-pair server |
| (iv) + 0.1 VAL | `IVPlusValueMatcher` / `ensemble` | Additive recipe; preserves absolute score scale |
| C3 | `C3Matcher` / `ensemble` | mean(unit(char3_soft), unit(jsd_values), VAL); needs Python char3_soft |

`DistributionMatcher` remains available for compatibility with existing configurations and its `smx.pmf.distance` JVM property. New configurations should use `PMFMatcher`, whose default L2 metric is independent of that property. PMF retains the old null policy: empty strings count as a value category. It does not perform Hungarian assignment and is not a full joint-distribution matching (JMM) implementation.

Composite MINMAX operates on each complete scenario component matrix. SUM honors component weights and does not divide by the sum of weights. The named ensemble recipes preserve the nested normalization order; they should not be flattened into an equal-weight average.

```yaml
# first_line_matchers.yaml: choose one base if a refinement uses baseMatcherIndex 0.
---
name: "PMFMatcher"
packageName: "similarity.instance"
params:
  distance: ["L2"]
```

To use the all-native value ensemble, replace that entry with `ValueAverageMatcher`, package `ensemble`. To use the hybrid ensemble, choose `IVPlusValueMatcher`, package `ensemble`, and start the seed server.

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
| `BdikitContrastiveLearningMatcher` | `bdikit_cl` | bdi-kit's `ct_learning` (RoBERTa-base + SimCLR checkpoint bdi-cl-v0.2, 15-row column samples, cosine); inference checked against bdi-kit 0.6.0 without importing bdi-kit; the checkpoint is read with `weights_only=True`. Needs `checkpoint` and `roberta` options or `SCHEMATCH_BDIKIT_CL_CHECKPOINT` / `SCHEMATCH_ROBERTA`. Trained for GDC harmonisation: treat GDC benchmarks as seen |
| `SnoopyMatcher` | `snoopy` | Snoopy's released proxy columns (90 x 50 x 300; WikiTable checkpoint by default) over fastText cc.en.300 cell vectors, cosine of the projected columns; a semantic-join signal. The repository has no licence: internal comparison only. Needs `checkpoint_dir` and `vectors` options or `SCHEMATCH_SNOOPY` / `SCHEMATCH_FASTTEXT` |
| `BdikitMaxValSimMatcher` | `bdikit_max_val_sim` | bdikit MaxValSim: zero-shot Magneto top-20, re-scored 0.5 x TF-IDF value similarity + 0.5 x Magneto; Magneto environment |
| `BdikitTwoPhaseMatcher` | `bdikit_two_phase` | bdikit TwoPhase: zero-shot Magneto top-20, then SimFlood per source column (1:1 output); Magneto environment |
| `BdikitTwoPhaseComaMatcher` | `bdikit_two_phase_coma` | The same with COMA as second stage |

### Simple Python measures

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

Server code and numerical helpers are bundled with Schematch. Optional third-party methods use installed packages, cached models, or explicitly configured interpreters. See [Python installation](python-matchers.md). No sibling checkout is discovered automatically.

## EmbDI service adapter

`EmbdiMatcher` in package `sota` is a Java HTTP adapter to a separately installed EmbDI service, using port 5001 by default. The EmbDI algorithm and service are not bundled or launched by Schematch. This adapter sends the scenario path and expects a complete scenario matrix; the service must have access to the same dataset files. Its presence in the Java source tree does not make EmbDI a native Java implementation.

## Scenario methods and named presets

`ScenarioPythonServerMatcher` sends one scenario-wide request to `study_server.py`. The named classes below select backend methods directly and are all in package `external`. Configure refinements as second-line matchers; standalone methods are listed below.

| Class | Backend | Default behavior |
|---|---|---|
| `StudyBaseMatcher` | `base` | Supplied base, or composition of external matrix files in SERVER mode |
| `PythonQualifiedNameMatcher` | `q` | Base + 0.5 Q, with server ordering/tie options |
| `DependencyMatcher` | `dep` | Entity, reference and relaxed-FD-signature refinement; `q_weight` adds Q |
| `EntityAlignmentMatcher` | `entity_alignment` | Greedy, canonical order, seeded 1e-7 tie noise |
| `ExactEntityAlignmentMatcher` | `entity_alignment` | Exact SciPy MILP; canonical/tie-noise defaults retained |
| `StudyReplayEntityAlignmentMatcher` | `entity_alignment` | Exact solver, canonical=false, tie_noise=0 |

The standalone scenario matchers below read the scenario's tables themselves, need no base (a sent base is ignored) and default to `initialMatrix: SERVER`, so they also run without a first-line matcher. ALITE and RACT follow their published code (read, not copied); OmniMatch is described below the table.

| Class | Backend | Method |
|---|---|---|
| `PythonAliteMatcher` | `alite` | ALITE holistic matching (Khatiwada et al., VLDB 2023; northeastern-datalab/alite): values-only serialisation (unique lower-cased alphanumeric tokens, top 512 by IDF), RoBERTa-base `pooler_output` (`roberta_serialized`), sklearn agglomerative clustering of the string columns of tables with >= 3 rows (Euclidean, complete linkage, connectivity 0 inside a table), k from the widest table to n - 1 by silhouette. ALITE outputs clusters only; this adapter converts them to scores with (cos + 0.5 same-cluster) / 1.5 |
| `PythonRactMatcher` | `ract` | RACT (Traeger et al.; leotraeg/RACT, variant b.I): mpnet embedding of the SerReference serialisation (column, table, type, PK/FK, 5 values, weak and strong tables), a 768-512-256-#tables sigmoid classifier (dropout 0.2, L2 0.001, focal loss alpha 0.9 gamma 2, Adam 1e-3, 100 epochs, batch 16, 5-fold stratified ensemble) trained on the target columns plus 1-hop join-path view copies labelled exp(-0.5) for the other table; cosine inside each source column's top-2 tables, 0.01 x cosine outside |
| `PythonOmniMatchReleaseMatcher` | `omnimatch_release` | OmniMatch ported from the authors' release (amazon-science/omnimatch 515f8ce, CC BY-NC 4.0): join / non-join pairs generated from the scenario's own tables (splits, overlaps, typos), five relations (infrequent-token Jaccard, fastText cosine, header-token JS, Jaccard, containment), top-3 graph, 987 node features, 2-layer RGCN (dgl RelGraphConv semantics) with a triplet loss, 30 epochs, 256-d; score 1 - d / 2. Needs fastText cc.en.300 (`vectors` or `SCHEMATCH_FASTTEXT`; cut to 50-d by PCA as the release's cc.en.50; `vectors_cache` / `SCHEMATCH_FASTTEXT_CACHE` stores the reduction). Record the random seed and column order when comparing repeated runs |
| `PythonOmniMatchMatcher` | `omnimatch` | Alternative OmniMatch reconstruction using sentence embeddings (five relations, mpnet value embeddings, split-and-perturbed views, plain-torch RGCN); kept for comparison, prefer `omnimatch_release` |
| `PythonRemaMatcher` | `rema` | REMA-ARC reconstruction using an attribute-row-cell graph, random walks and skip-gram embeddings. Instance-only; one table per side; requires `gensim`. Computes its own scores without a base matrix. |

Deviations to keep in mind: RACT reads declared foreign keys, which may be absent from input scenarios; this adapter uses profiled or partial (>= 0.9) INDs into keys; it drops columns outside the top-k tables, which would leave the matrix incomplete; and the original sweeps k (`top_tables`). RoBERTa-base has no trained pooler, so ALITE's `pooler_output` comes from a randomly initialised layer, in the original as here; the initialisation is seeded (`seed`) and `pooling` can be set to `cls` or `mean`. ALITE needs a local roberta-base (`options.roberta` or `SCHEMATCH_ROBERTA`); `encoder: "mpnet"` uses the sentence encoder instead. The OmniMatch port documents its own deviations in `schematch_py/omnimatch_release.py` (placeholder generator settings, seeded RNG, guards for tables of 1-2 rows, NFKD instead of unidecode, a wordninja-style splitter over the vector vocabulary).

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

RAW_FIRST_LINE uses the original score matrix; PREVIOUS may use threshold-boosted scores. SERVER composes user-supplied stored score matrices and needs `base_recipe` and `seed_root`. The native `QualifiedNameRefinementMatcher` implements base+Q without Python or artificial tie noise; for direct numerical comparison with the Python Q adapter set `tie_noise: 0` there.

`options` replaces the whole JSON object, including a named preset's defaults. When overriding the archived-configuration replay preset, retain all three settings: `greedy:false`, `canonical:false`, `tie_noise:0`. Exact optimization and benchmark accuracy are different properties.

See [the scenario-server guide](scenario-python.md) for profile requirements, startup, and bundled checks. Its computational modules are bundled with Schematch and require only the declared numerical dependencies.

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

## One-step additive terms (information-matched baselines)

These give SF's information to a matcher without propagation. Each starts from q⁺ = max(q, 0) of its base matrix and adds weighted matrices of the same shape; nothing iterates. D is `DatatypeMatcher` with NaN cells (columns without a non-null value) set to 0. The table lists the implementation defaults; they are configurable, not a guarantee of optimal accuracy on new datasets. All five are in `refinement`, take the base choice described above (`initialMatrix`, `baseMatcherIndex` or `base`), and accept `datatypeWeight` (β) and, except the first, `weight`.

| Class | Matrix | Defaults | Needs |
|---|---|---|---|
| `DatatypeTermMatcher` | q⁺ + βD | β = 5 | — |
| `ClassTermMatcher` | q⁺ + βD + γ·[same value-shape class] | γ = 0.2 | — |
| `RoleTermMatcher` | q⁺ + βD + γR, R = similarity of FD/UCC/key/IND role vectors | γ = 0.2; configurable for recoded data | dependency profiles (`readDependencies`) |
| `TableContextTermMatcher` | q⁺ + βD + λC, C = scaled best-partner mean of each table pair | λ = 0.5 | — |
| `LiteralTermMatcher` | q⁺ + βD + w·L/max L, L = SF's two-step literal evidence | w = 0.05 | — |

A value-shape class is a column's dominant value shape (≥ 50% of its non-empty values), else MIXED. Rule-based semantic-type classes are not included. `LiteralTermMatcher` builds SF's published graph (no dependency edges, inverse-average coefficients, prefix/suffix literal similarities) with the SF graph builder and applies the propagation matrix twice to the literal similarities; L does not depend on q. Its `form` selects RESCALED (default), RAW (unrescaled L) or TWO_UPDATE (weight c = 2/(M1 + 4) from SF's first update on q, ignoring `weight`). With `datatypeWeight: 0`, TWO_UPDATE reproduces the two-update ranking for single-table inputs when the one-edge literal contribution is constant across scored pairs. This condition is checked in the numerical fixtures; it is not a guarantee for arbitrary graph inputs. RAW with weight 0.3 is a fixed-weight approximation.

```yaml
---
name: "RoleTermMatcher"
packageName: "refinement"
params:
  initialMatrix: ["RAW_FIRST_LINE"]
  datatypeWeight: [5.0]
  weight: [0.2]
---
name: "LiteralTermMatcher"
packageName: "refinement"
params:
  initialMatrix: ["RAW_FIRST_LINE"]
  datatypeWeight: [0.0]
  form: ["TWO_UPDATE"]
```

`OneStepTermsTest` checks the terms on small synthetic schemas. With `-Dschematch.test.valentineRoot=<Valentine-layout datasets with FD/UCC/IND profiles>`, it also compares all terms with frozen Python reference matrices on three Valentine scenarios (bundled expected matrices). The scenarios themselves are not bundled.

## Training score combinations

`tools/learned_combiner.py` learns a combination of recorded base-matcher scores
using labelled scenarios from other groups. It writes held-out matrices for
`FileSeedMatcher` or SF FILE input. Training needs multiple labelled scenarios,
so it is a separate tool rather than a refinement trained on the current pair.
See [the tool guide](tools.md) for its inputs and prerequisites.

## External installations and unbundled methods

- **PDED** has a live scenario-server adapter (`pded`, `PdedMatcher`) and requires an explicitly configured compatible build through `options.pded_home` or `SCHEMATCH_PDED`. The PDED runner is not bundled or discovered in a neighboring checkout.
- **PRISMA, SiMa and SemStruct reconstructions:** Schematch can consume externally computed matrices through `FileSeedMatcher` or SF FILE input; it cannot compute these methods through the bundled servers. Live integration still needs each system's preprocessing, execution and output contract.
- **Additional hierarchy, sibling, block, preservation and multistage graph variants:** these are not bundled implementations. They need explicit profile inputs and a validated backend; existing classes should not be treated as aliases for them.
- **JMM-2:** no second-order implementation is bundled. `PMFMatcher` supplies the sorted marginal-distribution similarity; joint column-pair distributions and second-order assignment search remain separate work.
- **Candidate pruning:** a configurable table/column candidate-blocking pipeline is not bundled.

## Repeating the audit and checks

```powershell
python tools/audit_matchers.py
python tools/audit_matchers.py --check
python tools/audit_dataset_sources.py --check
python -m unittest discover -s tools/tests -v
mvn -o test
```

The matcher audit follows the Matcher superclass chain, checks the archived parameter configurations, and verifies complete named-adapter coverage of both server registries. `--check` fails on stale generated files without changing them. `MatcherCatalogTest` configures every audited seed through MatcherFactory, including inherited parameters. Offline native tests additionally check explicit PMF semantics, nested ensemble recipes, native role terms, the one-step additive terms, Python-reference Q/Sinkhorn/Hungarian values, matrix input selection, and table-pair/scenario HTTP dispatch. Transport tests use a local stub; they do not assert model availability or trained-model accuracy.
