package com.lafz.app;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.List;
import java.util.Map;

/** App-facing view of one stable lexical entry; meanings remain separate senses. */
final class Word implements java.io.Serializable {
    private static final long serialVersionUID = 1L;
    final String id;
    final String spelling;
    final String pronunciation;
    final String pronunciationAlphabet;
    final String partOfSpeech;
    final String category;
    final String meaning;
    /** A sentence from the same source sense as meaning, never a word-only Tatoeba match. */
    final String example;
    final String synonyms;
    final String origin;
    final String usageNote;
    final String source;
    final String sourceUrl;
    final String licenseUrl;
    final int difficultyEstimate;
    final double difficultyConfidence;
    final String difficultyEvidence;

    Word(String id, String spelling, String pronunciation, String partOfSpeech,
         String category, String meaning, String example, String synonyms,
         String origin, String usageNote) {
        this(id, spelling, pronunciation, "", partOfSpeech, category, meaning, example, synonyms,
                origin, usageNote, "Lafz Lexicon", "", "", 3, 0.0, "Not estimated");
    }

    Word(String id, String spelling, String pronunciation, String pronunciationAlphabet,
         String partOfSpeech, String category, String meaning, String example, String synonyms,
         String origin, String usageNote, String source, String sourceUrl, String licenseUrl,
         int difficultyEstimate, double difficultyConfidence, String difficultyEvidence) {
        this.id = id;
        this.spelling = spelling;
        this.pronunciation = pronunciation == null ? "" : pronunciation;
        this.pronunciationAlphabet = pronunciationAlphabet == null ? "" : pronunciationAlphabet;
        this.partOfSpeech = partOfSpeech == null || partOfSpeech.isEmpty() ? "word" : partOfSpeech;
        this.category = category == null || category.isEmpty() ? "Unclassified" : category;
        this.meaning = meaning == null ? "" : meaning;
        this.example = example == null ? "" : example;
        this.synonyms = synonyms == null ? "" : synonyms;
        this.origin = origin == null ? "" : origin;
        this.usageNote = usageNote == null ? "" : usageNote;
        this.source = source == null ? "Lafz Lexicon" : source;
        this.sourceUrl = sourceUrl == null ? "" : sourceUrl;
        this.licenseUrl = licenseUrl == null ? "" : licenseUrl;
        this.difficultyEstimate = difficultyEstimate;
        this.difficultyConfidence = difficultyConfidence;
        this.difficultyEvidence = difficultyEvidence == null ? "" : difficultyEvidence;
    }

    String pronunciationDisplay() {
        if (pronunciation.isEmpty()) return "";
        if ("IPA".equalsIgnoreCase(pronunciationAlphabet))
            return "/" + pronunciation.trim().replaceAll("^[/\\[\\]]+|[/\\[\\]]+$", "") + "/";
        if (!"ARPABET".equalsIgnoreCase(pronunciationAlphabet)) return pronunciation;
        StringBuilder ipa = new StringBuilder();
        for (String token : pronunciation.toUpperCase(Locale.ROOT).trim().split("\\s+")) {
            if (token.isEmpty()) continue;
            char last = token.charAt(token.length() - 1);
            if (last == '1') ipa.append('ˈ');
            else if (last == '2') ipa.append('ˌ');
            String phone = last >= '0' && last <= '2' ? token.substring(0, token.length() - 1) : token;
            ipa.append(CMU_IPA.getOrDefault(phone, phone.toLowerCase(Locale.ROOT)));
        }
        return "US (approx.) /" + ipa + "/";
    }

    private static final Map<String, String> CMU_IPA = cmuMap();

    private static Map<String, String> cmuMap() {
        Map<String, String> map = new HashMap<>();
        String[][] pairs = {
                {"AA", "ɑ"}, {"AE", "æ"}, {"AH", "ʌ"}, {"AO", "ɔ"}, {"AW", "aʊ"}, {"AY", "aɪ"},
                {"B", "b"}, {"CH", "tʃ"}, {"D", "d"}, {"DH", "ð"}, {"EH", "ɛ"}, {"ER", "ɝ"},
                {"EY", "eɪ"}, {"F", "f"}, {"G", "ɡ"}, {"HH", "h"}, {"IH", "ɪ"}, {"IY", "i"},
                {"JH", "dʒ"}, {"K", "k"}, {"L", "l"}, {"M", "m"}, {"N", "n"}, {"NG", "ŋ"},
                {"OW", "oʊ"}, {"OY", "ɔɪ"}, {"P", "p"}, {"R", "ɹ"}, {"S", "s"}, {"SH", "ʃ"},
                {"T", "t"}, {"TH", "θ"}, {"UH", "ʊ"}, {"UW", "u"}, {"V", "v"}, {"W", "w"},
                {"Y", "j"}, {"Z", "z"}, {"ZH", "ʒ"}
        };
        for (String[] pair : pairs) map.put(pair[0], pair[1]);
        return map;
    }
}

final class LexiconSense {
    final String id;
    final String partOfSpeech;
    final int order;
    final List<LexiconDefinition> definitions = new ArrayList<>();
    final List<LexiconExample> examples = new ArrayList<>();
    final List<String> labels = new ArrayList<>();

    LexiconSense(String id, String partOfSpeech, int order) {
        this.id = id;
        this.partOfSpeech = partOfSpeech;
        this.order = order;
    }
}

final class LexiconDefinition {
    final String text;
    final String source;
    final String attribution;
    final String license;
    final String licenseUrl;
    final String kind;

    LexiconDefinition(String text, String source, String attribution, String license, String licenseUrl, String kind) {
        this.text = text;
        this.source = source;
        this.attribution = attribution;
        this.license = license;
        this.licenseUrl = licenseUrl;
        this.kind = kind;
    }
}

final class LexiconExample {
    final String text;
    final String source;
    final String license;
    final String licenseUrl;
    final String validationStatus;
    final Double senseMatchConfidence;
    final boolean senseAssociated;

    LexiconExample(String text, String source, String license, String licenseUrl,
                   String validationStatus, Double senseMatchConfidence, boolean senseAssociated) {
        this.text = text;
        this.source = source;
        this.license = license;
        this.licenseUrl = licenseUrl;
        this.validationStatus = validationStatus;
        this.senseMatchConfidence = senseMatchConfidence;
        this.senseAssociated = senseAssociated;
    }
}

final class LexiconSource {
    final String name;
    final String publisher;
    final String version;
    final String license;
    final String licenseUrl;
    final String attribution;
    final String redistribution;

    LexiconSource(String name, String publisher, String version, String license,
                  String licenseUrl, String attribution, String redistribution) {
        this.name = name;
        this.publisher = publisher;
        this.version = version;
        this.license = license;
        this.licenseUrl = licenseUrl;
        this.attribution = attribution;
        this.redistribution = redistribution;
    }
}
