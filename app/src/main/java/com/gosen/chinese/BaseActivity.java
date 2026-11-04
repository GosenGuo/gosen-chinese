package com.gosen.chinese;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import org.json.JSONObject;

/** Shared native controls and restrained paper/ink palette. */
public abstract class BaseActivity extends Activity {
    protected ContentRepository repository;
    protected StudyStore store;
    protected LinearLayout shell, content;
    protected ScrollView scroll;
    protected int PAPER,SURFACE,INK,MUTED,GREEN,SOFT,LINE,GOLD,RED;
    protected boolean dark;

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        store = new StudyStore(this);
        repository = new ContentRepository(this);
        buildShell();
    }

    protected void buildShell() {
        dark = "true".equals(store.setting("dark", "false"));
        PAPER = color(dark ? "#151C18" : "#F7F4ED");
        SURFACE = color(dark ? "#202B24" : "#FFFEFA");
        INK = color(dark ? "#E9EDE5" : "#29342D");
        MUTED = color(dark ? "#A3B1A5" : "#798176");
        GREEN = color(dark ? "#A5CCAD" : "#315F4A");
        SOFT = color(dark ? "#2C4031" : "#E7EDE1");
        LINE = color(dark ? "#3C493F" : "#E5E3D9");
        GOLD = color(dark ? "#D1BA88" : "#937647");
        RED = color(dark ? "#E4A495" : "#A85648");
        getWindow().setStatusBarColor(PAPER);
        getWindow().setNavigationBarColor(PAPER);
        getWindow().getDecorView().setSystemUiVisibility(dark ? 0 :
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        if (android.os.Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
        shell = column();
        shell.setBackgroundColor(PAPER);
        shell.setOnApplyWindowInsetsListener((view, insets) -> {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets safe = insets.getInsets(WindowInsets.Type.systemBars()
                        | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                view.setPadding(safe.left, safe.top, safe.right, safe.bottom);
            } else view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        content = column();
        content.setPadding(dp(24), dp(16), dp(24), dp(32));
        scroll.addView(content);
        shell.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(shell);
    }

    protected void clear() { content.removeAllViews(); scroll.scrollTo(0, 0); }
    protected int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    protected int color(String hex) { return Color.parseColor(hex); }
    protected LinearLayout column() { LinearLayout view = new LinearLayout(this); view.setOrientation(LinearLayout.VERTICAL); return view; }
    protected LinearLayout row() { LinearLayout view = new LinearLayout(this); view.setOrientation(LinearLayout.HORIZONTAL); view.setGravity(Gravity.CENTER_VERTICAL); return view; }
    protected GradientDrawable rounded(int color, int radius) { GradientDrawable drawable = new GradientDrawable(); drawable.setColor(color); drawable.setCornerRadius(dp(radius)); return drawable; }
    protected LinearLayout card(int background) { LinearLayout box = column(); box.setPadding(dp(20),dp(20),dp(20),dp(20)); box.setBackground(rounded(background,18)); return box; }
    protected TextView text(String value,float size,int color,boolean bold) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(color);
        view.setIncludeFontPadding(false); view.setLineSpacing(dp(3),1.13f);
        view.setTypeface(Typeface.DEFAULT,bold ? Typeface.BOLD : Typeface.NORMAL); return view;
    }
    protected void gap(int size) { Space space = new Space(this); content.addView(space,new LinearLayout.LayoutParams(1,dp(size))); }
    protected void margin(LinearLayout parent,View child,int top) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1,-2); params.topMargin=dp(top); parent.addView(child,params);
    }
    protected void weighted(LinearLayout parent,View child) { parent.addView(child,new LinearLayout.LayoutParams(0,-2,1)); }
    protected void heading(String title,String subtitle) { content.addView(text(title,28,INK,true)); if(!subtitle.isEmpty()) margin(content,text(subtitle,13,MUTED,false),10); gap(25); }
    protected Button button(String label,boolean primary,Runnable action) {
        Button view = new Button(this); view.setText(label); view.setAllCaps(false); view.setTextSize(15);
        view.setTextColor(primary ? PAPER : GREEN); view.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        view.setBackground(rounded(primary ? GREEN : SOFT,13)); view.setPadding(dp(16),dp(12),dp(16),dp(12));
        view.setMinHeight(dp(49)); view.setMinimumHeight(dp(49)); view.setStateListAnimator(null);
        view.setOnClickListener(v -> action.run()); return view;
    }
    protected void smallButton(LinearLayout parent,String title,boolean selected,Runnable action) {
        TextView view=text(title,13,selected ? PAPER : GREEN,selected); view.setGravity(Gravity.CENTER);
        view.setPadding(dp(15),dp(11),dp(15),dp(11)); view.setMinHeight(dp(44));
        view.setBackground(rounded(selected ? GREEN : SOFT,20)); view.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-2,-2);params.rightMargin=dp(8);parent.addView(view,params);
    }
    protected EditText input(String hint,String value,boolean multi) {
        EditText edit=new EditText(this);edit.setHint(hint);edit.setText(value);edit.setTextColor(INK);edit.setHintTextColor(MUTED);
        edit.setTextSize(16);edit.setPadding(dp(15),dp(13),dp(15),dp(13));edit.setBackground(rounded(SURFACE,12));
        edit.setGravity(Gravity.TOP|Gravity.LEFT); edit.setInputType(android.text.InputType.TYPE_CLASS_TEXT |
                (multi ? android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES : 0));
        edit.setMinLines(multi ? 3 : 1);return edit;
    }
    protected interface Change { void accept(String value); }
    protected TextWatcher watcher(Change change) {
        return new TextWatcher() {
            public void beforeTextChanged(CharSequence s,int a,int c,int after) {}
            public void onTextChanged(CharSequence s,int a,int b,int c) {}
            public void afterTextChanged(Editable value) { change.accept(value.toString()); }
        };
    }
    protected void info(String title,String message) {
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton("知道了",null).show();
        TextView body=dialog.findViewById(android.R.id.message);
        if(body!=null){
            android.text.util.Linkify.addLinks(body,android.text.util.Linkify.WEB_URLS);
            body.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
        }
    }
    protected void sourceInfo(JSONObject article) {
        String details=article.optString("sourceLabel")+"\n\n"+article.optString("verification")+"\n\n"+article.optString("sourceUrl");
        info("内容来源",details.trim());
    }
    protected int fontSize() { try { return Math.max(18,Math.min(24,Integer.parseInt(store.setting("fontSize","20")))); } catch(Exception e){return 20;} }
    protected void chooseFont(Runnable refresh) {
        String[] labels={"18 · 紧凑","20 · 标准","22 · 舒展","24 · 大字"};int[] sizes={18,20,22,24};
        new AlertDialog.Builder(this).setTitle("正文字号").setSingleChoiceItems(labels,(fontSize()-18)/2,(dialog,which)->{
            store.putSetting("fontSize",String.valueOf(sizes[which]));dialog.dismiss();refresh.run();
        }).setNegativeButton("取消",null).show();
    }
    protected void dismissKeyboard() { ((InputMethodManager)getSystemService(Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(shell.getWindowToken(),0); }
    protected void animate(View view) { if(android.animation.ValueAnimator.areAnimatorsEnabled()) {view.setAlpha(.4f);view.animate().alpha(1).setDuration(160).start();} }
    protected String moduleName(String key) {
        switch(key) {case "classics":return "古诗文";case "modern":return "现代文";case "classical":return "文言阅读";case "essay":return "每日作文";default:return "篇库";}
    }
    protected static JSONObject json(Object... values) { JSONObject result=new JSONObject();for(int i=0;i+1<values.length;i+=2)put(result,String.valueOf(values[i]),values[i+1]);return result; }
    protected static void put(JSONObject object,String key,Object value) { try {object.put(key,value);}catch(Exception e){throw new IllegalArgumentException(e);} }
}
