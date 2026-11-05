package com.gosen.chinese;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.function.LongSupplier;

/** Owns drafts, first attempts and occurrence-specific spaced-review records. */
public final class StudyStore {
    private static final String PREFS = "gosen_chinese_study_v1";
    private static final Object GLOBAL_LOCK = new Object();
    private static final long DAY = 86_400_000L;
    private static final int[] INTERVAL_DAYS = {1, 3, 7, 14, 30, 60};
    private static final String[] OBJECT_FIELDS = {"drafts", "scrolls", "attempts", "completed", "reviews", "settings"};
    private final SharedPreferences preferences;
    private final LongSupplier clock;
    private JSONObject state;
    private int accessDepth;

    public StudyStore(Context context) { this(context, System::currentTimeMillis); }

    StudyStore(Context context, LongSupplier clock) {
        this.preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        this.clock = clock;
        synchronized (GLOBAL_LOCK) { reload(); }
    }

    private void reload() {
        try {
            state = checkedState(preferences.getString("state", newState().toString()));
        } catch (Exception exception) {
            // Preserve the original damaged bytes without recursively reading a wrong preference type.
            Object unreadable = preferences.getAll().get("state");
            preferences.edit().putString("unreadable_state", String.valueOf(unreadable)).apply();
            state = newState();
        }
    }

    private void beginAccess() {
        // Public helpers can call one another; never reload midway through an uncommitted operation.
        if (accessDepth == 0) reload();
        accessDepth++;
    }

    private static JSONObject newState() {
        JSONObject result = new JSONObject();
        put(result, "schemaVersion", 1);
        for (String key : OBJECT_FIELDS) put(result, key, new JSONObject());
        put(result, "activeDays", new JSONArray());
        put(result, "lastArticle", "");
        return result;
    }

    public String draft(String articleId, String key) {
        synchronized (GLOBAL_LOCK) {
            beginAccess();
            try {
                JSONObject values = state.optJSONObject("drafts").optJSONObject(articleId);
                return values == null ? "" : values.optString(key, "");
            } finally { accessDepth--; }
        }
    }

    public void putDraft(String articleId, String key, String value) {
        synchronized (GLOBAL_LOCK) {
            beginAccess();
            try {
                requireKey(articleId); requireKey(key);
                put(object(state.optJSONObject("drafts"), articleId), key, value == null ? "" : value);
                persist();
            } finally { accessDepth--; }
        }
    }

    public int scroll(String articleId, String page) {
        synchronized (GLOBAL_LOCK) {
            beginAccess();
            try {
                JSONObject values = state.optJSONObject("scrolls").optJSONObject(articleId);
                return values == null ? 0 : Math.max(0, values.optInt(page, 0));
            } finally { accessDepth--; }
        }
    }

    public void putScroll(String articleId, String page, int position) {
        synchronized (GLOBAL_LOCK) {
            beginAccess();
            try {
                requireKey(articleId); requireKey(page);
                put(object(state.optJSONObject("scrolls"), articleId), page, Math.max(0, position));
                persist();
            } finally { accessDepth--; }
        }
    }

    public void submit(String articleId, JSONObject answers, boolean testMode) {
        synchronized (GLOBAL_LOCK) {
            beginAccess();
            try {
                requireKey(articleId);
                if (answers == null) throw new IllegalArgumentException("答案不能为空");
                JSONObject record = object(object(state.optJSONObject("attempts"), articleId), mode(testMode));
                if (!record.has("first")) {
                    put(record, "first", copy(answers));
                    put(record, "firstAt", clock.getAsLong());
                }
                put(record, "latest", copy(answers));
                put(record, "latestAt", clock.getAsLong());
                markCompleteInternal(articleId);
                persist();
            } finally { accessDepth--; }
        }
    }

    public JSONObject firstAnswers(String articleId) {
        synchronized (GLOBAL_LOCK) {
            beginAccess();
            try {
                return firstAnswers(articleId, false);
            } finally { accessDepth--; }
        }
    }
    public JSONObject latestAnswers(String articleId) {
        synchronized (GLOBAL_LOCK) {
            beginAccess();
            try {
                return latestAnswers(articleId, false);
            } finally { accessDepth--; }
        }
    }

    public JSONObject firstAnswers(String articleId, boolean testMode) {
        synchronized (GLOBAL_LOCK) {
            beginAccess();
            try {
                JSONObject attempt = attempt(articleId, testMode);
                return attempt == null ? new JSONObject() : copy(attempt.optJSONObject("first"));
            } finally { accessDepth--; }
        }
    }

    public JSONObject latestAnswers(String articleId, boolean testMode) {
        synchronized (GLOBAL_LOCK) {
            beginAccess();
            try {
                JSONObject attempt = attempt(articleId, testMode);
                return attempt == null ? new JSONObject() : copy(attempt.optJSONObject("latest"));
            } finally { accessDepth--; }
        }
    }

    private JSONObject attempt(String articleId, boolean testMode) {
        JSONObject article = state.optJSONObject("attempts").optJSONObject(articleId);
        return article == null ? null : article.optJSONObject(mode(testMode));
    }

    public boolean hasAttempt(String articleId) {
        synchronized (GLOBAL_LOCK) {
            beginAccess();
            try {
                return hasAttempt(articleId, false) || hasAttempt(articleId, true);
            } finally { accessDepth--; }
        }
    }

    public boolean hasAttempt(String articleId, boolean testMode) {
        synchronized (GLOBAL_LOCK) {
            beginAccess();
            try {
                JSONObject attempt = attempt(articleId, testMode);
                return attempt != null && attempt.has("first");
            } finally { accessDepth--; }
        }
    }

    public boolean isCompleted(String articleId) {
        synchronized (GLOBAL_LOCK) {
            beginAccess();
            try {
                return state.optJSONObject("completed").has(articleId);
            } finally { accessDepth--; }
        }
    }

    public void markComplete(String articleId) {
        synchronized (GLOBAL_LOCK) {
            beginAccess();
            try {
                requireKey(articleId);
                markCompleteInternal(articleId);
                persist();
            } finally { accessDepth--; }
        }
    }

    private void markCompleteInternal(String articleId) {
        JSONObject completed = state.optJSONObject("completed");
        if (!completed.has(articleId)) put(completed, articleId, clock.getAsLong());
        put(object(state.optJSONObject("drafts"), articleId), "daily.completed:" + today(), "true");
        touchDay();
    }

    public boolean completedOn(String articleId, LocalDate day) {
        if (articleId == null || day == null) return false;
        synchronized (GLOBAL_LOCK) {
            beginAccess();
            try {
                JSONObject record = state.optJSONObject("drafts").optJSONObject(articleId);
                return record != null && "true".equals(record.optString("daily.completed:" + day));
            } finally { accessDepth--; }
        }
    }

    public boolean completedToday(String articleId) { return completedOn(articleId, today()); }

    public int completedCount() {
        synchronized (GLOBAL_LOCK) {
            beginAccess();
            try {
                return state.optJSONObject("completed").length();
            } finally { accessDepth--; }
        }
    }

    public int streak() {
        synchronized (GLOBAL_LOCK) {
            beginAccess();
            try {
                Set<String> days = new HashSet<>();
                JSONArray activeDays = state.optJSONArray("activeDays");
                for (int i = 0; i < activeDays.length(); i++) days.add(activeDays.optString(i));
                LocalDate current = today();
                if (!days.contains(current.toString())) current = current.minusDays(1);
                int result = 0;
                while (days.contains(current.toString())) { result++; current = current.minusDays(1); }
                return result;
            } finally { accessDepth--; }
        }
    }

    public String lastArticle() {
        synchronized (GLOBAL_LOCK) {
            beginAccess();
            try {
                return state.optString("lastArticle", "");
            } finally { accessDepth--; }
        }
    }

    public void setLastArticle(String articleId) {
        synchronized (GLOBAL_LOCK) {
            beginAccess();
            try {
                put(state, "lastArticle", articleId == null ? "" : articleId);
                persist();
            } finally { accessDepth--; }
        }
    }

    public boolean hasReview(String key) {
        synchronized (GLOBAL_LOCK) {
            beginAccess();
            try {
                return state.optJSONObject("reviews").has(key);
            } finally { accessDepth--; }
        }
    }

    public void saveReview(JSONObject card) {
        synchronized (GLOBAL_LOCK) {
            beginAccess();
            try {
                validateCard(card);
                String key = card.optString("key");
                JSONObject reviews = state.optJSONObject("reviews");
                JSONObject existing = reviews.optJSONObject(key);
                JSONObject saved = copy(card);
                put(saved, "dueAt", existing == null ? clock.getAsLong() : existing.optLong("dueAt", clock.getAsLong()));
                put(saved, "stage", existing == null ? 0 : existing.optInt("stage", 0));
                put(saved, "createdAt", existing == null ? clock.getAsLong() : existing.optLong("createdAt", clock.getAsLong()));
                put(saved, "lastReviewedAt", existing == null ? 0L : existing.optLong("lastReviewedAt", 0));
                put(reviews, key, saved);
                persist();
            } finally { accessDepth--; }
        }
    }

    public void removeReview(String key) {
        synchronized (GLOBAL_LOCK) {
            beginAccess();
            try {
                state.optJSONObject("reviews").remove(key);
                persist();
            } finally { accessDepth--; }
        }
    }

    public List<JSONObject> reviews() {
        synchronized (GLOBAL_LOCK) {
            beginAccess();
            try {
                List<JSONObject> result = new ArrayList<>();
                JSONObject values = state.optJSONObject("reviews");
                Iterator<String> keys = values.keys();
                while (keys.hasNext()) result.add(copy(values.optJSONObject(keys.next())));
                result.sort(Comparator.<JSONObject>comparingLong(value -> value.optLong("dueAt", 0))
                        .thenComparing(value -> value.optString("key")));
                return result;
            } finally { accessDepth--; }
        }
    }

    public List<JSONObject> dueReviews() {
        synchronized (GLOBAL_LOCK) {
            beginAccess();
            try {
                List<JSONObject> result = reviews();
                long now = clock.getAsLong();
                result.removeIf(value -> value.optLong("dueAt", 0) > now);
                return result;
            } finally { accessDepth--; }
        }
    }

    public void gradeReview(String key, boolean remembered) {
        synchronized (GLOBAL_LOCK) {
            beginAccess();
            try {
                JSONObject card = state.optJSONObject("reviews").optJSONObject(key);
                if (card == null) return;
                long now = clock.getAsLong();
                int stage = Math.max(0, Math.min(INTERVAL_DAYS.length, card.optInt("stage", 0)));
                if (remembered) {
                    put(card, "dueAt", now + INTERVAL_DAYS[Math.min(stage, INTERVAL_DAYS.length - 1)] * DAY);
                    put(card, "stage", Math.min(stage + 1, INTERVAL_DAYS.length));
                } else {
                    put(card, "dueAt", now + 15 * 60_000L);
                    put(card, "stage", 0);
                }
                put(card, "lastReviewedAt", now);
                touchDay();
                persist();
            } finally { accessDepth--; }
        }
    }

    public int dueCount() {
        synchronized (GLOBAL_LOCK) {
            beginAccess();
            try {
                return dueReviews().size();
            } finally { accessDepth--; }
        }
    }
    public String exportJson() {
        synchronized (GLOBAL_LOCK) {
            beginAccess();
            try {
                return state.toString();
            } finally { accessDepth--; }
        }
    }

    /** Validates the complete backup before replacing the single atomic preferences value. */
    public void importJson(String json) {
        synchronized (GLOBAL_LOCK) {
            beginAccess();
            try {
                JSONObject candidate = checkedState(json);
                String previous = state.toString();
                boolean saved = preferences.edit().putString("previous_import_state", previous)
                        .putString("state", candidate.toString()).commit();
                if (!saved) {
                    preferences.edit().putString("state", previous).commit();
                    throw new IllegalStateException("备份未能写入，请保留原文件后重试");
                }
                state = candidate;
            } finally { accessDepth--; }
        }
    }

    public String setting(String key, String defaultValue) {
        synchronized (GLOBAL_LOCK) {
            beginAccess();
            try {
                return state.optJSONObject("settings").optString(key, defaultValue);
            } finally { accessDepth--; }
        }
    }

    public void putSetting(String key, String value) {
        synchronized (GLOBAL_LOCK) {
            beginAccess();
            try {
                requireKey(key);
                put(state.optJSONObject("settings"), key, value == null ? "" : value);
                persist();
            } finally { accessDepth--; }
        }
    }

    private void persist() { preferences.edit().putString("state", state.toString()).apply(); }
    private static String mode(boolean testMode) { return testMode ? "test" : "learning"; }
    private LocalDate today() { return Instant.ofEpochMilli(clock.getAsLong()).atZone(ZoneId.systemDefault()).toLocalDate(); }

    private void touchDay() {
        JSONArray days = state.optJSONArray("activeDays");
        String day = today().toString();
        for (int i = 0; i < days.length(); i++) if (day.equals(days.optString(i))) return;
        days.put(day);
    }

    private static JSONObject checkedState(String json) {
        try {
            if (json == null || json.length() > 10_000_000) throw new IllegalArgumentException("备份大小不正确");
            JSONObject candidate = new JSONObject(json);
            if (!(candidate.opt("schemaVersion") instanceof Number) || candidate.optInt("schemaVersion") != 1) {
                throw new IllegalArgumentException("不支持的备份版本");
            }
            for (String key : OBJECT_FIELDS) {
                if (candidate.optJSONObject(key) == null) throw new IllegalArgumentException("备份缺少" + key);
            }
            if (candidate.optJSONArray("activeDays") == null || !(candidate.opt("lastArticle") instanceof String)) {
                throw new IllegalArgumentException("学习日期或继续学习记录损坏");
            }
            validateNested(candidate.optJSONObject("drafts"), String.class);
            validateNested(candidate.optJSONObject("scrolls"), Number.class);
            validateValues(candidate.optJSONObject("completed"), Number.class);
            validateValues(candidate.optJSONObject("settings"), String.class);
            JSONArray days = candidate.optJSONArray("activeDays");
            for (int i = 0; i < days.length(); i++) {
                if (!(days.opt(i) instanceof String)) throw new IllegalArgumentException("学习日期损坏");
                LocalDate.parse(days.getString(i));
            }
            JSONObject reviews = candidate.optJSONObject("reviews");
            Iterator<String> reviewKeys = reviews.keys();
            while (reviewKeys.hasNext()) {
                String key = reviewKeys.next();
                JSONObject card = reviews.optJSONObject(key);
                validateCard(card);
                if (!key.equals(card.optString("key")) || !(card.opt("dueAt") instanceof Number)
                        || !(card.opt("stage") instanceof Number) || card.optLong("dueAt", -1) < 0
                        || card.optInt("stage", -1) < 0 || card.optInt("stage") > INTERVAL_DAYS.length) {
                    throw new IllegalArgumentException("复习计划损坏");
                }
            }
            JSONObject attempts = candidate.optJSONObject("attempts");
            Iterator<String> articleKeys = attempts.keys();
            while (articleKeys.hasNext()) {
                JSONObject article = attempts.optJSONObject(articleKeys.next());
                if (article == null) throw new IllegalArgumentException("答题记录损坏");
                Iterator<String> modes = article.keys();
                while (modes.hasNext()) {
                    String key = modes.next();
                    JSONObject record = article.optJSONObject(key);
                    if (!(key.equals("learning") || key.equals("test")) || record == null
                            || record.optJSONObject("first") == null || record.optJSONObject("latest") == null
                            || !(record.opt("firstAt") instanceof Number) || !(record.opt("latestAt") instanceof Number)) {
                        throw new IllegalArgumentException("首次答题记录损坏");
                    }
                }
            }
            return candidate;
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("备份无法读取，现有记录已保留", exception);
        }
    }

    private static void validateNested(JSONObject root, Class<?> valueClass) {
        Iterator<String> keys = root.keys();
        while (keys.hasNext()) {
            JSONObject child = root.optJSONObject(keys.next());
            if (child == null) throw new IllegalArgumentException("学习记录格式错误");
            validateValues(child, valueClass);
        }
    }

    private static void validateValues(JSONObject root, Class<?> valueClass) {
        Iterator<String> keys = root.keys();
        while (keys.hasNext()) {
            if (!valueClass.isInstance(root.opt(keys.next()))) throw new IllegalArgumentException("学习记录值错误");
        }
    }

    private static void validateCard(JSONObject card) {
        if (card == null) throw new IllegalArgumentException("复习卡片缺失");
        for (String field : new String[]{"key", "type", "title", "prompt", "answer", "articleId"}) {
            if (!(card.opt(field) instanceof String) || card.optString(field).trim().isEmpty()) {
                throw new IllegalArgumentException("复习卡片缺少" + field);
            }
        }
    }

    private static void requireKey(String key) {
        if (key == null || key.trim().isEmpty()) throw new IllegalArgumentException("记录编号不能为空");
    }

    private static JSONObject object(JSONObject parent, String key) {
        JSONObject child = parent.optJSONObject(key);
        if (child == null) { child = new JSONObject(); put(parent, key, child); }
        return child;
    }

    private static JSONObject copy(JSONObject value) {
        if (value == null) return new JSONObject();
        try { return new JSONObject(value.toString()); }
        catch (Exception exception) { throw new IllegalArgumentException("记录无法复制", exception); }
    }

    private static void put(JSONObject target, String key, Object value) {
        try { target.put(key, value); }
        catch (Exception exception) { throw new IllegalArgumentException("记录无法保存", exception); }
    }
}
