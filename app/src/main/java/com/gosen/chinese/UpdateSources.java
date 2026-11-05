package com.gosen.chinese;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.LinkedHashSet;
import java.util.Set;

/** Independent public routes to the same signed release metadata. */
final class UpdateSources {
    static final String PAGES="https://gosenguo.github.io/gosen-chinese/app-update.json";
    static final String RAW="https://raw.githubusercontent.com/GosenGuo/gosen-chinese/main/release/app-update.json";
    static final String RELEASES="https://api.github.com/repos/GosenGuo/gosen-chinese/releases/latest";
    interface Fetcher { JSONObject fetch(String url) throws Exception; }

    static AppUpdateManager.UpdateInfo load(String primary,String packageName,Fetcher fetcher) throws Exception {
        Exception last=null;
        Set<String> routes=new LinkedHashSet<>();
        if(primary!=null&&!primary.trim().isEmpty())routes.add(primary.trim());
        routes.add(PAGES);routes.add(RAW);
        for(String route:routes) {
            try { return AppUpdateManager.parseManifest(fetcher.fetch(route),packageName); }
            catch(Exception failure) { last=failure; }
        }
        try {
            JSONObject release=fetcher.fetch(RELEASES);
            if(release.optBoolean("draft")||release.optBoolean("prerelease"))throw new IllegalArgumentException("Not a stable release");
            JSONArray assets=release.getJSONArray("assets");
            for(int i=0;i<assets.length();i++){
                JSONObject asset=assets.getJSONObject(i);
                if("app-update.json".equals(asset.optString("name"))) {
                    String url=asset.getString("browser_download_url");
                    java.net.URL address=new java.net.URL(url);
                    if(!"https".equals(address.getProtocol())||address.getUserInfo()!=null
                            ||!"github.com".equals(address.getHost()))throw new IllegalArgumentException("Invalid release metadata asset");
                    return AppUpdateManager.parseManifest(fetcher.fetch(url),packageName);
                }
            }
            throw new IllegalStateException("Release manifest asset missing");
        } catch(Exception failure) { last=failure; }
        throw new IllegalStateException("All update routes unavailable",last);
    }
}
