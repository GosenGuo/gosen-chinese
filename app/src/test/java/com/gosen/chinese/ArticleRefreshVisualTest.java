package com.gosen.chinese;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,qualifiers="w393dp-h852dp-notnight-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ArticleRefreshVisualTest {
    @Test public void captureLibraryRefreshButton()throws Exception{
        try(ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class).setup()){
            MainActivity activity=controller.get();ArrayList<View> found=new ArrayList<>();
            activity.shell.findViewsWithText(found,"篇库",View.FIND_VIEWS_WITH_TEXT);found.get(0).performClick();
            View decor=activity.getWindow().getDecorView();
            decor.measure(View.MeasureSpec.makeMeasureSpec(786,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1704,View.MeasureSpec.EXACTLY));
            decor.layout(0,0,786,1704);assertNotNull(activity.content.findViewWithTag("refreshArticles"));
            Bitmap bitmap=Bitmap.createBitmap(786,1704,Bitmap.Config.ARGB_8888);decor.draw(new Canvas(bitmap));
            File dir=new File("../qa");assertTrue(dir.exists()||dir.mkdirs());
            try(FileOutputStream output=new FileOutputStream(new File(dir,"library-refresh.png"))){assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,output));}
        }
    }
}
