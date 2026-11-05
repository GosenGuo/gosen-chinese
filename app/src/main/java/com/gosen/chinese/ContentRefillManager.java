package com.gosen.chinese;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONObject;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.LongSupplier;

/** Per-module low-stock refill requests; holds neither the AI key nor a GitHub credential. */
public final class ContentRefillManager {
    static final int UNREAD_THRESHOLD=5,REFILL_COUNT=30;
    static final long COOLDOWN_MS=6L*60*60*1000;
    interface Sender { int send(String endpoint,String sharedToken,String body)throws Exception; }
    private final SharedPreferences preferences;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final Set<String> pending=new HashSet<>();
    private final String endpoint,token;
    private final Sender sender;
    private final LongSupplier clock;
    private boolean closed;
    public ContentRefillManager(Context context){
        this(context,BuildConfig.CONTENT_REFILL_URL,BuildConfig.CONTENT_REFILL_TOKEN,ContentRefillManager::send,System::currentTimeMillis);
    }
    ContentRefillManager(Context context,String endpoint,String token,Sender sender,LongSupplier clock){
        this.preferences=context.getApplicationContext().getSharedPreferences("gosen_chinese_refill_v1",Context.MODE_PRIVATE);
        this.endpoint=endpoint;this.token=token;this.sender=sender;this.clock=clock;
    }
    public void checkInventory(ContentRepository repository,StudyStore store){
        for(String module:new String[]{"modern","classical","essay"}){
            int unread=0;for(JSONObject item:repository.items(module))if(!store.isCompleted(item.optString("id")))unread++;
            requestIfBelowThreshold(module,unread,repository.contentVersion());
        }
    }
    public void requestIfBelowThreshold(String module,int unread){requestIfBelowThreshold(module,unread,0);}
    synchronized void requestIfBelowThreshold(String module,int unread,int contentVersion){
        if(closed||!Arrays.asList("modern","classical","essay").contains(module)||unread<0||unread>=UNREAD_THRESHOLD||pending.contains(module))return;
        try{URL address=new URL(endpoint);if(!"https".equals(address.getProtocol())||address.getUserInfo()!=null)return;}catch(Exception ignored){return;}
        long now=clock.getAsLong(),last=preferences.getLong(module+".lastAcceptedAt",0);
        if(last>0&&now-last<COOLDOWN_MS)return;
        pending.add(module);
        try{
            worker.execute(()->{
                try{
                    JSONObject payload=new JSONObject().put("module",module).put("unreadCount",unread).put("requestedCount",REFILL_COUNT).put("appVersion",BuildConfig.VERSION_NAME).put("contentVersion",contentVersion);
                    int status=sender.send(endpoint,token,payload.toString());
                    synchronized(ContentRefillManager.this){
                        if(!closed&&((status>=200&&status<300)||status==409))preferences.edit().putLong(module+".lastAcceptedAt",clock.getAsLong()).apply();
                    }
                }catch(Exception ignored){
                    // Another foreground inventory check can retry; reading continues offline.
                }finally{synchronized(ContentRefillManager.this){pending.remove(module);}}
            });
        }catch(RejectedExecutionException ignored){pending.remove(module);}
    }
    static int send(String endpoint,String sharedToken,String json)throws Exception{
        URL address=new URL(endpoint);if(!"https".equals(address.getProtocol())||address.getUserInfo()!=null)throw new IllegalArgumentException("HTTPS refill endpoint required");
        HttpURLConnection connection=(HttpURLConnection)address.openConnection();
        connection.setRequestMethod("POST");connection.setInstanceFollowRedirects(false);connection.setConnectTimeout(8_000);connection.setReadTimeout(12_000);connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type","application/json");connection.setRequestProperty("User-Agent","GosenChinese/"+BuildConfig.VERSION_NAME);
        if(sharedToken!=null&&!sharedToken.trim().isEmpty())connection.setRequestProperty("Authorization","Bearer "+sharedToken.trim());
        byte[] body=json.getBytes(StandardCharsets.UTF_8);connection.setFixedLengthStreamingMode(body.length);
        try{try(OutputStream output=connection.getOutputStream()){output.write(body);}return connection.getResponseCode();}
        finally{connection.disconnect();}
    }
    public synchronized void close(){closed=true;worker.shutdownNow();pending.clear();}
    synchronized boolean idle(){return pending.isEmpty();}
}
