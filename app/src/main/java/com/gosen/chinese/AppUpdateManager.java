package com.gosen.chinese;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;
import android.widget.Toast;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/** Optional, signed in-place updates. Every install handoff consumes its pending state. */
public final class AppUpdateManager {
    static final String PREFS = "gosen_chinese_updates_v1";
    private final Activity activity;
    private final SharedPreferences prefs;
    private final DownloadManager downloads;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private volatile boolean closed, checking, verifying;
    private boolean dialogVisible;
    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(intent.getAction())) {
                long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -2);
                if (id == prefs.getLong("downloadId", -1)) inspectDownload(id);
            }
        }
    };

    public AppUpdateManager(Activity activity) {
        this.activity = activity;
        prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        downloads = (DownloadManager) activity.getSystemService(Context.DOWNLOAD_SERVICE);
        IntentFilter filter = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        if (Build.VERSION.SDK_INT >= 33) activity.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
        else activity.registerReceiver(receiver, filter);
    }

    public void checkForUpdates() { checkForUpdates(false); }
    public void checkForUpdates(boolean userInitiated) {
        if (checking || closed) { if (userInitiated) message("正在检查更新"); return; }
        checking = true;
        execute(() -> {
            try {
                UpdateInfo info = fetch(BuildConfig.UPDATE_MANIFEST_URL);
                ui(() -> {
                    if (info.versionCode <= BuildConfig.VERSION_CODE) {
                        if (userInitiated) message("已是最新版本 " + BuildConfig.VERSION_NAME);
                    } else if (userInitiated || info.versionCode > prefs.getInt("dismissedVersion", 0)) {
                        showOffer(info);
                    }
                });
            } catch (Exception error) {
                if (userInitiated) ui(() -> message("暂时无法检查更新，请稍后重试"));
            } finally { checking = false; }
        });
    }

    public void resumePendingInstall() {
        if (closed) return;
        int code = prefs.getInt("versionCode", -1);
        if (code > 0 && code <= BuildConfig.VERSION_CODE) { clearPending(); return; }
        if (prefs.getBoolean("waitingPermission", false)) {
            prefs.edit().putBoolean("waitingPermission", false).apply();
            if (canInstall()) inspectDownload(prefs.getLong("downloadId", -1));
            else message("安装许可未开启，可在设置中重新检查更新");
            return;
        }
        // Only an unfinished download is resumed. Cancelled installer handoffs have no pending ID.
        if (!prefs.getBoolean("verified", false)) inspectDownload(prefs.getLong("downloadId", -1));
    }

    public void close() {
        closed = true;
        worker.shutdownNow();
        try { activity.unregisterReceiver(receiver); } catch (IllegalArgumentException ignored) { }
    }

    static UpdateInfo parseManifest(JSONObject json, String packageName) throws Exception {
        int versionCode = json.getInt("versionCode");
        String versionName = json.getString("versionName").trim();
        String apkUrl = json.getString("apkUrl").trim();
        String sha = json.getString("sha256").trim().toLowerCase(Locale.ROOT);
        String cert = json.getString("certificateSha256").replace(":", "").trim().toLowerCase(Locale.ROOT);
        if (versionCode < 1 || versionName.isEmpty()) throw new IllegalArgumentException("Invalid version");
        if (!packageName.equals(json.getString("packageName"))) throw new IllegalArgumentException("Wrong package");
        URL url = new URL(apkUrl);
        if (!"https".equals(url.getProtocol()) || url.getHost().isEmpty()) throw new IllegalArgumentException("HTTPS required");
        if (!sha.matches("[0-9a-f]{64}") || !cert.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Missing digest");
        return new UpdateInfo(versionCode, versionName, apkUrl, sha, cert,
                json.optString("releaseNotes", "改进学习体验并修复问题。"));
    }

    private UpdateInfo fetch(String url) throws Exception {
        for (int redirects=0; redirects<5; redirects++) {
            URL address = new URL(url);
            if (!"https".equals(address.getProtocol())) throw new IllegalArgumentException("HTTPS required");
            HttpURLConnection connection = (HttpURLConnection) address.openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(10_000); connection.setReadTimeout(15_000);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", "GosenChinese/" + BuildConfig.VERSION_NAME);
            try {
                int status = connection.getResponseCode();
                if (status>=300 && status<400) {
                    url = new URL(address, connection.getHeaderField("Location")).toString(); continue;
                }
                if (status != 200) throw new IllegalStateException("Update service unavailable");
                try (InputStream stream=connection.getInputStream(); ByteArrayOutputStream out=new ByteArrayOutputStream()) {
                    byte[] buffer=new byte[4096]; int count;
                    while ((count=stream.read(buffer))!=-1) {
                        if (out.size()+count>131072) throw new IllegalArgumentException("Manifest too large");
                        out.write(buffer,0,count);
                    }
                    return parseManifest(new JSONObject(out.toString(StandardCharsets.UTF_8.name())), activity.getPackageName());
                }
            } finally { connection.disconnect(); }
        }
        throw new IllegalStateException("Too many redirects");
    }

    private void showOffer(UpdateInfo info) {
        if (dialogVisible || !usable()) return;
        dialogVisible=true;
        AlertDialog dialog=new AlertDialog.Builder(activity).setTitle("发现新版本 " + info.versionName)
                .setMessage(info.releaseNotes)
                .setNegativeButton("以后再说",(d,w)->dismissVersion(info.versionCode))
                .setPositiveButton("下载更新",(d,w)->startDownload(info)).create();
        dialog.setOnCancelListener(d->dismissVersion(info.versionCode));
        dialog.setOnDismissListener(d->dialogVisible=false);
        dialog.show();
    }

    private void dismissVersion(int code) { prefs.edit().putInt("dismissedVersion",code).apply(); }

    private void startDownload(UpdateInfo info) {
        if (downloads==null) { message("系统下载服务不可用"); return; }
        try {
            long old=prefs.getLong("downloadId",-1);
            if (old>=0) downloads.remove(old);
            clearPending();
            String name="GosenChinese-"+info.versionCode+".apk";
            File file=updateFile(name);
            if (file.exists() && !file.delete()) throw new IllegalStateException("Cannot replace APK");
            DownloadManager.Request request=new DownloadManager.Request(Uri.parse(info.apkUrl))
                    .setTitle("拾文更新 "+info.versionName)
                    .setMimeType("application/vnd.android.package-archive")
                    .setAllowedOverMetered(true).setAllowedOverRoaming(false)
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationInExternalFilesDir(activity,Environment.DIRECTORY_DOWNLOADS,name);
            long id=downloads.enqueue(request);
            prefs.edit().putLong("downloadId",id).putString("fileName",name)
                    .putString("sha256",info.sha256).putString("certificateSha256",info.certificateSha256)
                    .putString("apkUrl",info.apkUrl).putString("versionName",info.versionName)
                    .putString("releaseNotes",info.releaseNotes).putInt("versionCode",info.versionCode)
                    .putBoolean("verified",false).putBoolean("waitingPermission",false).apply();
            message("更新包正在下载");
        } catch (Exception error) { clearPending(); offerRetry(info,"下载未能开始"); }
    }

    private void inspectDownload(long id) {
        if (downloads==null || id<0 || closed || verifying) return;
        try (Cursor cursor=downloads.query(new DownloadManager.Query().setFilterById(id))) {
            if (cursor==null || !cursor.moveToFirst()) { clearPending(); return; }
            int status=cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
            if (status==DownloadManager.STATUS_PENDING||status==DownloadManager.STATUS_RUNNING||status==DownloadManager.STATUS_PAUSED) return;
            if (status!=DownloadManager.STATUS_SUCCESSFUL) { failedDownload("更新包下载失败"); return; }
        } catch (Exception error) { failedDownload("暂时无法读取更新包"); return; }
        final UpdateInfo info=pendingInfo();
        final String name=prefs.getString("fileName","");
        verifying=true;
        execute(()-> {
            String error;
            File file=null;
            try { file=updateFile(name); error=verifyApk(file,info); }
            catch (Exception e) { error="更新包验证失败"; }
            final File apk=file; final String result=error;
            ui(()-> {
                verifying=false;
                if (id!=prefs.getLong("downloadId",-1)) return;
                if (result!=null) {
                    if (apk!=null) apk.delete();
                    clearPending(); offerRetry(info,result); return;
                }
                prefs.edit().putBoolean("verified",true).apply();
                requestInstall(apk);
            });
        });
    }

    private String verifyApk(File file, UpdateInfo info) throws Exception {
        if (info==null || !checksumMatches(file,info.sha256)) return "安装包校验失败，已取消安装";
        PackageManager pm=activity.getPackageManager();
        int flags=Build.VERSION.SDK_INT>=28?PackageManager.GET_SIGNING_CERTIFICATES:PackageManager.GET_SIGNATURES;
        PackageInfo archive=pm.getPackageArchiveInfo(file.getAbsolutePath(),flags);
        PackageInfo installed=pm.getPackageInfo(activity.getPackageName(),flags);
        if (!packageMetadataMatches(archive,activity.getPackageName(),info.versionCode,BuildConfig.VERSION_CODE)) return "更新包包名或版本不匹配";
        if (!signaturesMatch(signatures(installed),signatures(archive),info.certificateSha256)) return "安装包签名与当前应用不一致";
        return null;
    }

    static boolean packageMetadataMatches(PackageInfo archive,String packageName,int expectedCode,int currentCode) {
        if(archive==null||!packageName.equals(archive.packageName)) return false;
        long archiveCode=Build.VERSION.SDK_INT>=28?archive.getLongVersionCode():archive.versionCode;
        return archiveCode==expectedCode&&archiveCode>currentCode;
    }

    private static Signature[] signatures(PackageInfo info) {
        if (info==null) return null;
        if (Build.VERSION.SDK_INT>=28) return info.signingInfo==null?null:info.signingInfo.getApkContentsSigners();
        return info.signatures;
    }

    static boolean signaturesMatch(Signature[] installed, Signature[] archive, String expectedCertificate) {
        if (installed==null||archive==null||installed.length!=1||archive.length!=1) return false;
        if (expectedCertificate==null||!expectedCertificate.matches("[0-9a-fA-F]{64}")) return false;
        return Arrays.equals(installed[0].toByteArray(),archive[0].toByteArray())
                && expectedCertificate.equalsIgnoreCase(digest(archive[0].toByteArray()));
    }

    static boolean checksumMatches(File file,String expected) {
        if (file==null||!file.isFile()||file.length()==0||expected==null||!expected.matches("[0-9a-fA-F]{64}")) return false;
        try (InputStream stream=new FileInputStream(file)) {
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
            byte[] buffer=new byte[8192]; int count;
            while ((count=stream.read(buffer))!=-1) digest.update(buffer,0,count);
            return expected.equalsIgnoreCase(hex(digest.digest()));
        } catch (Exception error) { return false; }
    }

    static String digest(byte[] bytes) {
        try { return hex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception error) { throw new IllegalStateException(error); }
    }
    private static String hex(byte[] bytes) {
        StringBuilder out=new StringBuilder();
        for(byte value:bytes) out.append(String.format(Locale.ROOT,"%02x",value&255));
        return out.toString();
    }

    private void requestInstall(File file) {
        if (!canInstall()) {
            prefs.edit().putBoolean("waitingPermission",true).apply();
            try {
                message("请允许拾文安装更新包");
                activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+activity.getPackageName())));
            } catch (Exception error) { prefs.edit().putBoolean("waitingPermission",false).apply(); message("请在系统设置中开启安装许可"); }
            return;
        }
        Intent install=new Intent(Intent.ACTION_VIEW)
                .setDataAndType(new Uri.Builder().scheme("content").authority(activity.getPackageName()+".apk-files").appendPath(file.getName()).build(),"application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        // Clear BEFORE leaving: returning from a cancelled installer must not relaunch it.
        clearPending();
        try { activity.startActivity(install); }
        catch (Exception error) { message("无法打开系统安装界面，请重新检查更新"); }
    }

    private boolean canInstall() { return Build.VERSION.SDK_INT<26||activity.getPackageManager().canRequestPackageInstalls(); }
    private File updateFile(String name) {
        if (!name.matches("GosenChinese-[0-9]+\\.apk")) throw new IllegalArgumentException("Invalid APK name");
        File directory=activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if(directory==null) throw new IllegalStateException("Download directory unavailable");
        return new File(directory,name);
    }
    private UpdateInfo pendingInfo() {
        try {
            return new UpdateInfo(prefs.getInt("versionCode",-1),prefs.getString("versionName",""),prefs.getString("apkUrl",""),
                    prefs.getString("sha256",""),prefs.getString("certificateSha256",""),prefs.getString("releaseNotes",""));
        } catch(Exception error) { return null; }
    }
    private void failedDownload(String message) { UpdateInfo info=pendingInfo(); clearPending(); ui(()->offerRetry(info,message)); }
    private void offerRetry(UpdateInfo info,String reason) {
        if (!usable()) return;
        new AlertDialog.Builder(activity).setTitle("更新未完成").setMessage(reason)
                .setNegativeButton("稍后再试",null)
                .setPositiveButton("重试",(d,w)->{if(info!=null)startDownload(info);else checkForUpdates(true);}).show();
    }
    private void clearPending() { prefs.edit().remove("downloadId").remove("fileName").remove("sha256").remove("certificateSha256")
            .remove("versionCode").remove("versionName").remove("apkUrl").remove("releaseNotes").remove("verified").remove("waitingPermission").apply(); }
    private boolean usable() { return !closed&&!activity.isFinishing()&&!activity.isDestroyed(); }
    private void ui(Runnable action) { activity.runOnUiThread(()->{if(usable())action.run();}); }
    private void message(String text) { if(usable())Toast.makeText(activity,text,Toast.LENGTH_LONG).show(); }
    private void execute(Runnable action) { try { if(!closed)worker.execute(action); } catch(RejectedExecutionException ignored) { } }

    static final class UpdateInfo {
        final int versionCode; final String versionName,apkUrl,sha256,certificateSha256,releaseNotes;
        UpdateInfo(int code,String name,String url,String sha,String cert,String notes) {
            versionCode=code;versionName=name;apkUrl=url;sha256=sha;certificateSha256=cert;releaseNotes=notes;
        }
    }
}
