"""Lafz browsing topics: source domains first, with explicit broad fallbacks.

Topics are navigation aids, not assertions of sense alignment. An unaligned
learner definition keeps its own sense ID and only receives a topic label.
"""
from __future__ import annotations

import sqlite3
from collections import Counter, defaultdict

from .normalize import lexical_key, tokens

TOPIC_REVISION = "4"
TOPIC_POLICY = (
    "Topics follow the displayed meaning, using OEWN subject domains, scoped "
    "definition rules and lexnames. Unaligned learner senses use definition "
    "evidence, unambiguous same-POS topics, then broad part-of-speech fallbacks. "
    "No sense alignment is inferred. Confidence and method remain recorded."
)


def classify_lexname(word_key, lexname, config):
    excluded = {lexical_key(value) for value in config.get("topic_exclusions", [])}
    if lexical_key(word_key) in excluded or not lexname:
        return None
    value = config.get("topic_lexname_map", {}).get(lexname)
    return (value[0], float(value[1])) if value else None


def classify_definition_topic(lexname, definition, config):
    if not lexname or not definition:
        return None
    terms = set(tokens(definition))
    for rule in config.get("topic_definition_rules", {}).get(lexname, []):
        if terms.intersection({lexical_key(term) for term in rule.get("any_terms", [])}):
            return str(rule["category"]), float(rule["confidence"])
    return None


def add_topics(db: sqlite3.Connection, config: dict) -> dict:
    # Keep the selected definition order identical to LafzRepository.buildWordFrom.
    senses = db.execute(
        "SELECT s.sense_id,s.word_id,s.part_of_speech,s.synset_id,sy.lexname,w.lookup_key,"
        "(SELECT d.definition_text FROM definitions d WHERE d.sense_id=s.sense_id "
        "ORDER BY CASE WHEN d.definition_kind='learner' THEN 0 ELSE 1 END,"
        "d.is_preferred DESC,d.display_rank,d.quality_score DESC,d.definition_id LIMIT 1) "
        "FROM senses s JOIN words w ON w.word_id=s.word_id "
        "LEFT JOIN synsets sy ON sy.synset_id=s.synset_id"
    ).fetchall()
    domain_map = config.get("topic_domain_map", {})
    domains = defaultdict(set)
    for synset, term in db.execute(
        "SELECT r.source_synset_id,w.lookup_key FROM semantic_relations r "
        "JOIN senses s ON s.synset_id=r.target_synset_id JOIN words w ON w.word_id=s.word_id "
        "WHERE r.relation_type='domain_topic'"
    ):
        if term in domain_map:
            domains[synset].add(domain_map[term])
    classified = {}
    source_topics = defaultdict(set)
    for sid, wid, pos, synset, lexname, key, definition in senses:
        if not synset:
            continue
        subject = domains.get(synset, set())
        label = None
        if len(subject) == 1:
            label = (next(iter(subject)), 0.92, "OEWN subject domain")
        scoped = classify_definition_topic(lexname, definition, config)
        broad = classify_lexname(key, lexname, config)
        if label is None and scoped:
            label = (*scoped, "scoped definition rule")
        if label is None and broad:
            label = (*broad, "OEWN lexname: " + lexname)
        if label:
            classified[sid] = label
            source_topics[wid, pos].add(label[0])

    methods = Counter()
    for sid, wid, pos, synset, lexname, key, definition in senses:
        if sid not in classified:
            # Global lexical-field rules apply only where source-domain evidence
            # is unavailable, never overriding a known plant/person/etc. domain.
            rule = classify_definition_topic("learner." + pos, definition, config)
            options = source_topics.get((wid, pos), set())
            if rule:
                classified[sid] = (*rule, "learner definition topic rule")
            elif len(options) == 1:
                classified[sid] = (next(iter(options)), 0.60, "unambiguous same-POS topic; no sense alignment")
            else:
                category = config["topic_pos_fallbacks"].get(pos, "Ideas & Concepts")
                classified[sid] = (category, 0.35, "broad part-of-speech fallback: " + pos)
        category, confidence, method = classified[sid]
        methods[method.split(":")[0]] += 1
        db.execute(
            "INSERT OR REPLACE INTO sense_labels VALUES(?,?,?,?,?)",
            (sid, "oewn_2025", category, "topic", confidence),
        )

    selected = db.execute(
        "SELECT w.word_id,(SELECT d.sense_id FROM senses s JOIN definitions d ON d.sense_id=s.sense_id "
        "WHERE s.word_id=w.word_id ORDER BY CASE WHEN EXISTS(SELECT 1 FROM sense_labels usage WHERE usage.sense_id=s.sense_id AND usage.label_type<>'topic' AND lower(usage.label) IN ('slang','vulgar','offensive','archaic','obsolete','dated','rare','historical','nautical','grammar','linguistics','computing','mathematics','medicine','anatomy','chemistry','physics','baseball','cricket','theatre')) THEN 1 ELSE 0 END,CASE WHEN s.sense_id IN ('s_70957dc2eabb9ef082c16017','s_73b5e9d6b14bcaf5a0181df9','s_5ec200a996fe9865e9e22a3d') THEN 0 ELSE 1 END,"
        "CASE WHEN d.definition_kind='learner' THEN 0 ELSE 1 END,"
        "s.sense_order,s.part_of_speech,d.is_preferred DESC,d.display_rank,d.quality_score DESC,"
        "d.definition_id LIMIT 1) FROM words w"
    ).fetchall()
    for wid, sid in selected:
        if sid not in classified:
            continue
        category, confidence, method = classified[sid]
        db.execute("INSERT OR REPLACE INTO word_categories VALUES(?,?,?,?,?)",
                   (wid, category, "oewn_2025", confidence, "displayed sense " + sid + "; " + method))
    db.execute("INSERT OR REPLACE INTO build_metadata VALUES('topic_revision',?)", (TOPIC_REVISION,))
    db.execute("INSERT OR REPLACE INTO build_metadata VALUES('topic_policy',?)", (TOPIC_POLICY,))
    assigned = db.execute("SELECT COUNT(DISTINCT word_id) FROM word_categories").fetchone()[0]
    return {"sense_topic_labels": len(classified), "categorized_words": assigned,
            "words_without_category": len(selected) - assigned, "methods": dict(methods)}


def refresh_topics(db, config):
    db.execute("DELETE FROM sense_labels WHERE label_type='topic' AND source_id='oewn_2025'")
    db.execute("DELETE FROM word_categories WHERE source_id='oewn_2025'")
    return add_topics(db, config)
