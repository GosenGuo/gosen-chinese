package com.gosen.chinese;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.util.List;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE)
public class CharacterAnnotationResolverTest {
    private JSONObject article() throws Exception{
        return new JSONObject().put("id","sample").put("body","孤鹜，孤飞。𠮷")
                .put("notes",new JSONArray()
                        .put(new JSONObject().put("quote","孤鹜").put("occurrence",0).put("meaning","孤单的野鸭").put("sentence","孤鹜，"))
                        .put(new JSONObject().put("quote","飞").put("occurrence",0).put("meaning","飞翔").put("sentence","孤飞。")))
                .put("translations",new JSONArray().put(new JSONObject().put("quote","孤鹜，孤飞。").put("translation","一只野鸭独自飞翔。")));
    }
    @Test public void charactersHaveSeparateExactRangesAndRepeatedCharactersHaveIndependentContext()throws Exception{
        JSONObject data=article().put("characterNotes",new JSONArray()
                .put(new JSONObject().put("start",0).put("end",1).put("character","孤").put("meaning","孤单的"))
                .put(new JSONObject().put("start",1).put("end",2).put("character","鹜").put("meaning","野鸭"))
                .put(new JSONObject().put("start",3).put("end",4).put("character","孤").put("meaning","独自地")));
        List<AnnotationResolver.ResolvedNote> notes=CharacterAnnotationResolver.resolve(data);
        assertEquals(5,notes.size());
        assertEquals("孤单的",notes.get(0).note.getString("meaning"));
        assertEquals("野鸭",notes.get(1).note.getString("meaning"));
        assertEquals(0,notes.get(0).start);assertEquals(1,notes.get(0).end);
        assertEquals("独自地",notes.get(2).note.getString("meaning"));
        assertNotEquals(notes.get(0).key("sample"),notes.get(2).key("sample"));
        assertEquals(6,notes.get(4).start);assertEquals(8,notes.get(4).end);
    }
    @Test public void legacyWordsRemainContextualAndAreNotMislabelledAsIndependentCharacterMeanings()throws Exception{
        List<AnnotationResolver.ResolvedNote> notes=CharacterAnnotationResolver.resolve(article());
        assertTrue(notes.get(0).note.getString("meaning").startsWith("在“孤鹜”中，整体意为"));
        assertEquals("飞翔",notes.get(3).note.getString("meaning"));
        assertEquals("此字释义待补充",notes.get(4).note.getString("meaning"));
        for(AnnotationResolver.ResolvedNote note:notes)assertNotEquals("一只野鸭独自飞翔。",note.note.getString("meaning"));
    }
    @Test public void malformedExplicitOffsetsCannotMoveACharacterToTheWrongPosition()throws Exception{
        JSONObject data=article().put("characterNotes",new JSONArray()
                .put(new JSONObject().put("start",0).put("end",2).put("character","孤").put("meaning","错误范围"))
                .put(new JSONObject().put("start",1).put("end",2).put("character","孤").put("meaning","错误字符")));
        List<AnnotationResolver.ResolvedNote> notes=CharacterAnnotationResolver.resolve(data);
        assertFalse(notes.get(0).note.getString("meaning").contains("错误"));
        assertFalse(notes.get(1).note.getString("meaning").contains("错误"));
        assertEquals("孤",notes.get(0).note.getString("quote"));assertEquals("鹜",notes.get(1).note.getString("quote"));
    }
}
