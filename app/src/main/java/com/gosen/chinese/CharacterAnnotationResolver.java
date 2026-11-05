package com.gosen.chinese;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Every Han codepoint has its own tap range; contextual character data never becomes a sentence gloss. */
public final class CharacterAnnotationResolver {
    private CharacterAnnotationResolver(){}
    public static List<AnnotationResolver.ResolvedNote> resolve(JSONObject article){
        String body=article.optString("body");Map<Integer,JSONObject> explicit=new HashMap<>();
        JSONArray supplied=article.optJSONArray("characterNotes");
        if(supplied!=null)for(int i=0;i<supplied.length();i++){
            JSONObject note=supplied.optJSONObject(i);if(note==null)continue;
            int start=note.optInt("start",-1),end=note.optInt("end",-1);
            if(start<0||end>body.length()||end<=start||note.optString("meaning").trim().isEmpty())continue;
            int cp=body.codePointAt(start);
            if(Character.UnicodeScript.of(cp)!=Character.UnicodeScript.HAN||start+Character.charCount(cp)!=end
                    ||!body.substring(start,end).equals(note.optString("character")))continue;
            explicit.putIfAbsent(start,note);
        }
        List<AnnotationResolver.ResolvedNote> legacy=AnnotationResolver.resolve(article),result=new ArrayList<>();
        Map<String,Integer> occurrences=new HashMap<>();
        for(int start=0;start<body.length();){
            int codepoint=body.codePointAt(start),end=start+Character.charCount(codepoint);
            if(Character.UnicodeScript.of(codepoint)==Character.UnicodeScript.HAN){
                String character=body.substring(start,end);int occurrence=occurrences.getOrDefault(character,0);
                occurrences.put(character,occurrence+1);
                JSONObject raw=explicit.get(start);String meaning=raw==null?"":raw.optString("meaning");
                String detail=raw==null?"":raw.optString("detail");
                String source="本软件整理的语境字义；以所在原句为准";
                if(meaning.isEmpty())for(AnnotationResolver.ResolvedNote old:legacy){
                    if(start<old.start||end>old.end)continue;
                    if(old.start==start&&old.end==end){
                        meaning=old.note.optString("meaning");detail=old.note.optString("detail");
                        source=old.note.optString("source",source);break;
                    }
                    if(meaning.isEmpty()){
                        meaning="在“"+old.note.optString("quote")+"”中，整体意为"+old.note.optString("meaning");
                        detail="这是所在词语的整体意思，请结合原句理解该字。";
                    }
                }
                if(meaning.isEmpty()){meaning="此字释义待补充";detail="当前资料尚未收录该字的准确语境义。";}
                JSONObject note=new JSONObject();
                put(note,"quote",character);put(note,"occurrence",occurrence);put(note,"meaning",meaning);put(note,"detail",detail);
                put(note,"source",source);put(note,"character",true);
                result.add(new AnnotationResolver.ResolvedNote(start,end,note));
            }
            start=end;
        }
        return result;
    }
    private static void put(JSONObject object,String key,Object value){try{object.put(key,value);}catch(Exception error){throw new IllegalArgumentException(error);}}
}
