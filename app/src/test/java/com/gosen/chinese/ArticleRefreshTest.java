package com.gosen.chinese;

import android.app.AlertDialog;
import android.os.Looper;
import android.widget.Button;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35)
public class ArticleRefreshTest {
    @Before public void resetCache(){
        java.io.File dir=org.robolectric.RuntimeEnvironment.getApplication().getFilesDir();
        new java.io.File(dir,ContentRepository.CACHE_FILE).delete();new java.io.File(dir,ContentRepository.CACHE_FILE+".bak").delete();
    }
    @Test public void libraryButtonRefreshesAndReportsNewContent()throws Exception{
        try(ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class).setup()){
            MainActivity activity=controller.get();activity.repository.close();
            CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
            final String[] response={null};
            activity.repository=new ContentRepository(activity,"https://example.invalid",url->{entered.countDown();release.await();return response[0];},3);
            JSONArray items=new JSONArray();for(JSONObject item:activity.repository.all())items.put(item);
            JSONObject extra=new JSONObject(activity.repository.items("modern").get(0).toString()).put("id","refresh-modern-new");items.put(extra);
            response[0]=new JSONObject().put("schemaVersion",1).put("contentVersion",1).put("bundleVersionCode",3)
                    .put("generatedAt","2026-11-05T00:00:00Z").put("items",items).toString();
            java.util.ArrayList<android.view.View> found=new java.util.ArrayList<>();
            activity.shell.findViewsWithText(found,"篇库",android.view.View.FIND_VIEWS_WITH_TEXT);found.get(0).performClick();
            Button button=activity.content.findViewWithTag("refreshArticles");assertNotNull(button);button.performClick();
            assertTrue(entered.await(2,TimeUnit.SECONDS));assertFalse(button.isEnabled());assertEquals("正在刷新…",button.getText());
            release.countDown();long end=System.currentTimeMillis()+15000;
            while(!button.isEnabled()&&System.currentTimeMillis()<end){shadowOf(Looper.getMainLooper()).idle();Thread.sleep(10);}
            assertTrue(button.isEnabled());assertNotNull(activity.repository.find("refresh-modern-new"));
            AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();assertNotNull(dialog);
            assertEquals("已同步最新文章和题库。",((TextView)dialog.findViewById(android.R.id.message)).getText().toString());
            dialog.dismiss();
        }
    }
    @Test public void failedRefreshReportsFailureAndRetainsExistingContent()throws Exception{
        try(ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class).setup()){
            MainActivity activity=controller.get();activity.repository.close();
            activity.repository=new ContentRepository(activity,"https://example.invalid",url->{throw new java.io.IOException("offline");},3);
            java.util.ArrayList<android.view.View> found=new java.util.ArrayList<>();
            activity.shell.findViewsWithText(found,"篇库",android.view.View.FIND_VIEWS_WITH_TEXT);found.get(0).performClick();
            Button button=activity.content.findViewWithTag("refreshArticles");button.performClick();long end=System.currentTimeMillis()+10000;
            while(!button.isEnabled()&&System.currentTimeMillis()<end){shadowOf(Looper.getMainLooper()).idle();Thread.sleep(10);}
            assertTrue(button.isEnabled());assertEquals(60,activity.repository.items("classics").size());
            AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();assertNotNull(dialog);
            assertTrue(((TextView)dialog.findViewById(android.R.id.message)).getText().toString().contains("检查网络"));dialog.dismiss();
        }
    }
}
