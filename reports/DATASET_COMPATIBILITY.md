# Dataset compatibility audit

Completed 2026-10-14 using the normal Java CSV and ground-truth parsers. All rows were parsed; no matcher was run and no dataset was rewritten.

**111 indexed corpora, 384 dataset directories, 9931 scenarios: 9927 evaluable, 4 inference-only, 0 parsing/ground-truth errors.**

Index-count mismatches: 0. Counts include transformations, corruption variants and self-matching scenarios; they are not independent statistical units.

The four inference-only scenarios are in Efes-bib and lack ground truth. Raw Pubs/ORIG_/ENC_ layouts are outside the conventional-layout index and require conversion/supplied truth.

Nine roots initially blocked by filesystem permissions were retried with the required access; all 857 scenarios in that retry passed. The completed counts below include them.

Validation covers UTF-8 CSV syntax, every row width, headers, table discovery, matrix dimensions, label alignment, binary truth and full/pair-block consistency. It does not certify annotation semantics or profile discovery/readiness.

Full per-scenario reports are under `target/dataset-audit-report.json` and `target/dataset-audit-linked-report.json`. The compact [JSON summary](dataset_compatibility_summary.json) is retained with this report. See [usage and limitations](../EVALUATION_AND_DATASETS.md).

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
