# Historical dataset compatibility and variants

This reference records parser checks on a historical evaluation installation.
Identifiers in the table name archived collections and transformations, not
directories required in a fresh checkout or bundled datasets. For acquisition
commands use [dataset preparation](../datasets.md). Names such as `x87` identify
old experiment runs; `md5` and `opaque` denote recoded data, and `self` denotes
self-matching controls.

## Parser validation record

Completed 2026-10-14 using the normal Java CSV and ground-truth parsers. All rows were parsed; no matcher was run and no dataset was rewritten.

**111 indexed corpora, 384 dataset directories, 9931 scenarios: 9927 evaluable, 4 inference-only, 0 parsing/ground-truth errors.**

Index-count mismatches: 0. Counts include transformations, corruption variants and self-matching scenarios; they are not independent statistical units.

The four inference-only scenarios are in Efes-bib and lack ground truth. Raw Pubs/ORIG_/ENC_ layouts are outside the conventional-layout index and require conversion/supplied truth.

Nine roots initially blocked by filesystem permissions were retried with the required access; all 857 scenarios in that retry passed. The completed counts below include them.

Validation covers UTF-8 CSV syntax, every row width, headers, table discovery, matrix dimensions, label alignment, binary truth and full/pair-block consistency. It does not certify annotation semantics or profile discovery/readiness.

Full per-scenario reports were written to the original installation's ignored `target/` directory and are not bundled. The compact [JSON summary](../../reports/dataset_compatibility_summary.json) is retained with this report. See [usage and limitations](../evaluation.md).

| Corpus | Dataset directories | Scenarios | Evaluable | Inference only | Errors |
|---|---:|---:|---:|---:|---:|
| `benchmark-full` | 14 | 390 | 390 | 0 | 0 |
| `benchmark-full-md5` | 14 | 390 | 390 | 0 | 0 |
| `benchmark-smoke` | 5 | 5 | 5 | 0 | 0 |
| `controlled-imported` | 8 | 16 | 16 | 0 | 0 |
| `controlled-imported-v2` | 8 | 16 | 16 | 0 | 0 |
| `controlled-imported-v3` | 8 | 16 | 16 | 0 | 0 |
| `final-controlled` | 16 | 96 | 96 | 0 | 0 |
| `gdcsm-imported` | 1 | 10 | 10 | 0 | 0 |
| `gdcsm_md5` | 1 | 10 | 10 | 0 | 0 |
| `goby-imported` | 1 | 200 | 200 | 0 | 0 |
| `goby_md5` | 1 | 200 | 200 | 0 | 0 |
| `hdxsm-imported` | 1 | 204 | 204 | 0 | 0 |
| `hdxsm-usable` | 1 | 199 | 199 | 0 | 0 |
| `hdxsm_md5` | 1 | 199 | 199 | 0 | 0 |
| `madi-imported` | 1 | 18 | 18 | 0 | 0 |
| `madi_md5` | 1 | 18 | 18 | 0 | 0 |
| `scale-imported` | 93 | 93 | 93 | 0 | 0 |
| `t2d-imported` | 1 | 178 | 178 | 0 | 0 |
| `t2d-sm-wh` | 1 | 178 | 178 | 0 | 0 |
| `t2d-usable` | 1 | 175 | 175 | 0 | 0 |
| `x87-hdxsm-perm` | 1 | 40 | 40 | 0 | 0 |
| `x87-hdxsm-permfd` | 1 | 40 | 40 | 0 | 0 |
| `x87-madi-perm` | 1 | 18 | 18 | 0 | 0 |
| `x87-madi-permfd` | 1 | 18 | 18 | 0 | 0 |
| `schematch/data/ChEMBL-Unionable` | 1 | 36 | 36 | 0 | 0 |
| `schematch/data/ChEMBL-Unionable_corrupt000` | 1 | 12 | 12 | 0 | 0 |
| `schematch/data/ChEMBL-Unionable_corrupt001` | 1 | 12 | 12 | 0 | 0 |
| `schematch/data/ChEMBL-Unionable_corrupt005` | 1 | 12 | 12 | 0 | 0 |
| `schematch/data/ChEMBL-Unionable_corrupt010` | 1 | 12 | 12 | 0 | 0 |
| `schematch/data/ChEMBL-Unionable_corrupt020` | 1 | 12 | 12 | 0 | 0 |
| `schematch/data/ChEMBL-Unionable_corrupt040` | 1 | 12 | 12 | 0 | 0 |
| `schematch/data/ChEMBL-Unionable_corruptfull000` | 1 | 36 | 36 | 0 | 0 |
| `schematch/data/ChEMBL-Unionable_corruptfull010` | 1 | 36 | 36 | 0 | 0 |
| `schematch/data/ChEMBL-Unionable_corruptfull040` | 1 | 36 | 36 | 0 | 0 |
| `schematch/data/ChEMBL-Unionable_md5_encrypted` | 1 | 36 | 36 | 0 | 0 |
| `schematch/data/ChEMBL-Unionable_opaque` | 1 | 12 | 12 | 0 | 0 |
| `schematch/data/ChEMBL-Unionable_opaque50` | 1 | 12 | 12 | 0 | 0 |
| `schematch/data/ChEMBL-Unionable_partial000` | 1 | 12 | 12 | 0 | 0 |
| `schematch/data/ChEMBL-Unionable_partial020` | 1 | 12 | 12 | 0 | 0 |
| `schematch/data/ChEMBL-Unionable_partial030` | 1 | 12 | 12 | 0 | 0 |
| `schematch/data/ChEMBL-Unionable_partial050` | 1 | 12 | 12 | 0 | 0 |
| `schematch/data/ChEMBL-Unionable_revalue000` | 1 | 12 | 12 | 0 | 0 |
| `schematch/data/ChEMBL-Unionable_revalue010` | 1 | 12 | 12 | 0 | 0 |
| `schematch/data/ChEMBL-Unionable_revalue025` | 1 | 12 | 12 | 0 | 0 |
| `schematch/data/ChEMBL-Unionable_revalue050` | 1 | 12 | 12 | 0 | 0 |
| `schematch/data/ChEMBL-Unionable_revalue075` | 1 | 12 | 12 | 0 | 0 |
| `schematch/data/ChEMBL-Unionable_revalue100` | 1 | 12 | 12 | 0 | 0 |
| `schematch/data/DeNorm` | 1 | 6 | 6 | 0 | 0 |
| `schematch/data/DeNorm_corrupt000` | 1 | 6 | 6 | 0 | 0 |
| `schematch/data/DeNorm_corrupt001` | 1 | 6 | 6 | 0 | 0 |
| `schematch/data/DeNorm_corrupt005` | 1 | 6 | 6 | 0 | 0 |
| `schematch/data/DeNorm_corrupt010` | 1 | 6 | 6 | 0 | 0 |
| `schematch/data/DeNorm_corrupt020` | 1 | 6 | 6 | 0 | 0 |
| `schematch/data/DeNorm_corrupt040` | 1 | 6 | 6 | 0 | 0 |
| `schematch/data/DeNorm_md5_encrypted` | 1 | 6 | 6 | 0 | 0 |
| `schematch/data/DeNorm_opaque` | 1 | 6 | 6 | 0 | 0 |
| `schematch/data/DeNorm_opaque50` | 1 | 6 | 6 | 0 | 0 |
| `schematch/data/DeNorm_revalue000` | 1 | 6 | 6 | 0 | 0 |
| `schematch/data/DeNorm_revalue010` | 1 | 6 | 6 | 0 | 0 |
| `schematch/data/DeNorm_revalue025` | 1 | 6 | 6 | 0 | 0 |
| `schematch/data/DeNorm_revalue050` | 1 | 6 | 6 | 0 | 0 |
| `schematch/data/DeNorm_revalue075` | 1 | 6 | 6 | 0 | 0 |
| `schematch/data/DeNorm_revalue100` | 1 | 6 | 6 | 0 | 0 |
| `schematch/data/Efes-bib` | 1 | 4 | 0 | 4 | 0 |
| `schematch/data/Magellan-Unionable` | 1 | 7 | 7 | 0 | 0 |
| `schematch/data/Magellan-Unionable_md5_encrypted` | 1 | 7 | 7 | 0 | 0 |
| `schematch/data/Magellan-Unionable_opaque` | 1 | 7 | 7 | 0 | 0 |
| `schematch/data/Magellan-Unionable_opaque50` | 1 | 7 | 7 | 0 | 0 |
| `schematch/data/Open_Data-Unionable` | 1 | 36 | 36 | 0 | 0 |
| `schematch/data/TPC-DI-Unionable` | 1 | 36 | 36 | 0 | 0 |
| `schematch/data/TPC-DI-Unionable_md5_encrypted` | 1 | 36 | 36 | 0 | 0 |
| `schematch/data/TPC-DI-Unionable_partial000` | 1 | 12 | 12 | 0 | 0 |
| `schematch/data/TPC-DI-Unionable_partial020` | 1 | 12 | 12 | 0 | 0 |
| `schematch/data/TPC-DI-Unionable_partial030` | 1 | 12 | 12 | 0 | 0 |
| `schematch/data/TPC-DI-Unionable_partial050` | 1 | 12 | 12 | 0 | 0 |
| `schematch/data/Valentine-Wikidata-Unionable` | 1 | 1 | 1 | 0 | 0 |
| `schematch/data/Valentine-Wikidata-Unionable_md5_encrypted` | 1 | 1 | 1 | 0 | 0 |
| `schematch/data/_ref_clean_exact` | 1 | 6 | 6 | 0 | 0 |
| `sf-matcher-study/data/final_indep` | 9 | 9 | 9 | 0 | 0 |
| `sf-matcher-study/data/final_indep_md5` | 9 | 9 | 9 | 0 | 0 |
| `sf-matcher-study/data/final_indep_on` | 9 | 9 | 9 | 0 | 0 |
| `sf-matcher-study/data/indep2_md5` | 2 | 54 | 54 | 0 | 0 |
| `sf-matcher-study/data/indep2_on` | 2 | 54 | 54 | 0 | 0 |
| `sf-matcher-study/data/indep2_plain` | 2 | 54 | 54 | 0 | 0 |
| `sf-matcher-study/data/indep_plain` | 3 | 81 | 81 | 0 | 0 |
| `sf-matcher-study/data/indep_prof` | 3 | 81 | 81 | 0 | 0 |
| `sf-matcher-study/data/indep_prof_md5` | 3 | 81 | 81 | 0 | 0 |
| `sf-matcher-study/data/indep_prof_on` | 3 | 81 | 81 | 0 | 0 |
| `sf-matcher-study/data/knobs` | 26 | 1992 | 1992 | 0 | 0 |
| `sf-matcher-study/data/mimic_md5` | 1 | 1 | 1 | 0 | 0 |
| `sf-matcher-study/data/mimic_omop` | 1 | 1 | 1 | 0 | 0 |
| `sf-matcher-study/data/mimic_on` | 1 | 1 | 1 | 0 | 0 |
| `sf-matcher-study/data/self_ctl` | 8 | 32 | 32 | 0 | 0 |
| `sf-matcher-study/data/self_final_controlled` | 16 | 192 | 192 | 0 | 0 |
| `sf-matcher-study/data/self_gdcsm` | 1 | 20 | 20 | 0 | 0 |
| `sf-matcher-study/data/self_gdcsm_md5` | 1 | 20 | 20 | 0 | 0 |
| `sf-matcher-study/data/self_goby` | 1 | 400 | 400 | 0 | 0 |
| `sf-matcher-study/data/self_goby_md5` | 1 | 400 | 400 | 0 | 0 |
| `sf-matcher-study/data/self_hdxsm` | 1 | 398 | 398 | 0 | 0 |
| `sf-matcher-study/data/self_hdxsm_md5` | 1 | 398 | 398 | 0 | 0 |
| `sf-matcher-study/data/self_madi` | 1 | 36 | 36 | 0 | 0 |
| `sf-matcher-study/data/self_madi_md5` | 1 | 36 | 36 | 0 | 0 |
| `sf-matcher-study/data/self_md5` | 13 | 764 | 764 | 0 | 0 |
| `sf-matcher-study/data/self_plain` | 13 | 764 | 764 | 0 | 0 |
| `sf-matcher-study/data/self_t2d` | 1 | 350 | 350 | 0 | 0 |
| `sf-matcher-study/data/synthea_md5` | 2 | 39 | 39 | 0 | 0 |
| `sf-matcher-study/data/synthea_on` | 2 | 39 | 39 | 0 | 0 |
| `sf-matcher-study/data/synthea_plain` | 2 | 39 | 39 | 0 | 0 |
| `sf-matcher-study/data/test_md5` | 3 | 27 | 27 | 0 | 0 |
| `sf-matcher-study/data/test_on` | 3 | 27 | 27 | 0 | 0 |
| `sf-matcher-study/data/test_plain` | 3 | 27 | 27 | 0 | 0 |

## Archived dataset variants and reproduction requirements

These are generated artifacts, not additional public downloads. The table above preserves the historical inventory identifiers.
These historical variants require the corresponding frozen input snapshots,
selection manifests and annotations. The [dataset preparation guide](../datasets.md)
covers the available release adapters; they do not infer unpublished annotations or
load construction code from another repository. Acquisition-only sources are
marked `manual preparation` by the `list` command.

| Indexed corpus/variant | Base inputs and original recipe |
|---|---|
| `benchmark-full`, `benchmark-smoke` | Valentine/GDC import; portable `valentine`/`gdc` plus frozen selected cases/corrections. |
| `controlled-imported*` | MusicBrainz/Lahman joined views; `tools/dataset_support/controlled.py` and the version's design manifest. Portable `controlled` reproduces v2. |
| `final-controlled` | AdventureWorks/IMDb/Sakila/SCOPe, 96 tasks; the frozen joined-view design, source hashes, 5,000-row samples, three seeds. |
| `indep_plain`, `indep_prof*` | TPC-H/AdventureWorks/Chinook; normalized/denormalized schema provenance and frozen independent samples; 81 tasks. Profiles are a separate step. |
| `indep2_*` | Lahman/MusicBrainz; normalized/denormalized schema provenance; 54 tasks across seeds/overlap/schema views. |
| `test_*` | Sakila/IMDb/SCOPe; frozen schema-view and sampling manifests; 27 disjoint-instance multi-table tasks. |
| `final_indep*` | Amalgam/music instances plus the curated correspondence CSVs; use the curated correspondence annotations and the declared MusicBrainz table restriction. Nine tasks. |
| `synthea_*`, `mimic_*` | Portable `synthea`/`mimic` for readable bases; original hashes, sampling and conversion reports define the exact variants. |
| `t2d-usable`, `hdxsm-usable` | Frozen subsets of 175/199 pairs; importing all 178/204 is not the same case selection. Keep exclusions and analysis manifests as code/data-free JSON. |
| `*_md5`, `*_md5_encrypted`, `*_on` | Original readable inputs plus their frozen transformation manifests, salts/seeds, null/strip policies and label maps. `_on` hashes names; `_md5` also recodes values. |
| `*_opaque*`, `*_partial*`, `*_corrupt*`, `*_revalue*` | Original bases plus the corresponding frozen perturbation recipe/settings. Preserve the manifest identifying affected cells/columns and truth. |
| `knobs` | Frozen perturbation settings; derived perturbation grid, not a separately acquired dataset. |
| `self_*` | Construct two within-side self-matches per original case, identity/provenance truth. |
| `x87-*-perm*` | Apply recorded column permutations, truth/profile remapping. |
| `scale-imported` | Portable `scale`; 31 size/density/row settings × three repetitions = 93 cases. `--smoke` creates one case only. |
| `_ref_clean_exact`, `DeNorm`, thesis examples | Preserve their generation recipe or small original fixture if redistribution permits; their names alone are insufficient provenance. |

The historical opaque multi-table builder preserves readable table/column order.
That order can affect tied assignment results; use the experiment's permutation/tie
protocol for replication. Changing order or null policies creates a new dataset
version. These recipes and curated annotations must be published alongside the
paper for a complete exact rerun; downloading current raw databases cannot
recover them. This guide makes that remaining release work visible.

