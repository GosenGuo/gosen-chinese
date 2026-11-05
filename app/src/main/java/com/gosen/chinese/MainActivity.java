package com.gosen.chinese;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

public class MainActivity extends BaseActivity {
    private LinearLayout navigation;
    private String page="home", module="classics", filter="";
    private final Set<String> round=new HashSet<>();
    private final List<JSONObject> roundCards=new ArrayList<>();
    private AppUpdateManager updater;
    private ContentRefillManager refill;
    private boolean initialized;
    private boolean resumed;
    private boolean manualRefreshInProgress;
    private LinearLayout libraryList;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable contentPoll = new Runnable() {
        @Override public void run() {
            if (!resumed) return;
            repository.checkForUpdates((success, changed) -> {
                if (changed && resumed && !isFinishing() && !isDestroyed()) refreshContentViews();
                if (resumed && refill != null) refill.checkInventory(repository,store);
            });
            mainHandler.postDelayed(this,15L*60L*1000L);
        }
    };
    private final Runnable dayRollover = new Runnable() {
        @Override public void run() { if (resumed) { if (page.equals("home")) refreshContentViews(); scheduleRollover(); } }
    };

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        if(saved!=null){page=saved.getString("page","home");module=saved.getString("module","classics");filter=saved.getString("filter","");}
        updater=new AppUpdateManager(this);
        refill=new ContentRefillManager(this);
        initialized=true; render(); updater.checkForUpdates();
    }
    @Override protected void onResume(){
        super.onResume();
        if(isThemeChanged())return;
        resumed=true;
        if(initialized){
            // ReadingActivity writes the same store; always reload before the next mutation.
            store=new StudyStore(this);
            int y=scroll.getScrollY();render();scroll.post(()->scroll.scrollTo(0,y));
            updater.resumePendingInstall();
            updater.checkForUpdates();
            repository.checkForUpdates((success, changed) -> {
                if(changed && resumed && !isFinishing() && !isDestroyed()) refreshContentViews();
                if(resumed && refill!=null)refill.checkInventory(repository,store);
            });
        }
        scheduleRollover();
        mainHandler.removeCallbacks(contentPoll);
        mainHandler.postDelayed(contentPoll,15L*60L*1000L);
    }
    @Override protected void onPause(){resumed=false;mainHandler.removeCallbacks(dayRollover);mainHandler.removeCallbacks(contentPoll);super.onPause();}
    @Override protected void onDestroy(){mainHandler.removeCallbacks(dayRollover);mainHandler.removeCallbacks(contentPoll);if(updater!=null)updater.close();if(refill!=null)refill.close();if(repository!=null)repository.close();super.onDestroy();}
    @Override protected void onSaveInstanceState(Bundle state){
        state.putString("page",page);state.putString("module",module);state.putString("filter",filter);super.onSaveInstanceState(state);
    }
    private void scheduleRollover(){
        mainHandler.removeCallbacks(dayRollover);
        long next=LocalDate.now().plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        mainHandler.postDelayed(dayRollover,Math.max(1000,next-System.currentTimeMillis()+100));
    }
    private void refreshContentViews(){
        int y=scroll.getScrollY();
        if(page.equals("home"))home();
        else if(page.equals("library") && libraryList!=null)populate(libraryList);
        else return;
        scroll.post(()->scroll.scrollTo(0,y));
    }
    private void render(){
        switch(page){case "library":library();break;case "review":review();break;case "allReviews":allReviews();break;case "settings":settings();break;default:home();}
    }
    private void screen(String name){
        dismissKeyboard();page=name;clear();
        if(navigation!=null)shell.removeView(navigation);
        navigation=row();navigation.setPadding(dp(12),dp(8),dp(12),dp(10));
        navigation.setBackgroundColor(PAPER);
        shell.addView(navigation,new LinearLayout.LayoutParams(-1,-2));
        nav("今日","home",this::home);nav("篇库","library",this::library);
        nav("复习","review",()->{round.clear();roundCards.clear();review();});nav("我的","settings",this::settings);
    }
    private void nav(String title,String target,Runnable action){
        boolean selected=page.equals(target)||(page.equals("allReviews")&&target.equals("review"));
        TextView link=text(title,14,selected?GREEN:MUTED,selected);
        link.setGravity(Gravity.CENTER);link.setMinHeight(dp(46));
        link.setBackground(rounded(selected?SOFT:PAPER,16));link.setOnClickListener(v->action.run());
        navigation.addView(link,new LinearLayout.LayoutParams(0,-2,1));
    }
    private void open(JSONObject article){
        store.setLastArticle(article.optString("id"));
        startActivity(new Intent().setClassName(this,"com.gosen.chinese.ReadingActivity").putExtra("articleId",article.optString("id")));
    }
    private void home(){
        screen("home");
        LinearLayout mast=row();weighted(mast,text("拾文",28,INK,true));
        mast.addView(text(LocalDate.now().format(DateTimeFormatter.ofPattern("M月d日  E",Locale.CHINA)),12,MUTED,false));
        content.addView(mast);gap(25);
        DailyPlan plan=DailyPlan.today(repository,store);
        int completed=plan.completedCount(store);
        LinearLayout progress=row();weighted(progress,text("今日学习",19,INK,true));
        TextView counter=text(completed+" / 3",19,GREEN,true);counter.setTag("dailyProgress");progress.addView(counter);content.addView(progress);
        ProgressBar track=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);
        track.setMax(3);track.setProgress(completed);track.setProgressTintList(ColorStateList.valueOf(GREEN));
        track.setProgressBackgroundTintList(ColorStateList.valueOf(LINE));
        LinearLayout.LayoutParams trackParams=new LinearLayout.LayoutParams(-1,dp(5));trackParams.topMargin=dp(14);content.addView(track,trackParams);gap(20);
        for(DailyPlan.Task task:plan.tasks())dailyCard(task,plan);
        gap(8);
        LinearLayout extras=row();
        smallButton(extras,"古诗文 · "+repository.items("classics").size()+"篇",false,()->{module="classics";filter="";library();});
        smallButton(extras,"复习 · "+store.dueCount(),false,()->{round.clear();roundCards.clear();review();});
        content.addView(extras);
        JSONObject recent=repository.find(store.lastArticle());
        boolean alreadyToday=false;for(DailyPlan.Task task:plan.tasks())if(task.articleId.equals(store.lastArticle()))alreadyToday=true;
        if(recent!=null && !alreadyToday){
            gap(23);LinearLayout resume=card(SURFACE);resume.addView(text("继续上次阅读",12,MUTED,false));
            margin(resume,text(recent.optString("title")+"  →",17,INK,true),10);
            resume.setOnClickListener(v->open(recent));content.addView(resume);
        }
        gap(24);LinearLayout stats=row();stat(stats,store.completedCount(),"已学篇目");stat(stats,store.dueCount(),"待复习");stat(stats,store.streak(),"连续学习");content.addView(stats);
        if(!repository.error().isEmpty()){gap(20);content.addView(text(repository.error(),13,RED,false));}
    }
    private void dailyCard(DailyPlan.Task task,DailyPlan plan){
        boolean done=task.completed(store,plan.date());
        LinearLayout item=card(done?SOFT:SURFACE);item.setPadding(dp(19),dp(18),dp(19),dp(18));item.setTag("daily:"+task.module);
        String label=task.module.equals("modern")?"现代文阅读":task.module.equals("classical")?"文言文阅读":"每日作文";
        LinearLayout top=row();weighted(top,text(label,13,GREEN,true));top.addView(text(done?"已完成 ✓":"未完成  →",12,done?GREEN:MUTED,done));item.addView(top);
        TextView title=text(task.title.isEmpty()?"进入篇库选择":task.title,18,INK,true);title.setMaxLines(2);title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        margin(item,title,13);item.setContentDescription(label+"，"+task.title+"，"+(done?"今天已完成":"今天未完成"));
        item.setOnClickListener(v->{if(task.article!=null)open(task.article);else{module=task.module;filter="";library();}});
        margin(content,item,12);
    }
    private void stat(LinearLayout parent,int value,String label){
        LinearLayout block=column();block.addView(text(String.valueOf(value),24,GREEN,true));margin(block,text(label,12,MUTED,false),5);
        parent.addView(block,new LinearLayout.LayoutParams(0,-2,1));
    }
    private void tile(LinearLayout parent,String mark,String title,String sub,String key,int background,boolean left){
        LinearLayout tile=card(background);TextView letter=text(mark,31,GREEN,false);letter.setTypeface(Typeface.SERIF);tile.addView(letter);
        margin(tile,text(title,17,INK,true),14);margin(tile,text(sub,11,MUTED,false),6);
        tile.setOnClickListener(v->{module=key;filter="";library();});
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(0,-2,1);if(left)params.rightMargin=dp(6);else params.leftMargin=dp(6);parent.addView(tile,params);
    }
    private void library(){
        screen("library");heading("篇库","");
        Button refresh=button(manualRefreshInProgress?"正在刷新…":"刷新最新文章",false,this::refreshArticles);
        refresh.setTag("refreshArticles");refresh.setEnabled(!manualRefreshInProgress);content.addView(refresh);gap(14);
        HorizontalScrollView horizontal=new HorizontalScrollView(this);horizontal.setHorizontalScrollBarEnabled(false);LinearLayout tabs=row();
        for(String key:new String[]{"classics","modern","classical","essay"})
            smallButton(tabs,moduleName(key),module.equals(key),()->{module=key;filter="";library();});
        horizontal.addView(tabs);content.addView(horizontal);gap(19);
        EditText search=input("搜索篇名、作者或正文",filter,false);search.setSingleLine(true);content.addView(search);gap(16);
        if(module.equals("classics")){
            TextView info=text("目录与版本  ↗",12,MUTED,false);info.setPadding(0,0,0,dp(18));info.setOnClickListener(v->info("古诗文目录",repository.catalogNote()));content.addView(info);
        }
        LinearLayout list=column();libraryList=list;content.addView(list);populate(list);
        search.addTextChangedListener(watcher(value->{filter=value;populate(list);}));
    }
    private void refreshArticles(){
        if(manualRefreshInProgress)return;
        manualRefreshInProgress=true;View refresh=content.findViewWithTag("refreshArticles");
        if(refresh instanceof Button){((Button)refresh).setText("正在刷新…");refresh.setEnabled(false);}
        repository.checkForUpdates((success,changed)->{
            manualRefreshInProgress=false;
            if(isFinishing()||isDestroyed())return;
            View current=content.findViewWithTag("refreshArticles");
            if(current instanceof Button){((Button)current).setText("刷新最新文章");current.setEnabled(true);}
            if(changed)refreshContentViews();
            if(success&&refill!=null)refill.checkInventory(repository,store);
            info(success?"刷新完成":"刷新失败",success?(changed?"已同步最新文章和题库。":"文章和题库已是最新。"):
                    "暂时无法获取最新文章，请检查网络后重试。已有内容仍可使用。");
        });
    }
    private void populate(LinearLayout list){
        list.removeAllViews();String previous="";int count=0;
        for(JSONObject item:repository.items(module)){
            if(!filter.trim().isEmpty()&&!(item.optString("title")+item.optString("author")+item.optString("body")).contains(filter.trim()))continue;
            String book=item.optString("book");
            if(!book.isEmpty()&&!book.equals(previous)&&filter.isEmpty()){
                TextView label=text(book,12,MUTED,true);label.setPadding(0,dp(10),0,dp(12));list.addView(label);previous=book;
            }
            LinearLayout box=card(SURFACE),head=row();weighted(head,text(item.optString("title"),18,INK,true));
            head.addView(text(store.isCompleted(item.optString("id"))?"已学 ✓":"→",12,GREEN,false));box.addView(head);
            margin(box,text(module.equals("classics")?item.optString("author"):item.optString("contentType",item.optString("sourceLabel")),12,MUTED,false),9);
            box.setOnClickListener(v->open(item));
            LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);params.bottomMargin=dp(11);list.addView(box,params);count++;
        }
        if(count==0)list.addView(text("没有找到相关篇目",15,MUTED,false));
    }
    private void review(){
        screen("review");heading("复习","");LinearLayout tabs=row();
        smallButton(tabs,"今日待复习",true,this::review);smallButton(tabs,"管理全部 · "+store.reviews().size(),false,this::allReviews);content.addView(tabs);gap(28);
        if(roundCards.isEmpty())roundCards.addAll(store.dueReviews());
        List<JSONObject> due=new ArrayList<>();for(JSONObject card:roundCards)if(!round.contains(card.optString("key"))&&store.hasReview(card.optString("key")))due.add(card);
        if(due.isEmpty()){
            LinearLayout done=card(SOFT);done.addView(text(round.isEmpty()?"今天的复习已完成":"本轮已完成",25,GREEN,true));
            margin(done,text(round.isEmpty()?"继续读一篇，积累一点。":"本轮复习了 "+round.size()+" 项",15,MUTED,false),15);
            margin(done,button("去阅读",true,this::home),26);
            if(!round.isEmpty())margin(done,button("再来一轮",false,()->{round.clear();review();}),10);
            content.addView(done);return;
        }
        JSONObject item=due.get(0);String key=item.optString("key");
        content.addView(text("本轮已复习 "+round.size()+" 项   ·   待复习 "+due.size()+" 项",12,MUTED,false));gap(18);
        LinearLayout card=card(SURFACE);card.setPadding(dp(23),dp(26),dp(23),dp(26));
        card.addView(text(reviewType(item.optString("type")),12,GOLD,true));
        TextView title=text(item.optString("title"),30,GREEN,true);title.setTypeface(Typeface.SERIF,Typeface.BOLD);margin(card,title,26);
        margin(card,text(item.optString("prompt"),19,INK,false),23);
        TextView answer=text(item.optString("answer"),17,MUTED,false);answer.setVisibility(View.GONE);margin(card,answer,26);
        TextView source=text(item.optString("source")+"  ↗",12,MUTED,false);margin(card,source,24);
        source.setOnClickListener(v->{JSONObject article=repository.find(item.optString("articleId"));if(article!=null)open(article);});
        content.addView(card);gap(24);
        LinearLayout grades=row();grades.setVisibility(View.GONE);
        Button reveal=button("回忆后，查看答案",true,()->{answer.setVisibility(View.VISIBLE);grades.setVisibility(View.VISIBLE);animate(answer);});content.addView(reveal);margin(content,grades,12);
        Button again=button("还不熟",false,()->{store.gradeReview(key,false);round.add(key);review();});
        Button remembered=button("记住了",true,()->{store.gradeReview(key,true);round.add(key);review();});
        LinearLayout.LayoutParams a=new LinearLayout.LayoutParams(0,-2,1);a.rightMargin=dp(6);grades.addView(again,a);
        LinearLayout.LayoutParams b=new LinearLayout.LayoutParams(0,-2,1);b.leftMargin=dp(6);grades.addView(remembered,b);
    }
    private void allReviews(){
        screen("allReviews");heading("全部复习","");
        EditText search=input("搜索字词、原句或出处","",false);content.addView(search);gap(18);LinearLayout list=column();content.addView(list);
        Runnable populate=()->{
            list.removeAllViews();String query=search.getText().toString().trim();
            for(JSONObject item:store.reviews()){
                if(!query.isEmpty()&&!(item.optString("title")+item.optString("prompt")+item.optString("source")).contains(query))continue;
                LinearLayout box=card(SURFACE);box.addView(text(item.optString("title"),20,GREEN,true));
                margin(box,text(item.optString("prompt"),15,INK,false),12);margin(box,text(reviewType(item.optString("type"))+" / "+item.optString("source"),11,MUTED,false),11);
                LinearLayout actions=row();smallButton(actions,"查看",false,()->info(item.optString("title"),item.optString("answer")));
                smallButton(actions,"移出",false,()->{int y=scroll.getScrollY();store.removeReview(item.optString("key"));list.removeView(box);scroll.post(()->scroll.scrollTo(0,y));});margin(box,actions,12);
                LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);params.bottomMargin=dp(12);list.addView(box,params);
            }
        };populate.run();search.addTextChangedListener(watcher(value->populate.run()));
    }
    private void settings(){
        screen("settings");heading("我的","");
        LinearLayout summary=card(SOFT);summary.addView(text("Gosen 的语文书桌",23,GREEN,true));
        margin(summary,text("已学 "+store.completedCount()+" 篇  ·  积累 "+store.reviews().size()+" 项",14,MUTED,false),13);content.addView(summary);gap(22);
        settingsRow("正文字号",fontSize()+"",()->chooseFont(this::settings));
        settingsRow("外观",appearanceLabel(),()->chooseAppearance(this::settings));
        settingsRow("软件版本",BuildConfig.VERSION_NAME,null);
        settingsRow("导出学习备份","→",()->startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .setType("application/json").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,"拾文学习备份-"+LocalDate.now()+".json"),301));
        settingsRow("导入学习备份","→",()->startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("application/json").addCategory(Intent.CATEGORY_OPENABLE),302));
        settingsRow("目录与内容来源","→",()->info("目录与内容来源",repository.catalogNote()+"\n\n现代文 "+repository.items("modern").size()+" 组\n文言阅读 "+repository.items("classical").size()+" 组\n作文 "+repository.items("essay").size()+" 篇\n\n每篇来源与核验状态可在文末查看。AI示范文不代表真实考场满分作文。"));
        gap(28);content.addView(text("拾文 "+BuildConfig.VERSION_NAME+"\n每日有所读，每日有所思。",12,MUTED,false));
    }
    private void settingsRow(String title,String value,Runnable action){
        LinearLayout row=row();row.setPadding(0,dp(19),0,dp(19));weighted(row,text(title,16,INK,false));row.addView(text(value,13,MUTED,false));if(action!=null)row.setOnClickListener(v->action.run());content.addView(row);
        View border=new View(this);border.setBackgroundColor(LINE);content.addView(border,new LinearLayout.LayoutParams(-1,dp(1)));
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);if(result!=RESULT_OK||data==null||data.getData()==null)return;
        try{
            if(request==301){
                try(OutputStream output=getContentResolver().openOutputStream(data.getData())){if(output==null)throw new IOException("无法写入文件");output.write(store.exportJson().getBytes(StandardCharsets.UTF_8));}
                info("备份已导出","学习草稿、作答和复习记录已保存。");
            }else if(request==302){
                String json;
                try(InputStream input=getContentResolver().openInputStream(data.getData())){
                    if(input==null)throw new IOException("无法读取文件");ByteArrayOutputStream buffer=new ByteArrayOutputStream();
                    byte[] bytes=new byte[8192];int n;while((n=input.read(bytes))!=-1){if(buffer.size()+n>16*1024*1024)throw new IOException("备份文件过大");buffer.write(bytes,0,n);}json=buffer.toString("UTF-8");
                }
                store.importJson(json);settings();info("备份已导入","学习记录已恢复。");
            }
        }catch(Exception e){info("未能完成",e.getMessage()==null?"请检查文件后重试。":e.getMessage());}
    }
    private String reviewType(String type){switch(type){case "word":return "字词语境";case "question":return "阅读错题";case "recite":return "背诵默写";case "writing":return "作文积累";default:return "复习";}}
    @Override public void onBackPressed(){if(!page.equals("home"))home();else super.onBackPressed();}
}
