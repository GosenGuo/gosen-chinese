package com.gosen.chinese;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Loads source-labelled learning material bundled with this independent app. */
public final class ContentRepository {
    private final Map<String, JSONObject> byId = new LinkedHashMap<>();
    private final List<String> errors = new ArrayList<>();
    private String catalogNote = "";

    public ContentRepository(Context context) {
        for (String name : new String[]{"classics.json", "practice.json", "essays.json"}) {
            try (InputStream input = context.getAssets().open(name)) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
                JSONObject root = new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
                if (root.optInt("schemaVersion", -1) != 1) {
                    throw new IllegalArgumentException("不支持的内容版本");
                }
                JSONObject catalog = root.optJSONObject("catalog");
                if (catalog != null) catalogNote = catalog.optString("note", "");
                JSONArray items = root.optJSONArray("items");
                if (items == null) throw new IllegalArgumentException("缺少内容列表");
                for (int i = 0; i < items.length(); i++) {
                    JSONObject item = items.optJSONObject(i);
                    if (item == null || item.optString("id").isEmpty()
                            || item.optString("module").isEmpty() || item.optString("body").trim().isEmpty()) {
                        errors.add(name + " 第" + (i + 1) + "条内容不完整");
                        continue;
                    }
                    String id = item.optString("id");
                    if (byId.containsKey(id)) {
                        errors.add("内容编号重复：" + id);
                        continue;
                    }
                    byId.put(id, item);
                }
            } catch (Exception exception) {
                errors.add(name + "：" + exception.getClass().getSimpleName());
            }
        }
    }

    public List<JSONObject> items(String module) {
        List<JSONObject> result = new ArrayList<>();
        for (JSONObject item : byId.values()) {
            if (module.equals(item.optString("module"))) result.add(item);
        }
        return Collections.unmodifiableList(result);
    }

    public List<JSONObject> all() { return Collections.unmodifiableList(new ArrayList<>(byId.values())); }
    public JSONObject find(String id) { return byId.get(id); }
    public String catalogNote() { return catalogNote; }
    public String error() { return String.join("\n", errors); }
}
