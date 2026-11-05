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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE)
public class DailyPlanTest {
    private static final LocalDate DAY = LocalDate.of(2026,11,4);
    private Context context;
    private AtomicLong clock;
    private StudyStore store;

    @Before public void setUp(){
        context=RuntimeEnvironment.getApplication();
        context.getSharedPreferences("gosen_chinese_study_v1",Context.MODE_PRIVATE).edit().clear().commit();
        clock=new AtomicLong(DAY.atTime(12,0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli());
        store=new StudyStore(context,clock::get);
    }
    private JSONObject item(String id,String module) throws Exception {return new JSONObject().put("id",id).put("module",module).put("title",id+"的题目").put("body","可离线学习的正文");}
    private List<JSONObject> material(int count) throws Exception {
        List<JSONObject> result=new ArrayList<>();
        for(String module:Arrays.asList("modern","classical","essay"))for(int i=0;i<count;i++)result.add(item(module+"-"+i,module));
        return result;
    }
    private List<String> ids(DailyPlan plan){List<String> ids=new ArrayList<>();for(DailyPlan.Task task:plan.tasks())ids.add(task.articleId);return ids;}
    private void advanceTo(LocalDate date){clock.set(date.atTime(12,0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli());}

    @Test public void eachDayHasExactlyOneAssignmentPerRequiredModule() throws Exception {
        DailyPlan plan=DailyPlan.forDate(material(3),store,DAY);
        assertEquals(3,plan.tasks().size());
        for(String module:Arrays.asList("modern","classical","essay")){
            DailyPlan.Task task=plan.task(module);assertNotNull(task);assertNotNull(task.article);
            assertEquals(module,task.article.optString("module"));assertFalse(task.articleId.isEmpty());
        }
        assertEquals(0,plan.completedCount(store));
    }

    @Test public void restartingSwitchingModulesAndAppendingFeedCannotChangeTodaysAssignments() throws Exception {
        List<JSONObject> available=material(3);
        DailyPlan first=DailyPlan.forDate(available,store,DAY);List<String> expected=ids(first);
        store.markComplete(first.task("modern").articleId);
        Collections.reverse(available);available.add(item("modern-new","modern"));available.add(item("essay-new","essay"));
        StudyStore reopened=new StudyStore(context,clock::get);
        DailyPlan afterUpdate=DailyPlan.forDate(available,reopened,DAY);
        assertEquals(expected,ids(afterUpdate));assertEquals(1,afterUpdate.completedCount(reopened));
        assertEquals(expected,ids(DailyPlan.forDate(available,reopened,DAY)));
    }

    @Test public void completedYesterdayNeverChecksSameArticleOffToday() throws Exception {
        List<JSONObject> available=material(1);
        DailyPlan yesterday=DailyPlan.forDate(available,store,DAY);
        for(DailyPlan.Task task:yesterday.tasks())store.markComplete(task.articleId);
        assertEquals(3,yesterday.completedCount(store));
        advanceTo(DAY.plusDays(1));
        DailyPlan today=DailyPlan.forDate(available,new StudyStore(context,clock::get),DAY.plusDays(1));
        assertEquals(ids(yesterday),ids(today));assertEquals(0,today.completedCount(store));
        for(DailyPlan.Task task:today.tasks())assertTrue(store.isCompleted(task.articleId));
        store.markComplete(today.task("modern").articleId);assertEquals(1,today.completedCount(store));
        store.submit(today.task("classical").articleId,new JSONObject().put("q1","作答"),false);assertEquals(2,today.completedCount(store));
        store.markComplete(today.task("essay").articleId);assertEquals(3,today.completedCount(store));
        assertTrue(store.completedOn(today.task("essay").articleId,DAY));
        assertTrue(store.completedOn(today.task("essay").articleId,DAY.plusDays(1)));
    }

    @Test public void unreadAndNewFeedArticlesParticipateInLaterDays() throws Exception {
        List<JSONObject> available=material(2);
        DailyPlan first=DailyPlan.forDate(available,store,DAY);
        for(DailyPlan.Task task:first.tasks())store.markComplete(task.articleId);
        advanceTo(DAY.plusDays(1));DailyPlan second=DailyPlan.forDate(available,store,DAY.plusDays(1));
        for(String module:Arrays.asList("modern","classical","essay"))assertNotEquals(first.task(module).articleId,second.task(module).articleId);
        for(DailyPlan.Task task:second.tasks())store.markComplete(task.articleId);
        available.add(item("modern-new","modern"));
        advanceTo(DAY.plusDays(2));DailyPlan third=DailyPlan.forDate(available,store,DAY.plusDays(2));
        assertEquals("modern-new",third.task("modern").articleId);
        assertNotEquals(second.task("essay").articleId,third.task("essay").articleId);
    }

    @Test public void unfinishedAssignmentsRotateAcrossDaysRatherThanRepeatingOneFixedArticle() throws Exception {
        List<JSONObject> available=material(3);
        DailyPlan first=DailyPlan.forDate(available,store,DAY);
        advanceTo(DAY.plusDays(1));DailyPlan second=DailyPlan.forDate(available,store,DAY.plusDays(1));
        advanceTo(DAY.plusDays(2));DailyPlan third=DailyPlan.forDate(available,store,DAY.plusDays(2));
        for(String module:Arrays.asList("modern","classical","essay")){
            assertNotEquals(first.task(module).articleId,second.task(module).articleId);
            assertNotEquals(first.task(module).articleId,third.task(module).articleId);
            assertNotEquals(second.task(module).articleId,third.task(module).articleId);
        }
    }

    @Test public void backupKeepsFixedPlanAndLegacyCompletedRecordsDoNotInventTodayCompletion() throws Exception {
        List<JSONObject> available=material(2);DailyPlan first=DailyPlan.forDate(available,store,DAY);List<String> expected=ids(first);
        String backup=store.exportJson();
        context.getSharedPreferences("gosen_chinese_study_v1",Context.MODE_PRIVATE).edit().clear().commit();
        StudyStore restored=new StudyStore(context,clock::get);restored.importJson(backup);
        available.add(item("another-modern","modern"));
        assertEquals(expected,ids(DailyPlan.forDate(available,restored,DAY)));
        JSONObject legacy=new JSONObject(restored.exportJson());
        legacy.getJSONObject("completed").put(first.task("essay").articleId,clock.get()-86400000L);
        restored.importJson(legacy.toString());
        assertTrue(restored.isCompleted(first.task("essay").articleId));
        assertFalse(restored.completedToday(first.task("essay").articleId));
        assertEquals(0,DailyPlan.forDate(available,restored,DAY).completedCount(restored));
    }
}
