from __future__ import annotations

import re
from collections import Counter

from .normalize import clean_text, lexical_key, sentence_contains, tokens

_SUSPICIOUS = re.compile(r"[\x00-\x08\x0b\x0c\x0e-\x1f]")
_ALIGNMENT_STOPWORDS = {
    "a", "an", "the", "and", "or", "but", "as", "at", "by", "for", "from",
    "in", "into", "of", "on", "onto", "over", "through", "to", "with", "without",
    "about", "after", "before", "between", "during", "per", "than", "then", "there",
    "this", "that", "these", "those", "it", "its", "they", "them", "their", "which",
    "who", "whom", "whose", "what", "when", "where", "why", "how", "be", "is", "are",
    "was", "were", "been", "being", "has", "have", "had", "do", "does", "did", "can",
    "could", "may", "might", "must", "shall", "should", "will", "would", "one", "some",
    "any", "such", "other", "another", "certain", "especially", "often", "usually",
}


def _alignment_lemma(token: str) -> str:
    """A deliberately small, deterministic normalizer for definition tokens."""
    if len(token) > 5 and token.endswith("ies"):
        return token[:-3] + "y"
    if len(token) > 5 and token.endswith("ing"):
        stem = token[:-3]
        return stem[:-1] if len(stem) > 2 and stem[-1] == stem[-2] else stem
    if len(token) > 4 and token.endswith("ed"):
        stem = token[:-2]
        return stem[:-1] if len(stem) > 2 and stem[-1] == stem[-2] else stem
    if len(token) > 4 and token.endswith("es"):
        return token[:-2]
    if len(token) > 3 and token.endswith("s") and not token.endswith(("ss", "us", "is")):
        return token[:-1]
    return token


def _definition_content(text: str) -> set[str]:
    return {
        lemma for token in tokens(text)
        if token not in _ALIGNMENT_STOPWORDS
        and (lemma := _alignment_lemma(token)) not in _ALIGNMENT_STOPWORDS
        and len(lemma) > 1
    }


def definition_quality(text: str, source_rank: int = 0) -> float:
    text = clean_text(text)
    if not text or _SUSPICIOUS.search(text):
        return 0.0
    words = tokens(text)
    if not words:
        return 0.0
    score = 0.80 if source_rank == 0 else 0.60
    if 4 <= len(words) <= 28:
        score += 0.10
    elif len(words) > 50:
        score -= 0.15
    if text[0].islower():
        score += 0.02
    if text.endswith((".", "!", "?")):
        score -= 0.02
    return round(max(0.0, min(1.0, score)), 3)


def example_quality(text: str, lemma: str, explicit_forms: set[str] | None = None) -> tuple[str, float] | None:
    text = clean_text(text)
    if not text or len(text) > 280 or _SUSPICIOUS.search(text):
        return None
    if not sentence_contains(lemma, text, explicit_forms):
        return None
    surface = set(tokens(text))
    status = "verified_word" if lexical_key(lemma) in surface else "verified_inflection"
    score = 0.75
    word_count = len(surface)
    if 4 <= word_count <= 20:
        score += 0.15
    if text[-1:] in ".!?":
        score += 0.05
    if len(text) < 18:
        score -= 0.10
    return status, round(max(0.0, min(1.0, score)), 3)


def align_definition(source_definition: str, candidates: list[str]) -> tuple[int | None, float, str]:
    """Same-lemma/POS candidate alignment from unique content-word evidence.

    This is a lexical similarity screen, not a semantic model. Negation stays
    in the token set. Weak or tied matches remain independent source senses.
    """
    source_key = " ".join(tokens(source_definition))
    source_tokens = _definition_content(source_definition)
    if not source_tokens:
        return None, 0.0, "no_content_tokens"
    scored = []
    for index, candidate in enumerate(candidates):
        if source_key and source_key == " ".join(tokens(candidate)):
            scored.append((1.0, 999, index))
            continue
        target = _definition_content(candidate)
        shared = len(source_tokens & target)
        similarity = 2 * shared / (len(source_tokens) + len(target)) if source_tokens and target else 0.0
        scored.append((similarity, shared, index))
    if not scored:
        return None, 0.0, "no_candidates"
    scored.sort(key=lambda row: (-row[0], -row[1], row[2]))
    best, shared, index = scored[0]
    second = scored[1][0] if len(scored) > 1 else 0.0
    if best < 0.60:
        return None, round(best, 3), "below_content_f1_threshold"
    if shared < 2:
        return None, round(best, 3), "insufficient_shared_content_tokens"
    if best - second < 0.12:
        return None, round(best, 3), "ambiguous_tie"
    return index, round(best, 3), "definition_content_f1"


def valid_arpabet(value: str) -> bool:
    phones = value.split()
    return bool(phones) and all(re.fullmatch(r"[A-Z]+[0-2]?", p) for p in phones)


def duplicate_definition_pairs(texts: list[str]) -> list[tuple[str, str]]:
    normalized = [(clean_text(item), lexical_key(item)) for item in texts]
    counts = Counter(key for _, key in normalized)
    return [(text, key) for text, key in normalized if key and counts[key] > 1]
