package com.gosen.chinese;

import android.content.Context;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, manifest = Config.NONE)
public class StudyStoreTest {
    private Context context;
    private AtomicLong now;
    private StudyStore store;
    private static final long DAY = 86_400_000L;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("gosen_chinese_study_v1", Context.MODE_PRIVATE).edit().clear().commit();
        now = new AtomicLong(LocalDate.of(2026, 11, 4).atTime(12, 0)
                .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli());
        store = new StudyStore(context, now::get);
    }

    private JSONObject answer(String value) throws Exception { return new JSONObject().put("q1", value); }

    private JSONObject card(String key, String meaning) throws Exception {
        return new JSONObject().put("key", key).put("type", "word").put("title", "以")
                .put("prompt", "以吾一日长乎尔").put("answer", meaning).put("articleId", "classic-1")
                .put("quote", "以").put("source", "原句");
    }

    @Test public void answersStayEditableWhileFirstAttemptIsImmutableAndModesAreSeparate() throws Exception {
        JSONObject first = answer("A");
        store.submit("reading-1", first, false);
        first.put("q1", "external mutation");
        store.submit("reading-1", answer("B"), false);
        store.submit("reading-1", answer("C"), true);
        assertEquals("A", store.firstAnswers("reading-1").getString("q1"));
        assertEquals("B", store.latestAnswers("reading-1").getString("q1"));
        assertEquals("C", store.firstAnswers("reading-1", true).getString("q1"));
        assertTrue(store.hasAttempt("reading-1", false));
        assertTrue(store.hasAttempt("reading-1", true));
        assertTrue(store.isCompleted("reading-1"));
        assertEquals(1, store.completedCount());
        JSONObject retrieved = store.firstAnswers("reading-1");
        retrieved.put("q1", "mutated copy");
        assertEquals("A", store.firstAnswers("reading-1").getString("q1"));
    }

    @Test public void draftsPositionsAndSettingsSurviveStoreRecreation() {
        store.putDraft("reading-1", "q1", "先写到这里");
        store.putScroll("reading-1", "body", 483);
        store.putScroll("reading-1", "questions", 122);
        store.putSetting("fontSize", "23");
        store.setLastArticle("reading-1");
        StudyStore recreated = new StudyStore(context, now::get);
        assertEquals("先写到这里", recreated.draft("reading-1", "q1"));
        assertEquals(483, recreated.scroll("reading-1", "body"));
        assertEquals(122, recreated.scroll("reading-1", "questions"));
        assertEquals("23", recreated.setting("fontSize", "20"));
        assertEquals("reading-1", recreated.lastArticle());
        assertEquals(0, recreated.scroll("unread", "body"));
    }

    @Test public void sameCharacterDifferentContextKeepsIndependentReviewSchedules() throws Exception {
        store.saveReview(card("classic-1:note:0:1", "因为"));
        store.saveReview(card("classic-1:note:15:16", "凭借"));
        assertEquals(2, store.dueCount());
        store.gradeReview("classic-1:note:0:1", true);
        assertEquals(1, store.dueCount());
        assertEquals("classic-1:note:15:16", store.dueReviews().get(0).getString("key"));
        store.saveReview(card("classic-1:note:0:1", "更新后的释义"));
        assertEquals(1, store.dueCount());
        now.addAndGet(DAY);
        assertEquals(2, store.dueCount());
        store.removeReview("classic-1:note:15:16");
        assertEquals(1, store.reviews().size());
    }

    @Test public void failedRecallReturnsSoonAndSuccessfulRecallIncreasesInterval() throws Exception {
        String key = "classic-1:note:0:1";
        store.saveReview(card(key, "因为"));
        store.gradeReview(key, false);
        assertEquals(0, store.dueCount());
        now.addAndGet(15 * 60_000L);
        assertEquals(1, store.dueCount());
        store.gradeReview(key, true);
        now.addAndGet(DAY);
        assertEquals(1, store.dueCount());
        store.gradeReview(key, true);
        now.addAndGet(2 * DAY);
        assertEquals(0, store.dueCount());
        now.addAndGet(DAY);
        assertEquals(1, store.dueCount());
    }

    @Test public void exportedBackupRestoresAllLearningRecords() throws Exception {
        store.putDraft("r1", "q1", "我的答案");
        store.putScroll("r1", "body", 120);
        store.submit("r1", answer("初答"), false);
        store.saveReview(card("r1:note:0:1", "因为"));
        store.gradeReview("r1:note:0:1", true);
        store.putSetting("fontSize", "22");
        String backup = store.exportJson();
        context.getSharedPreferences("gosen_chinese_study_v1", Context.MODE_PRIVATE).edit().clear().commit();
        StudyStore restored = new StudyStore(context, now::get);
        restored.importJson(backup);
        StudyStore recreated = new StudyStore(context, now::get);
        assertEquals("我的答案", recreated.draft("r1", "q1"));
        assertEquals(120, recreated.scroll("r1", "body"));
        assertEquals("初答", recreated.firstAnswers("r1").getString("q1"));
        assertEquals(1, recreated.reviews().size());
        assertEquals(0, recreated.dueCount());
        assertEquals("22", recreated.setting("fontSize", "20"));
        assertEquals(1, recreated.completedCount());
    }

    @Test public void malformedOrPartiallyDamagedBackupsNeverReplaceCurrentRecords() throws Exception {
        store.putDraft("r1", "q1", "保留答案");
        String before = store.exportJson();
        String[] invalid = {"{", "{}", new JSONObject(before).put("schemaVersion", 99).toString(),
                new JSONObject(before).put("drafts", "broken").toString(),
                new JSONObject(before).put("scrolls", new JSONObject().put("r1", "broken")).toString(),
                new JSONObject(before).put("reviews", new JSONObject().put("r1:0", card("r1:0", "因为"))).toString()};
        for (String candidate : invalid) {
            try { store.importJson(candidate); fail("Should reject damaged backup"); }
            catch (IllegalArgumentException expected) { /* Expected: state must remain untouched. */ }
            assertEquals(before, store.exportJson());
            assertEquals("保留答案", new StudyStore(context, now::get).draft("r1", "q1"));
        }
    }

    @Test public void streakCountsDistinctDaysAndDoesNotCountRepeatCompletionTwice() {
        store.markComplete("r1");
        store.markComplete("r1");
        assertEquals(1, store.streak());
        assertEquals(1, store.completedCount());
        now.addAndGet(DAY);
        assertEquals(1, store.streak());
        store.markComplete("r2");
        assertEquals(2, store.streak());
        now.addAndGet(2 * DAY);
        assertEquals(0, store.streak());
    }

    @Test public void existingInstancesReadEachOthersChangesWithoutOverwritingUnrelatedRecords() throws Exception {
        StudyStore other = new StudyStore(context, now::get);
        store.putDraft("reading-1", "q1", "阅读页的新答案");
        other.putSetting("fontSize", "22");
        assertEquals("阅读页的新答案", other.draft("reading-1", "q1"));
        assertEquals("22", store.setting("fontSize", "20"));
        other.saveReview(card("reading-1:note:1", "凭借"));
        store.putScroll("reading-1", "body", 360);
        other.submit("reading-1", answer("首次作答"), false);
        store.submit("reading-1", answer("再次作答"), false);
        assertEquals("首次作答", other.firstAnswers("reading-1").getString("q1"));
        assertEquals("再次作答", other.latestAnswers("reading-1").getString("q1"));
        assertEquals(360, other.scroll("reading-1", "body"));
        assertTrue(store.hasReview("reading-1:note:1"));
        assertEquals(1, other.dueCount());
        other.removeReview("reading-1:note:1");
        store.setLastArticle("reading-1");
        assertFalse(store.hasReview("reading-1:note:1"));
        assertEquals("阅读页的新答案", new StudyStore(context, now::get).draft("reading-1", "q1"));
    }

    @Test public void importReplacesLatestStateAndOldInstancesCannotRestoreTheirSnapshots() throws Exception {
        String emptyBackup = store.exportJson();
        StudyStore importer = new StudyStore(context, now::get);
        store.putDraft("old", "q1", "导入前最新答案");
        importer.importJson(emptyBackup);
        JSONObject rollback = new JSONObject(context.getSharedPreferences("gosen_chinese_study_v1", Context.MODE_PRIVATE)
                .getString("previous_import_state", "{}"));
        assertEquals("导入前最新答案", rollback.getJSONObject("drafts").getJSONObject("old").getString("q1"));
        store.putSetting("fontSize", "24");
        importer.putDraft("new", "q1", "导入后的答案");
        assertEquals("", store.draft("old", "q1"));
        assertEquals("导入后的答案", store.draft("new", "q1"));
        assertEquals("24", importer.setting("fontSize", "20"));
        try { store.importJson("{broken"); fail("Corrupt backup must be rejected"); }
        catch (IllegalArgumentException expected) { /* Keep imported state. */ }
        assertEquals("导入后的答案", importer.draft("new", "q1"));
    }

    @Test public void malformedStoredPreferenceRetainsRecoverableBytesAndAllowsFreshState() {
        context.getSharedPreferences("gosen_chinese_study_v1", Context.MODE_PRIVATE)
                .edit().putInt("state", 12345).commit();
        StudyStore recovered = new StudyStore(context, now::get);
        recovered.putDraft("r1", "q1", "恢复后新答案");
        assertEquals("12345", context.getSharedPreferences("gosen_chinese_study_v1", Context.MODE_PRIVATE)
                .getString("unreadable_state", ""));
        assertEquals("恢复后新答案", recovered.draft("r1", "q1"));
    }
}
