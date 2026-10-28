"""bdi-kit's contrastive-learning column matcher (`ct_learning`, bdi-kit <= 0.6), without importing bdi-kit.

bdi-kit 0.6.0 (bdikit/models/contrastive_learning/cl_api.py) embeds each column separately: the column is cut to 15 rows
(distinct rows, then a seeded sample; random_state 1), serialised as "<s>name</s>v1</s>v2..." (distinct values in order, the
"head" sampling, at most 256 values) and truncated to 127 tokens, encoded by RoBERTa-base plus a 768 x 768 linear projector
trained with SimCLR (checkpoint bdi-cl-v0.2), and represented by the projected vector at the <s> position. Scores are the
cosine of source and target column vectors.

Differences from bdi-kit: the checkpoint is read with torch.load(weights_only=True) (bdi-kit uses weights_only=False, which
unpickles arbitrary objects), RoBERTa is loaded from an explicit local path, and no embedding cache is written. Resources:
options["checkpoint"] (or SCHEMATCH_BDIKIT_CL_CHECKPOINT) -> bdi-cl-v0.2.pt; options["roberta"] (or SCHEMATCH_ROBERTA) -> a
local roberta-base directory. Neither is downloaded here.

Leakage note: bdi-cl-v0.2 was trained by the bdi-kit authors for the GDC harmonisation use case; treat GDC benchmarks as seen.
"""
from __future__ import annotations

import os
from functools import lru_cache

import numpy as np
import pandas as pd

MAX_LEN, ROWS = 128, 15


@lru_cache(maxsize=2)
def load(checkpoint: str, roberta: str):
    import torch
    from transformers import AutoModel, AutoTokenizer

    class Encoder(torch.nn.Module):
        def __init__(self):
            super().__init__()
            self.bert = AutoModel.from_pretrained(roberta)
            self.projector = torch.nn.Linear(768, 768)

    model = Encoder()
    import argparse
    # the checkpoint pickles tensors, an OrderedDict and the training arguments (argparse.Namespace); nothing else is allowed
    with torch.serialization.safe_globals([argparse.Namespace]):
        state = torch.load(checkpoint, map_location="cpu", weights_only=True)["model"]
    used = {k: v for k, v in state.items() if k.startswith(("bert.", "projector."))}
    missing, unexpected = model.load_state_dict(used, strict=False)
    # position_ids is a buffer some transformers versions keep and others drop
    missing = [k for k in missing if not k.endswith("position_ids")]
    if missing or unexpected:
        raise ValueError(f"bdi-cl checkpoint does not fit RoBERTa-base: missing {missing[:5]}, unexpected {unexpected[:5]}")
    model.eval()
    return model, AutoTokenizer.from_pretrained(roberta)


def sample_rows(column: pd.DataFrame) -> pd.DataFrame:
    """bdi-kit's _sample_to_15_rows, unchanged."""
    if len(column) > ROWS:
        unique_rows = column.drop_duplicates()
        if len(unique_rows) <= ROWS:
            extra = column[~column.index.isin(unique_rows.index)].sample(n=ROWS - len(unique_rows), replace=True, random_state=1)
            column = pd.concat([unique_rows, extra])
        else:
            column = unique_rows.sample(n=ROWS, random_state=1)
    return column


def token_ids(name: str, values: pd.Series, tokenizer) -> list[int]:
    max_tokens, budget = MAX_LEN * 2, max(1, MAX_LEN - 1)
    tokens = []
    for v in (str(x) for x in values):
        if v not in tokens:
            tokens.append(v)
            if len(tokens) >= max_tokens:
                break
    text = tokenizer.cls_token + str(name) + tokenizer.sep_token + tokenizer.sep_token.join(tokens[:max_tokens])
    return tokenizer.encode(text=text, max_length=budget, add_special_tokens=False, truncation=True)


def embeddings(table: pd.DataFrame, model, tokenizer, batch_size: int = 64) -> np.ndarray:
    import torch
    ids = [token_ids(c, sample_rows(pd.DataFrame(table[c]))[c], tokenizer) for c in table.columns]
    out = []
    for start in range(0, len(ids), batch_size):
        batch = ids[start:start + batch_size]
        width = max(len(x) for x in batch)
        x = torch.LongTensor([b + [tokenizer.pad_token_id] * (width - len(b)) for b in batch])
        with torch.no_grad():
            z = model.projector(model.bert(x)[0])
        for row, seq in zip(z, x):
            cls = (seq == tokenizer.cls_token_id).nonzero().flatten()
            out.append(row[int(cls[-1])].numpy())
    return np.asarray(out)


def scores(source: pd.DataFrame, target: pd.DataFrame, options: dict) -> np.ndarray:
    checkpoint = options.get("checkpoint") or os.environ.get("SCHEMATCH_BDIKIT_CL_CHECKPOINT")
    roberta = options.get("roberta") or os.environ.get("SCHEMATCH_ROBERTA")
    if not checkpoint or not roberta:
        raise FileNotFoundError("bdikit_cl needs options.checkpoint / SCHEMATCH_BDIKIT_CL_CHECKPOINT (bdi-cl-v0.2.pt) and "
                                "options.roberta / SCHEMATCH_ROBERTA (a local roberta-base directory)")
    model, tokenizer = load(str(checkpoint), str(roberta))
    A, B = embeddings(source, model, tokenizer), embeddings(target, model, tokenizer)
    A = A / (np.linalg.norm(A, axis=1, keepdims=True) + 1e-12)
    B = B / (np.linalg.norm(B, axis=1, keepdims=True) + 1e-12)
    return A @ B.T
