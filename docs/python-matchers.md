# Python matcher installation

Both servers use code bundled under
`src/main/resources/python_matchers/schematch_py/`. They do not search parent
directories for other repositories, source checkouts or virtual environments.
The Java matchers communicate with these servers over localhost.

## Numerical methods and scenario matching

Activate a Python 3.10+ environment and run from the Schematch project directory:

```shell
python -m pip install -r requirements/python.txt
python src/main/resources/python_matchers/seed_server.py --port 5101
```

The built-in table-pair methods are Kang–Naughton, its MI-profile variant,
character/token soft overlap, the three relational-role methods, the random
embedding control, and the twenty simple label / value / record measures of
`schematch_py/simple_matchers.py` (Ratcliff–Obershelp, rapidfuzz token ratios,
Editex, bag distance, Tversky, overlap coefficient, generalized Jaccard,
abbreviation-aware names, TF-IDF over values, range IoU, value length,
histogram intersection, Bhattacharyya, energy distance, top-k values, Benford,
digit counts and DUMAS). These require no external model or source checkout.

Additional numerical channels are bundled with their source hashes in
`schematch_py/SOURCES.json`. They use the supplied tables and profiles; no
surrounding research checkout is imported. Named classes and server method IDs
are listed in [the generated inventory](reference/matcher-catalog.md). Optional model
methods still require their declared packages and local resources.

## COMA, Cupid and Valentine methods

Install the declared package rather than adding a source checkout to Python's
import path:

```shell
python -m pip install -r requirements/valentine.txt
python -m nltk.downloader -d resources/nltk punkt_tab stopwords wordnet omw-1.4
```

Set `NLTK_DATA` to the relative directory `resources/nltk` before starting the
seed server. On PowerShell use `$env:NLTK_DATA="resources/nltk"`; on a POSIX shell
use `export NLTK_DATA=resources/nltk`. Matching requires the resources to be
present and does not download them. Dense COMA/Cupid matrix extraction is bundled
and targets Valentine 1.0.0. An incompatible installed version produces an
explicit error.

## Optional systems

These methods remain optional dependencies. Their absence does not affect the
numerical methods or Java matchers. Models and vector files are user-provided
resources, not source-code dependencies on another checkout.

| Matcher | Installation/configuration |
|---|---|
| SBERT | Install `sentence-transformers` and prepare the requested model in its normal local cache. The server uses offline model loading. |
| ALITE, RACT, OmniMatch | Install `torch` and `sentence-transformers` in the scenario server's environment and cache `all-mpnet-base-v2` (or set `options.model`). ALITE also needs a local roberta-base (`options.roberta` or `SCHEMATCH_ROBERTA`). Model loading is offline. They read the scenario's tables directly and train small per-scenario networks on CPU (runtime depends on table size, model and hardware). |
| bdikit contrastive learning | `transformers` and `torch`; the bdi-cl-v0.2 checkpoint (501 MB, from bdi-kit's model registry) and a local roberta-base, set by `options.checkpoint` / `options.roberta` or `SCHEMATCH_BDIKIT_CL_CHECKPOINT` / `SCHEMATCH_ROBERTA`. bdi-kit itself is not needed. |
| OmniMatch (release port) | `gensim` and `torch`; fastText `cc.en.300.bin` via `options.vectors` / `SCHEMATCH_FASTTEXT` (reduced to 50-d once; set `SCHEMATCH_FASTTEXT_CACHE` to an .npz path to keep the reduction, about 0.8 GB). |
| Snoopy | `gensim` and `torch`; fastText `cc.en.300.bin` (7 GB) via `options.vectors` / `SCHEMATCH_FASTTEXT`, and the repository's `check/` directory via `options.checkpoint_dir` / `SCHEMATCH_SNOOPY`. Loading the fastText model takes several GB of memory. |
| bdikit MaxValSim / TwoPhase | Same environment as Magneto. The top-k stage is the zero-shot `MagnetoZSBP`: bdikit's default `MagnetoFTBP` is fine-tuned on GDC and would leak into GDC benchmarks. TwoPhase's second stage is bdikit's SimFlood or COMA wrapper (COMA needs Java). |
| Magneto | Install a compatible `bdikit`/Magneto environment and prepare its model cache. Select that interpreter with `options.python` or `SCHEMATCH_MAGNETO_PYTHON`. The subprocess executes the bundled worker; it imports installed packages. Use a separate environment if its Valentine requirements differ. |
| ISResMat | Install a compatible `isresmat` CLI in the selected environment. Set `options.python` or `SCHEMATCH_ISRESMAT_PYTHON` when using a separate interpreter; `options.module` defaults to `isresmat`. The CLI must support the bridge's `--export-matrix` option. No checkout or interpreter is discovered automatically. |
| Word vectors | Set `options.vectors` to your local text vector resource. |
| Semantic types | Set `options.adapter` to an installed `module:function` returning type-probability vectors. |
| LLM matcher | Install the configured API client and explicitly enable the existing service gate. |
| PDED | Configure `options.pded_home` or `SCHEMATCH_PDED` to a compatible installed build containing `PdedSchematchRunner`, `target/classes` and `cp.txt`. No sibling-repository search is performed. |
| EmbDI | Configure its separately installed HTTP service; the Java adapter does not load a neighboring repository. |

An adapter class is not evidence that an optional system is installed. In
particular, this repository does not ship ISResMat training code, Magneto model
weights, or a universal installer for every optional system. Missing resources
fail the selected method; no fallback searches the surrounding filesystem.

## Checks

```shell
python tools/audit_matchers.py
python -m unittest discover -s tools/tests -v
mvn test dependency:build-classpath -Dmdep.outputFile=target/cp.txt
python src/main/resources/python_matchers/test_study_server_java.py
```

Regression tests generate small relational tables and compare the bundled methods
with frozen reference matrices. The Java/Python check starts its own local server
and generates its own data and seed matrices. No benchmark dataset or external
experiment repository is required.

For a stricter packaging check, run `python -I tools/verify_standalone.py
--blocked-root <unavailable-directory>` from a separate copy. The check rejects
file reads and imports from the specified directory while running the regression
suite. Normal installed dependencies must be available outside that directory.

The [standalone validation record](../reports/standalone_validation.json) covers a
separate checkout with a fresh Python environment: 139 Java tests, 25 Python
checks, installed Valentine methods, all four Java/scenario dispatch methods,
and a normal PMF/refinement/evaluation run. Model-heavy optional backends were
checked for subprocess configuration only, not trained or benchmarked.
