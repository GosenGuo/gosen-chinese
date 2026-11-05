package com.gosen.chinese;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Looper;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
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
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, qualifiers = "w393dp-h852dp-notnight-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class AppearanceTest {
    @Before public void clear() {
        RuntimeEnvironment.getApplication().getSharedPreferences("gosen_chinese_study_v1", Context.MODE_PRIVATE).edit().clear().commit();
        RuntimeEnvironment.setQualifiers("w393dp-h852dp-notnight-xhdpi");
    }
    private ActivityController<ReadingActivity> open(String id) {
        ActivityController<ReadingActivity> controller = Robolectric.buildActivity(ReadingActivity.class,
                new Intent(RuntimeEnvironment.getApplication(), ReadingActivity.class).putExtra("articleId", id)).setup();
        settle(controller.get()); return controller;
    }
    private void settle(BaseActivity activity) {
        shadowOf(Looper.getMainLooper()).idle();
        View decor = activity.getWindow().getDecorView();
        decor.measure(View.MeasureSpec.makeMeasureSpec(786, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1704, View.MeasureSpec.EXACTLY));
        decor.layout(0, 0, 786, 1704);
        decor.getViewTreeObserver().dispatchOnGlobalLayout();
        shadowOf(Looper.getMainLooper()).idle();
    }
    private TextView named(View root, String label) {
        if (root instanceof TextView && label.contentEquals(((TextView)root).getText())) return (TextView)root;
        if (root instanceof ViewGroup) for (int i = 0; i < ((ViewGroup)root).getChildCount(); i++) {
            TextView found = named(((ViewGroup)root).getChildAt(i), label); if (found != null) return found;
        }
        return null;
    }
    private void click(BaseActivity activity, String label) { TextView view = named(activity.shell, label); assertNotNull(label, view); view.performClick(); settle(activity); }
    private int themeColor(Context context, int attribute) {
        TypedValue value = new TypedValue(); assertTrue(context.getTheme().resolveAttribute(attribute, value, true)); return value.data;
    }

    @Test public void legacyDarkSettingMigratesToPersistentNativeDarkTheme() {
        StudyStore store = new StudyStore(RuntimeEnvironment.getApplication()); store.putSetting("dark", "true");
        try (ActivityController<ReadingActivity> controller = open("classic-01")) {
            ReadingActivity activity = controller.get(); assertTrue(activity.dark);
            assertEquals("dark", activity.themeMode());
            assertEquals("dark", store.setting("appearanceMode", ""));
            assertEquals(activity.INK, themeColor(activity, android.R.attr.textColorPrimary));
            activity.sourceInfo(activity.repository.find("classic-01"));
            AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
            assertEquals(activity.SURFACE, themeColor(dialog.getContext(), android.R.attr.colorBackground));
            assertEquals(activity.INK, ((TextView)dialog.findViewById(android.R.id.message)).getCurrentTextColor());
            dialog.dismiss();
        }
    }

    @Test public void defaultFollowsSystemWhileExplicitLightAndLegacyFalseRemainLight() {
        RuntimeEnvironment.setQualifiers("w393dp-h852dp-night-xhdpi");
        try (ActivityController<ReadingActivity> controller = open("classic-01")) {
            assertTrue(controller.get().dark); assertEquals("system", controller.get().themeMode());
        }
        StudyStore store = new StudyStore(RuntimeEnvironment.getApplication()); store.putSetting("appearanceMode", "light");
        try (ActivityController<ReadingActivity> controller = open("classic-01")) { assertFalse(controller.get().dark); }
        RuntimeEnvironment.getApplication().getSharedPreferences("gosen_chinese_study_v1", Context.MODE_PRIVATE).edit().clear().commit();
        store.putSetting("dark", "false");
        try (ActivityController<ReadingActivity> controller = open("classic-01")) {
            assertFalse(controller.get().dark); assertEquals("light", controller.get().themeMode());
        }
    }

    @Test public void appearanceChooserHasThreeModesAndPersistsSelection() {
        try (ActivityController<ReadingActivity> controller = open("classic-01")) {
            ReadingActivity activity = controller.get(); activity.chooseAppearance(() -> {});
            AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
            assertEquals(3, dialog.getListView().getAdapter().getCount());
            assertEquals("跟随系统", dialog.getListView().getAdapter().getItem(0));
            dialog.getListView().performItemClick(null, 2, 2);
            assertEquals("dark", new StudyStore(activity).setting("appearanceMode", ""));
            controller.recreate(); assertTrue(controller.get().dark);
        }
    }

    @Test public void changingNativeThemeRetainsQuestionDraftModeAndScroll() {
        try (ActivityController<ReadingActivity> controller = open("modern-lamp")) {
            ReadingActivity activity = controller.get(); click(activity, "练习");
            EditText answer = activity.content.findViewWithTag("answer:lamp-3");
            answer.setText("①以具体动作写人物。②结合原文说明主题。");
            activity.scroll.scrollTo(0, 260); int y = activity.scroll.getScrollY(); assertTrue(y > 0);
            new StudyStore(activity).putSetting("appearanceMode", "dark");
            controller.recreate(); activity = controller.get(); settle(activity);
            assertTrue(activity.dark);
            assertEquals("①以具体动作写人物。②结合原文说明主题。", ((EditText)activity.content.findViewWithTag("answer:lamp-3")).getText().toString());
            assertEquals(y, activity.scroll.getScrollY());
            assertEquals(activity.INK, ((EditText)activity.content.findViewWithTag("answer:lamp-3")).getCurrentTextColor());
        }
    }

    @Test public void systemNightConfigurationRebuildKeepsReaderPositionAndExplicitOverride() {
        try (ActivityController<ReadingActivity> controller = open("classic-01")) {
            ReadingActivity activity = controller.get(); activity.scroll.scrollTo(0, 220); int y = activity.scroll.getScrollY();
            Configuration night = new Configuration(activity.getResources().getConfiguration());
            night.uiMode = (night.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | Configuration.UI_MODE_NIGHT_YES;
            controller.configurationChange(night).visible(); activity = controller.get(); settle(activity);
            assertTrue(activity.dark);
            assertEquals("Saved reading position="+activity.store.scroll("classic-01","learning:body")
                    +", viewport="+activity.scroll.getHeight()+", content="+activity.content.getHeight()
                    +", themeChange="+activity.isThemeChanged(),
                    y, activity.scroll.getScrollY());
            activity.store.putSetting("appearanceMode", "light"); controller.recreate(); activity = controller.get(); settle(activity);
            assertFalse(activity.dark);
        }
    }
}
