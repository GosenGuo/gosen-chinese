package com.gosen.chinese;

import android.content.Context;
import android.content.Intent;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,qualifiers="w393dp-h852dp-xhdpi")
public class DailyHomeIntegrationTest {
    @Before public void clear(){
        RuntimeEnvironment.getApplication().getSharedPreferences("gosen_chinese_study_v1",Context.MODE_PRIVATE).edit().clear().commit();
    }
    private void settle(BaseActivity activity){
        shadowOf(Looper.getMainLooper()).idle();
        View decor=activity.getWindow().getDecorView();
        decor.measure(View.MeasureSpec.makeMeasureSpec(786,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1704,View.MeasureSpec.EXACTLY));
        decor.layout(0,0,786,1704);shadowOf(Looper.getMainLooper()).idle();
    }
    @Test public void threeDailyCardsOpenTheirStableAssignmentsAndReturningReflectsCompletion(){
        try(ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class).setup()){
            MainActivity activity=controller.get();settle(activity);
            assertEquals("0 / 3",((TextView)activity.content.findViewWithTag("dailyProgress")).getText().toString());
            DailyPlan plan=DailyPlan.today(activity.repository,activity.store);
            for(String module:new String[]{"modern","classical","essay"}){
                View card=activity.content.findViewWithTag("daily:"+module);
                assertNotNull(module,card);assertTrue(card.getContentDescription().toString().contains("今天未完成"));
                card.performClick();Intent intent=shadowOf(activity).getNextStartedActivity();assertNotNull(intent);
                assertEquals(plan.task(module).articleId,intent.getStringExtra("articleId"));
                new StudyStore(activity).markComplete(plan.task(module).articleId);
            }
            controller.pause().resume();activity=controller.get();settle(activity);
            assertEquals("3 / 3",((TextView)activity.content.findViewWithTag("dailyProgress")).getText().toString());
            for(String module:new String[]{"modern","classical","essay"})
                assertTrue(activity.content.findViewWithTag("daily:"+module).getContentDescription().toString().contains("今天已完成"));
            controller.recreate();activity=controller.get();settle(activity);
            DailyPlan after=DailyPlan.today(activity.repository,activity.store);
            for(String module:new String[]{"modern","classical","essay"})assertEquals(plan.task(module).articleId,after.task(module).articleId);
            assertEquals("3 / 3",((TextView)activity.content.findViewWithTag("dailyProgress")).getText().toString());
        }
    }
}
