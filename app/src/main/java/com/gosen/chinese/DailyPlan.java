package com.gosen.chinese;

import org.json.JSONArray;
import org.json.JSONObject;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One persisted assignment per module and calendar day, independent of later feed changes. */
public final class DailyPlan {
    private static final String STORE_ID = "__daily_plan__";
    private static final String[] MODULES = {"modern", "classical", "essay"};
    private final LocalDate date;
    private final List<Task> tasks;

    public static final class Task {
        public final String module;
        public final String articleId;
        public final String title;
        public final JSONObject article;

        private Task(String module, String articleId, String title, JSONObject article) {
            this.module = module; this.articleId = articleId; this.title = title; this.article = article;
        }
        public boolean completed(StudyStore store, LocalDate date) {
            return !articleId.isEmpty() && store.completedOn(articleId, date);
        }
    }

    private DailyPlan(LocalDate date, List<Task> tasks) { this.date = date; this.tasks = Collections.unmodifiableList(tasks); }
    public LocalDate date() { return date; }
    public List<Task> tasks() { return tasks; }
    public Task task(String module) { for (Task task : tasks) if (task.module.equals(module)) return task; return null; }
    public int completedCount(StudyStore store) { int total = 0; for (Task task : tasks) if (task.completed(store, date)) total++; return total; }

    public static DailyPlan today(ContentRepository repository, StudyStore store) {
        return forDate(repository.all(), store, LocalDate.now());
    }

    static DailyPlan forDate(List<JSONObject> available, StudyStore store, LocalDate day) {
        Map<String, JSONObject> content = new LinkedHashMap<>();
        for (JSONObject article : available) if (article != null && !article.optString("id").isEmpty()) content.put(article.optString("id"), article);
        JSONObject saved = read(store.draft(STORE_ID, "plan:" + day));
        Map<String, JSONObject> assignments = new HashMap<>();
        if (saved != null && day.toString().equals(saved.optString("date"))) {
            JSONArray values = saved.optJSONArray("tasks");
            if (values != null) for (int i = 0; i < values.length(); i++) {
                JSONObject task = values.optJSONObject(i);
                if (task != null) assignments.put(task.optString("module"), task);
            }
        }
        boolean changed = assignments.size() != MODULES.length;
        Map<String, Long> history = null;
        List<Task> tasks = new ArrayList<>();
        JSONArray serialized = new JSONArray();
        for (String module : MODULES) {
            JSONObject previous = assignments.get(module);
            String id = previous == null ? "" : previous.optString("id");
            String title = previous == null ? "" : previous.optString("title");
            if (id.isEmpty()) {
                if (history == null) history = recentAssignments(store, day);
                JSONObject selected = select(content.values(), module, store, day, history);
                if (selected != null) { id = selected.optString("id"); title = selected.optString("title"); changed = true; }
            }
            JSONObject article = content.get(id);
            if (title.isEmpty() && article != null) title = article.optString("title");
            tasks.add(new Task(module, id, title, article));
            JSONObject record = new JSONObject(); put(record, "module", module); put(record, "id", id); put(record, "title", title); serialized.put(record);
        }
        if (changed || saved == null) {
            JSONObject record = new JSONObject(); put(record, "date", day.toString()); put(record, "tasks", serialized);
            store.putDraft(STORE_ID, "plan:" + day, record.toString());
        }
        return new DailyPlan(day, tasks);
    }

    private static JSONObject select(Iterable<JSONObject> available, String module, StudyStore store, LocalDate day, Map<String, Long> history) {
        JSONObject best = null;
        for (JSONObject candidate : available) {
            if (!module.equals(candidate.optString("module"))) continue;
            if (best == null || preferred(candidate, best, store, day, history)) best = candidate;
        }
        return best;
    }

    private static boolean preferred(JSONObject candidate, JSONObject current, StudyStore store, LocalDate day, Map<String, Long> history) {
        String a = candidate.optString("id"), b = current.optString("id");
        boolean aRead = store.isCompleted(a), bRead = store.isCompleted(b);
        if (aRead != bRead) return !aRead;
        long aLast = history.getOrDefault(a, Long.MIN_VALUE), bLast = history.getOrDefault(b, Long.MIN_VALUE);
        if (aLast != bLast) return aLast < bLast;
        long aScore = hash(day + ":" + a), bScore = hash(day + ":" + b);
        int comparison = Long.compareUnsigned(aScore, bScore);
        return comparison != 0 ? comparison < 0 : a.compareTo(b) < 0;
    }

    private static Map<String, Long> recentAssignments(StudyStore store, LocalDate day) {
        Map<String, Long> history = new HashMap<>();
        for (int previous = 1; previous <= 30; previous++) {
            LocalDate date = day.minusDays(previous);
            JSONObject plan = read(store.draft(STORE_ID, "plan:" + date));
            if (plan == null) continue;
            JSONArray tasks = plan.optJSONArray("tasks");
            if (tasks == null) continue;
            for (int i = 0; i < tasks.length(); i++) {
                JSONObject task = tasks.optJSONObject(i); if (task == null) continue;
                String id = task.optString("id");
                if (!id.isEmpty() && !history.containsKey(id)) history.put(id, date.toEpochDay());
            }
        }
        return history;
    }

    private static long hash(String value) {
        long hash = 0xcbf29ce484222325L;
        for (int i = 0; i < value.length(); i++) { hash ^= value.charAt(i); hash *= 0x100000001b3L; }
        return hash;
    }
    private static JSONObject read(String value) { try { return value.isEmpty() ? null : new JSONObject(value); } catch (Exception exception) { return null; } }
    private static void put(JSONObject object, String key, Object value) { try { object.put(key, value); } catch (Exception exception) { throw new IllegalArgumentException(exception); } }
}
