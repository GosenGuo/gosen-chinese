package com.gosen.chinese;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.Signature;
import android.net.Uri;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowToast;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE)
public class AppUpdateManagerTest {
    private JSONObject manifest() throws Exception {
        return new JSONObject().put("versionCode",2).put("versionName","0.1.1")
                .put("apkUrl","https://github.com/GosenGuo/gosen-chinese/releases/download/app-v0.1.1/GosenChinese-0.1.1.apk")
                .put("sha256","ab".repeat(32)).put("certificateSha256","cd".repeat(32))
                .put("packageName","com.gosen.chinese");
    }
    @Test public void manifestRequiresBothNonemptyDigestsAndCorrectPackage() throws Exception {
        assertEquals(2,AppUpdateManager.parseManifest(manifest(),"com.gosen.chinese").versionCode);
        for(String key:new String[]{"sha256","certificateSha256"}) {
            JSONObject missing=manifest();missing.remove(key);
            assertThrows(Exception.class,()->AppUpdateManager.parseManifest(missing,"com.gosen.chinese"));
            JSONObject blank=manifest().put(key,"");
            assertThrows(Exception.class,()->AppUpdateManager.parseManifest(blank,"com.gosen.chinese"));
        }
        assertThrows(Exception.class,()->AppUpdateManager.parseManifest(manifest().put("packageName","other.app"),"com.gosen.chinese"));
    }
    @Test public void manifestRejectsInsecureOrMalformedDownloadAddress() throws Exception {
        for(String address:new String[]{"http://example.com/app.apk","file:///tmp/app.apk","https://"})
            assertThrows(Exception.class,()->AppUpdateManager.parseManifest(manifest().put("apkUrl",address),"com.gosen.chinese"));
    }
    @Test public void apkChecksumCannotBeSkippedOrFaked() throws Exception {
        File file=File.createTempFile("update-test",".apk",RuntimeEnvironment.getApplication().getCacheDir());
        byte[] original="signed APK bytes".getBytes(StandardCharsets.UTF_8);Files.write(file.toPath(),original);
        assertTrue(AppUpdateManager.checksumMatches(file,AppUpdateManager.digest(original)));
        assertFalse(AppUpdateManager.checksumMatches(file,""));
        assertFalse(AppUpdateManager.checksumMatches(file,"00".repeat(32)));
        Files.write(file.toPath(),"modified APK bytes".getBytes(StandardCharsets.UTF_8));
        assertFalse(AppUpdateManager.checksumMatches(file,AppUpdateManager.digest(original)));
    }
    @Test public void signingIdentityMustMatchInstalledAppAndPinnedCertificate() {
        Signature trusted=new Signature("01020304"), stranger=new Signature("01020305");
        String pin=AppUpdateManager.digest(trusted.toByteArray());
        assertTrue(AppUpdateManager.signaturesMatch(new Signature[]{trusted},new Signature[]{trusted},pin));
        assertFalse(AppUpdateManager.signaturesMatch(new Signature[]{trusted},new Signature[]{stranger},pin));
        assertFalse(AppUpdateManager.signaturesMatch(new Signature[]{trusted},new Signature[]{trusted},"00".repeat(32)));
        assertFalse(AppUpdateManager.signaturesMatch(null,new Signature[]{trusted},pin));
        assertFalse(AppUpdateManager.signaturesMatch(new Signature[]{trusted},null,pin));
    }
    @Test public void archiveVersionMustExactlyMatchManifestAndBeNewer() {
        PackageInfo archive=new PackageInfo();archive.packageName="com.gosen.chinese";archive.versionCode=2;
        assertTrue(AppUpdateManager.packageMetadataMatches(archive,"com.gosen.chinese",2,1));
        assertFalse(AppUpdateManager.packageMetadataMatches(archive,"com.gosen.chinese",3,1));
        assertFalse(AppUpdateManager.packageMetadataMatches(archive,"com.gosen.chinese",2,2));
        assertFalse(AppUpdateManager.packageMetadataMatches(archive,"different.app",2,1));
    }
    @Test public void returningFromCancelledInstallerDoesNotLaunchItAgain() throws Exception {
        Activity activity=Robolectric.buildActivity(Activity.class).setup().get();
        Shadows.shadowOf(activity.getPackageManager()).setCanRequestPackageInstalls(true);
        SharedPreferences prefs=activity.getSharedPreferences(AppUpdateManager.PREFS,Context.MODE_PRIVATE);
        prefs.edit().clear().putLong("downloadId",10).putInt("versionCode",2).putBoolean("verified",true).commit();
        AppUpdateManager manager=new AppUpdateManager(activity);
        try {
            java.lang.reflect.Method request=AppUpdateManager.class.getDeclaredMethod("requestInstall",File.class);
            request.setAccessible(true);request.invoke(manager,new File(activity.getCacheDir(),"GosenChinese-2.apk"));
            Intent first=Shadows.shadowOf(activity).getNextStartedActivity();assertNotNull(first);assertEquals(Intent.ACTION_VIEW,first.getAction());
            assertFalse(prefs.contains("downloadId"));
            manager.resumePendingInstall();assertNull(Shadows.shadowOf(activity).getNextStartedActivity());
        } finally {manager.close();activity.finish();}
    }
    @Test public void providerRejectsTraversalAndWriteAccess() throws Exception {
        ApkFileProvider provider=Robolectric.buildContentProvider(ApkFileProvider.class).create().get();
        Uri traversal=Uri.parse("content://com.gosen.chinese.apk-files/..%2Fprivate.apk");
        assertThrows(IllegalArgumentException.class,()->provider.query(traversal,null,null,null,null));
        assertThrows(java.io.FileNotFoundException.class,()->provider.openFile(Uri.parse("content://com.gosen.chinese.apk-files/update.apk"),"w"));
    }
    @Test public void unavailablePagesRouteFallsBackToRawManifest() throws Exception {
        List<String> calls=new ArrayList<>();
        AppUpdateManager.UpdateInfo result=UpdateSources.load(UpdateSources.PAGES,"com.gosen.chinese",url->{
            calls.add(url);
            if(url.equals(UpdateSources.PAGES))throw new java.net.SocketTimeoutException();
            return manifest();
        });
        assertEquals(2,result.versionCode);
        assertEquals(Arrays.asList(UpdateSources.PAGES,UpdateSources.RAW),calls);
    }
    @Test public void invalidPrimaryManifestCannotDisableValidBackup() throws Exception {
        AtomicInteger calls=new AtomicInteger();
        AppUpdateManager.UpdateInfo result=UpdateSources.load(UpdateSources.PAGES,"com.gosen.chinese",url->{
            calls.incrementAndGet();return url.equals(UpdateSources.PAGES)?manifest().put("sha256",""):manifest();
        });
        assertEquals(2,result.versionCode);assertEquals(2,calls.get());
    }
    @Test public void githubReleaseAssetIsAnIndependentThirdRoute() throws Exception {
        List<String> calls=new ArrayList<>();
        String asset="https://github.com/GosenGuo/gosen-chinese/releases/download/app-v0.1.1/app-update.json";
        AppUpdateManager.UpdateInfo result=UpdateSources.load(UpdateSources.PAGES,"com.gosen.chinese",url->{
            calls.add(url);
            if(url.equals(UpdateSources.RELEASES))return new JSONObject().put("assets",
                    new org.json.JSONArray().put(new JSONObject().put("name","app-update.json").put("browser_download_url",asset)));
            if(url.equals(asset))return manifest();
            throw new java.io.IOException("route unavailable");
        });
        assertEquals(2,result.versionCode);
        assertEquals(Arrays.asList(UpdateSources.PAGES,UpdateSources.RAW,UpdateSources.RELEASES,asset),calls);
    }
    @Test public void allRoutesFailRatherThanClaimingCachedVersionIsLatest() {
        assertThrows(IllegalStateException.class,()->UpdateSources.load(UpdateSources.PAGES,"com.gosen.chinese",url->{throw new java.io.IOException("offline");}));
    }
    @Test public void failedAutomaticCheckRetriesOnLaterForegroundAndManualFailureHasUsefulFeedback() {
        Activity activity=Robolectric.buildActivity(Activity.class).setup().get();
        activity.getSharedPreferences(AppUpdateManager.PREFS,Context.MODE_PRIVATE).edit().clear().commit();
        AtomicInteger calls=new AtomicInteger();AtomicLong now=new AtomicLong(1000);
        AppUpdateManager manager=new AppUpdateManager(activity,url->{calls.incrementAndGet();throw new java.io.IOException("offline");},new DirectExecutor(),now::get);
        try{
            manager.checkForUpdates();int first=calls.get();assertEquals(3,first);
            manager.checkForUpdates();assertEquals(first,calls.get());
            now.addAndGet(31_000);manager.checkForUpdates();assertEquals(first*2,calls.get());
            manager.checkForUpdates(true);
            assertEquals("暂时连接不上更新服务，下次打开会自动重试",ShadowToast.getTextOfLatestToast());
        }finally{manager.close();activity.finish();}
    }
    @Test public void automaticSuccessIsThrottledUntilNextForegroundWindow() throws Exception {
        Activity activity=Robolectric.buildActivity(Activity.class).setup().get();
        activity.getSharedPreferences(AppUpdateManager.PREFS,Context.MODE_PRIVATE).edit().clear().commit();
        AtomicInteger calls=new AtomicInteger();AtomicLong now=new AtomicLong(1000);
        AppUpdateManager manager=new AppUpdateManager(activity,url->{
            calls.incrementAndGet();return manifest().put("packageName",activity.getPackageName()).put("versionCode",BuildConfig.VERSION_CODE);
        },new DirectExecutor(),now::get);
        try{
            manager.checkForUpdates();manager.checkForUpdates();assertEquals(1,calls.get());
            now.addAndGet(181_000);manager.checkForUpdates();assertEquals(2,calls.get());
        }finally{manager.close();activity.finish();}
    }
    @Test public void automaticBackupResultActuallyOffersNewVersionWithoutASettingsButton() throws Exception {
        Activity activity=Robolectric.buildActivity(Activity.class).setup().get();
        activity.getSharedPreferences(AppUpdateManager.PREFS,Context.MODE_PRIVATE).edit().clear().commit();
        AppUpdateManager manager=new AppUpdateManager(activity,url->{
            if(url.equals(UpdateSources.PAGES))throw new java.net.SocketTimeoutException();
            return manifest().put("packageName",activity.getPackageName())
                    .put("versionCode",BuildConfig.VERSION_CODE+1).put("releaseNotes","自动更新线路修复");
        },new DirectExecutor(),()->1000L);
        try{
            manager.checkForUpdates();
            android.app.AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();
            assertNotNull(dialog);assertTrue(dialog.isShowing());
            assertEquals("下载更新",dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).getText().toString());
            android.widget.TextView message=dialog.findViewById(android.R.id.message);
            assertEquals("自动更新线路修复",message.getText().toString());
            dialog.dismiss();
        }finally{manager.close();activity.finish();}
    }
    @Test public void completingARequestAfterCloseCannotDisplayAnUpdateDialog() throws Exception {
        Activity activity=Robolectric.buildActivity(Activity.class).setup().get();
        activity.getSharedPreferences(AppUpdateManager.PREFS,Context.MODE_PRIVATE).edit().clear().commit();
        int existing=ShadowAlertDialog.getShownDialogs().size();
        AppUpdateManager[] holder=new AppUpdateManager[1];
        holder[0]=new AppUpdateManager(activity,url->{
            holder[0].close();
            return manifest().put("packageName",activity.getPackageName()).put("versionCode",BuildConfig.VERSION_CODE+1);
        },new DirectExecutor(),()->1000L);
        holder[0].checkForUpdates();
        assertEquals(existing,ShadowAlertDialog.getShownDialogs().size());
        activity.finish();
    }
    private static final class DirectExecutor extends AbstractExecutorService {
        private boolean shutdown;
        @Override public void shutdown(){shutdown=true;}
        @Override public List<Runnable> shutdownNow(){shutdown=true;return Collections.emptyList();}
        @Override public boolean isShutdown(){return shutdown;}
        @Override public boolean isTerminated(){return shutdown;}
        @Override public boolean awaitTermination(long timeout,TimeUnit unit){return shutdown;}
        @Override public void execute(Runnable action){if(shutdown)throw new java.util.concurrent.RejectedExecutionException();action.run();}
    }
}
