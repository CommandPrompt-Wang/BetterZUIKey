package moe.lovefirefly.betterzuikey.Hook;

import android.content.pm.ApplicationInfo;
import android.os.Build;

import moe.lovefirefly.betterzuikey.Utils.LogHelper;
import static moe.lovefirefly.betterzuikey.Utils.LogHelper.VerboseLevel;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 在 hooked 进程（输入法）里加载 module 自带的 {@code libdexkit.so}。
 *
 * <p>难点：so 打包在 module APK 内，而我们的代码跑在**别人的进程**里，那个进程的
 * linker namespace 不包含 module 的原生库目录。三条按可靠性排序的路径：
 *
 * <ol>
 *   <li>{@code System.loadLibrary("dexkit")} — 若框架已把 module 的库目录挂到
 *       module ClassLoader 的 namespace，这是零成本路径；</li>
 *   <li>{@code System.load(moduleInfo.nativeLibraryDir + "/libdexkit.so")} —
 *       module 以 legacy packaging 安装时该目录有实体文件；</li>
 *   <li>从 module APK 的 {@code lib/<abi>/libdexkit.so} 解到**宿主自己的** data
 *       目录（hook 进程就是宿主的 uid，写自己的目录不需要任何权限），再
 *       {@code System.load(absPath)}。内容寻址（crc-size）做缓存。</li>
 * </ol>
 *
 * <p>全程失败只返回 false + 日志，绝不向宿主抛异常。
 */
public final class DexKitLoader {

    private static final String TAG = "DexKitLoader";
    private static final String LIB_FILE_NAME = "libdexkit.so";
    private static final String LIB_BASE_NAME = "libdexkit";
    private static final String LIB_ENTRY_PREFIX = "lib/";

    private static volatile boolean sLoaded = false;
    private static volatile String sSource = "none";

    private DexKitLoader() {}

    public static boolean isLoaded() {
        return sLoaded;
    }

    /** 人类可读的加载来源，用于诊断日志。 */
    public static String getSource() {
        return sSource;
    }

    /**
     * 保证 libdexkit.so 已加载。
     *
     * @param moduleInfo  module 的 ApplicationInfo（来自 getModuleApplicationInfo()），可为 null
     * @param hostDataDir 宿主 app 的 data 目录（hook 进程可写），可为 null
     * @return true 表示已加载（含此前已加载）
     */
    public static synchronized boolean ensureLoaded(ApplicationInfo moduleInfo, String hostDataDir) {
        if (sLoaded) return true;

        if (tryLoadLibrary()) return true;
        if (tryLoadFromNativeLibraryDir(moduleInfo)) return true;
        if (tryLoadFromApk(moduleInfo, hostDataDir)) return true;

        LogHelper.log(VerboseLevel.ERROR, TAG, ": all load strategies failed");
        return false;
    }

    // ------------------------------------------------------------------
    // 策略 1：框架已配好 namespace
    // ------------------------------------------------------------------

    private static boolean tryLoadLibrary() {
        try {
            System.loadLibrary("dexkit");
            markLoaded("System.loadLibrary(\"dexkit\")");
            return true;
        } catch (Throwable t) {
            LogHelper.log(VerboseLevel.DEBUG, TAG, ": loadLibrary failed: ", t.getMessage());
            return false;
        }
    }

    // ------------------------------------------------------------------
    // 策略 2：module 的 nativeLibraryDir
    // ------------------------------------------------------------------

    private static boolean tryLoadFromNativeLibraryDir(ApplicationInfo moduleInfo) {
        if (moduleInfo == null || moduleInfo.nativeLibraryDir == null) return false;
        File lib = new File(moduleInfo.nativeLibraryDir, LIB_FILE_NAME);
        if (!lib.isFile()) return false;
        return loadFile(lib, "nativeLibraryDir");
    }

    // ------------------------------------------------------------------
    // 策略 3：从 module APK 解压到宿主 data 目录
    // ------------------------------------------------------------------

    private static boolean tryLoadFromApk(ApplicationInfo moduleInfo, String hostDataDir) {
        if (moduleInfo == null || moduleInfo.sourceDir == null || hostDataDir == null) return false;
        try {
            String apkPath = moduleInfo.sourceDir;
            File outDir = new File(hostDataDir, "code_cache/dexkit");
            if (!outDir.isDirectory() && !outDir.mkdirs()) {
                LogHelper.log(VerboseLevel.ERROR, TAG, ": cannot create ", outDir.getAbsolutePath());
                return false;
            }

            // 一次开 zip：挑 ABI + 取 crc/size 做内容寻址 key
            String abi;
            String entryName;
            long crc;
            long size;
            try (ZipFile zip = new ZipFile(apkPath)) {
                ZipEntry entry = null;
                String pickedAbi = null;
                for (String candidate : Build.SUPPORTED_ABIS) {
                    ZipEntry e = zip.getEntry(LIB_ENTRY_PREFIX + candidate + "/" + LIB_FILE_NAME);
                    if (e != null) {
                        entry = e;
                        pickedAbi = candidate;
                        break;
                    }
                }
                if (entry == null) {
                    LogHelper.log(VerboseLevel.ERROR, TAG,
                            ": no libdexkit.so for any ABI in module apk");
                    return false;
                }
                abi = pickedAbi;
                entryName = entry.getName();
                crc = entry.getCrc();
                size = entry.getSize();
            }

            File lib = new File(outDir, LIB_BASE_NAME + "-" + abi + "-"
                    + Long.toHexString(crc) + "-" + size + ".so");
            if (!lib.isFile() || lib.length() != size) {
                extract(apkPath, entryName, lib, size);
            }
            boolean ok = loadFile(lib, "apk-extract(" + abi + ")");
            if (ok) cleanupSuperseded(outDir, lib);
            return ok;
        } catch (Throwable t) {
            LogHelper.log(VerboseLevel.ERROR, TAG, ": extract-from-apk failed: ", t.getMessage());
            return false;
        }
    }

    private static void extract(String apkPath, String entryName, File out, long expectedSize)
            throws Exception {
        File tmp = new File(out.getParentFile(), out.getName() + ".tmp");
        try (ZipFile zip = new ZipFile(apkPath)) {
            ZipEntry entry = zip.getEntry(entryName);
            if (entry == null) throw new IllegalStateException("missing entry " + entryName);
            try (InputStream in = zip.getInputStream(entry);
                 FileOutputStream fos = new FileOutputStream(tmp)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    fos.write(buf, 0, n);
                }
                fos.getFD().sync();
            }
        }
        if (expectedSize > 0 && tmp.length() != expectedSize) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw new IllegalStateException("extracted size mismatch");
        }
        try {
            Files.move(tmp.toPath(), out.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (Throwable atomicUnsupported) {
            //noinspection ResultOfMethodCallIgnored
            tmp.renameTo(out);
        }
    }

    /** 删掉旧的 libdexkit-*.so，只保留本次加载的那个。best-effort。 */
    private static void cleanupSuperseded(File dir, File keep) {
        try {
            File[] siblings = dir.listFiles((d, name) ->
                    name.startsWith(LIB_BASE_NAME + "-") && name.endsWith(".so"));
            if (siblings == null) return;
            for (File f : siblings) {
                if (!f.equals(keep)) {
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                }
            }
        } catch (Throwable ignored) {
            // 清理失败不影响加载
        }
    }

    // ------------------------------------------------------------------

    private static boolean loadFile(File lib, String how) {
        try {
            System.load(lib.getAbsolutePath());
            markLoaded(how + ":" + lib.getAbsolutePath());
            return true;
        } catch (Throwable t) {
            LogHelper.log(VerboseLevel.WARNING, TAG, ": System.load via ", how,
                    " failed: ", t.getMessage());
            return false;
        }
    }

    private static void markLoaded(String source) {
        sLoaded = true;
        sSource = source;
        LogHelper.log(VerboseLevel.INFO, TAG, ": libdexkit loaded via ", source);
    }
}
