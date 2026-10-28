# Local release preparation

This checkout contains the curated framework release prepared on 2026-10-28.
It retains the upstream Git history and the existing FILE-seed integration.
The research working checkout is unchanged. No public fork, push or release tag
has been created by this preparation.

## Included

- Java matchers, refinements and compositions, with named Python adapters.
- Bundled numerical Python implementations and explicit optional-service adapters.
- Strict CSV/truth loading, paper metrics and portable dataset preparation.
- Synthetic examples, generated inventories, regression fixtures and tests.
- The six inherited profiler JARs and a small, checksummed Maven repository for
  legacy build-support snapshots that the original remote no longer supplies.

## Excluded

Benchmark datasets, large seed matrices, model weights, run outputs, logs,
compiled classes, caches, editor settings, backup files, old database-download
scripts, and validation scripts requiring a surrounding research checkout are
excluded from the current release tree. Historical objects remain in Git history.
The paper's protocols, experiment configurations and full cached results belong
to the separate paper artifact and should pin this framework's exact commit.

## Publication requirements

The technical validation is recorded in `reports/release_validation.json`.
Before publishing, the maintainers must resolve upstream licensing and the
redistribution terms of the bundled profiler/build-support binaries and copied
implementations. See [THIRD_PARTY.md](THIRD_PARTY.md). No project-wide license is
assigned by this preparation, and a passing technical check does not settle
those permissions.

Optional learned systems and third-party services are not covered by numerical
reference tests alone. Their required resources are listed in
[PYTHON_MATCHERS.md](PYTHON_MATCHERS.md); models are never downloaded by default.

## Review and publish

```shell
python tools/check_release.py
python tools/audit_matchers.py --check
python tools/audit_dataset_sources.py --check
python -m unittest discover -s tools/tests -v
mvn test compile dependency:build-classpath -Dmdep.outputFile=target/cp.txt
python tools/run_schematch.py
git log --oneline -4
git status --short
```

After the permissions review, create the GitHub fork, add it as `origin`, review
the target URL, then push the prepared branch. Keep `upstream` pointing to the
existing Schematch repository. Create the paper's immutable tag only after the
final review, and record its commit in the paper artifact. Never force-push to
the upstream repository as part of this procedure.
