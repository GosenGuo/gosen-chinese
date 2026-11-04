package com.gosen.chinese;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.util.List;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, manifest = Config.NONE)
public class AnnotationResolverTest {
    @Test public void repeatedCharacterKeepsSeparateOccurrenceAndMeaning() throws Exception {
        JSONObject article = new JSONObject("{\"body\":\"以其求思之深而无不在也。以吾一日长乎尔。\",\"notes\":["
                + "{\"quote\":\"以\",\"occurrence\":0,\"meaning\":\"因为\"},"
                + "{\"quote\":\"以\",\"occurrence\":1,\"meaning\":\"因为\"}]}");
        List<AnnotationResolver.ResolvedNote> notes = AnnotationResolver.resolve(article);
        assertEquals(2, notes.size());
        assertEquals(0, notes.get(0).start);
        assertEquals(article.getString("body").lastIndexOf("以"), notes.get(1).start);
        assertNotEquals(notes.get(0).key("lesson"), notes.get(1).key("lesson"));
        assertNotEquals(notes.get(0).key("lesson"), notes.get(0).key("another"));
    }

    @Test public void longerPhraseWinsOverlappingCharacterButKeepsOtherPosition() throws Exception {
        JSONObject article = new JSONObject("{\"body\":\"无以至千里，无以成江海。\",\"notes\":["
                + "{\"quote\":\"无\",\"occurrence\":0,\"meaning\":\"没有\"},"
                + "{\"quote\":\"无以\",\"occurrence\":0,\"meaning\":\"没有用来……的办法\"},"
                + "{\"quote\":\"无\",\"occurrence\":1,\"meaning\":\"没有\"}]}");
        List<AnnotationResolver.ResolvedNote> notes = AnnotationResolver.resolve(article);
        assertEquals(2, notes.size());
        assertEquals("无以", notes.get(0).note.getString("quote"));
        assertEquals(0, notes.get(0).start);
        assertEquals(2, notes.get(0).end);
        assertEquals(article.getString("body").lastIndexOf("无"), notes.get(1).start);
    }

    @Test public void absentInvalidAndUnexplainedNotesNeverBecomeClickable() throws Exception {
        JSONObject article = new JSONObject("{\"body\":\"学不可以已。\",\"notes\":["
                + "{\"quote\":\"学\",\"occurrence\":2,\"meaning\":\"学习\"},"
                + "{\"quote\":\"学\",\"occurrence\":-1,\"meaning\":\"学习\"},"
                + "{\"quote\":\"不\",\"meaning\":\"\"},"
                + "{\"quote\":\"不存在\",\"meaning\":\"解释\"}]}");
        assertTrue(AnnotationResolver.resolve(article).isEmpty());
    }

    @Test public void harmlessPrefixCorrectionKeepsReviewIdentity() throws Exception {
        JSONObject article = new JSONObject("{\"body\":\"学不可以已。\",\"notes\":["
                + "{\"quote\":\"已\",\"meaning\":\"停止\"}]}");
        String key = AnnotationResolver.resolve(article).get(0).key("classic-1");
        article.put("body", "君子曰：学不可以已。");
        AnnotationResolver.ResolvedNote corrected = AnnotationResolver.resolve(article).get(0);
        assertEquals(key, corrected.key("classic-1"));
        assertEquals(article.getString("body").indexOf("已"), corrected.start);
    }
}
