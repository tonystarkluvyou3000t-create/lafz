package com.lafz.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/** Search and collection organization are kept outside the activity's navigation code. */
final class SavedWordsScreen extends LinearLayout {
    private final int ink, muted, accent, card, line, selectedInk;
    private final List<Word> words;
    private final Consumer<Word> open;
    private final SharedPreferences prefs;
    private final LinearLayout entries;
    private final TextView count;
    private final EditText search;
    private String sort;
    private String topic;
    private boolean compact;

    SavedWordsScreen(Context context, List<Word> saved, int ink, int muted, int accent,
                     int card, int line, int selectedInk, Consumer<Word> open) {
        super(context);
        this.ink = ink; this.muted = muted; this.accent = accent; this.card = card;
        this.line = line; this.selectedInk = selectedInk; this.open = open;
        words = new ArrayList<>(saved); prefs = LafzPrefs.get(context);
        sort = prefs.getString("saved_sort", "Recent");
        topic = prefs.getString("saved_topic", "All topics");
        compact = prefs.getBoolean("saved_compact", false);
        setOrientation(VERTICAL);
        TextView eyebrow = text("SAVED BY YOU", 11, muted, false);
        eyebrow.setLetterSpacing(.12f); addView(eyebrow, gap(0, 8, 10));
        addView(text("Your collection", 36, ink, true), gap(0, 0, 10));
        addView(text(words.size() + (words.size() == 1 ? " word worth coming back to." : " words worth coming back to."), 14, muted, false), gap(0, 0, 20));
        search = new EditText(context); search.setSingleLine(true); search.setTextSize(14);
        search.setTextColor(ink); search.setHintTextColor(muted);
        search.setHint("Search your saved words");
        search.setContentDescription("Search saved words by word, meaning or topic");
        search.setPadding(dp(18), 0, dp(18), 0); search.setBackground(surface(card, 18, line));
        search.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        search.setOnEditorActionListener((view, action, event) -> {
            if (action != android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) return false;
            InputMethodManager keyboard = (InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (keyboard != null) keyboard.hideSoftInputFromWindow(search.getWindowToken(), 0);
            search.clearFocus(); return true;
        });
        addView(search, new LayoutParams(-1, dp(54)));
        LinearLayout sorts = strip();
        for (String option : new String[]{"Recent", "A–Z", "Due for review"}) {
            TextView chip = chip(option, option.equals(sort));
            chip.setOnClickListener(v -> {
                sort = option; prefs.edit().putString("saved_sort", sort).apply();
                updateChoices(sorts, sort); refresh();
            }); sorts.addView(chip, chipParams());
        }
        LinearLayout topics = strip();
        List<String> available = new ArrayList<>(); available.add("All topics");
        for (String category : LafzWords.CATEGORIES) {
            for (Word word : words) if (category.equals(word.category)) { available.add(category); break; }
        }
        if (!available.contains(topic)) topic = "All topics";
        for (String option : available) {
            TextView chip = chip(option, option.equals(topic));
            chip.setOnClickListener(v -> {
                topic = option; prefs.edit().putString("saved_topic", topic).apply();
                updateChoices(topics, topic); refresh();
            }); topics.addView(chip, chipParams());
        }
        LinearLayout heading = new LinearLayout(context); heading.setGravity(Gravity.CENTER_VERTICAL);
        count = text("", 11, muted, false);
        heading.addView(count, new LayoutParams(0, -2, 1));
        TextView density = chip(compact ? "Roomy view" : "Compact view", false);
        density.setOnClickListener(v -> {
            compact = !compact; prefs.edit().putBoolean("saved_compact", compact).apply();
            density.setText(compact ? "Roomy view" : "Compact view"); refresh();
        }); heading.addView(density, new LayoutParams(-2, dp(48)));
        addView(heading, gap(0, 10, 10));
        entries = new LinearLayout(context); entries.setOrientation(VERTICAL); addView(entries);
        search.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            public void onTextChanged(CharSequence s, int start, int before, int count) { refresh(); }
            public void afterTextChanged(android.text.Editable value) { }
        }); refresh();
    }

    private void refresh() {
        String query = search.getText().toString().trim().toLowerCase(Locale.ROOT);
        List<Word> visible = new ArrayList<>();
        for (Word word : words) {
            if (!topic.equals("All topics") && !topic.equals(word.category)) continue;
            if (sort.equals("Due for review") && prefs.getInt("due_" + word.id, LafzPrefs.today()) > LafzPrefs.today()) continue;
            if (!(word.spelling + " " + word.meaning + " " + word.category).toLowerCase(Locale.ROOT).contains(query)) continue;
            visible.add(word);
        }
        Comparator<Word> alphabetical = Comparator.comparing(word -> word.spelling.toLowerCase(Locale.ROOT));
        if (sort.equals("Recent")) visible.sort(Comparator.comparingLong((Word word) -> prefs.getLong("saved_at_" + word.id, 0)).reversed().thenComparing(alphabetical));
        else if (sort.equals("Due for review")) visible.sort(Comparator.comparingInt((Word word) -> prefs.getInt("due_" + word.id, LafzPrefs.today())).thenComparing(alphabetical));
        else visible.sort(alphabetical);
        count.setText(visible.size() + (visible.size() == 1 ? " WORD" : " WORDS") + (sort.equals("Due for review") ? " DUE" : ""));
        entries.removeAllViews();
        for (Word word : visible) {
            LinearLayout row = new LinearLayout(getContext()); row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(18), dp(compact ? 12 : 18), dp(16), dp(compact ? 12 : 18));
            row.setBackground(surface(card, 20, card));
            LinearLayout copy = new LinearLayout(getContext()); copy.setOrientation(VERTICAL);
            copy.addView(text(word.spelling, compact ? 23 : 27, ink, true));
            boolean due = prefs.getInt("due_" + word.id, LafzPrefs.today()) <= LafzPrefs.today();
            copy.addView(text(word.partOfSpeech + " · " + word.category + (due ? " · Due" : ""), 11, accent, false), gap(0, 5, compact ? 0 : 8));
            if (!compact) {
                TextView meaning = text(word.meaning, 14, muted, false);
                meaning.setMaxLines(2); meaning.setEllipsize(android.text.TextUtils.TruncateAt.END); copy.addView(meaning);
            }
            row.addView(copy, new LayoutParams(0, -2, 1));
            TextView arrow = text("↗", 20, accent, false); arrow.setGravity(Gravity.CENTER);
            row.addView(arrow, new LayoutParams(dp(36), dp(48))); row.setOnClickListener(v -> open.accept(word));
            row.setContentDescription(word.spelling + ", " + word.partOfSpeech + ", " + word.category + (due ? ", due for review" : ""));
            addEntry(row);
        }
        if (visible.isEmpty()) addEntry(text(sort.equals("Due for review") && query.isEmpty() ? "No words due in this view. Try All topics or Recent." : "No words found. Try another word or topic.", 15, muted, false));
    }

    private void addEntry(android.view.View view) { entries.addView(view, gap(0, 0, 10)); }
    private LinearLayout strip() {
        HorizontalScrollView scroll = new HorizontalScrollView(getContext()); scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(getContext()); row.setGravity(Gravity.CENTER_VERTICAL);
        scroll.addView(row); addView(scroll, gap(0, 12, 0)); return row;
    }
    private void updateChoices(LinearLayout row, String selected) {
        for (int i = 0; i < row.getChildCount(); i++) styleChip((TextView) row.getChildAt(i), ((TextView) row.getChildAt(i)).getText().toString().equals(selected));
    }
    private TextView chip(String value, boolean selected) {
        TextView view = text(value, 12, accent, false); view.setGravity(Gravity.CENTER);
        view.setPadding(dp(14), 0, dp(14), 0); styleChip(view, selected); return view;
    }
    private void styleChip(TextView view, boolean selected) {
        view.setTextColor(selected ? selectedInk : accent); view.setSelected(selected);
        view.setBackground(surface(selected ? accent : card, 22, selected ? accent : line));
        view.setContentDescription(view.getText() + (selected ? ", selected" : ""));
    }
    private LayoutParams chipParams() { LayoutParams p = new LayoutParams(-2, dp(48)); p.rightMargin = dp(8); return p; }
    private LayoutParams gap(int left, int top, int bottom) { LayoutParams p = new LayoutParams(-1, -2); p.setMargins(dp(left), dp(top), 0, dp(bottom)); return p; }
    private TextView text(String value, int size, int color, boolean serif) {
        TextView view = new TextView(getContext()); view.setText(value); view.setTextSize(size); view.setTextColor(color);
        view.setTypeface(Typeface.create(serif ? "serif" : "sans-serif", Typeface.NORMAL)); return view;
    }
    private GradientDrawable surface(int fill, int radius, int stroke) {
        GradientDrawable shape = new GradientDrawable(); shape.setColor(fill); shape.setCornerRadius(dp(radius)); shape.setStroke(dp(1), stroke); return shape;
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
