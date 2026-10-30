# Preparing a release

Use this checklist to prepare and publish a source release. Preserve upstream
Git history, contributor attribution and stored-matrix compatibility. Run all
commands from the repository root. See [the documentation index](../README.md)
for the user guides.

## Included

- Java matchers, refinements and compositions, with named Python adapters.
- Bundled numerical Python implementations and explicit optional-service adapters.
- Strict CSV/truth loading, raw-score metrics and portable dataset preparation.
- Synthetic examples, generated inventories, regression fixtures and tests.
- The six inherited profiler JARs and a small, checksummed Maven repository for
  legacy build-support snapshots that the original remote no longer supplies.

## Excluded

Benchmark datasets, large seed matrices, model weights, run outputs, logs,
compiled classes, caches, editor settings, backup files, old database-download
scripts, and validation scripts requiring a surrounding research checkout are
excluded from the current release tree. Historical objects remain in Git history.
Published experiments should distribute protocols, configurations and result
snapshots separately and pin the exact framework commit.

## Publication requirements

The technical validation is recorded in `reports/release_validation.json`.
Before publishing, the maintainers must resolve upstream licensing and the
redistribution terms of the bundled profiler/build-support binaries and copied
implementations. See [THIRD_PARTY.md](../THIRD_PARTY.md). No project-wide license is
assigned by this preparation, and a passing technical check does not settle
those permissions.

Optional learned systems and third-party services are not covered by numerical
reference tests alone. Their required resources are listed in
[Python installation](../python-matchers.md); models are never downloaded by default.

## Review and publish

```shell
python tools/check_release.py --working-tree
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
existing Schematch repository. Create an immutable release tag after the
final review and record its commit in associated experiment records. Never force-push to
the upstream repository as part of this procedure.
