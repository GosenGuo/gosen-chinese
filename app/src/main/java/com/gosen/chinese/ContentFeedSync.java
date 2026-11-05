package com.gosen.chinese;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/** Data validation shared by bundled and downloaded content; contains no API credentials. */
public final class ContentFeedSync {
    static final int MAX_BYTES=16*1024*1024;
    interface Fetcher{String fetch(String url)throws Exception;}
    private ContentFeedSync(){}
    static String[] sources(String primary) {
        if("https://gosenguo.github.io/gosen-chinese/library.json".equals(primary))
            return new String[]{primary,"https://raw.githubusercontent.com/GosenGuo/gosen-chinese/main/docs/library.json"};
        return new String[]{primary};
    }
    static String download(String url)throws Exception{
        for(int attempt=0;attempt<4;attempt++) {
            URL address=new URL(url);
            if(!"https".equals(address.getProtocol())||address.getUserInfo()!=null)throw new IllegalArgumentException("内容源需要HTTPS");
            HttpURLConnection connection=(HttpURLConnection)address.openConnection();
            connection.setConnectTimeout(7_000);connection.setReadTimeout(12_000);connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Accept","application/json");connection.setRequestProperty("User-Agent","GosenChinese/"+BuildConfig.VERSION_NAME);
            try {
                int status=connection.getResponseCode();
                if(status>=300&&status<400){url=new URL(address,connection.getHeaderField("Location")).toString();continue;}
                if(status!=200)throw new IllegalStateException("内容服务不可用");
                try(InputStream input=connection.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){
                    byte[] bytes=new byte[8192];int count;
                    while((count=input.read(bytes))!=-1){if(out.size()+count>MAX_BYTES)throw new IllegalArgumentException("内容包过大");out.write(bytes,0,count);}
                    return out.toString(StandardCharsets.UTF_8.name());
                }
            }finally{connection.disconnect();}
        }
        throw new IllegalStateException("内容源重定向过多");
    }
    public static void validateFeed(JSONObject feed)throws Exception{
        require(feed.optInt("schemaVersion",-1)==1,"不支持的内容版本");
        Object version=feed.opt("contentVersion");
        require(version instanceof Number&&((Number)version).doubleValue()==feed.optInt("contentVersion")&&feed.optInt("contentVersion")>0,"内容版本无效");
        require(feed.optInt("bundleVersionCode",0)>0,"内容包应用版本无效");
        Instant.parse(text(feed,"generatedAt"));
        JSONArray items=feed.getJSONArray("items");
        require(items.length()>=63&&items.length()<=5000,"内容包数量异常");
        Set<String> ids=new HashSet<>(),modules=new HashSet<>();int classics=0;
        for(int i=0;i<items.length();i++){
            JSONObject item=items.getJSONObject(i);validateItem(item);
            require(ids.add(item.getString("id")),"内容编号重复");modules.add(item.getString("module"));
            if("classics".equals(item.getString("module")))classics++;
        }
        require(classics>=60&&modules.contains("modern")&&modules.contains("classical")&&modules.contains("essay"),"内容包缺少学习模块");
    }
    public static void validateItem(JSONObject item)throws Exception{
        String id=text(item,"id"),module=text(item,"module"),body=text(item,"body");
        require(id.matches("[A-Za-z0-9_-]{1,140}"),"内容编号无效");text(item,"title");text(item,"sourceLabel");text(item,"verification");
        require(module.equals("classics")||module.equals("modern")||module.equals("classical")||module.equals("essay"),"未知模块");
        require(body.length()>=25&&body.length()<=100000&&!body.contains("\ufffd"),"正文异常");
        if(item.has("characterNotes")){
            JSONArray characters=item.getJSONArray("characterNotes");int previousEnd=0;
            for(int i=0;i<characters.length();i++){
                JSONObject character=characters.getJSONObject(i);int start=character.getInt("start"),end=character.getInt("end");
                String word=text(character,"character");text(character,"meaning");text(character,"detail");
                require(start>=previousEnd&&end<=body.length()&&end>start&&word.codePointCount(0,word.length())==1&&body.substring(start,end).equals(word),"单字注释定位无效");
                previousEnd=end;
            }
            JSONObject coverage=item.getJSONObject("characterAnnotationCoverage");
            require(coverage.optBoolean("complete",false)&&coverage.getInt("totalHanPositions")==characters.length()&&coverage.getInt("annotatedHanPositions")==characters.length(),"单字注释覆盖计数无效");
        }
        JSONArray notes=item.optJSONArray("notes");
        if(notes!=null)for(int i=0;i<notes.length();i++){
            JSONObject note=notes.getJSONObject(i);String quote=text(note,"quote");text(note,"meaning");
            int occurrence=note.optInt("occurrence",0);require(occurrence>=0&&occurrence<body.length(),"字词定位无效");
            int offset=-1,from=0;for(int n=0;n<=occurrence;n++){offset=body.indexOf(quote,from);if(offset<0)break;from=offset+quote.length();}
            require(offset>=0,"字词不在原文");String sentence=text(note,"sentence");
            boolean found=false;int start=body.indexOf(sentence);
            while(start>=0){if(offset>=start&&offset+quote.length()<=start+sentence.length()){found=true;break;}start=body.indexOf(sentence,start+sentence.length());}
            require(found,"字词语境不匹配");
        }
        JSONArray translations=item.optJSONArray("translations");
        if(translations!=null)for(int i=0;i<translations.length();i++){
            JSONObject translation=translations.getJSONObject(i);require(body.contains(text(translation,"quote")),"译文定位无效");text(translation,"translation");
        }
        JSONArray questions=item.optJSONArray("questions");
        if(!module.equals("essay"))require(questions!=null&&questions.length()>0,"缺少阅读练习");
        Set<String> questionIds=new HashSet<>();
        if(questions!=null)for(int i=0;i<questions.length();i++){
            JSONObject q=questions.getJSONObject(i);require(questionIds.add(text(q,"id")),"题目编号重复");
            String type=text(q,"type");require(type.equals("single")||type.equals("short")||type.equals("translation")||type.equals("segmentation"),"未知题型");
            text(q,"prompt");text(q,"answer");text(q,"analysis");
            JSONArray evidence=q.getJSONArray("evidence");require(evidence.length()>0,"缺少原文证据");
            for(int j=0;j<evidence.length();j++)require(!evidence.getString(j).isEmpty()&&body.contains(evidence.getString(j)),"证据不在原文");
            JSONArray points=q.getJSONArray("points");require(points.length()>0,"缺少得分点");int sum=0;
            for(int j=0;j<points.length();j++){JSONObject point=points.getJSONObject(j);text(point,"text");int score=point.getInt("score");require(score>0,"得分点分值无效");sum+=score;}
            require(sum==q.getInt("score"),"得分点合计错误");
            if(type.equals("single")){
                JSONArray options=q.getJSONArray("options"),analyses=q.getJSONArray("optionAnalyses");int answer=q.getInt("answerIndex");
                require(options.length()==4&&analyses.length()==4&&answer>=0&&answer<4,"选择题数据不完整");
                require(options.getString(answer).equals(q.getString("answer")),"选择题答案不匹配");
                for(int j=0;j<4;j++)require(!options.getString(j).trim().isEmpty()&&!analyses.getString(j).trim().isEmpty(),"选择题解析缺失");
            }
        }
        if(module.equals("classical")){
            require(notes!=null&&notes.length()>0&&translations!=null&&translations.length()>0,"文言文注译不完整");
            StringBuilder covered=new StringBuilder();for(int i=0;i<translations.length();i++)covered.append(translations.getJSONObject(i).getString("quote"));
            require(covered.toString().replaceAll("\\s","").equals(body.replaceAll("\\s","")),"课外文言文译文须完整连续覆盖");
        }
        if(module.equals("essay")){
            text(item,"prompt");text(item,"requirements");text(item,"exercise");
            JSONArray outline=item.getJSONArray("outline");require(outline.length()>=4,"作文拆解不完整");
            for(int i=0;i<outline.length();i++){text(outline.getJSONObject(i),"title");text(outline.getJSONObject(i),"text");}
            require(!item.optBoolean("isFullScore",false)&&!item.has("examScore")&&!item.has("score"),"未经核实的作文评分");
        }
    }
    static boolean sameExercise(JSONObject a,JSONObject b)throws Exception{
        for(String key:new String[]{"id","module","body","questions","prompt","requirements"})if(!canonical(a.opt(key)).equals(canonical(b.opt(key))))return false;
        return true;
    }
    static boolean sameValue(JSONObject a,JSONObject b)throws Exception{return canonical(a).equals(canonical(b));}
    private static String canonical(Object value)throws Exception{
        if(value==null||value==JSONObject.NULL)return "null";
        if(value instanceof JSONObject){JSONObject object=(JSONObject)value;List<String> keys=new ArrayList<>();Iterator<String> iterator=object.keys();while(iterator.hasNext())keys.add(iterator.next());Collections.sort(keys);StringBuilder out=new StringBuilder("{");for(String key:keys)out.append(JSONObject.quote(key)).append(':').append(canonical(object.get(key))).append(',');return out.append('}').toString();}
        if(value instanceof JSONArray){JSONArray array=(JSONArray)value;StringBuilder out=new StringBuilder("[");for(int i=0;i<array.length();i++)out.append(canonical(array.get(i))).append(',');return out.append(']').toString();}
        return value instanceof String?JSONObject.quote((String)value):String.valueOf(value);
    }
    private static String text(JSONObject object,String key)throws Exception{Object value=object.opt(key);require(value instanceof String&&!((String)value).trim().isEmpty(),"缺少"+key);return (String)value;}
    private static void require(boolean value,String message){if(!value)throw new IllegalArgumentException(message);}
}
