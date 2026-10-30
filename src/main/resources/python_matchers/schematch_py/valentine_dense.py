"""Bundled matcher implementation; see SOURCES.json for provenance."""
from __future__ import annotations
import numpy as np

COMA_NATIVE = dict(max_n=0, delta=0.15, threshold=0.0)


CUPID_NATIVE_TH_ACCEPT = 0.7


def run_coma(source_table, target_table, s_cols, t_cols, options, verify: bool):
    """COMA at delta=0 gives every positively-scored pair, i.e. the matrix.

    `select_both_multiple` is a pure function of that matrix, so re-applying it
    at COMA's published delta reproduces `Coma(delta=0.15).get_matches` exactly
    -- which is what --verify checks, at the cost of a second matcher run.
    """
    from valentine.algorithms import Coma
    from valentine.algorithms.coma.selection import select_both_multiple

    dense = Coma(max_n=0, delta=0.0, threshold=0.0, **options).get_matches(
        source_table, target_table
    )
    sim = {(p.source_column, p.target_column): v for p, v in dense.items()}

    s_index = {c: i for i, c in enumerate(s_cols)}
    t_index = {c: j for j, c in enumerate(t_cols)}
    S = np.zeros((len(s_cols), len(t_cols)))
    for (a, b), v in sim.items():
        S[s_index[a], t_index[b]] = float(v)

    selected = select_both_multiple(sim, list(s_cols), list(t_cols), **COMA_NATIVE)
    P = np.zeros(S.shape, dtype=bool)
    for a, b in selected:
        P[s_index[a], t_index[b]] = True

    if verify:
        native = Coma(**COMA_NATIVE, **options).get_matches(source_table, target_table)
        want = {(p.source_column, p.target_column) for p in native}
        if want != set(selected):
            raise AssertionError(
                f"COMA native arm does not reproduce get_matches: "
                f"{len(want)} vs {len(selected)} pairs"
            )
    return S, P


def run_cupid(source_table, target_table, s_cols, t_cols, options, verify: bool):
    """Cupid's leaf-pair wsim matrix, read before `mapping_generation_leaves`.

    Cupid has no public accessor for it, and relaxing th_accept is not an
    option -- `tree_match` passes th_accept into `compute_ssim`, so it changes
    the propagation and not just the cut. So the matcher is driven through its
    own private helpers at its published parameters, and the wsim map that
    `get_matches` would threshold is read out directly.
    """
    from valentine.algorithms import Cupid
    from valentine.algorithms.cupid.tree_match import recompute_wsim, tree_match

    cu = Cupid(**options)
    cu._Cupid__add_data("DB__" + source_table.name, source_table)
    cu._Cupid__add_data("DB__" + target_table.name, target_table)
    s_tree = cu._Cupid__get_schema_by_name("DB__" + source_table.name)
    t_tree = cu._Cupid__get_schema_by_name("DB__" + target_table.name)
    sims = tree_match(
        s_tree, t_tree, cu._Cupid__categories,
        cu._Cupid__leaf_w_struct, cu._Cupid__w_struct, cu._Cupid__th_accept,
        cu._Cupid__th_high, cu._Cupid__th_low, cu._Cupid__c_inc,
        cu._Cupid__c_dec, cu._Cupid__th_ns, cu._Cupid__process_num,
    )
    sims = recompute_wsim(s_tree, t_tree, sims)

    leaves_s, leaves_t = set(s_tree.get_leaf_names()), set(t_tree.get_leaf_names())
    s_index = {c: i for i, c in enumerate(s_cols)}
    t_index = {c: j for j, c in enumerate(t_cols)}
    S = np.zeros((len(s_cols), len(t_cols)))
    pairs = {}
    for (a, b), v in sims.items():
        if a in leaves_s and b in leaves_t:
            # long_name is (table, table_guid, column, column_guid)
            i, j = s_index.get(a[2]), t_index.get(b[2])
            if i is not None and j is not None:
                S[i, j] = float(v["wsim"])
                pairs[(a[2], b[2])] = float(v["wsim"])

    P = S >= CUPID_NATIVE_TH_ACCEPT

    if verify:
        native = Cupid(**options).get_matches(source_table, target_table)
        want = {(p.source_column, p.target_column) for p in native}
        got = {k for k, v in pairs.items() if v >= CUPID_NATIVE_TH_ACCEPT}
        if want != got:
            raise AssertionError(
                f"Cupid native arm does not reproduce get_matches: "
                f"{len(want)} vs {len(got)} pairs"
            )
    return S, P
