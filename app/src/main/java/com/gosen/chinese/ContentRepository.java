package com.gosen.chinese;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.AtomicFile;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/** Bundled material plus a validated, atomic, append-only remote library cache. */
public final class ContentRepository {
    static final String CACHE_FILE="content-library-v1.json";
    private final Map<String,JSONObject> byId=new LinkedHashMap<>();
    private final List<String> errors=new ArrayList<>();
    private final AtomicFile cache;
    private final ContentFeedSync.Fetcher fetcher;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final Handler main=new Handler(Looper.getMainLooper());
    private final String feedUrl;
    private final int bundledVersionCode;
    private String catalogNote="";
    private int contentVersion=0;
    private boolean checking,closed;
    private final List<UpdateCallback> pendingCallbacks=new ArrayList<>();

    public ContentRepository(Context context) { this(context,BuildConfig.CONTENT_FEED_URL,ContentFeedSync::download); }
    ContentRepository(Context context,String feedUrl,ContentFeedSync.Fetcher fetcher) {
        this(context,feedUrl,fetcher,BuildConfig.BUNDLED_CONTENT_VERSION_CODE);
    }
    ContentRepository(Context context,String feedUrl,ContentFeedSync.Fetcher fetcher,int bundledVersionCode) {
        this.feedUrl=feedUrl;this.fetcher=fetcher;this.bundledVersionCode=bundledVersionCode;
        cache=new AtomicFile(new File(context.getApplicationContext().getFilesDir(),CACHE_FILE));
        for(String name:new String[]{"classics.json","practice.json","essays.json"}) {
            try(InputStream input=context.getAssets().open(name)) {
                JSONObject root=new JSONObject(read(input));
                if(root.optInt("schemaVersion",-1)!=1)throw new IllegalArgumentException("不支持的内容版本");
                JSONObject catalog=root.optJSONObject("catalog");
                if(catalog!=null)catalogNote=catalog.optString("note","");
                JSONArray items=root.getJSONArray("items");
                for(int i=0;i<items.length();i++) {
                    JSONObject item=items.getJSONObject(i);
                    ContentFeedSync.validateItem(item);
                    String id=item.getString("id");
                    if(byId.containsKey(id))throw new IllegalArgumentException("内容编号重复");
                    byId.put(id,copy(item));
                }
            } catch(Exception error) {errors.add(name+"："+error.getClass().getSimpleName());}
        }
        try {
            if(cache.getBaseFile().isFile()||new File(cache.getBaseFile()+".bak").isFile()) {
                JSONObject stored=new JSONObject(new String(cache.readFully(),StandardCharsets.UTF_8));
                ContentFeedSync.validateFeed(stored);
                apply(stored,false);
            }
        } catch(Exception ignored) {
            // A damaged cache cannot displace the independent bundled copy.
        }
    }

    public synchronized List<JSONObject> items(String module) {
        List<JSONObject> result=new ArrayList<>();
        for(JSONObject item:byId.values())if(module.equals(item.optString("module")))result.add(copy(item));
        result.sort(Comparator.comparingInt(item->item.optInt("order",Integer.MAX_VALUE)));
        return Collections.unmodifiableList(result);
    }
    public synchronized List<JSONObject> all() {
        List<JSONObject> result=new ArrayList<>();for(JSONObject item:byId.values())result.add(copy(item));
        return Collections.unmodifiableList(result);
    }
    public synchronized JSONObject find(String id) {JSONObject found=byId.get(id);return found==null?null:copy(found);}
    public synchronized String catalogNote(){return catalogNote;}
    public synchronized String error(){return String.join("\n",errors);}
    public synchronized int contentVersion(){return contentVersion;}

    public void checkForUpdates(UpdateCallback callback) {
        synchronized(this) {
            if(closed)return;
            if(callback!=null)pendingCallbacks.add(callback);
            if(checking)return;
            checking=true;
        }
        try {
            worker.execute(()->{
                boolean success=false,changed=false;
                try {
                    for(String source:ContentFeedSync.sources(feedUrl)) {
                        try {
                            JSONObject incoming=new JSONObject(fetcher.fetch(source));
                            ContentFeedSync.validateFeed(incoming);
                            synchronized(this) {if(!closed){changed=apply(incoming,true);success=true;}}
                            break;
                        } catch(Exception ignored) {
                            // Another independent source may still be reachable.
                        }
                    }
                } catch(Exception ignored) {
                    // Network, validation or disk failure leaves the previous library untouched.
                } finally {finishCheck(success,changed);}
            });
        } catch(RejectedExecutionException ignored) {finishCheck(false,false);}
    }

    private void finishCheck(boolean success,boolean changed){
        List<UpdateCallback> callbacks;
        synchronized(this){checking=false;callbacks=new ArrayList<>(pendingCallbacks);pendingCallbacks.clear();}
        for(UpdateCallback callback:callbacks)deliver(callback,success,changed);
    }

    private synchronized boolean apply(JSONObject incoming,boolean persist) throws Exception {
        int nextVersion=incoming.getInt("contentVersion");
        if(nextVersion<contentVersion)throw new IllegalArgumentException("拒绝旧内容版本");
        Map<String,JSONObject> merged=new LinkedHashMap<>(byId);
        JSONArray additions=incoming.getJSONArray("items");
        boolean allowEnrichment=incoming.optInt("bundleVersionCode",0)>=bundledVersionCode;
        boolean changed=false;
        for(int i=0;i<additions.length();i++) {
            JSONObject next=additions.getJSONObject(i);String id=next.getString("id");
            JSONObject old=merged.get(id);
            if(old!=null) {
                // Protect answer drafts and old question IDs, but permit verified annotation additions.
                if(allowEnrichment&&ContentFeedSync.sameExercise(old,next)&&!ContentFeedSync.sameValue(old,next)){
                    merged.put(id,copy(next));changed=true;
                }
            } else {merged.put(id,copy(next));changed=true;}
        }
        if(nextVersion==contentVersion&&changed)throw new IllegalArgumentException("新增内容必须提升版本");
        JSONObject catalog=incoming.optJSONObject("catalog");
        String note=!allowEnrichment||catalog==null?catalogNote:catalog.optString("note",catalogNote);
        JSONObject complete=new JSONObject().put("schemaVersion",1).put("contentVersion",nextVersion)
                .put("bundleVersionCode",Math.max(bundledVersionCode,incoming.optInt("bundleVersionCode",0)))
                .put("generatedAt",incoming.getString("generatedAt")).put("catalog",new JSONObject().put("note",note));
        JSONArray list=new JSONArray();for(JSONObject item:merged.values())list.put(item);complete.put("items",list);
        if(persist) {
            FileOutputStream out=null;
            try {out=cache.startWrite();out.write(complete.toString().getBytes(StandardCharsets.UTF_8));cache.finishWrite(out);}
            catch(Exception error){if(out!=null)cache.failWrite(out);throw error;}
        }
        byId.clear();byId.putAll(merged);contentVersion=nextVersion;catalogNote=note;
        return changed;
    }

    public synchronized void close(){closed=true;pendingCallbacks.clear();worker.shutdownNow();}
    private void deliver(UpdateCallback callback,boolean success,boolean changed) {
        if(callback!=null)main.post(()->{synchronized(ContentRepository.this){if(closed)return;}callback.onComplete(success,changed);});
    }
    static JSONObject copy(JSONObject value){try{return new JSONObject(value.toString());}catch(Exception error){throw new IllegalArgumentException(error);}}
    private static String read(InputStream input)throws Exception{
        ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int count;
        while((count=input.read(buffer))!=-1){if(out.size()+count>ContentFeedSync.MAX_BYTES)throw new IllegalArgumentException("内容过大");out.write(buffer,0,count);}
        return out.toString(StandardCharsets.UTF_8.name());
    }
    public interface UpdateCallback{void onComplete(boolean success,boolean changed);}
}
