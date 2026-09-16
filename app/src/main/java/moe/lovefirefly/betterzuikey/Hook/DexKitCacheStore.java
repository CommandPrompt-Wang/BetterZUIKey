package moe.lovefirefly.betterzuikey.Hook;

import com.google.gson.Gson;

import org.luckypray.dexkit.DexKitCacheBridge;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link DexKitCacheBridge.Cache} 的文件后端实现。
 *
 * <p>DexKit 的查询结果本身就是可序列化字符串（单个结果 / 字符串列表），所以只需要
 * 两种值。这里用一个 JSON 文件承载，落在**宿主 app 自己的** code_cache 目录：
 * hook 进程就是宿主的 uid，写自己的目录不需要任何跨进程授权，也就绕开了
 * 「XSharedPreferences 只读」那一整类问题。
 *
 * <p>命名空间由 {@code appTag}（包名 + versionCode）保证，版本一变就是新 key，
 * 不会读到上一个版本的结果。
 */
public final class DexKitCacheStore implements DexKitCacheBridge.Cache {

    /** JSON 落盘结构。 */
    private static final class Snapshot {
        Map<String, String> s = new LinkedHashMap<>();
        Map<String, List<String>> l = new LinkedHashMap<>();
    }

    private static final Gson GSON = new Gson();

    private final File file;
    private final Snapshot data = new Snapshot();
    private boolean loaded = false;

    public DexKitCacheStore(File file) {
        this.file = file;
    }

    // ------------------------------------------------------------------
    // DexKitCacheBridge.Cache
    // ------------------------------------------------------------------

    @Override
    public synchronized String getString(String key, String defaultValue) {
        ensureLoaded();
        String v = data.s.get(key);
        return v != null ? v : defaultValue;
    }

    @Override
    public synchronized void putString(String key, String value) {
        ensureLoaded();
        data.s.put(key, value);
        flush();
    }

    @Override
    public synchronized List<String> getStringList(String key, List<String> defaultValue) {
        ensureLoaded();
        List<String> v = data.l.get(key);
        return v != null ? v : defaultValue;
    }

    @Override
    public synchronized void putStringList(String key, List<String> value) {
        ensureLoaded();
        data.l.put(key, value);
        flush();
    }

    @Override
    public synchronized void remove(String key) {
        ensureLoaded();
        data.s.remove(key);
        data.l.remove(key);
        flush();
    }

    @Override
    public synchronized Collection<String> getAllKeys() {
        ensureLoaded();
        List<String> all = new ArrayList<>(data.s.size() + data.l.size());
        all.addAll(data.s.keySet());
        for (String k : data.l.keySet()) {
            if (!data.s.containsKey(k)) all.add(k);
        }
        return all;
    }

    @Override
    public synchronized void clearAll() {
        ensureLoaded();
        data.s.clear();
        data.l.clear();
        flush();
    }

    // ------------------------------------------------------------------

    private void ensureLoaded() {
        if (loaded) return;
        loaded = true;
        if (file == null || !file.isFile()) return;
        try (Reader r = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
            Snapshot snap = GSON.fromJson(r, Snapshot.class);
            if (snap != null) {
                if (snap.s != null) data.s.putAll(snap.s);
                if (snap.l != null) data.l.putAll(snap.l);
            }
        } catch (Throwable ignored) {
            // 缓存损坏 → 当作空缓存，下次查询自然重建
        }
    }

    private void flush() {
        if (file == null) return;
        File tmp = null;
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.isDirectory()) {
                //noinspection ResultOfMethodCallIgnored
                parent.mkdirs();
            }
            tmp = new File(parent, file.getName() + ".tmp");
            try (Writer w = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8)) {
                GSON.toJson(data, w);
            }
            if (!tmp.renameTo(file)) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
                //noinspection ResultOfMethodCallIgnored
                tmp.renameTo(file);
            }
        } catch (Throwable ignored) {
            // 缓存写失败不能影响 hook 主流程
        }
    }
}
