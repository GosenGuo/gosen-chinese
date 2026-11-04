package com.gosen.chinese;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Resolves annotations by their exact occurrence in the original UTF-16 text. */
public final class AnnotationResolver {
    private AnnotationResolver() { }

    public static final class ResolvedNote {
        public final int start;
        public final int end;
        public final JSONObject note;

        ResolvedNote(int start, int end, JSONObject note) {
            this.start = start;
            this.end = end;
            this.note = note;
        }

        public String key(String articleId) {
            String stableId = note.optString("id", "");
            if (stableId.isEmpty()) {
                stableId = note.optString("quote") + ":" + note.optInt("occurrence", 0);
            }
            return articleId + ":note:" + stableId;
        }
    }

    public static List<ResolvedNote> resolve(JSONObject article) {
        String body = article.optString("body");
        JSONArray notes = article.optJSONArray("notes");
        List<ResolvedNote> candidates = new ArrayList<>();
        if (notes == null) return candidates;
        for (int i = 0; i < notes.length(); i++) {
            JSONObject note = notes.optJSONObject(i);
            if (note == null) continue;
            String quote = note.optString("quote");
            int occurrence = note.optInt("occurrence", 0);
            if (quote.isEmpty() || occurrence < 0 || note.optString("meaning").trim().isEmpty()) continue;
            int start = -1;
            for (int n = 0, from = 0; n <= occurrence; n++) {
                start = body.indexOf(quote, from);
                if (start < 0) break;
                from = start + quote.length();
            }
            if (start >= 0) candidates.add(new ResolvedNote(start, start + quote.length(), note));
        }
        // The most specific phrase wins over a single-character annotation at the same position.
        candidates.sort(Comparator.<ResolvedNote>comparingInt(value -> value.end - value.start)
                .reversed().thenComparingInt(value -> value.start));
        List<ResolvedNote> resolved = new ArrayList<>();
        for (ResolvedNote candidate : candidates) {
            boolean overlaps = false;
            for (ResolvedNote accepted : resolved) {
                if (candidate.start < accepted.end && accepted.start < candidate.end) {
                    overlaps = true;
                    break;
                }
            }
            if (!overlaps) resolved.add(candidate);
        }
        resolved.sort(Comparator.comparingInt(value -> value.start));
        return resolved;
    }
}
