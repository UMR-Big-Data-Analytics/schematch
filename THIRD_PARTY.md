# Provenance and licensing review

Schematch originated with Alexander Vielhauer and the Big Data Analytics group
at Marburg University. This release builds on the existing Schematch fork and
retains its contributors and history:

- [Original Schematch](https://github.com/avielhauer/schematch)
- [Development upstream](https://github.com/juliantroesser/schematch)

No root license file was present in the development checkout at preparation.
The maintainers must establish the applicable project license with the rights
holders before describing this release as generally reusable open-source
software. This document records unresolved work rather than granting rights.

## Bundled profiler binaries

The following files were already tracked by the upstream-based checkout:

| File under `src/lib/` | Component |
|---|---|
| `HyFD-1.2-SNAPSHOT.jar` | Functional-dependency discovery |
| `HyUCC-1.2-SNAPSHOT.jar` | Unique-column-combination discovery |
| `BINDER-1.2-SNAPSHOT.jar` | Inclusion-dependency discovery |
| `pyro-distro-1.0-SNAPSHOT-distro.jar` | Approximate dependency discovery |
| `sawfish-1.1-SNAPSHOT.jar` | Inclusion-dependency discovery |
| `FAIDA-1.2-SNAPSHOT.jar` | Approximate inclusion-dependency discovery |

The build also uses legacy Metanome and metadata-store snapshots. A clean-cache
build could not retrieve these from the original snapshot repository. Their
exact resolved JARs and parent POMs are therefore archived under `src/lib/maven/`.
[SOURCES.json](src/lib/maven/SOURCES.json) records every SHA-256 checksum and size;
the original POMs and their embedded licensing information are preserved.
This is a selected build dependency set, not a developer's Maven cache.

Confirm redistribution permissions and required notices for both sets of
binaries, including their bundled dependencies. Preserving an old JAR does not
establish that every embedded component has the same license as Schematch.

## Bundled source and optional implementations

[Python provenance](src/main/resources/python_matchers/schematch_py/SOURCES.json)
and [dataset-converter provenance](tools/dataset_support/SOURCES.json) record the
origins and hashes of packaged computations. Historical source identifiers are
provenance, not runtime imports or required source locations. Preserve the
original source notices and establish distribution terms for copied material.

The bundled OmniMatch release port and other reconstructed systems require
particular attention to their source provenance and notices. Adapter classes do
not include or license third-party models, vector files, training data, or external
services. Installed dependencies retain their own terms.

## Remaining release decisions

1. Establish the upstream project's license and permission for the additions.
2. Record license/notice requirements for copied implementations and every
   distributed binary, or replace components that cannot be redistributed.
3. Add the agreed project license and required third-party notices.
4. Keep restricted benchmark data and model files outside the Git repository.
