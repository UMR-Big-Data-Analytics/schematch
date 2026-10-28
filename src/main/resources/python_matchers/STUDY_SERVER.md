# Study matchers inside Schematch (2026-10-14)

The follow-up study's scenario-level matchers run inside Schematch through a Java wrapper that calls a local Python server.
The computational implementations are bundled in `schematch_py/`; no external study checkout is imported.
The base/refinement presets include `StudyBaseMatcher`, `PythonQualifiedNameMatcher`,
`DependencyMatcher`, `EntityAlignmentMatcher`, `ExactEntityAlignmentMatcher`, and
`StudyReplayEntityAlignmentMatcher`. Additional scenario methods and their
requirements are listed in the generated inventory. See
[the matcher guide](../../../../MATCHERS.md) for their options and pipeline examples.

| part | file |
|---|---|
| Java matcher (second line, one request per scenario) | `src/main/java/de/uni_marburg/schematch/matching/external/ScenarioPythonServerMatcher.java` |
| Java smoke check (no full pipeline) | `src/main/java/de/uni_marburg/schematch/matching/external/ScenarioPythonServerMatcherCheck.java` |
| Python server | `src/main/resources/python_matchers/study_server.py` (port 5102; `GET /health` lists the methods) |
| Python checks | `test_study_server.py` (reproduction, order invariance, labels, HTTP), `test_study_server_java.py` (Java → server end to end) |

## Methods (frozen study configurations)

| method | what | options |
|---|---|---|
| `base` | the base matrix | – |
| `q` | base + 0.5 · table-qualified-name Jaccard (X105 Q) | `q_weight` |
| `dep` | X105 dep: M1 entity term (λ 0, tie-break 0.2) + M3 references (0.1) + M4 relaxed-FD signatures (tie-break 0.1); `q_weight` > 0 gives dep + Q | `q_weight` |
| `entity_alignment` | X109: entity graph (keys, FDs, partial INDs), entity alignment λ 0.1, block bonus μ 0.1, no preservation, virtual entities off; single tables return the base | `greedy` (default **true**), `virtual`, `key_equiv`, `lam`, `mu` |
| `alite`, `ract`, `omnimatch` | standalone matchers added 2026-10-15 (reimplementations of ALITE, RACT, OmniMatch; see MATCHERS.md); they read the scenario's tables, ignore the base and need torch + sentence-transformers | see MATCHERS.md |

**Common options:**
- **Base:** sent by Java (`initialMatrix: PREVIOUS` or `RAW_FIRST_LINE`, the first-line matcher at `baseMatcherIndex`), or composed
  on the server (`initialMatrix: SERVER` with `base_recipe` ∈ {`iv`, `iv+0.1val`, `c3`, `val`} and `seed_root`, as the X105 / X109
  test builds the regime bases).
- **Order and ties:** by default the server computes in a canonical column order (labels sorted) and adds a seeded 1e-7 tie-breaking
  noise (`canonical`, `tie_noise`, `tie_seed`). The output then does not depend on Schematch's column order.
  `canonical: false, tie_noise: 0` reproduces the study's registered matrices exactly.

## Running

1. Install `requirements-python.txt` from the project root, then start the server:
   ```
   python src/main/resources/python_matchers/study_server.py --port 5102
   ```
2. Add it as a second-line matcher, e.g. in `second_line_matchers.yaml` (example configuration):
   ```yaml
   ---
   name: "ScenarioPythonServerMatcher"
   packageName: "external"
   params:
     serverPort: [5102]
     method: ["entity_alignment"]
     initialMatrix: ["SERVER"]
     options: ['{"base_recipe": "iv+0.1val", "seed_root": "seed_in/test"}']
   ```
   With `initialMatrix: PREVIOUS` the base is the first first-line matcher's matrix, e.g. a CompositeMatcher of COMA, char3,
   Cupid and SBERT.
3. **Smoke check without a pipeline:**
   ```
   java -cp target/classes;<deps> de.uni_marburg.schematch.matching.external.ScenarioPythonServerMatcherCheck <scenario> 5102 dep '{"base_recipe":"iv","seed_root":"..."}' out.txt
   ```

## Checks

Run `python -m unittest discover -s tools/tests -v` from the project root for
frozen-matrix parity, column-order invariance and HTTP checks on generated data.
Run `python src/main/resources/python_matchers/test_study_server_java.py` after
compiling and generating `target/cp.txt` for Java/Python dispatch checks.
The frozen reference tests are part of the standalone source release. Historical
full-corpus measurements belong to the separate paper artifact.

## Greedy vs exact entity alignment

The wrapper uses the greedy solver by default. The study's registered X109 results used the exact solver (scipy MILP).

| test corpora, J_tf | exact solver | greedy |
|---|---|---|
| readable | equal (9 IMDB scenarios) | equal |
| all hashed, IMDB, study order without noise | 0.728 | 0.786 |
| all hashed, Sakila, same | 0.428 | 0.442 |
| all hashed, SCOPe, same | 0.564 | 0.564 |
| all hashed, IMDB, canonical order with noise | 0.729 | 0.793 |
| all hashed, Sakila, same | 0.356 | 0.370 |
| all hashed, SCOPe, same | 0.512 | 0.512 |

- Greedy is never worse here and is clearly better on IMDB. This is a post-hoc observation, not a registered result.
- Results computed with the wrapper's defaults are therefore not the registered X109 numbers. Use `greedy: false` to reproduce
  those.
