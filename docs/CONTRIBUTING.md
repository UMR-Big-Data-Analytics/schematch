# Contributing

Keep changes focused and include enough context to explain the resulting
behavior. Preserve upstream attribution and source provenance when adapting an
existing implementation.

For changes to numerical methods, compare against an independent reference or
a frozen expected result. For new adapters, document the actual backend,
required resources and supported input/output contract. A named class alone
does not establish that a published system was reproduced.

Run the checks in [the release checklist](maintainers/releasing.md). Use `mvn test` and the Python test
suite for routine development. Two learned-model subtests require optional
PyTorch and are explicitly skipped when it is absent; model weights are not
needed by those synthetic tests. Heavy model or dataset runs are separate from
the standard test suite.
Java WordNet tests also skip explicitly when the optional NLTK WordNet corpus is
not installed; the corpus is not bundled or downloaded by the tests.

Use relative configuration paths. New runtime code must not discover source
checkouts or environments outside this repository. External installations and
resources require explicit configuration and actionable errors when absent.

Keep generated outputs, raw datasets, model files and caches out of commits.
Regenerate the matcher and dataset inventories when changing their registries.
