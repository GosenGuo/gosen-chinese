package com.gosen.chinese;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Looper;
import android.text.Spanned;
import android.text.style.ClickableSpan;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.*;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.util.ReflectionHelpers;
import java.io.File;
import java.io.FileOutputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,qualifiers="w393dp-h852dp-notnight-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class DarkVisualTest {
    @Before public void darkMode(){
        Context context=RuntimeEnvironment.getApplication();
        context.getSharedPreferences("gosen_chinese_study_v1",Context.MODE_PRIVATE).edit().clear().commit();
        new StudyStore(context).putSetting("appearanceMode","dark");
    }
    private ActivityController<ReadingActivity> open(String id){
        return Robolectric.buildActivity(ReadingActivity.class,new Intent(RuntimeEnvironment.getApplication(),ReadingActivity.class).putExtra("articleId",id)).setup();
    }
    private void layout(BaseActivity activity){
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250));
        View decor=activity.getWindow().getDecorView();
        decor.measure(View.MeasureSpec.makeMeasureSpec(786,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1704,View.MeasureSpec.EXACTLY));
        decor.layout(0,0,786,1704);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250));
    }
    private void snapshot(BaseActivity activity,String name,View overlay)throws Exception{
        layout(activity);Bitmap bitmap=Bitmap.createBitmap(786,1704,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(bitmap);
        activity.getWindow().getDecorView().draw(canvas);
        if(overlay!=null){
            View root=overlay.getRootView();int x=0,y=0,width=root.getWidth(),height=root.getHeight();
            if(root.getLayoutParams() instanceof WindowManager.LayoutParams){
                WindowManager.LayoutParams p=(WindowManager.LayoutParams)root.getLayoutParams();x=p.x;y=p.y;
                if(width<=0)width=p.width;if(height<=0)height=p.height;
                if((p.gravity&android.view.Gravity.HORIZONTAL_GRAVITY_MASK)==android.view.Gravity.CENTER_HORIZONTAL)x=(786-width)/2;
                if((p.gravity&android.view.Gravity.VERTICAL_GRAVITY_MASK)==android.view.Gravity.CENTER_VERTICAL)y=(1704-height)/2;
            }
            if(width<=0)width=690;if(height<=0)height=1000;
            root.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));
            root.layout(0,0,width,height);canvas.save();canvas.translate(x,y);root.draw(canvas);canvas.restore();
        }
        File directory=new File("../qa");assertTrue(directory.exists()||directory.mkdirs());
        try(FileOutputStream output=new FileOutputStream(new File(directory,name+".png"))){assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,output));}
    }
    @Test public void captureDarkHomeSettingsAndNativeDialog()throws Exception{
        try(ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class).setup()){
            MainActivity activity=controller.get();assertTrue(activity.dark);snapshot(activity,"dark-home",null);
            click(activity,"我的");snapshot(activity,"dark-settings",null);
            activity.chooseAppearance(()->{});AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();
            assertNotNull(dialog);snapshot(activity,"dark-appearance-dialog",dialog.getWindow().getDecorView());dialog.dismiss();
        }
    }
    @Test public void captureDarkReaderAndActiveWordBubble()throws Exception{
        try(ActivityController<ReadingActivity> controller=open("classic-01")){
            ReadingActivity activity=controller.get();assertTrue(activity.dark);snapshot(activity,"dark-reader",null);
            AnnotatedTextView body=activity.content.findViewWithTag("articleBody");Spanned content=(Spanned)body.getText();
            ClickableSpan[] spans=content.getSpans(0,content.length(),ClickableSpan.class);assertTrue(spans.length>0);spans[0].onClick(body);
            PopupWindow popup=ReflectionHelpers.getField(activity,"bubble");assertNotNull(popup);
            snapshot(activity,"dark-word-bubble",popup.getContentView());popup.getContentView().performClick();
            AlertDialog detail=ShadowAlertDialog.getLatestAlertDialog();assertNotNull(detail);
            snapshot(activity,"dark-word-detail",detail.getWindow().getDecorView());detail.dismiss();
        }
    }
    @Test public void captureDarkQuestionsAnalysisAndEssay()throws Exception{
        try(ActivityController<ReadingActivity> controller=open("modern-lamp")){
            ReadingActivity activity=controller.get();click(activity,"练习");snapshot(activity,"dark-questions",null);
            for(RadioGroup group:all(activity.content,RadioGroup.class))group.check(group.getChildAt(0).getId());
            for(EditText edit:all(activity.content,EditText.class))edit.setText("①根据原文概括动作。②结合细节说明人物与主题。");
            click(activity,"提交并查看解析");layout(activity);
            CheckBox first=all(activity.content,CheckBox.class).get(0);int[] loc=new int[2];first.getLocationOnScreen(loc);
            activity.scroll.scrollTo(0,activity.scroll.getScrollY()+loc[1]-180);snapshot(activity,"dark-analysis",null);
        }
        try(ActivityController<ReadingActivity> controller=open("essay-questions")){
            ReadingActivity activity=controller.get();snapshot(activity,"dark-essay-topic",null);
            click(activity,"展开范文与拆解");snapshot(activity,"dark-essay-example",null);
        }
    }
    private void click(BaseActivity activity,String text){TextView view=named(activity.shell,text);assertNotNull(text,view);view.performClick();layout(activity);}
    private TextView named(View root,String value){
        if(root instanceof TextView&&!(root instanceof EditText)&&value.contentEquals(((TextView)root).getText()))return (TextView)root;
        if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){TextView found=named(((ViewGroup)root).getChildAt(i),value);if(found!=null)return found;}
        return null;
    }
    private <T extends View> List<T> all(View root,Class<T> type){
        List<T> views=new ArrayList<>();if(type.isInstance(root))views.add(type.cast(root));
        if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++)views.addAll(all(((ViewGroup)root).getChildAt(i),type));
        return views;
    }
}
