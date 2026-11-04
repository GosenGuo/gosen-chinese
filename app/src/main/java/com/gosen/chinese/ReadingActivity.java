package com.gosen.chinese;

import android.app.AlertDialog;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Reading, contextual annotation, practice, self-assessment and writing in one saved session. */
public class ReadingActivity extends BaseActivity {
    private JSONObject article;
    private String articleId;
    private String page = "body";
    private boolean testMode;
    private boolean showTranslations;
    private boolean reciteRevealed;
    private boolean reciteBlank;
    private int reciteIndex;
    private PopupWindow bubble;
    private AnnotatedTextView bodyView;
    private List<String> pendingEvidence = new ArrayList<>();

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        articleId = getIntent().getStringExtra("articleId");
        article = repository.find(articleId == null ? "" : articleId);
        if (article == null) {
            heading("这篇内容暂时无法打开", "请返回篇库重新选择。");
            content.addView(button("返回篇库", true, this::finish));
            return;
        }
        store.setLastArticle(articleId);
        page = saved == null ? store.draft(articleId, "reader.page") : saved.getString("page", "body");
        if (!Arrays.asList("body", "questions", "analysis", "recite", "essay").contains(page)) page = "body";
        testMode = saved == null ? "true".equals(store.draft(articleId, "reader.test")) : saved.getBoolean("testMode");
        reciteIndex = integer(store.draft(articleId, "recite.index"), 0);
        reciteBlank = "true".equals(store.draft(articleId, "recite.blank"));
        showTranslations = "true".equals(store.draft(articleId, "reader.translations"));
        if (isEssay() && !(page.equals("body") || page.equals("essay"))) page = "body";
        if (page.equals("analysis") && !store.hasAttempt(articleId, testMode)) page = "questions";
        render();
    }

    @Override protected void onPause() {
        savePosition();
        dismissBubble();
        super.onPause();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        savePosition();
        state.putString("page", page);
        state.putBoolean("testMode", testMode);
        super.onSaveInstanceState(state);
    }

    @Override public void onBackPressed() {
        if (bubble != null && bubble.isShowing()) { dismissBubble(); return; }
        if (!page.equals("body")) navigate("body");
        else { savePosition(); super.onBackPressed(); }
    }

    private boolean isEssay() { return article != null && "essay".equals(article.optString("module")); }
    private boolean isClassic() { return article != null && "classics".equals(article.optString("module")); }
    private String modeKey() { return testMode ? "test" : "learning"; }
    private String pageKey() { return modeKey() + ":" + page; }
    private String answerKey(String questionId) { return modeKey() + ":answer:" + questionId; }
    private String selfKey(String questionId, int point) { return modeKey() + ":self:" + questionId + ":" + point; }
    private JSONArray questions() { JSONArray value = article.optJSONArray("questions"); return value == null ? new JSONArray() : value; }

    private void savePosition() {
        if (article == null || scroll == null) return;
        store.putScroll(articleId, pageKey(), scroll.getScrollY());
        store.putDraft(articleId, "reader.page", page);
        store.putDraft(articleId, "reader.test", String.valueOf(testMode));
    }

    private void navigate(String next) {
        savePosition(); dismissKeyboard(); dismissBubble();
        page = next;
        store.putDraft(articleId, "reader.page", next);
        render();
    }

    private void refresh() { savePosition(); render(); }

    private void render() {
        dismissBubble(); bodyView = null; clear();
        // New answer fields must not take focus and move the saved reading viewport.
        content.setFocusableInTouchMode(true);
        content.setDescendantFocusability(ViewGroup.FOCUS_BEFORE_DESCENDANTS);
        content.requestFocus();
        LinearLayout top = row();
        TextView back = text("‹  篇库", 15, GREEN, true);
        back.setPadding(0, dp(12), dp(18), dp(12));
        back.setOnClickListener(v -> { savePosition(); finish(); });
        top.addView(back);
        weighted(top, text(moduleName(article.optString("module")), 13, MUTED, false));
        TextView font = text("字", 18, GREEN, true);
        font.setGravity(Gravity.CENTER); font.setMinWidth(dp(44)); font.setMinHeight(dp(44));
        font.setContentDescription("调整正文字号");
        font.setOnClickListener(v -> chooseFont(this::refresh)); top.addView(font);
        content.addView(top); gap(16);
        heading(article.optString("title"), join(article.optString("author"), article.optString("book")));
        LinearLayout provenance = row();
        String type = article.optString("contentType", article.optString("kind"));
        if (type.equals("prose")) type = "文言文";
        if (type.equals("poem")) type = "古诗词";
        if (!type.isEmpty()) weighted(provenance, text(type, 12, GOLD, true));
        else weighted(provenance, text("", 12, MUTED, false));
        TextView source = text("来源与核验", 12, MUTED, false);
        source.setPadding(dp(10), dp(10), 0, dp(10)); source.setMinHeight(dp(44));
        source.setOnClickListener(v -> sourceInfo(article)); provenance.addView(source);
        content.addView(provenance); gap(14);
        if (!isEssay()) renderTabs();
        if (isEssay()) { if (page.equals("essay")) renderEssay(); else renderEssayPrompt(); }
        else if (page.equals("questions")) renderQuestions();
        else if (page.equals("analysis")) renderAnalysis();
        else if (page.equals("recite")) renderRecitation();
        else renderBody();
        int restore = store.scroll(articleId, pageKey());
        scroll.post(() -> {
            if (bodyView != null && !pendingEvidence.isEmpty()) {
                bodyView.highlight(pendingEvidence);
                String first = pendingEvidence.get(0);
                int offset = article.optString("body").indexOf(first);
                if (offset >= 0 && bodyView.getLayout() != null) {
                    int y = bodyView.getTop() + bodyView.getLayout().getLineTop(bodyView.getLayout().getLineForOffset(offset));
                    scroll.scrollTo(0, Math.max(0, y - dp(80)));
                }
                pendingEvidence.clear();
            } else scroll.scrollTo(0, restore);
        });
        animate(content);
    }

    private void renderTabs() {
        HorizontalScrollView bar = new HorizontalScrollView(this);
        bar.setHorizontalScrollBarEnabled(false);
        LinearLayout tabs = row();
        smallButton(tabs, "原文", page.equals("body"), () -> navigate("body"));
        if (questions().length() > 0) smallButton(tabs, "练习", page.equals("questions"), () -> navigate("questions"));
        if (store.hasAttempt(articleId, testMode)) smallButton(tabs, "解析", page.equals("analysis"), () -> navigate("analysis"));
        if (isClassic()) smallButton(tabs, "背诵", page.equals("recite"), () -> navigate("recite"));
        bar.addView(tabs); content.addView(bar); gap(23);
    }

    private void renderBody() {
        if (!isClassic()) {
            LinearLayout modes = row();
            smallButton(modes, "学习", !testMode, () -> switchMode(false));
            smallButton(modes, "检测", testMode, () -> switchMode(true));
            content.addView(modes); gap(22);
        }
        bodyView = new AnnotatedTextView(this);
        bodyView.setTextColor(INK); bodyView.setTextSize(fontSize());
        bodyView.setLineSpacing(dp(9), 1.22f); bodyView.setIncludeFontPadding(false);
        bodyView.setTypeface(Typeface.create("serif", Typeface.NORMAL));
        boolean hints = !testMode || store.hasAttempt(articleId, true);
        bodyView.bind(article, store, hints, this::showNoteBubble);
        if (hints) bodyView.setOnUnannotatedTapListener(this::showUnannotatedFeedback);
        bodyView.setTag("articleBody"); content.addView(bodyView);
        gap(26);
        JSONArray translations = article.optJSONArray("translations");
        boolean canTranslate = !testMode || store.hasAttempt(articleId, true);
        if (translations != null && translations.length() > 0 && canTranslate) {
            content.addView(button(showTranslations ? "收起逐句译文" : "逐句译文", false, () -> {
                showTranslations = !showTranslations;
                store.putDraft(articleId, "reader.translations", String.valueOf(showTranslations));
                refresh();
            }));
            if (showTranslations) for (int i = 0; i < translations.length(); i++) {
                JSONObject unit = translations.optJSONObject(i);
                if (unit == null) continue;
                LinearLayout box = card(SURFACE);
                box.addView(text(unit.optString("quote"), fontSize() - 2, INK, false));
                margin(box, text(unit.optString("translation"), 16, MUTED, false), 12);
                margin(content, box, 12);
            }
            gap(18);
        }
        if (questions().length() > 0) content.addView(button("开始练习  ·  " + questions().length() + " 题", true, () -> navigate("questions")));
        else if (isClassic()) content.addView(button("逐句背诵", true, () -> navigate("recite")));
        gap(12);
        content.addView(button(store.isCompleted(articleId) ? "已完成本篇" : "完成本篇阅读", false, () -> {
            store.markComplete(articleId); refresh();
        }));
    }

    private void switchMode(boolean next) {
        if (next == testMode) return;
        savePosition(); testMode = next;
        store.putDraft(articleId, "reader.test", String.valueOf(next));
        render();
    }

    private void renderQuestions() {
        LinearLayout mode = row();
        weighted(mode, text(testMode ? "检测模式" : "学习模式", 14, GREEN, true));
        TextView original = text("查看原文 ↗", 14, GREEN, true);
        original.setMinHeight(dp(44)); original.setGravity(Gravity.CENTER_VERTICAL);
        original.setOnClickListener(v -> navigate("body")); mode.addView(original);
        content.addView(mode); gap(16);
        JSONArray questions = questions();
        for (int i = 0; i < questions.length(); i++) {
            JSONObject question = questions.optJSONObject(i);
            if (question == null) continue;
            String id = question.optString("id", "q" + i);
            LinearLayout box = card(SURFACE);
            box.addView(text((i + 1) + "  /  " + typeLabel(question.optString("type"))
                    + "  ·  " + question.optInt("score") + " 分", 12, GOLD, true));
            margin(box, text(question.optString("prompt"), 18, INK, true), 12);
            JSONArray options = question.optJSONArray("options");
            if ("single".equals(question.optString("type")) && options != null) {
                RadioGroup choices = new RadioGroup(this);
                choices.setOrientation(RadioGroup.VERTICAL);
                int selected = integer(store.draft(articleId, answerKey(id)), -1);
                for (int n = 0; n < options.length(); n++) {
                    RadioButton option = new RadioButton(this);
                    option.setId(View.generateViewId()); option.setTag(n);
                    option.setText((char) ('A' + n) + "  " + options.optString(n));
                    option.setTextSize(16); option.setTextColor(INK);
                    option.setPadding(dp(2), dp(11), dp(2), dp(11));
                    choices.addView(option, new RadioGroup.LayoutParams(-1, -2));
                    if (selected == n) choices.check(option.getId());
                }
                choices.setOnCheckedChangeListener((group, checked) -> {
                    View option = group.findViewById(checked);
                    if (option != null) store.putDraft(articleId, answerKey(id), String.valueOf(option.getTag()));
                });
                margin(box, choices, 12);
            } else {
                String hint = "segmentation".equals(question.optString("type")) ? "用 / 标出断句，写下你的判断" : "分点写下你的答案";
                EditText answer = input(hint, store.draft(articleId, answerKey(id)), true);
                answer.setTag("answer:" + id); answer.setContentDescription("第" + (i + 1) + "题作答");
                answer.addTextChangedListener(watcher(value -> store.putDraft(articleId, answerKey(id), value)));
                margin(box, answer, 16);
            }
            margin(content, box, 14);
        }
        gap(24);
        content.addView(button(store.hasAttempt(articleId, testMode) ? "提交本次作答" : "提交并查看解析", true, this::submitAnswers));
        gap(12); content.addView(button("返回原文", false, () -> navigate("body")));
    }

    private JSONObject currentAnswers() {
        JSONObject answers = new JSONObject();
        JSONArray questions = questions();
        for (int i = 0; i < questions.length(); i++) {
            JSONObject question = questions.optJSONObject(i);
            if (question != null) {
                String id = question.optString("id", "q" + i);
                put(answers, id, store.draft(articleId, answerKey(id)));
            }
        }
        return answers;
    }

    private void submitAnswers() {
        JSONObject answers = currentAnswers();
        int missing = 0;
        for (int i = 0; i < questions().length(); i++) {
            JSONObject question = questions().optJSONObject(i);
            if (question != null && answers.optString(question.optString("id", "q" + i)).trim().isEmpty()) missing++;
        }
        if (missing > 0) new AlertDialog.Builder(this).setTitle("还有 " + missing + " 题未作答")
                .setMessage("可以继续作答，也可以保留空题后对照解析。")
                .setNegativeButton("继续作答", null).setPositiveButton("仍然提交", (dialog, which) -> commitAnswers(answers)).show();
        else commitAnswers(answers);
    }

    private void commitAnswers(JSONObject answers) {
        JSONObject previous = store.latestAnswers(articleId, testMode);
        for (int i = 0; i < questions().length(); i++) {
            JSONObject question = questions().optJSONObject(i);
            if (question == null) continue;
            String id = question.optString("id", "q" + i);
            if (!previous.optString(id).equals(answers.optString(id))) {
                JSONArray points = question.optJSONArray("points");
                if (points != null) for (int p = 0; p < points.length(); p++) store.putDraft(articleId, selfKey(id, p), "false");
            }
        }
        store.submit(articleId, answers, testMode);
        navigate("analysis");
    }

    private void renderAnalysis() {
        if (!store.hasAttempt(articleId, testMode)) { renderQuestions(); return; }
        JSONObject first = store.firstAnswers(articleId, testMode);
        JSONObject latest = store.latestAnswers(articleId, testMode);
        content.addView(text((testMode ? "检测" : "学习") + "  ·  参考解析", 14, GREEN, true));
        gap(12);
        int choiceScore = 0, choiceTotal = 0;
        for (int i = 0; i < questions().length(); i++) {
            JSONObject q = questions().optJSONObject(i);
            if (q != null && "single".equals(q.optString("type"))) {
                choiceTotal += q.optInt("score");
                if (integer(latest.optString(q.optString("id", "q" + i)), -1) == q.optInt("answerIndex", -2)) choiceScore += q.optInt("score");
            }
        }
        if (choiceTotal > 0) { content.addView(text("客观题 " + choiceScore + " / " + choiceTotal + " 分", 22, INK, true)); gap(10); }
        for (int i = 0; i < questions().length(); i++) {
            JSONObject question = questions().optJSONObject(i);
            if (question == null) continue;
            String id = question.optString("id", "q" + i);
            LinearLayout box = card(SURFACE);
            box.addView(text((i + 1) + "  " + question.optString("prompt"), 18, INK, true));
            margin(box, text("本次作答\n" + readableAnswer(question, latest.optString(id)), 15, MUTED, false), 16);
            if (!first.optString(id).equals(latest.optString(id))) margin(box,
                    text("首次作答\n" + readableAnswer(question, first.optString(id)), 14, GOLD, false), 12);
            margin(box, text("参考答案", 13, GREEN, true), 18);
            margin(box, text(referenceAnswer(question), 17, INK, false), 9);
            String focus = question.optString("focus");
            if (!focus.isEmpty()) margin(box, text("考查：" + focus, 13, GOLD, false), 14);
            margin(box, text("审题与分析", 13, GREEN, true), 18);
            margin(box, text(question.optString("analysis"), 16, INK, false), 9);
            JSONArray evidence = question.optJSONArray("evidence");
            if (evidence != null && evidence.length() > 0) {
                margin(box, text("原文依据", 13, GREEN, true), 18);
                List<String> quotes = new ArrayList<>();
                for (int n = 0; n < evidence.length(); n++) {
                    String quote = evidence.optString(n);
                    if (!quote.isEmpty()) { quotes.add(quote); margin(box, text("“" + quote + "”", 15, MUTED, false), 10); }
                }
                margin(box, button("回到原文定位", false, () -> { pendingEvidence = quotes; navigate("body"); }), 14);
            }
            JSONArray optionAnalyses = question.optJSONArray("optionAnalyses");
            if (optionAnalyses != null) for (int n = 0; n < optionAnalyses.length(); n++) {
                margin(box, text(optionAnalyses.optString(n), 15, MUTED, false), 12);
            }
            if (!"single".equals(question.optString("type"))) renderSelfAssessment(box, question, id);
            String reviewKey = articleId + ":question:" + id;
            Button collect = button(store.hasReview(reviewKey) ? "已加入错题复习 · 点击移出" : "加入错题复习", false, () -> {});
            collect.setOnClickListener(v -> {
                toggleReview(reviewKey, json("key", reviewKey, "type", "question", "title", article.optString("title") + " · 第" + (questionsIndex(id) + 1) + "题",
                        "prompt", question.optString("prompt"), "answer", referenceAnswer(question) + "\n\n" + question.optString("analysis"),
                        "articleId", articleId, "quote", question.optString("prompt"), "source", article.optString("sourceLabel")));
                collect.setText(store.hasReview(reviewKey) ? "已加入错题复习 · 点击移出" : "加入错题复习");
                animate(collect);
            });
            margin(box, collect, 18); margin(content, box, 16);
        }
        gap(24); content.addView(button("返回题目再练一次", true, () -> navigate("questions")));
        gap(12); content.addView(button("返回原文", false, () -> navigate("body")));
    }

    private void renderSelfAssessment(LinearLayout box, JSONObject question, String id) {
        JSONArray points = question.optJSONArray("points");
        if (points == null || points.length() == 0) return;
        margin(box, text("逐项核对得分点", 13, GREEN, true), 18);
        TextView subtotal = text("", 13, GOLD, true);
        for (int i = 0; i < points.length(); i++) {
            JSONObject point = points.optJSONObject(i);
            if (point == null) continue;
            final int index = i;
            CheckBox check = new CheckBox(this);
            check.setText(point.optString("text") + "  （" + point.optInt("score") + "分）");
            check.setTextSize(15); check.setTextColor(INK); check.setPadding(0, dp(8), 0, dp(8));
            check.setChecked("true".equals(store.draft(articleId, selfKey(id, i))));
            check.setOnCheckedChangeListener((button, checked) -> {
                store.putDraft(articleId, selfKey(id, index), String.valueOf(checked));
                subtotal.setText("主观题自评 " + selfScore(question, id) + " / " + question.optInt("score") + " 分");
            });
            margin(box, check, 8);
        }
        subtotal.setText("主观题自评 " + selfScore(question, id) + " / " + question.optInt("score") + " 分");
        margin(box, subtotal, 10);
    }

    private int selfScore(JSONObject question, String id) {
        int total = 0; JSONArray points = question.optJSONArray("points");
        if (points != null) for (int i = 0; i < points.length(); i++) {
            JSONObject point = points.optJSONObject(i);
            if (point != null && "true".equals(store.draft(articleId, selfKey(id, i)))) total += point.optInt("score");
        }
        return total;
    }

    private int questionsIndex(String id) {
        for (int i = 0; i < questions().length(); i++) {
            JSONObject question = questions().optJSONObject(i);
            if (question != null && id.equals(question.optString("id"))) return i;
        }
        return 0;
    }

    private String readableAnswer(JSONObject question, String value) {
        if (value.isEmpty()) return "未作答";
        if ("single".equals(question.optString("type"))) {
            int selected = integer(value, -1); JSONArray options = question.optJSONArray("options");
            if (options == null || selected < 0 || selected >= options.length()) return "未作答";
            return (char) ('A' + selected) + "  " + options.optString(selected);
        }
        return value;
    }

    private String referenceAnswer(JSONObject question) {
        String answer = question.optString("answer");
        if ("single".equals(question.optString("type"))) {
            int correct = question.optInt("answerIndex", -1);
            JSONArray options = question.optJSONArray("options");
            if (correct >= 0 && options != null && correct < options.length()) return (char) ('A' + correct) + "  " + options.optString(correct);
        }
        return answer;
    }

    private void renderRecitation() {
        List<String> units = reciteUnits();
        if (units.isEmpty()) { content.addView(text("这篇原文暂时无法分句。", 16, MUTED, false)); return; }
        reciteIndex = Math.max(0, Math.min(reciteIndex, units.size() - 1));
        String unit = units.get(reciteIndex);
        content.addView(text("第 " + (reciteIndex + 1) + " / " + units.size() + " 句", 14, GOLD, true)); gap(18);
        LinearLayout modes = row();
        smallButton(modes, "逐句回忆", !reciteBlank, () -> { reciteBlank = false; store.putDraft(articleId, "recite.blank", "false"); reciteRevealed = false; refresh(); });
        smallButton(modes, "关键词填空", reciteBlank, () -> {
            int index = reciteIndex;
            for (int n = 0; n < units.size(); n++) {
                int candidate = (reciteIndex + n) % units.size();
                if (!blankWord(units.get(candidate)).isEmpty()) { index = candidate; break; }
            }
            reciteIndex = index; store.putDraft(articleId, "recite.index", String.valueOf(index));
            reciteBlank = true; store.putDraft(articleId, "recite.blank", "true"); reciteRevealed = false; refresh();
        });
        content.addView(modes); gap(22);
        if (reciteIndex > 0) { content.addView(text("上句：" + units.get(reciteIndex - 1), 14, MUTED, false)); gap(16); }
        String missing = blankWord(unit);
        LinearLayout box = card(SURFACE);
        boolean hasBlank = reciteBlank && !missing.isEmpty();
        String prompt = hasBlank ? replaceFirstLiteral(unit, missing, "____") : "回忆本句，再展开核对";
        box.addView(text(reciteRevealed ? unit : prompt, fontSize() + 1, INK, false));
        if (hasBlank) {
            String key = "recite.answer:" + reciteIndex;
            EditText answer = input("补出空缺字词", store.draft(articleId, key), false);
            answer.addTextChangedListener(watcher(value -> store.putDraft(articleId, key, value)));
            margin(box, answer, 18);
            if (reciteRevealed) margin(box, text("原文：" + missing + "\n" + (missing.equals(store.draft(articleId, key).trim()) ? "与原文一致" : "请对照原文检查字词"), 15, GOLD, true), 12);
        }
        if (reciteRevealed) {
            String translation = translationFor(unit);
            if (!translation.isEmpty()) margin(box, text(translation, 16, MUTED, false), 18);
        }
        margin(content, box, 0); gap(20);
        if (!reciteRevealed) content.addView(button("展开核对", true, () -> { reciteRevealed = true; refresh(); }));
        else {
            content.addView(button(reciteIndex + 1 == units.size() ? "本轮完成" : "已掌握 · 下一句", true, () -> advanceRecitation(units.size())));
            gap(12);
            String key = articleId + ":sentence:" + unit;
            content.addView(button(store.hasReview(key) ? "已加入错句复习" : "还不熟 · 加入错句复习", false, () -> {
                if (!store.hasReview(key)) store.saveReview(json("key", key, "type", "sentence", "title", article.optString("title") + " · 背诵",
                        "prompt", "根据上句回忆下一句：\n" + (reciteIndex > 0 ? units.get(reciteIndex - 1) : article.optString("title") + "的开头"),
                        "answer", unit + "\n" + translationFor(unit), "articleId", articleId, "quote", unit, "source", article.optString("sourceLabel")));
                refresh();
            }));
        }
        gap(16);
        LinearLayout navigation = row();
        smallButton(navigation, "上一句", false, () -> changeReciteIndex(Math.max(0, reciteIndex - 1)));
        smallButton(navigation, "选句", false, () -> chooseReciteSentence(units));
        smallButton(navigation, "下一句", false, () -> changeReciteIndex(Math.min(units.size() - 1, reciteIndex + 1)));
        content.addView(navigation);
    }

    private void advanceRecitation(int count) {
        if (reciteIndex + 1 < count) { changeReciteIndex(reciteIndex + 1); return; }
        new AlertDialog.Builder(this).setTitle("本轮已完成")
                .setMessage("已回忆 " + count + " 句，未掌握的句子可以在复习中继续练习。")
                .setPositiveButton("完成本篇", (dialog, which) -> { store.markComplete(articleId); navigate("body"); })
                .setNeutralButton("再来一轮", (dialog, which) -> changeReciteIndex(0)).setNegativeButton("留在这里", null).show();
    }

    private void changeReciteIndex(int index) {
        reciteIndex = index; reciteRevealed = false;
        store.putDraft(articleId, "recite.index", String.valueOf(index)); refresh();
    }

    private void chooseReciteSentence(List<String> units) {
        String[] labels = new String[units.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = (i + 1) + "  " + units.get(i);
        new AlertDialog.Builder(this).setTitle("选择背诵句段").setItems(labels, (dialog, which) -> changeReciteIndex(which)).show();
    }

    private List<String> reciteUnits() {
        List<String> units = new ArrayList<>();
        String body = article.optString("body");
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < body.length(); i++) {
            char value = body.charAt(i);
            if (value == '\n' || value == '\r') { addReciteUnit(units, current); continue; }
            current.append(value);
            if ("。！？；".indexOf(value) >= 0) {
                while (i + 1 < body.length() && "”’\"」』）》）".indexOf(body.charAt(i + 1)) >= 0) current.append(body.charAt(++i));
                addReciteUnit(units, current);
            }
        }
        addReciteUnit(units, current);
        return units;
    }

    private void addReciteUnit(List<String> units, StringBuilder current) {
        String value = current.toString().trim(); current.setLength(0);
        if (value.isEmpty()) return;
        boolean hasText = false;
        for (int i = 0; i < value.length(); i++) if (Character.isLetterOrDigit(value.charAt(i))) { hasText = true; break; }
        if (hasText) units.add(value);
        else if (!units.isEmpty()) units.set(units.size() - 1, units.get(units.size() - 1) + value);
    }

    private String blankWord(String unit) {
        String choice = "";
        for (AnnotationResolver.ResolvedNote note : AnnotationResolver.resolve(article)) {
            String quote = note.note.optString("quote");
            if (unit.contains(quote) && quote.length() > choice.length() && quote.length() <= 6) choice = quote;
        }
        if (!choice.isEmpty()) return choice;
        return "";
    }

    private void renderEssayPrompt() {
        content.addView(text("作文题目", 15, GREEN, true)); gap(12);
        content.addView(text(article.optString("prompt"), fontSize(), INK, false));
        String requirements = article.optString("requirements");
        if (!requirements.isEmpty()) { gap(16); content.addView(text(requirements, 15, MUTED, false)); }
        gap(26);
        content.addView(text("我的立意或提纲", 17, INK, true)); gap(12);
        EditText thought = input("先写一句核心观点，也可以列出分论点", store.draft(articleId, "essay.thought"), true);
        thought.setTag("essayThought");
        thought.addTextChangedListener(watcher(value -> store.putDraft(articleId, "essay.thought", value)));
        content.addView(thought); gap(22);
        content.addView(button("展开范文与拆解", true, () -> navigate("essay")));
    }

    private void renderEssay() {
        LinearLayout top = row();
        weighted(top, text("范文与拆解", 15, GREEN, true));
        TextView topic = text("回看题目 ↗", 14, GREEN, true); topic.setMinHeight(dp(44)); topic.setGravity(Gravity.CENTER_VERTICAL);
        topic.setOnClickListener(v -> navigate("body")); top.addView(topic); content.addView(top); gap(14);
        String thought = store.draft(articleId, "essay.thought");
        if (!thought.isEmpty()) {
            LinearLayout mine = card(SOFT); mine.addView(text("我的立意", 12, GREEN, true));
            margin(mine, text(thought, 16, INK, false), 10); content.addView(mine); gap(22);
        }
        TextView essay = text(article.optString("body"), fontSize(), INK, false);
        essay.setLineSpacing(dp(8), 1.2f); essay.setTextIsSelectable(true); essay.setTag("essayBody"); content.addView(essay); gap(20);
        content.addView(button("收藏一个段落", false, this::chooseEssayParagraph)); gap(28);
        JSONArray outline = article.optJSONArray("outline");
        if (outline != null && outline.length() > 0) {
            content.addView(text("审题与结构", 20, INK, true)); gap(8);
            for (int i = 0; i < outline.length(); i++) {
                JSONObject part = outline.optJSONObject(i); if (part == null) continue;
                LinearLayout box = card(SURFACE); box.addView(text(part.optString("title"), 16, GREEN, true));
                margin(box, text(part.optString("text"), 16, INK, false), 12); margin(content, box, 12);
            }
        }
        gap(26); content.addView(text("动笔练一小段", 20, INK, true)); gap(12);
        content.addView(text(article.optString("exercise", "从这篇范文选择一种论证方法，写一个自己的段落。"), 16, MUTED, false)); gap(14);
        EditText practice = input("写下你的仿写、分论点或论证段", store.draft(articleId, "essay.practice"), true);
        practice.setTag("essayPractice"); practice.setMinLines(5);
        practice.addTextChangedListener(watcher(value -> store.putDraft(articleId, "essay.practice", value)));
        content.addView(practice); gap(22);
        content.addView(button(store.isCompleted(articleId) ? "今日作文已完成" : "完成今日作文", true, () -> {
            store.markComplete(articleId); dismissKeyboard(); refresh();
        }));
    }

    private void chooseEssayParagraph() {
        List<String> paragraphs = new ArrayList<>();
        for (String value : article.optString("body").split("\\n\\s*\\n|\\n")) if (!value.trim().isEmpty()) paragraphs.add(value.trim());
        String[] labels = new String[paragraphs.size()];
        for (int i = 0; i < labels.length; i++) {
            String paragraph = paragraphs.get(i); labels[i] = (i + 1) + "  " + paragraph.substring(0, Math.min(30, paragraph.length())) + "…";
        }
        new AlertDialog.Builder(this).setTitle("选择要积累的段落").setItems(labels, (dialog, which) -> collectEssayParagraph(paragraphs.get(which), which)).show();
    }

    private void collectEssayParagraph(String paragraph, int index) {
        LinearLayout box = column(); box.setPadding(dp(22), dp(12), dp(22), dp(12));
        box.addView(text(paragraph, 16, INK, false));
        EditText note = input("适用话题、使用条件或我的改写", store.draft(articleId, "essay.collection:" + index), true);
        margin(box, note, 18); ScrollView scroller = new ScrollView(this); scroller.addView(box);
        String key = articleId + ":paragraph:" + index;
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("作文积累")
                .setView(scroller).setNegativeButton("取消", null).setPositiveButton("保存到复习", (window, which) -> {
                    String own = note.getText().toString(); store.putDraft(articleId, "essay.collection:" + index, own);
                    store.saveReview(json("key", key, "type", "writing", "title", article.optString("title") + " · 段落" + (index + 1),
                            "prompt", "回忆这段的表达方法与适用话题。\n" + own,
                            "answer", paragraph + (own.isEmpty() ? "" : "\n\n我的积累：" + own), "articleId", articleId,
                            "quote", paragraph, "source", article.optString("sourceLabel")));
                }).create();
        dialog.show();
    }

    private void showNoteBubble(AnnotationResolver.ResolvedNote resolved, RectF anchor) {
        JSONObject note = resolved.note;
        showTextBubble(note.optString("quote") + "  ·  " + note.optString("meaning"), "点开查看完整释义", anchor,
                () -> showNoteDetail(resolved));
    }

    private void showUnannotatedFeedback(int offset, RectF anchor) {
        String sentence = sentenceAt(offset);
        String translated = translationFor(sentence);
        if (!translated.isEmpty()) {
            String preview = translated.length() > 72 ? translated.substring(0, 72) + "…" : translated;
            showTextBubble("本句参考译文", preview + "\n点开查看完整句段", anchor, () -> {
                AlertDialog.Builder dialog = new AlertDialog.Builder(this).setTitle("本句参考译文")
                        .setMessage(sentence + "\n\n" + translated).setPositiveButton("关闭", null);
                if (!AnnotationResolver.resolve(article).isEmpty()) dialog.setNeutralButton("本篇重点词", (window, which) -> showCollectedNotes());
                dialog.show();
            });
        } else {
            boolean hasNotes = !AnnotationResolver.resolve(article).isEmpty();
            showTextBubble("此处暂未收录注释", hasNotes ? "点开本篇已收录的重点词" : "点开查看内容来源", anchor,
                    hasNotes ? this::showCollectedNotes : () -> sourceInfo(article));
        }
    }

    private void showCollectedNotes() {
        List<AnnotationResolver.ResolvedNote> notes = AnnotationResolver.resolve(article);
        String[] labels = new String[notes.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = notes.get(i).note.optString("quote") + "  ·  " + notes.get(i).note.optString("meaning");
        new AlertDialog.Builder(this).setTitle("本篇重点词").setItems(labels, (dialog, which) -> showNoteDetail(notes.get(which)))
                .setNegativeButton("关闭", null).show();
    }

    private void showTextBubble(String title, String hint, RectF anchor, Runnable action) {
        dismissBubble();
        LinearLayout tip = column();
        tip.setPadding(dp(16), dp(12), dp(16), dp(12)); tip.setBackground(rounded(SURFACE, 14));
        tip.addView(text(title, 16, INK, true));
        margin(tip, text(hint, 12, MUTED, false), 7);
        int maxWidth = Math.min(dp(310), getResources().getDisplayMetrics().widthPixels - dp(32));
        tip.measure(View.MeasureSpec.makeMeasureSpec(maxWidth, View.MeasureSpec.AT_MOST), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int width = Math.max(dp(170), tip.getMeasuredWidth());
        int arrowHeight = dp(8), height = tip.getMeasuredHeight() + arrowHeight;
        Rect visible = new Rect(); shell.getWindowVisibleDisplayFrame(visible);
        if (visible.width() <= 0) visible.set(0, 0, getResources().getDisplayMetrics().widthPixels, getResources().getDisplayMetrics().heightPixels);
        int x = Math.max(visible.left + dp(12), Math.min(Math.round(anchor.centerX() - width / 2f), visible.right - width - dp(12)));
        boolean above = anchor.top - height - dp(5) >= visible.top;
        int y = above ? Math.round(anchor.top - height - dp(4)) : Math.round(anchor.bottom + dp(4));
        float pointerX = Math.max(dp(12), Math.min(width - dp(12), anchor.centerX() - x));
        FrameLayout frame = new FrameLayout(this) {
            final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            @Override protected void onDraw(Canvas canvas) {
                super.onDraw(canvas); paint.setColor(SURFACE); Path pointer = new Path();
                float base = above ? getHeight() - arrowHeight : arrowHeight;
                pointer.moveTo(pointerX - dp(7), base); pointer.lineTo(pointerX, above ? getHeight() : 0);
                pointer.lineTo(pointerX + dp(7), base); pointer.close(); canvas.drawPath(pointer, paint);
            }
        };
        frame.setWillNotDraw(false); frame.setPadding(0, above ? 0 : arrowHeight, 0, above ? arrowHeight : 0);
        frame.addView(tip, new FrameLayout.LayoutParams(-1, -1));
        PopupWindow popup = new PopupWindow(frame, width, height, false);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT)); popup.setElevation(dp(8)); popup.setOutsideTouchable(true);
        if (Build.VERSION.SDK_INT >= 29) popup.setTouchModal(false);
        frame.setOnClickListener(v -> { dismissBubble(); action.run(); });
        bubble = popup; popup.showAtLocation(shell, Gravity.TOP | Gravity.LEFT, x, y); animate(frame);
    }

    private void showNoteDetail(AnnotationResolver.ResolvedNote resolved) {
        JSONObject note = resolved.note;
        LinearLayout box = column(); box.setPadding(dp(24), dp(14), dp(24), dp(22));
        box.addView(text(note.optString("meaning"), 22, INK, true));
        if (!note.optString("detail").isEmpty()) margin(box, text(note.optString("detail"), 16, MUTED, false), 15);
        String sentence = note.optString("sentence");
        if (sentence.isEmpty()) sentence = sentenceAt(resolved.start);
        margin(box, text("所在句", 12, GREEN, true), 23); margin(box, text(sentence, 18, INK, false), 10);
        String translation = note.optString("translation");
        if (translation.isEmpty()) translation = translationFor(sentence);
        if (!translation.isEmpty()) margin(box, text(translation, 16, MUTED, false), 12);
        String key = resolved.key(articleId);
        final String finalSentence = sentence;
        final String finalTranslation = translation;
        Button review = button(store.hasReview(key) ? "已加入复习 · 点击移出" : "加入字词复习", true, () -> {});
        review.setOnClickListener(v -> {
            toggleReview(key, json("key", key, "type", "word", "title", note.optString("quote"),
                    "prompt", finalSentence, "answer", note.optString("meaning") + "\n" + note.optString("detail") + "\n" + finalTranslation,
                    "articleId", articleId, "quote", note.optString("quote"), "source", article.optString("title")));
            review.setText(store.hasReview(key) ? "已加入复习 · 点击移出" : "加入字词复习");
            if (bodyView != null) bodyView.refreshMarks(); animate(review);
        });
        margin(box, review, 24);
        String source = note.optString("source", "本软件参考整理；教材注释请以所用版本为准。");
        margin(box, text(source, 12, MUTED, false), 18);
        ScrollView wrapper = new ScrollView(this); wrapper.addView(box);
        new AlertDialog.Builder(this).setTitle(note.optString("quote")).setView(wrapper).setPositiveButton("关闭", null).show();
    }

    private void toggleReview(String key, JSONObject card) {
        if (store.hasReview(key)) store.removeReview(key); else store.saveReview(card);
    }

    private void dismissBubble() { if (bubble != null) { bubble.dismiss(); bubble = null; } }

    private String sentenceAt(int offset) {
        String body = article.optString("body"); int start = Math.max(0, Math.min(offset, body.length())), end = start;
        while (start > 0 && "。！？；\n".indexOf(body.charAt(start - 1)) < 0) start--;
        while (end < body.length() && "。！？；\n".indexOf(body.charAt(end)) < 0) end++;
        if (end < body.length()) end++;
        return body.substring(start, end).trim();
    }

    private String translationFor(String quote) {
        JSONArray translations = article.optJSONArray("translations");
        if (translations != null) for (int i = 0; i < translations.length(); i++) {
            JSONObject unit = translations.optJSONObject(i); if (unit == null) continue;
            String original = unit.optString("quote");
            if (!original.isEmpty() && !quote.isEmpty() && (original.contains(quote) || quote.contains(original))) return unit.optString("translation");
        }
        return "";
    }

    private static int integer(String value, int fallback) { try { return Integer.parseInt(value); } catch (Exception exception) { return fallback; } }
    private static String join(String first, String second) { return first.isEmpty() ? second : second.isEmpty() ? first : first + "  ·  " + second; }
    private static String replaceFirstLiteral(String text, String target, String replacement) {
        int index = text.indexOf(target); return index < 0 ? text : text.substring(0, index) + replacement + text.substring(index + target.length());
    }
    private static String typeLabel(String type) {
        switch (type) { case "single": return "选择题"; case "translation": return "翻译题"; case "segmentation": return "断句题"; default: return "简答题"; }
    }
}
