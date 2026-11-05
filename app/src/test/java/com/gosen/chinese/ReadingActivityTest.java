package com.gosen.chinese;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Spanned;
import android.text.style.ClickableSpan;
import android.view.View;
import android.view.ViewGroup;
import android.view.MotionEvent;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.PopupWindow;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Switch;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.util.ReflectionHelpers;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class ReadingActivityTest {
    @Before public void clearRecords() {
        RuntimeEnvironment.getApplication().getSharedPreferences("gosen_chinese_study_v1", Context.MODE_PRIVATE).edit().clear().commit();
    }

    private ActivityController<ReadingActivity> open(String id) {
        Intent intent = new Intent(RuntimeEnvironment.getApplication(), ReadingActivity.class).putExtra("articleId", id);
        ActivityController<ReadingActivity> controller = Robolectric.buildActivity(ReadingActivity.class, intent).setup();
        settle(controller.get()); return controller;
    }

    private void settle(ReadingActivity activity) {
        shadowOf(Looper.getMainLooper()).idle();
        View decor = activity.getWindow().getDecorView();
        decor.measure(View.MeasureSpec.makeMeasureSpec(420, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(900, View.MeasureSpec.EXACTLY));
        decor.layout(0, 0, 420, 900);
        shadowOf(Looper.getMainLooper()).idle();
    }

    private TextView named(View root, String text) {
        if (root instanceof TextView && text.contentEquals(((TextView) root).getText())) return (TextView) root;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) { TextView found = named(group.getChildAt(i), text); if (found != null) return found; }
        }
        return null;
    }

    private void click(ReadingActivity activity, String text) {
        TextView found = named(activity.content, text); assertNotNull("Missing control: " + text, found);
        found.performClick(); settle(activity);
    }

    private <T extends View> List<T> instances(View root, Class<T> type) {
        List<T> found = new ArrayList<>();
        if (type.isInstance(root)) found.add(type.cast(root));
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) found.addAll(instances(group.getChildAt(i), type));
        }
        return found;
    }

    private void fillAllAnswers(ReadingActivity activity) {
        for (RadioGroup group : instances(activity.content, RadioGroup.class)) group.check(group.getChildAt(0).getId());
        for (EditText edit : instances(activity.content, EditText.class)) edit.setText("我的分点答案，依据原文分析。");
    }

    @Test public void clickingWordDoesNotCollectUntilDetailButtonIsUsed() {
        ActivityController<ReadingActivity> controller = open("classic-01");
        ReadingActivity activity = controller.get();
        JSONObject article = activity.repository.find("classic-01");
        AnnotationResolver.ResolvedNote first = CharacterAnnotationResolver.resolve(article).get(0);
        String key = first.key("classic-01");
        AnnotatedTextView body = activity.content.findViewWithTag("articleBody");
        Spanned text = (Spanned) body.getText();
        ClickableSpan[] spans = text.getSpans(first.start, first.end, ClickableSpan.class);
        assertFalse(activity.store.hasReview(key));
        spans[0].onClick(body);
        PopupWindow popup = ReflectionHelpers.getField(activity, "bubble");
        assertNotNull(popup); assertTrue(popup.isShowing()); assertFalse(activity.store.hasReview(key));
        assertEquals(first.start, (int) ReflectionHelpers.getField(body, "activeStart"));
        assertEquals(first.end, (int) ReflectionHelpers.getField(body, "activeEnd"));
        popup.getContentView().performClick();
        Dialog detail = ReflectionHelpers.getField(activity,"characterSheet");
        Switch add = detail.findViewById(android.R.id.content).findViewWithTag("characterReview");
        assertNotNull(add); add.performClick();
        assertTrue(activity.store.hasReview(key));
        detail.dismiss(); settle(activity);
        assertEquals(-1, (int) ReflectionHelpers.getField(body, "activeStart"));
        assertEquals(1, ((List<?>) ReflectionHelpers.getField(body, "marked")).size());
        spans[0].onClick(body);
        popup = ReflectionHelpers.getField(activity, "bubble"); popup.getContentView().performClick();
        detail = ReflectionHelpers.getField(activity,"characterSheet");
        Switch remove = detail.findViewById(android.R.id.content).findViewWithTag("characterReview");
        assertNotNull(remove);assertTrue(remove.isChecked()); remove.performClick(); assertFalse(activity.store.hasReview(key));
        detail.dismiss(); settle(activity);
        assertEquals(-1, (int) ReflectionHelpers.getField(body, "activeStart"));
        assertTrue(((List<?>) ReflectionHelpers.getField(body, "marked")).isEmpty());
        controller.pause().stop().destroy();
    }

    @Test public void practiceSubmissionRetainsFirstAnswerAndPersistsSelfAssessment() throws Exception {
        ActivityController<ReadingActivity> controller = open("modern-lamp");
        ReadingActivity activity = controller.get();
        click(activity, "练习"); fillAllAnswers(activity);
        click(activity, "提交并查看解析");
        assertEquals("0", activity.store.firstAnswers("modern-lamp").getString("lamp-1"));
        List<CheckBox> points = instances(activity.content, CheckBox.class);
        assertFalse(points.isEmpty()); points.get(0).setChecked(true);
        controller.recreate(); activity = controller.get(); settle(activity);
        assertTrue(instances(activity.content, CheckBox.class).get(0).isChecked());
        click(activity, "返回题目再练一次");
        RadioGroup choices = instances(activity.content, RadioGroup.class).get(0);
        choices.check(choices.getChildAt(2).getId());
        click(activity, "提交本次作答");
        assertEquals("0", activity.store.firstAnswers("modern-lamp").getString("lamp-1"));
        assertEquals("2", activity.store.latestAnswers("modern-lamp").getString("lamp-1"));
        assertTrue(activity.store.isCompleted("modern-lamp"));
        controller.pause().stop().destroy();
    }

    @Test public void detectingUsesSeparateDrafts() {
        ActivityController<ReadingActivity> controller = open("modern-lamp");
        ReadingActivity activity = controller.get();
        click(activity, "练习"); fillAllAnswers(activity);
        click(activity, "原文"); click(activity, "检测"); click(activity, "练习");
        for (EditText edit : instances(activity.content, EditText.class)) assertEquals("", edit.getText().toString());
        for (RadioGroup group : instances(activity.content, RadioGroup.class)) assertEquals(-1, group.getCheckedRadioButtonId());
        assertFalse(activity.store.hasAttempt("modern-lamp", true));
        click(activity, "原文"); click(activity, "学习"); click(activity, "练习");
        assertFalse(instances(activity.content, EditText.class).get(0).getText().toString().isEmpty());
        assertNotEquals(-1, instances(activity.content, RadioGroup.class).get(0).getCheckedRadioButtonId());
        controller.pause().stop().destroy();
    }

    @Test public void detectingHidesClassicalHintsAndTranslationsUntilSubmission() {
        ActivityController<ReadingActivity> controller = open("classical-oil");
        ReadingActivity activity = controller.get();
        assertNotNull(named(activity.content, "逐句译文"));
        click(activity, "检测");
        assertNull(named(activity.content, "逐句译文"));
        AnnotatedTextView body = activity.content.findViewWithTag("articleBody");
        Spanned text = (Spanned) body.getText();
        assertEquals(0, text.getSpans(0, text.length(), ClickableSpan.class).length);
        click(activity, "练习"); fillAllAnswers(activity); click(activity, "提交并查看解析");
        click(activity, "返回原文");
        assertNotNull(named(activity.content, "逐句译文"));
        body = activity.content.findViewWithTag("articleBody");
        text = (Spanned) body.getText();
        assertTrue(text.getSpans(0, text.length(), ClickableSpan.class).length > 0);
        controller.pause().stop().destroy();
    }

    @Test public void recitationRestoresSentenceAndCollectsOnlyOnRequest() {
        ActivityController<ReadingActivity> controller = open("classic-01");
        ReadingActivity activity = controller.get(); click(activity, "背诵");
        assertNotNull(named(activity.content, "回忆本句，再展开核对"));
        click(activity, "展开核对"); click(activity, "还不熟 · 加入错句复习");
        assertEquals(1, activity.store.reviews().size());
        click(activity, "已掌握 · 下一句");
        assertEquals("1", activity.store.draft("classic-01", "recite.index"));
        controller.recreate(); activity = controller.get(); settle(activity);
        assertNotNull(named(activity.content, "回忆本句，再展开核对"));
        assertEquals("1", activity.store.draft("classic-01", "recite.index"));
        controller.pause().stop().destroy();
    }

    @Test public void originalScrollPositionSurvivesPageSwitchAndRecreation() {
        ActivityController<ReadingActivity> controller = open("classic-01");
        ReadingActivity activity = controller.get();
        activity.scroll.scrollTo(0, 180);
        int position = activity.scroll.getScrollY();
        assertTrue(position > 0);
        click(activity, "背诵"); click(activity, "原文");
        assertEquals(position, activity.scroll.getScrollY());
        controller.recreate(); activity = controller.get(); settle(activity);
        assertEquals(position, activity.scroll.getScrollY());
        controller.pause().stop().destroy();
    }

    @Test public void realCharacterTapShowsOnlyItsMeaningAndDraggingDoesNotOpenFeedback() {
        try (ActivityController<ReadingActivity> controller = open("classical-oil")) {
            ReadingActivity activity = controller.get();
            AnnotatedTextView body = activity.content.findViewWithTag("articleBody");
            AnnotationResolver.ResolvedNote first=CharacterAnnotationResolver.resolve(activity.repository.find("classical-oil")).get(0);
            int offset = first.start;
            touch(body, offset, false); settle(activity);
            PopupWindow popup = ReflectionHelpers.getField(activity, "bubble");
            assertNotNull(popup); assertTrue(popup.isShowing());
            assertNotNull(named(popup.getContentView(), first.note.optString("quote")));
            assertNull(named(popup.getContentView(), "译文"));
            assertEquals(first.start, (int) ReflectionHelpers.getField(body, "activeStart"));
            assertEquals(first.end, (int) ReflectionHelpers.getField(body, "activeEnd"));
            assertTrue(activity.store.reviews().isEmpty());
            popup.dismiss();
            assertEquals(-1, (int) ReflectionHelpers.getField(body, "activeStart"));
            touch(body, offset, true); settle(activity);
            assertNull(ReflectionHelpers.getField(activity, "bubble"));
            assertFalse(popup.isShowing());
            assertTrue(activity.store.reviews().isEmpty());
        }
    }

    @Test public void modernReadingHasNoWordOrSentenceBubblesButKeepsEvidenceHighlighting() {
        try (ActivityController<ReadingActivity> controller = open("modern-lamp")) {
            ReadingActivity activity = controller.get();
            AnnotatedTextView body = activity.content.findViewWithTag("articleBody");
            assertTrue(body.isTextSelectable());
            Spanned text = (Spanned) body.getText();
            assertEquals(0, text.getSpans(0, text.length(), ClickableSpan.class).length);
            touch(body, firstUnannotatedLetter(body), false); settle(activity);
            assertNull(ReflectionHelpers.getField(activity, "bubble"));
            body.highlight(java.util.Collections.singletonList("小桥的路灯坏了第三天"));
            Spanned highlighted = (Spanned) body.getText();
            assertEquals(1, highlighted.getSpans(0, highlighted.length(), android.text.style.BackgroundColorSpan.class).length);
            assertTrue(activity.store.reviews().isEmpty());
        }
    }

    @Test public void incompleteAncientDataClearlyReportsMissingContent() throws Exception {
        try (ActivityController<ReadingActivity> controller = open("classical-oil")) {
            ReadingActivity activity = controller.get();
            JSONObject article = ReflectionHelpers.getField(activity, "article");
            article.put("notes", new org.json.JSONArray()); article.put("translations", new org.json.JSONArray());
            ReflectionHelpers.callInstanceMethod(activity, "refresh"); settle(activity);
            AnnotatedTextView body = activity.content.findViewWithTag("articleBody");
            touch(body, 0, false); settle(activity);
            PopupWindow popup = ReflectionHelpers.getField(activity, "bubble");
            assertNotNull(popup); assertNotNull(named(popup.getContentView(), "此字释义待补充"));
            assertTrue(activity.store.reviews().isEmpty());
        }
    }

    @Test public void tappingTheSameCharacterAgainOpensABottomSheetWithoutCollectingIt() {
        try(ActivityController<ReadingActivity> controller=open("classic-01")){
            ReadingActivity activity=controller.get();AnnotatedTextView body=activity.content.findViewWithTag("articleBody");
            Spanned text=(Spanned)body.getText();ClickableSpan first=text.getSpans(0,text.length(),ClickableSpan.class)[0];
            first.onClick(body);first.onClick(body);settle(activity);
            Dialog sheet=ReflectionHelpers.getField(activity,"characterSheet");assertNotNull(sheet);assertTrue(sheet.isShowing());
            assertEquals(android.view.Gravity.BOTTOM,sheet.getWindow().getAttributes().gravity&android.view.Gravity.VERTICAL_GRAVITY_MASK);
            assertNotNull(named(sheet.getWindow().getDecorView(),"译文"));
            assertTrue(activity.store.reviews().isEmpty());
            sheet.dismiss();settle(activity);assertEquals(-1,(int)ReflectionHelpers.getField(body,"activeStart"));
        }
    }

    private int firstUnannotatedLetter(AnnotatedTextView body) {
        Spanned text = (Spanned) body.getText();
        for (int i = 0; i < text.length(); i++) if (Character.isLetter(text.charAt(i)) && text.getSpans(i, i + 1, ClickableSpan.class).length == 0) return i;
        throw new AssertionError("Fixture needs an unannotated position");
    }

    private void touch(AnnotatedTextView body, int offset, boolean drag) {
        int line = body.getLayout().getLineForOffset(offset);
        float x = body.getTotalPaddingLeft() + body.getLayout().getPrimaryHorizontal(offset) + 2;
        float y = body.getTotalPaddingTop() + (body.getLayout().getLineTop(line) + body.getLayout().getLineBottom(line)) / 2f;
        long time = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, x, y, 0);
        body.dispatchTouchEvent(down); down.recycle();
        if (drag) {
            MotionEvent move = MotionEvent.obtain(time, time + 40, MotionEvent.ACTION_MOVE, x, y + 100, 0);
            body.dispatchTouchEvent(move); move.recycle();
        }
        MotionEvent up = MotionEvent.obtain(time, time + 80, MotionEvent.ACTION_UP, x, drag ? y + 100 : y, 0);
        body.dispatchTouchEvent(up); up.recycle();
    }

    @Test public void essayKeepsTopicThoughtPracticeAndCollectsParagraphWithOwnNotes() {
        ActivityController<ReadingActivity> controller = open("essay-questions");
        ReadingActivity activity = controller.get();
        assertNotNull(named(activity.content, "AI示范作文"));
        assertNull(activity.content.findViewWithTag("essayBody"));
        EditText thought = activity.content.findViewWithTag("essayThought"); thought.setText("工具提供答案，学习者承担判断。");
        click(activity, "展开范文与拆解");
        EditText practice = activity.content.findViewWithTag("essayPractice"); practice.setText("我自己的论证段。");
        click(activity, "收藏一个段落");
        AlertDialog chooser = ShadowAlertDialog.getLatestAlertDialog();
        chooser.getListView().performItemClick(null, 0, 0);
        AlertDialog collection = ShadowAlertDialog.getLatestAlertDialog();
        instances(collection.getWindow().getDecorView(), EditText.class).get(0).setText("适合技术与思考的话题。");
        collection.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        settle(activity);
        assertEquals(1, activity.store.reviews().size());
        assertTrue(activity.store.reviews().get(0).optString("answer").contains("适合技术与思考的话题"));
        controller.recreate(); activity = controller.get(); settle(activity);
        assertEquals("我自己的论证段。", ((EditText) activity.content.findViewWithTag("essayPractice")).getText().toString());
        click(activity, "回看题目 ↗");
        assertEquals("工具提供答案，学习者承担判断。", ((EditText) activity.content.findViewWithTag("essayThought")).getText().toString());
        controller.pause().stop().destroy();
    }
}
