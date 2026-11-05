package com.gosen.chinese;

import android.app.AlertDialog;
import android.app.Dialog;
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
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.PopupWindow;
import android.widget.RadioGroup;
import android.widget.TextView;
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

/** Native rendering samples for human visual review; interaction assertions live in ReadingActivityTest. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, qualifiers = "w393dp-h852dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ReadingVisualTest {
    @Before public void clearRecords() {
        RuntimeEnvironment.getApplication().getSharedPreferences("gosen_chinese_study_v1", Context.MODE_PRIVATE).edit().clear().commit();
    }

    private ActivityController<ReadingActivity> open(String id) {
        return Robolectric.buildActivity(ReadingActivity.class, new Intent(RuntimeEnvironment.getApplication(), ReadingActivity.class).putExtra("articleId", id)).setup();
    }

    private void layout(ReadingActivity activity) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300));
        View decor = activity.getWindow().getDecorView();
        decor.measure(View.MeasureSpec.makeMeasureSpec(786, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1704, View.MeasureSpec.EXACTLY));
        decor.layout(0, 0, 786, 1704);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300));
    }

    private void snapshot(ReadingActivity activity, String filename, View overlay) throws Exception {
        layout(activity);
        Bitmap bitmap = Bitmap.createBitmap(786, 1704, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap); activity.getWindow().getDecorView().draw(canvas);
        if (overlay != null) {
            View root = overlay.getRootView();
            int width = root.getWidth(), height = root.getHeight(), x = 0, y = 0;
            if (root.getLayoutParams() instanceof WindowManager.LayoutParams) {
                WindowManager.LayoutParams params = (WindowManager.LayoutParams) root.getLayoutParams();
                x = params.x; y = params.y;
                if (width <= 0) width = params.width;
                if (height <= 0) height = params.height;
            }
            if (width <= 0) width = 690;
            if (height <= 0) height = 1000;
            root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
            root.layout(0, 0, width, height);
            canvas.save(); canvas.translate(x, y); root.draw(canvas); canvas.restore();
        }
        File dir = new File("../qa"); assertTrue(dir.exists() || dir.mkdirs());
        try (FileOutputStream output = new FileOutputStream(new File(dir, filename + ".png"))) { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)); }
    }

    @Test public void captureClassicsAndContextBubble() throws Exception {
        new StudyStore(RuntimeEnvironment.getApplication()).putSetting("fontSize", "24");
        try (ActivityController<ReadingActivity> controller = open("classic-01")) {
            ReadingActivity activity = controller.get(); snapshot(activity, "reader-classics-large", null);
            AnnotatedTextView body = activity.content.findViewWithTag("articleBody");
            Spanned text = (Spanned) body.getText();
            ClickableSpan[] spans = text.getSpans(0, text.length(), ClickableSpan.class);
            assertTrue(spans.length > 0); spans[0].onClick(body);
            PopupWindow popup = ReflectionHelpers.getField(activity, "bubble");
            assertNotNull(popup); snapshot(activity, "reader-context-bubble", popup.getContentView());
            popup.getContentView().performClick();
            Dialog dialog = ReflectionHelpers.getField(activity,"characterSheet");
            assertNotNull(dialog); dialog.dismiss();
            click(activity, "背诵"); click(activity, "关键词填空"); snapshot(activity, "reader-recite", null);
        }
    }

    @Test public void captureSubjectiveAnalysisAndEssayTopic() throws Exception {
        try (ActivityController<ReadingActivity> controller = open("modern-lamp")) {
            ReadingActivity activity = controller.get(); click(activity, "练习");
            for (RadioGroup choices : views(activity.content, RadioGroup.class)) choices.check(choices.getChildAt(0).getId());
            for (EditText edit : views(activity.content, EditText.class)) edit.setText("①人物由等待顾客转为关心行人。②灯的意义在互相照应中延续。");
            click(activity, "提交并查看解析"); layout(activity);
            CheckBox point = views(activity.content, CheckBox.class).get(0);
            int[] location = new int[2]; point.getLocationOnScreen(location);
            activity.scroll.scrollTo(0, activity.scroll.getScrollY() + location[1] - 220);
            snapshot(activity, "reader-subjective-analysis", null);
        }
        try (ActivityController<ReadingActivity> controller = open("essay-questions")) {
            ReadingActivity activity = controller.get(); snapshot(activity, "reader-essay-topic", null);
            ((EditText) activity.content.findViewWithTag("essayThought")).setText("答案越容易获得，越要主动判断、继续追问。");
            click(activity, "展开范文与拆解"); snapshot(activity, "reader-essay-example", null);
        }
    }

    @Test public void captureExpandedClassicalTranslationAtLargeFont() throws Exception {
        new StudyStore(RuntimeEnvironment.getApplication()).putSetting("fontSize", "24");
        try (ActivityController<ReadingActivity> controller = open("classical-oil")) {
            ReadingActivity activity = controller.get(); click(activity, "逐句译文");
            String translated = activity.repository.find("classical-oil").getJSONArray("translations").getJSONObject(0).getString("translation");
            TextView translation = named(activity.content, translated); assertNotNull(translation);
            int[] location = new int[2]; ((View) translation.getParent()).getLocationOnScreen(location);
            activity.scroll.scrollTo(0, activity.scroll.getScrollY() + location[1] - 100);
            snapshot(activity, "reader-classical-translations-large", null);
        }
    }

    private void click(ReadingActivity activity, String label) { TextView view = named(activity.content, label); assertNotNull(label, view); view.performClick(); layout(activity); }
    private TextView named(View root, String text) {
        if (root instanceof TextView && !(root instanceof EditText) && text.contentEquals(((TextView) root).getText())) return (TextView) root;
        if (root instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++) { TextView value = named(((ViewGroup) root).getChildAt(i), text); if (value != null) return value; }
        return null;
    }
    private <T extends View> List<T> views(View root, Class<T> type) {
        List<T> result = new ArrayList<>(); if (type.isInstance(root)) result.add(type.cast(root));
        if (root instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++) result.addAll(views(((ViewGroup) root).getChildAt(i), type));
        return result;
    }
}
