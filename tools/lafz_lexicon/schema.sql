PRAGMA foreign_keys = ON;

CREATE TABLE data_sources (
    source_id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    publisher TEXT NOT NULL,
    version TEXT NOT NULL,
    download_url TEXT NOT NULL,
    license TEXT NOT NULL,
    license_url TEXT NOT NULL,
    attribution TEXT NOT NULL,
    redistribution TEXT NOT NULL,
    checksum_sha256 TEXT
);

CREATE TABLE words (
    word_id TEXT PRIMARY KEY,
    spelling TEXT NOT NULL,
    lookup_key TEXT NOT NULL,
    is_multiword INTEGER NOT NULL DEFAULT 0 CHECK (is_multiword IN (0, 1)),
    quality_score REAL NOT NULL DEFAULT 0.0 CHECK (quality_score BETWEEN 0.0 AND 1.0)
);

CREATE TABLE synsets (
    synset_id TEXT PRIMARY KEY,
    source_id TEXT NOT NULL REFERENCES data_sources(source_id),
    part_of_speech TEXT NOT NULL CHECK (part_of_speech IN ('noun','verb','adjective','adverb','other')),
    lexname TEXT,
    ili TEXT
);

CREATE TABLE senses (
    sense_id TEXT PRIMARY KEY,
    word_id TEXT NOT NULL REFERENCES words(word_id) ON DELETE CASCADE,
    synset_id TEXT REFERENCES synsets(synset_id),
    source_id TEXT NOT NULL REFERENCES data_sources(source_id),
    source_sense_id TEXT,
    part_of_speech TEXT NOT NULL CHECK (part_of_speech IN ('noun','verb','adjective','adverb','other')),
    sense_order INTEGER NOT NULL DEFAULT 0,
    alignment_confidence REAL CHECK (alignment_confidence IS NULL OR alignment_confidence BETWEEN 0.0 AND 1.0),
    quality_score REAL NOT NULL DEFAULT 0.0 CHECK (quality_score BETWEEN 0.0 AND 1.0)
);

CREATE TABLE definitions (
    definition_id TEXT PRIMARY KEY,
    sense_id TEXT NOT NULL REFERENCES senses(sense_id) ON DELETE CASCADE,
    source_id TEXT NOT NULL REFERENCES data_sources(source_id),
    definition_text TEXT NOT NULL,
    language TEXT NOT NULL DEFAULT 'en',
    definition_kind TEXT NOT NULL DEFAULT 'source',
    display_rank INTEGER NOT NULL DEFAULT 100,
    quality_score REAL NOT NULL DEFAULT 0.0 CHECK (quality_score BETWEEN 0.0 AND 1.0),
    is_preferred INTEGER NOT NULL DEFAULT 0 CHECK (is_preferred IN (0, 1))
);

CREATE TABLE examples (
    example_id TEXT PRIMARY KEY,
    sense_id TEXT NOT NULL REFERENCES senses(sense_id) ON DELETE CASCADE,
    source_id TEXT NOT NULL REFERENCES data_sources(source_id),
    example_text TEXT NOT NULL,
    validation_status TEXT NOT NULL CHECK (validation_status IN ('verified_word','verified_inflection','unverified_sense','unverified')),
    sense_match_confidence REAL CHECK (sense_match_confidence IS NULL OR sense_match_confidence BETWEEN 0.0 AND 1.0),
    quality_score REAL NOT NULL DEFAULT 0.0 CHECK (quality_score BETWEEN 0.0 AND 1.0)
);

-- General corpus examples are related to a word spelling/form only. Keeping
-- them outside `examples` prevents consumers from treating them as sense proof.
CREATE TABLE word_examples (
    word_example_id TEXT PRIMARY KEY,
    word_id TEXT NOT NULL REFERENCES words(word_id) ON DELETE CASCADE,
    source_id TEXT NOT NULL REFERENCES data_sources(source_id),
    example_text TEXT NOT NULL,
    validation_status TEXT NOT NULL CHECK (validation_status = 'word_presence_only'),
    sense_match_status TEXT NOT NULL DEFAULT 'unverified' CHECK (sense_match_status = 'unverified'),
    quality_score REAL NOT NULL DEFAULT 0.0 CHECK (quality_score BETWEEN 0.0 AND 1.0)
);

CREATE TABLE pronunciations (
    pronunciation_id TEXT PRIMARY KEY,
    word_id TEXT NOT NULL REFERENCES words(word_id) ON DELETE CASCADE,
    source_id TEXT NOT NULL REFERENCES data_sources(source_id),
    phonetic_alphabet TEXT NOT NULL,
    accent TEXT,
    variant_order INTEGER NOT NULL DEFAULT 1,
    transcription TEXT NOT NULL
);

CREATE TABLE word_forms (
    word_form_id TEXT PRIMARY KEY,
    word_id TEXT NOT NULL REFERENCES words(word_id) ON DELETE CASCADE,
    source_id TEXT NOT NULL REFERENCES data_sources(source_id),
    form TEXT NOT NULL,
    normalized_form TEXT NOT NULL,
    form_type TEXT NOT NULL DEFAULT 'inflection'
);

CREATE TABLE sense_labels (
    sense_id TEXT NOT NULL REFERENCES senses(sense_id) ON DELETE CASCADE,
    source_id TEXT NOT NULL REFERENCES data_sources(source_id),
    label TEXT NOT NULL,
    label_type TEXT NOT NULL DEFAULT 'usage',
    confidence REAL NOT NULL DEFAULT 1.0 CHECK (confidence BETWEEN 0.0 AND 1.0),
    PRIMARY KEY (sense_id, source_id, label, label_type)
);

CREATE TABLE sense_alignments (
    alignment_id TEXT PRIMARY KEY,
    source_id TEXT NOT NULL REFERENCES data_sources(source_id),
    source_sense_key TEXT NOT NULL,
    aligned_sense_id TEXT REFERENCES senses(sense_id) ON DELETE SET NULL,
    confidence REAL NOT NULL CHECK (confidence BETWEEN 0.0 AND 1.0),
    method TEXT NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('accepted','unmatched','ambiguous'))
);

CREATE TABLE word_relations (
    relation_id TEXT PRIMARY KEY,
    source_word_id TEXT NOT NULL REFERENCES words(word_id) ON DELETE CASCADE,
    target_word_id TEXT NOT NULL REFERENCES words(word_id) ON DELETE CASCADE,
    sense_id TEXT REFERENCES senses(sense_id) ON DELETE CASCADE,
    source_id TEXT NOT NULL REFERENCES data_sources(source_id),
    relation_type TEXT NOT NULL,
    confidence REAL NOT NULL DEFAULT 1.0 CHECK (confidence BETWEEN 0.0 AND 1.0),
    CHECK (source_word_id <> target_word_id)
);

CREATE TABLE semantic_relations (
    relation_id TEXT PRIMARY KEY,
    source_synset_id TEXT NOT NULL REFERENCES synsets(synset_id) ON DELETE CASCADE,
    target_synset_id TEXT NOT NULL REFERENCES synsets(synset_id) ON DELETE CASCADE,
    source_id TEXT NOT NULL REFERENCES data_sources(source_id),
    relation_type TEXT NOT NULL,
    confidence REAL NOT NULL DEFAULT 1.0 CHECK (confidence BETWEEN 0.0 AND 1.0)
);

CREATE TABLE word_categories (
    word_id TEXT NOT NULL REFERENCES words(word_id) ON DELETE CASCADE,
    category TEXT NOT NULL,
    source_id TEXT NOT NULL REFERENCES data_sources(source_id),
    confidence REAL NOT NULL CHECK (confidence BETWEEN 0.0 AND 1.0),
    method TEXT NOT NULL,
    PRIMARY KEY (word_id, category, source_id)
);

CREATE TABLE frequency_metadata (
    word_id TEXT NOT NULL REFERENCES words(word_id) ON DELETE CASCADE,
    source_id TEXT NOT NULL REFERENCES data_sources(source_id),
    ranking_scheme TEXT NOT NULL,
    rank INTEGER,
    usefulness_tier TEXT,
    difficulty_estimate INTEGER CHECK (difficulty_estimate IS NULL OR difficulty_estimate BETWEEN 1 AND 5),
    confidence REAL NOT NULL DEFAULT 1.0 CHECK (confidence BETWEEN 0.0 AND 1.0),
    evidence TEXT NOT NULL,
    PRIMARY KEY (word_id, source_id, ranking_scheme)
);

CREATE TABLE quiz_distractors (
    word_id TEXT NOT NULL REFERENCES words(word_id) ON DELETE CASCADE,
    distractor_word_id TEXT NOT NULL REFERENCES words(word_id) ON DELETE CASCADE,
    part_of_speech TEXT NOT NULL,
    rank_order INTEGER NOT NULL,
    confidence REAL NOT NULL CHECK (confidence BETWEEN 0.0 AND 1.0),
    selection_reason TEXT NOT NULL,
    PRIMARY KEY (word_id, distractor_word_id),
    CHECK (word_id <> distractor_word_id)
);

CREATE TABLE source_attributions (
    attribution_id TEXT PRIMARY KEY,
    source_id TEXT NOT NULL REFERENCES data_sources(source_id),
    record_type TEXT NOT NULL,
    record_id TEXT NOT NULL,
    detail TEXT NOT NULL DEFAULT ''
);

CREATE TABLE build_metadata (
    key TEXT PRIMARY KEY,
    value TEXT NOT NULL
);

CREATE INDEX idx_words_lookup ON words(lookup_key);
CREATE INDEX idx_pronunciations_word ON pronunciations(word_id, variant_order);
CREATE INDEX idx_senses_word_pos ON senses(word_id, part_of_speech, sense_order);
CREATE INDEX idx_senses_synset ON senses(synset_id);
CREATE INDEX idx_definitions_sense_rank ON definitions(sense_id, display_rank, is_preferred);
CREATE INDEX idx_examples_sense ON examples(sense_id, quality_score DESC);
CREATE INDEX idx_word_examples_word ON word_examples(word_id, quality_score DESC);
CREATE INDEX idx_forms_lookup ON word_forms(normalized_form);
CREATE INDEX idx_labels_sense ON sense_labels(sense_id, label_type);
CREATE INDEX idx_alignments_target ON sense_alignments(aligned_sense_id, status);
CREATE INDEX idx_word_relations_from ON word_relations(source_word_id, relation_type);
CREATE INDEX idx_relations_source ON semantic_relations(source_synset_id, relation_type);
CREATE INDEX idx_categories_category ON word_categories(category, confidence DESC);
CREATE INDEX idx_frequency_rank ON frequency_metadata(ranking_scheme, rank);
CREATE INDEX idx_distractors_word ON quiz_distractors(word_id, rank_order);
