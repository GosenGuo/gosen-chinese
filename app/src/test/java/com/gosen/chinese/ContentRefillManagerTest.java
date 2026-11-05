package com.gosen.chinese;

import android.content.Context;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35)
public class ContentRefillManagerTest {
    private Context context;
    private AtomicLong clock;
    private final List<ContentRefillManager> opened=new ArrayList<>();
    @Before public void reset(){
        context=RuntimeEnvironment.getApplication();clock=new AtomicLong(1_700_000_000_000L);
        context.getSharedPreferences("gosen_chinese_refill_v1",Context.MODE_PRIVATE).edit().clear().commit();
        context.getSharedPreferences("gosen_chinese_study_v1",Context.MODE_PRIVATE).edit().clear().commit();
    }
    @After public void close(){for(ContentRefillManager manager:opened)manager.close();}
    private ContentRefillManager manager(ContentRefillManager.Sender sender){
        ContentRefillManager manager=new ContentRefillManager(context,"https://service.example/refill","finite-shared-token",sender,clock::get);opened.add(manager);return manager;
    }
    private void idle(ContentRefillManager manager)throws Exception{
        long deadline=System.currentTimeMillis()+3000;while(!manager.idle()&&System.currentTimeMillis()<deadline)Thread.sleep(5);assertTrue("refill worker did not finish",manager.idle());
    }
    @Test public void debugBuildCannotCallTheProductionRefillService(){
        assertEquals("",BuildConfig.CONTENT_REFILL_URL);
        assertEquals("",BuildConfig.CONTENT_REFILL_TOKEN);
    }
    @Test public void unreadFiveDoesNotRequestButFourRequestsThirtyWithTheCorrectModule()throws Exception{
        List<JSONObject> sent=Collections.synchronizedList(new ArrayList<>());
        ContentRefillManager manager=manager((url,token,body)->{assertEquals("finite-shared-token",token);sent.add(new JSONObject(body));return 202;});
        manager.requestIfBelowThreshold("modern",5);idle(manager);assertTrue(sent.isEmpty());
        manager.requestIfBelowThreshold("modern",4);idle(manager);assertEquals(1,sent.size());assertEquals("modern",sent.get(0).getString("module"));assertEquals(4,sent.get(0).getInt("unreadCount"));assertEquals(30,sent.get(0).getInt("requestedCount"));
    }
    @Test public void threeModulesHaveIndependentPersistedCooldownsIncludingAcceptedConflict()throws Exception{
        AtomicInteger calls=new AtomicInteger();ContentRefillManager manager=manager((url,token,body)->{calls.incrementAndGet();return 409;});
        manager.requestIfBelowThreshold("modern",3);manager.requestIfBelowThreshold("classical",2);idle(manager);assertEquals(2,calls.get());
        ContentRefillManager recreated=manager((url,token,body)->{calls.incrementAndGet();return 202;});
        recreated.requestIfBelowThreshold("modern",0);recreated.requestIfBelowThreshold("classical",0);recreated.requestIfBelowThreshold("essay",4);idle(recreated);assertEquals(3,calls.get());
        clock.addAndGet(ContentRefillManager.COOLDOWN_MS+1);recreated.requestIfBelowThreshold("modern",4);idle(recreated);assertEquals(4,calls.get());
    }
    @Test public void failedRequestDoesNotSuppressTheNextForegroundRetry()throws Exception{
        AtomicInteger calls=new AtomicInteger();ContentRefillManager manager=manager((url,token,body)->calls.incrementAndGet()==1?500:202);
        manager.requestIfBelowThreshold("modern",0);idle(manager);manager.requestIfBelowThreshold("modern",0);idle(manager);assertEquals(2,calls.get());
        manager.requestIfBelowThreshold("modern",0);idle(manager);assertEquals(2,calls.get());
    }
    @Test public void overlappingInventoryChecksCannotDuplicateTheSameModuleRequest()throws Exception{
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);AtomicInteger calls=new AtomicInteger();
        ContentRefillManager manager=manager((url,token,body)->{calls.incrementAndGet();entered.countDown();release.await();return 202;});
        manager.requestIfBelowThreshold("modern",3);assertTrue(entered.await(1,TimeUnit.SECONDS));manager.requestIfBelowThreshold("modern",2);manager.requestIfBelowThreshold("modern",0);
        release.countDown();idle(manager);assertEquals(1,calls.get());
    }
    @Test public void inventoryCountsArePerModuleAndReadingFromFiveToFourTriggersARefill()throws Exception{
        List<JSONObject> sent=Collections.synchronizedList(new ArrayList<>());
        ContentRefillManager manager=manager((url,token,body)->{sent.add(new JSONObject(body));return 202;});
        ContentRepository repository=new ContentRepository(context,"https://example.invalid",url->{throw new IllegalStateException();});StudyStore store=new StudyStore(context);
        try{
            manager.checkInventory(repository,store);idle(manager);assertEquals(2,sent.size());
            assertTrue(sent.stream().anyMatch(item->item.optString("module").equals("modern")));assertTrue(sent.stream().anyMatch(item->item.optString("module").equals("classical")));
            assertFalse(sent.stream().anyMatch(item->item.optString("module").equals("essay")));
            store.markComplete(repository.items("essay").get(0).getString("id"));store.markComplete(repository.items("essay").get(1).getString("id"));
            manager.checkInventory(repository,store);idle(manager);assertEquals(2,sent.size());
            store.markComplete(repository.items("essay").get(2).getString("id"));manager.checkInventory(repository,store);idle(manager);
            assertEquals(3,sent.size());assertEquals("essay",sent.get(2).getString("module"));assertEquals(4,sent.get(2).getInt("unreadCount"));
        }finally{repository.close();}
    }
    @Test public void invalidModulesAndInsecureOrMissingEndpointsNeverSend()throws Exception{
        AtomicInteger calls=new AtomicInteger();ContentRefillManager manager=manager((url,token,body)->{calls.incrementAndGet();return 202;});
        manager.requestIfBelowThreshold("classics",0);manager.requestIfBelowThreshold("modern",-1);idle(manager);assertEquals(0,calls.get());
        ContentRefillManager insecure=new ContentRefillManager(context,"http://service.example/refill","", (url,token,body)->{calls.incrementAndGet();return 202;},clock::get);opened.add(insecure);
        insecure.requestIfBelowThreshold("modern",0);idle(insecure);assertEquals(0,calls.get());
    }
}
