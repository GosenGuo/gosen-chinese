package com.gosen.chinese;

import android.content.Context;
import android.os.Looper;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35)
public class ContentRepositoryTest {
    private Context context;
    private final List<ContentRepository> opened=new ArrayList<>();
    @Before public void reset(){
        context=RuntimeEnvironment.getApplication();
        new File(context.getFilesDir(),ContentRepository.CACHE_FILE).delete();
        new File(context.getFilesDir(),ContentRepository.CACHE_FILE+".bak").delete();
    }
    @After public void close(){for(ContentRepository repo:opened)repo.close();}
    private ContentRepository repo(ContentFeedSync.Fetcher fetcher){
        ContentRepository repo=new ContentRepository(context,"https://example.invalid/feed.json",fetcher,3);opened.add(repo);return repo;
    }
    private JSONObject feed(ContentRepository repo,int version,int bundleVersion)throws Exception{
        JSONArray items=new JSONArray();for(JSONObject item:repo.all())items.put(item);
        return new JSONObject().put("schemaVersion",1).put("contentVersion",version).put("bundleVersionCode",bundleVersion)
                .put("generatedAt","2026-11-05T00:00:00Z").put("catalog",new JSONObject().put("note","远端目录说明")).put("items",items);
    }
    private JSONObject item(JSONObject feed,String id)throws Exception{
        JSONArray items=feed.getJSONArray("items");for(int i=0;i<items.length();i++)if(id.equals(items.getJSONObject(i).optString("id")))return items.getJSONObject(i);
        throw new AssertionError(id);
    }
    private boolean[] sync(ContentRepository repo)throws Exception{
        AtomicReference<boolean[]> result=new AtomicReference<>();
        repo.checkForUpdates((ok,changed)->result.set(new boolean[]{ok,changed}));
        long end=System.currentTimeMillis()+5000;
        while(result.get()==null&&System.currentTimeMillis()<end){shadowOf(Looper.getMainLooper()).idle();Thread.sleep(10);}
        assertNotNull("content callback did not finish",result.get());return result.get();
    }
    @Test public void fullFeedUpdatesAnnotationsAndPersistsForNextReadingActivity()throws Exception{
        AtomicReference<String> response=new AtomicReference<>();ContentRepository first=repo(url->response.get());
        assertTrue(first.error(),first.error().isEmpty());String old=first.find("classic-01").getJSONArray("notes").getJSONObject(0).getString("meaning");
        JSONObject incoming=feed(first,1,3);item(incoming,"classic-01").getJSONArray("notes").getJSONObject(0).put("meaning","新的语境解释");
        response.set(incoming.toString());assertArrayEquals(new boolean[]{true,true},sync(first));
        assertEquals("新的语境解释",first.find("classic-01").getJSONArray("notes").getJSONObject(0).getString("meaning"));
        ContentRepository reopened=repo(url->{throw new IllegalStateException("offline");});
        assertEquals("新的语境解释",reopened.find("classic-01").getJSONArray("notes").getJSONObject(0).getString("meaning"));
        assertFalse(old.equals("新的语境解释"));assertEquals(1,reopened.contentVersion());
    }
    @Test public void malformedDownloadCannotReplaceExistingCacheOrBundledContent()throws Exception{
        AtomicReference<String> response=new AtomicReference<>();ContentRepository repository=repo(url->response.get());
        response.set(feed(repository,1,3).toString());assertArrayEquals(new boolean[]{true,false},sync(repository));
        byte[] before=java.nio.file.Files.readAllBytes(new File(context.getFilesDir(),ContentRepository.CACHE_FILE).toPath());
        response.set("{invalid");assertArrayEquals(new boolean[]{false,false},sync(repository));
        assertArrayEquals(before,java.nio.file.Files.readAllBytes(new File(context.getFilesDir(),ContentRepository.CACHE_FILE).toPath()));
        assertEquals(60,repository.items("classics").size());
    }
    @Test public void invalidCharacterAnchorsCannotEnterTheDownloadedFeed()throws Exception{
        ContentRepository repository=repo(url->{throw new IllegalStateException();});
        JSONObject classic=repository.find("classic-01");String body=classic.getString("body");
        int start=body.indexOf("君");assertTrue(start>=0);
        JSONObject character=new JSONObject().put("start",start).put("end",start+1).put("character","君").put("meaning","君子").put("detail","此处指君子");
        classic.put("characterNotes",new JSONArray().put(character));
        classic.put("characterAnnotationCoverage",new JSONObject().put("complete",true).put("totalHanPositions",1).put("annotatedHanPositions",1));
        ContentFeedSync.validateItem(classic);
        character.put("character","学");
        try{ContentFeedSync.validateItem(classic);fail("mismatched character anchor accepted");}catch(IllegalArgumentException expected){assertTrue(expected.getMessage().contains("单字"));}
    }
    @Test public void changedExistingExerciseIsKeptWhileIndependentNewIdsAreAdded()throws Exception{
        AtomicReference<String> response=new AtomicReference<>();ContentRepository repository=repo(url->response.get());
        JSONObject incoming=feed(repository,2,3),original=repository.items("modern").get(0);
        item(incoming,original.getString("id")).put("body",original.getString("body")+"正文不应被同ID改写。");
        JSONObject extra=new JSONObject(original.toString()).put("id","daily-modern-test");
        incoming.getJSONArray("items").put(extra);response.set(incoming.toString());
        assertArrayEquals(new boolean[]{true,true},sync(repository));
        assertEquals(original.getString("body"),repository.find(original.getString("id")).getString("body"));
        assertNotNull(repository.find("daily-modern-test"));
    }
    @Test public void anOlderApkCacheCannotUndoTheNewApksBundledAnnotations()throws Exception{
        AtomicReference<String> response=new AtomicReference<>();
        ContentRepository old=new ContentRepository(context,"https://example.invalid/feed.json",url->response.get(),1);opened.add(old);
        String bundled=old.find("classic-01").getJSONArray("notes").getJSONObject(0).getString("meaning");
        JSONObject incoming=feed(old,10,1);item(incoming,"classic-01").getJSONArray("notes").getJSONObject(0).put("meaning","旧APK缓存说明");
        response.set(incoming.toString());assertArrayEquals(new boolean[]{true,true},sync(old));
        ContentRepository upgraded=repo(url->response.get());
        assertEquals(bundled,upgraded.find("classic-01").getJSONArray("notes").getJSONObject(0).getString("meaning"));
        JSONObject next=feed(old,11,1);next.getJSONArray("items").put(new JSONObject(old.items("modern").get(0).toString()).put("id","new-daily-modern"));
        response.set(next.toString());assertArrayEquals(new boolean[]{true,true},sync(upgraded));
        assertEquals(bundled,upgraded.find("classic-01").getJSONArray("notes").getJSONObject(0).getString("meaning"));
        assertNotNull(upgraded.find("new-daily-modern"));
    }
    @Test public void changedSameVersionAndDowngradedPackagesAreRejected()throws Exception{
        AtomicReference<String> response=new AtomicReference<>();ContentRepository repository=repo(url->response.get());
        response.set(feed(repository,5,3).toString());assertTrue(sync(repository)[0]);
        JSONObject reused=feed(repository,5,3);item(reused,"classic-01").put("book","不能静默改变的同版本目录");response.set(reused.toString());
        assertArrayEquals(new boolean[]{false,false},sync(repository));
        response.set(feed(repository,4,3).toString());assertArrayEquals(new boolean[]{false,false},sync(repository));assertEquals(5,repository.contentVersion());
    }
    @Test public void pagesNetworkFailureUsesIndependentRawBackup()throws Exception{
        ContentRepository seed=repo(url->{throw new IllegalStateException();});String data=feed(seed,2,3).toString();AtomicInteger calls=new AtomicInteger();
        ContentRepository repository=new ContentRepository(context,"https://gosenguo.github.io/gosen-chinese/library.json",url->{
            calls.incrementAndGet();if(url.contains("github.io"))throw new java.net.SocketTimeoutException();return data;
        },3);opened.add(repository);
        assertArrayEquals(new boolean[]{true,false},sync(repository));assertEquals(2,calls.get());assertEquals(2,repository.contentVersion());
    }
    @Test public void corruptLocalCacheFallsBackToAllBundledModules()throws Exception{
        try(FileOutputStream output=new FileOutputStream(new File(context.getFilesDir(),ContentRepository.CACHE_FILE))){output.write("broken".getBytes(StandardCharsets.UTF_8));}
        ContentRepository repository=repo(url->{throw new IllegalStateException();});assertEquals(60,repository.items("classics").size());assertFalse(repository.items("essay").isEmpty());assertTrue(repository.error(),repository.error().isEmpty());
    }
    @Test public void closedRepositorySuppressesLateNetworkCallbacks()throws Exception{
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);AtomicInteger callbacks=new AtomicInteger();
        ContentRepository repository=repo(url->{entered.countDown();release.await();throw new IllegalStateException();});
        repository.checkForUpdates((ok,changed)->callbacks.incrementAndGet());assertTrue(entered.await(2,TimeUnit.SECONDS));
        repository.close();release.countDown();Thread.sleep(20);shadowOf(Looper.getMainLooper()).idle();assertEquals(0,callbacks.get());
    }
}
