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
}
