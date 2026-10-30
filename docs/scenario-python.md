# Scenario-level Python matchers

Some methods need all tables in a scenario to compare table membership,
dependencies or entities. `ScenarioPythonServerMatcher` sends one HTTP request
per scenario to the bundled `study_server.py` on port 5102. Table-pair methods
use `seed_server.py` on port 5101. The names are retained for compatibility;
no external research checkout is required.

See [Python installation](python-matchers.md) for dependencies and
[the matcher guide](matchers.md) for method-specific options.

## Start the server

Run from the repository root in your selected Python environment:

```shell
python -m pip install -r requirements/python.txt
python src/main/resources/python_matchers/study_server.py --port 5102
```

`GET http://127.0.0.1:5102/health` lists method identifiers. Computational modules
are bundled under `src/main/resources/python_matchers/schematch_py/`. Optional
learned methods need their separately configured packages and local models.

## Select a refinement

Configure one first-line matcher as the base. In
`src/main/resources/second_line_matchers.yaml`, use a named adapter:

```yaml
---
name: "DependencyMatcher"
packageName: "external"
params:
  serverPort: [5102]
  initialMatrix: ["RAW_FIRST_LINE"]
  baseMatcherIndex: [0]
```

Enable `runSecondLineMatchers` in `general.yaml`. Methods using dependencies also
need appropriate profiles and `readDependencies: true`. Named adapters fix the
backend method; the generic transport is available for custom methods.

## Base selection and standalone methods

| Setting | Behavior |
|---|---|
| `RAW_FIRST_LINE` | Send original scores from the first-line matcher at `baseMatcherIndex`; recommended for refinements. |
| `PREVIOUS` | Use available first-line scores, which may already have been threshold-boosted. Separate second-line entries do not chain into each other. |
| `SERVER` | Let the backend construct its input. Refinements require external matrices and a `base_recipe`; standalone methods read tables directly. |

For `SERVER` compositions, recipes `val`, `iv`, `iv+0.1val` and `c3` refer to
[the named ensembles](matchers.md). `seed_root` locates user-supplied matrices;
these are not bundled. `RAW_FIRST_LINE` needs no archived matrix files.

`PythonAliteMatcher`, `PythonRactMatcher`, `PythonOmniMatchMatcher`,
`PythonOmniMatchReleaseMatcher` and `PdedMatcher` can compute scores from scenario
tables without a base. They default to `SERVER` and still need their declared
external resources.

## Refinement methods

| Server method | Adapter | Computation |
|---|---|---|
| `base` | `StudyBaseMatcher` | Return a supplied base or compose external matrices. |
| `q` | `PythonQualifiedNameMatcher` | Add table-qualified-name Jaccard similarity; default `q_weight: 0.5`. |
| `dep` | `DependencyMatcher` | Use entity structure, cross-table references and relaxed FD signatures; `q_weight` optionally adds names. |
| `entity_alignment` | `EntityAlignmentMatcher` | Align entities derived from keys, FDs and partial INDs, then add a block bonus. Single-table scenarios return the base. |

Archived configurations sometimes call the dependency components M1, M3 and M4.
These identify the entity, reference and FD-signature components, respectively;
they are not separate executable matchers. Entity alignment defaults to
`lam: 0.1`, `mu: 0.1`, with virtual entities disabled.

## Ordering, ties and solver presets

The refinements normally sort labels into canonical order and add seeded
`1e-7` tie-breaking noise. Configure `canonical`, `tie_noise` and `tie_seed`
explicitly for reproducible comparisons.

| Adapter | Solver | Ordering and ties |
|---|---|---|
| `EntityAlignmentMatcher` | Greedy | Canonical ordering and seeded noise. |
| `ExactEntityAlignmentMatcher` | SciPy mixed-integer optimization | Same ordering and tie defaults. |
| `StudyReplayEntityAlignmentMatcher` | SciPy mixed-integer optimization | Original order, no noise; retained for archived configurations. |

An exact optimizer solves its encoded objective; higher matching accuracy is
not guaranteed. The replay preset fixes settings only. Reproducing a published
measurement also requires its input data and base matrices.

`options` is a JSON string that replaces the preset's entire options object.
To retain replay behavior when overriding it, keep `greedy: false`,
`canonical: false` and `tie_noise: 0`.

## Checks

```shell
python -m unittest discover -s tools/tests -v
mvn test dependency:build-classpath -Dmdep.outputFile=target/cp.txt
python src/main/resources/python_matchers/test_study_server_java.py
```

Checks generate small tables and reference matrices to validate numerical
agreement, labels, ordering and Java/Python communication. They need no benchmark
download and do not establish trained-model benchmark accuracy.
