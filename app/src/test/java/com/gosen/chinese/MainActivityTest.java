package com.gosen.chinese;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;
import java.io.File;
import java.io.FileOutputStream;
import android.os.Looper;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35, qualifiers="w393dp-h852dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class MainActivityTest {
    @Test public void homeModulesAndSearchOpenRealContent() {
        try(ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class).setup()){
            MainActivity activity=controller.get();
            assertNotNull(find(activity.shell,"拾文"));
            click(activity.shell,"篇库");
            assertNotNull(find(activity.shell,"目录与版本  ↗"));
            EditText search=findInput(activity.shell);
            assertNotNull(search);
            search.setText("赤壁赋");
            TextView title=find(activity.shell,"赤壁赋");
            assertNotNull(title);
            ((View)title.getParent().getParent()).performClick();
            android.content.Intent intent=shadowOf(activity).getNextStartedActivity();
            assertNotNull(intent);
            assertEquals("com.gosen.chinese.ReadingActivity",intent.getComponent().getClassName());
            assertFalse(intent.getStringExtra("articleId").isEmpty());
        }
    }
    @Test public void libraryAndReaderChangesAreVisibleAfterReturning() {
        try(ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class).setup()){
            MainActivity activity=controller.get();
            new StudyStore(activity).markComplete("classic-test");
            controller.pause().resume();
            assertEquals(1,activity.store.completedCount());
            click(activity.shell,"我的");
            assertNull(find(activity.shell,"检查软件更新"));
            assertNotNull(find(activity.shell,"软件版本"));
            assertNotNull(find(activity.shell,"导出学习备份"));
        }
    }
    @Test public void renderPhoneScreens() throws Exception {
        try(ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class).setup()){
            MainActivity activity=controller.get();
            screenshot(activity,"home");
            click(activity.shell,"篇库");screenshot(activity,"library");
            click(activity.shell,"复习");screenshot(activity,"review");
        }
    }
    @Test public void anotherReviewRoundReallyRepeatsTheCompletedCards() {
        try(ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class).setup()){
            MainActivity activity=controller.get();
            activity.store.saveReview(BaseActivity.json("key","test-word","type","word","title","知",
                    "prompt","回忆原句中的词义","answer","知道、了解","articleId","classic-01"));
            click(activity.shell,"复习");
            click(activity.shell,"回忆后，查看答案");
            click(activity.shell,"记住了");
            assertNotNull(find(activity.shell,"本轮已完成"));
            click(activity.shell,"再来一轮");
            assertNotNull(find(activity.shell,"知"));
            assertNotNull(find(activity.shell,"回忆后，查看答案"));
        }
    }
    private static void screenshot(MainActivity activity,String name) throws Exception {
        shadowOf(Looper.getMainLooper()).idle();
        View decor=activity.getWindow().getDecorView();
        decor.measure(View.MeasureSpec.makeMeasureSpec(786,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1704,View.MeasureSpec.EXACTLY));
        decor.layout(0,0,786,1704);
        Bitmap bitmap=Bitmap.createBitmap(786,1704,Bitmap.Config.ARGB_8888);
        decor.draw(new Canvas(bitmap));
        File dir=new File("../qa");assertTrue(dir.exists()||dir.mkdirs());
        try(FileOutputStream output=new FileOutputStream(new File(dir,name+".png"))){assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,output));}
    }
    private static void click(View root,String label){TextView target=find(root,label);assertNotNull(label,target);target.performClick();shadowOf(Looper.getMainLooper()).idle();}
    private static TextView find(View root,String label){
        if(root instanceof TextView&&!(root instanceof EditText)&&label.contentEquals(((TextView)root).getText()))return (TextView)root;
        if(root instanceof ViewGroup){ViewGroup group=(ViewGroup)root;for(int i=0;i<group.getChildCount();i++){TextView found=find(group.getChildAt(i),label);if(found!=null)return found;}}
        return null;
    }
    private static EditText findInput(View root){
        if(root instanceof EditText)return (EditText)root;
        if(root instanceof ViewGroup){ViewGroup group=(ViewGroup)root;for(int i=0;i<group.getChildCount();i++){EditText found=findInput(group.getChildAt(i));if(found!=null)return found;}}
        return null;
    }
}
