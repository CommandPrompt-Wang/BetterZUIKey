package moe.lovefirefly.betterzuikey.Hook;

import android.os.SystemClock;

import moe.lovefirefly.betterzuikey.Utils.LogHelper;
import static moe.lovefirefly.betterzuikey.Utils.LogHelper.VerboseLevel;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.DexKitCacheBridge;
import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.enums.StringMatchType;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.result.ClassData;
import org.luckypray.dexkit.result.ClassDataList;
import org.luckypray.dexkit.wrap.DexClass;

import java.util.NoSuchElementException;

/**
 * 用 DexKit 在输入法 APK 里定位目标类。
 *
 * <p>锚点只使用「混淆器改不了的事实」：这里是
 * {@code superClass = android.inputmethodservice.InputMethodService}。
 * 输入法服务类本身会被改名（实测搜狗 {@code coa}、GBoard {@code nix}），
 * 但父类字符串跨版本稳定，且服务类里的框架 override（onCreate / onKeyDown …）
 * 为了虚分派必须保留原名 —— 所以「父类定位 + override 名 hook」是一套
 * 与具体混淆结果无关的适配方式。
 *
 * <p>结果可选落盘（{@link DexKitCacheStore}）：appTag = 包名 + versionCode，
 * 进程重启直接命中缓存，省掉一次 dex 扫描。
 */
public final class ImeTargetResolver {

    private static final String TAG = "ImeTargetResolver";

    /** 输入法服务类的父类。 */
    public static final String IME_SERVICE_SUPERCLASS =
            "android.inputmethodservice.InputMethodService";

    /** 缓存显式 key：语义固定为「找到 IME 服务类」。 */
    public static final String KEY_IME_SERVICE = "ime-service";

    private static volatile boolean sCacheInitDone = false;
    private static volatile boolean sCacheUnavailable = false;

    private ImeTargetResolver() {}

    /**
     * 定位 IME 服务类。
     *
     * @param appTag  缓存命名空间，建议 {@code 包名:versionCode}
     * @param apkPath 宿主（输入法）APK 路径
     * @param cache   可选结果缓存，null 表示不缓存
     * @return 类名；未命中或出错返回 null
     */
    public static String resolveImeServiceClass(String appTag, String apkPath, DexKitCacheStore cache) {
        long t0 = SystemClock.uptimeMillis();
        String fromCache = findViaCacheBridge(appTag, apkPath, cache);
        if (fromCache != null) {
            LogHelper.log(VerboseLevel.INFO, TAG, ": IME service = ", fromCache,
                    " (cache-bridge, ", String.valueOf(SystemClock.uptimeMillis() - t0), "ms)");
            return fromCache;
        }
        String plain = findPlain(apkPath);
        LogHelper.log(VerboseLevel.INFO, TAG, ": IME service = ", String.valueOf(plain),
                " (bridge, ", String.valueOf(SystemClock.uptimeMillis() - t0), "ms)");
        return plain;
    }

    // ------------------------------------------------------------------
    // 带结果缓存的路径（DexKit 2.2 的 DexKitCacheBridge，实验性 API）
    // ------------------------------------------------------------------

    private static String findViaCacheBridge(String appTag, String apkPath, DexKitCacheStore cache) {
        if (cache == null || apkPath == null || sCacheUnavailable) return null;
        try {
            if (!sCacheInitDone) {
                // CachePolicy 默认只缓存成功结果；失败（未命中/不唯一）每次重查，
                // 避免版本更新后旧的失败态被永久复用。
                DexKitCacheBridge.setIdleTimeoutMillis(5000L);
                DexKitCacheBridge.init(cache);
                sCacheInitDone = true;
            }
            DexKitCacheBridge.RecyclableBridge bridge = DexKitCacheBridge.create(appTag, apkPath);
            try {
                DexClass found = bridge.getClass(KEY_IME_SERVICE, findClass ->
                        findClass.matcher(ClassMatcher.create().superClass(
                                IME_SERVICE_SUPERCLASS, StringMatchType.Equals, false)));
                return found != null ? found.getClassName() : null;
            } finally {
                bridge.close();
            }
        } catch (NoSuchElementException notFound) {
            // 缓存里没有、本次也没查到 —— 属于正常未命中，不要因此禁用缓存路径
            LogHelper.log(VerboseLevel.DEBUG, TAG, ": cache-bridge miss (no match)");
            return null;
        } catch (Throwable t) {
            sCacheUnavailable = true;
            LogHelper.log(VerboseLevel.WARNING, TAG,
                    ": DexKitCacheBridge unavailable, fallback to plain bridge: ", t.getMessage());
            return null;
        }
    }

    // ------------------------------------------------------------------
    // 裸 DexKitBridge 路径（兜底）
    // ------------------------------------------------------------------

    private static String findPlain(String apkPath) {
        if (apkPath == null) return null;
        try (DexKitBridge bridge = DexKitBridge.create(apkPath)) {
            ClassDataList list = bridge.findClass(FindClass.create().matcher(
                    ClassMatcher.create().superClass(
                            IME_SERVICE_SUPERCLASS, StringMatchType.Equals, false)));
            if (list == null || list.isEmpty()) {
                LogHelper.log(VerboseLevel.WARNING, TAG,
                        ": no InputMethodService subclass found in ", apkPath);
                return null;
            }
            if (list.size() > 1) {
                StringBuilder sb = new StringBuilder();
                for (ClassData c : list) {
                    sb.append(c.getName()).append(' ');
                }
                LogHelper.log(VerboseLevel.WARNING, TAG, ": ", String.valueOf(list.size()),
                        " candidates matched, using first: ", sb.toString().trim());
            }
            return list.get(0).getName();
        } catch (Throwable t) {
            LogHelper.log(VerboseLevel.ERROR, TAG, ": findPlain failed: ", t.getMessage());
            return null;
        }
    }
}
