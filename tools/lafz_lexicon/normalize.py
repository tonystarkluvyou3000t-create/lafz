from __future__ import annotations

import hashlib
import re
import unicodedata

_SPACE = re.compile(r"\s+")
_WORD = re.compile(r"[^\W_]+(?:['’][^\W_]+)?", re.UNICODE)


def clean_text(value: str | None) -> str:
    if not value:
        return ""
    return _SPACE.sub(" ", unicodedata.normalize("NFC", value)).strip()


def lexical_key(value: str) -> str:
    return unicodedata.normalize("NFKC", clean_text(value)).casefold()


def stable_id(prefix: str, *parts: str) -> str:
    payload = "\x1f".join(lexical_key(part) for part in parts).encode("utf-8")
    return f"{prefix}_{hashlib.sha256(payload).hexdigest()[:24]}"


def normalize_pos(value: str | None) -> str | None:
    if not value:
        return None
    aliases = {
        "n": "noun", "noun": "noun", "v": "verb", "verb": "verb",
        "a": "adjective", "s": "adjective", "adj": "adjective", "adjective": "adjective",
        "r": "adverb", "adv": "adverb", "adverb": "adverb",
        "x": "other", "other": "other",
    }
    return aliases.get(value.strip().casefold())


def valid_lemma(value: str) -> bool:
    value = clean_text(value)
    if not value or len(value) > 64 or len(value.split()) > 6:
        return False
    if any(ch.isdigit() for ch in value):
        return False
    # Unicode letters are accepted; internal apostrophes and hyphens are lexical.
    return all(ch.isalpha() or ch in " -'’" for ch in value) and any(ch.isalpha() for ch in value)


def tokens(value: str) -> list[str]:
    return [lexical_key(match.group(0)).replace("’", "'") for match in _WORD.finditer(value)]


def inflections(lemma: str, explicit: set[str] | None = None) -> set[str]:
    """Conservative English surface forms used only for example word-presence checks."""
    base = lexical_key(lemma)
    forms = {base}
    if explicit:
        forms.update(lexical_key(form) for form in explicit if form)
    # Multiword and irregular forms should be supplied by a source; avoid guessing.
    if " " in base or "-" in base or not base.isalpha():
        return forms
    forms.add(base + "s")
    forms.add(base + ("es" if base.endswith(("s", "x", "z", "ch", "sh")) else "s"))
    if base.endswith("y") and len(base) > 1 and base[-2] not in "aeiou":
        forms.add(base[:-1] + "ies")
    if base.endswith("e"):
        forms.update({base + "d", base[:-1] + "ing"})
    else:
        forms.update({base + "ed", base + "ing"})
    if len(base) > 2 and base[-1] not in "aeiou" and base[-2] in "aeiou" and base[-3] not in "aeiou":
        forms.update({base + base[-1] + "ed", base + base[-1] + "ing"})
    return forms


def sentence_contains(lemma: str, sentence: str, explicit: set[str] | None = None) -> bool:
    expected = [tuple(tokens(form)) for form in inflections(lemma, explicit)]
    return token_sequences_contain(tokens(sentence), expected)


def token_sequences_contain(surface_tokens: list[str] | tuple[str, ...], form_sequences) -> bool:
    """Match single words or contiguous multiword forms as complete tokens."""
    surface = tuple(surface_tokens)
    if not surface:
        return False
    for form_tokens in form_sequences:
        form_tokens = tuple(form_tokens)
        if not form_tokens:
            continue
        if len(form_tokens) == 1:
            if form_tokens[0] in surface:
                return True
            continue
        width = len(form_tokens)
        if any(surface[start:start + width] == form_tokens for start in range(len(surface) - width + 1)):
            return True
    return False
