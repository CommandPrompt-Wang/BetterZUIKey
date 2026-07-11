package moe.lovefirefly.betterzuikey.Hook;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;

/**
 * Compatibility adapter: bridges the legacy Xposed callback API
 * (XC_MethodHook / MethodHookParam) to the libxposed API 101 chain model.
 *
 * <p>This allows existing interceptor classes to work with minimal changes:
 * <ul>
 *   <li>{@code HookCompat.findAndHookMethod} → {@link #hookMethod}</li>
 *   <li>{@code HookCompat.findAndHookConstructor} → {@link #hookConstructor}</li>
 *   <li>{@code MethodHookParam.args[i]} → {@code param.args[i]} (via {@link HookParam})</li>
 *   <li>{@code param.setResult(x)} → {@code param.setResultEarly(x)}</li>
 *   <li>{@code param.getResult()} → {@code param.getResult()}</li>
 * </ul>
 */
public final class HookCompat {

    private HookCompat() {}

    // ----------------------------------------------------------------
    //  HookParam — mirrors the old MethodHookParam API
    // ----------------------------------------------------------------

    /**
     * Mirror of the legacy {@code HookCompat.HookParam}.
     * Wraps libxposed {@link XposedInterface.Chain} with the same getter/setter names
     * so existing interceptor code compiles with minimal edits.
     */
    public static class HookParam {
        private final XposedInterface.Chain chain;
        private Object resultEarly;
        private boolean returnEarly;
        private Object proceedResult;

        public final Member method;
        public final Object thisObject;
        public final Object[] args;

        @SuppressWarnings("unchecked")
        HookParam(XposedInterface.Chain chain) {
            this.chain = chain;
            this.method = (Member) chain.getExecutable();
            // Try to extract thisObject via reflection (libxposed doesn't expose it directly)
            Object inst = null;
            try {
                java.lang.reflect.Field f = chain.getClass().getDeclaredField("instance");
                f.setAccessible(true);
                inst = f.get(chain);
            } catch (Exception ignored) {}
            this.thisObject = inst;
            java.util.List<Object> argList = chain.getArgs();
            this.args = argList != null ? argList.toArray(new Object[0]) : new Object[0];
        }

        void setProceedResult(Object result) { this.proceedResult = result; }

        /** Get the original method result (only meaningful in "after" callbacks). */
        public Object getResult() { return proceedResult; }

        /**
         * Set a result and skip original method execution.
         * Equivalent to the old {@code param.setResult(x)} in beforeHookedMethod.
         */
        public void setResult(Object result) {
            this.resultEarly = result;
            this.returnEarly = true;
        }

        /** Access the underlying chain (for advanced use). */
        public XposedInterface.Chain getChain() { return chain; }

        boolean isReturnEarly() { return returnEarly; }
        Object getResultEarly() { return resultEarly; }
    }

    // ----------------------------------------------------------------
    //  HookCallback — mirrors the old XC_MethodHook
    // ----------------------------------------------------------------

    /** Replacement for {@code XC_MethodHook}. */
    public abstract static class HookCallback {
        /**
         * Called before the original method executes.
         * Call {@link HookParam#setResult(Object)} to skip the original.
         */
        protected void beforeHookedMethod(HookParam param) throws Throwable {}

        /**
         * Called after the original method executes.
         * {@link HookParam#getResult()} returns the original method's return value.
         */
        protected void afterHookedMethod(HookParam param) throws Throwable {}
    }

    // ----------------------------------------------------------------
    //  Hook registration (replaces HookCompat.findAndHookMethod)
    // ----------------------------------------------------------------

    /**
     * Hook a method — replacement for {@code HookCompat.findAndHookMethod}.
     *
     * @param module      the XposedModule instance (MainHook)
     * @param className   fully-qualified class name
     * @param classLoader ClassLoader to load the target class
     * @param methodName  target method name
     * @param callback    before/after hook callbacks
     * @param paramTypes  method parameter types
     */
    public static void hookMethod(XposedModule module, String className, ClassLoader classLoader,
            String methodName, HookCallback callback, Class<?>... paramTypes) {
        try {
            Class<?> clazz = Class.forName(className, false, classLoader);
            Method method = findMethod(clazz, methodName, paramTypes);
            hookMethod(module, method, callback);
        } catch (Throwable t) {
            moe.lovefirefly.betterzuikey.Utils.LogHelper.log(
                    moe.lovefirefly.betterzuikey.Utils.LogHelper.VerboseLevel.ERROR,
                    "HookCompat: hookMethod failed for ", className, ".", methodName, ": ", t.getMessage());
        }
    }

    /**
     * Hook a method on an already-resolved Class.
     */
    public static void hookMethod(XposedModule module, Class<?> targetClass,
            String methodName, HookCallback callback, Class<?>... paramTypes) {
        try {
            Method method = findMethod(targetClass, methodName, paramTypes);
            hookMethod(module, method, callback);
        } catch (Throwable t) {
            moe.lovefirefly.betterzuikey.Utils.LogHelper.log(
                    moe.lovefirefly.betterzuikey.Utils.LogHelper.VerboseLevel.ERROR,
                    "HookCompat: hookMethod failed for ", targetClass.getName(), ".", methodName, ": ", t.getMessage());
        }
    }

    /**
     * Hook a resolved Method object.
     */
    public static void hookMethod(XposedModule module, Method method, HookCallback callback) {
        module.hook(method).intercept(chain -> {
            HookParam param = new HookParam(chain);
            try {
                callback.beforeHookedMethod(param);
            } catch (Throwable t) {
                moe.lovefirefly.betterzuikey.Utils.LogHelper.log(
                        moe.lovefirefly.betterzuikey.Utils.LogHelper.VerboseLevel.ERROR,
                        "HookCompat: beforeHookedMethod error: ", t.getMessage());
            }
            if (param.isReturnEarly()) {
                return param.getResultEarly();
            }
            Object result = chain.proceed();
            param.setProceedResult(result);
            try {
                callback.afterHookedMethod(param);
            } catch (Throwable t) {
                moe.lovefirefly.betterzuikey.Utils.LogHelper.log(
                        moe.lovefirefly.betterzuikey.Utils.LogHelper.VerboseLevel.ERROR,
                        "HookCompat: afterHookedMethod error: ", t.getMessage());
            }
            return param.isReturnEarly() ? param.getResultEarly() : result;
        });
    }

    /**
     * Hook a constructor — replacement for {@code HookCompat.findAndHookConstructor}.
     */
    public static void hookConstructor(XposedModule module, String className, ClassLoader classLoader,
            Class<?>[] paramTypes, java.util.function.Consumer<HookParam> afterCallback) {
        try {
            Class<?> clazz = Class.forName(className, false, classLoader);
            Constructor<?> ctor = findConstructor(clazz, paramTypes);
            hookConstructor(module, ctor, afterCallback);
        } catch (Throwable t) {
            moe.lovefirefly.betterzuikey.Utils.LogHelper.log(
                    moe.lovefirefly.betterzuikey.Utils.LogHelper.VerboseLevel.ERROR,
                    "HookCompat: hookConstructor failed for ", className, ": ", t.getMessage());
        }
    }

    /**
     * Hook a resolved Constructor object.
     */
    public static void hookConstructor(XposedModule module, Constructor<?> ctor,
            java.util.function.Consumer<HookParam> afterCallback) {
        module.hook(ctor).intercept(chain -> {
            Object result = chain.proceed();
            HookParam param = new HookParam(chain);
            afterCallback.accept(param);
            return result;
        });
    }

    /**
     * Hook all methods with a given name — replacement for {@code XposedBridge.hookAllMethods}.
     */
    public static void hookAllMethods(XposedModule module, Class<?> targetClass,
            String methodName, HookCallback callback) {
        for (Method method : targetClass.getDeclaredMethods()) {
            if (method.getName().equals(methodName)) {
                hookMethod(module, method, callback);
            }
        }
    }

    // ----------------------------------------------------------------
    //  Reflection helpers (replaces XposedHelpers utility methods)
    // ----------------------------------------------------------------

    /** Replacement for HookCompat.setStaticBooleanField. */
    public static void setStaticBooleanField(Class<?> clazz, String fieldName, boolean value) {
        try {
            Field field = clazz.getDeclaredField(fieldName);
            field.setAccessible(true);
            field.setBoolean(null, value);
        } catch (Throwable ignored) {}
    }

    /** Replacement for HookCompat.getObjectField. */
    public static Object getObjectField(Object obj, String fieldName) {
        try {
            Field field = obj.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            return field.get(obj);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Replacement for HookCompat.callStaticMethod. */
    @SuppressWarnings("unchecked")
    public static <T> T callStaticMethod(Class<?> clazz, String methodName, Object... args) {
        try {
            Class<?>[] paramTypes = new Class<?>[args.length];
            for (int i = 0; i < args.length; i++) {
                paramTypes[i] = args[i] != null ? args[i].getClass() : Object.class;
            }
            Method method = findMethod(clazz, methodName, paramTypes);
            return (T) method.invoke(null, args);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Call a method by reflection.
     *
     * <p><b>IMPORTANT:</b> {@code args[i].getClass()} yields the runtime type
     * (e.g. {@code KeyEvent.class}) which may be narrower than the declared
     * parameter type (e.g. {@code InputEvent.class}).  The count-only fallback
     * in {@link #findMethod} compensates for this mismatch.  Do NOT attempt to
     * "fix" the try-catch into a direct throw — callers such as
     * {@code KeyInjector.injectKeyDown} rely on silent {@code null} return
     * for best-effort injection that must never crash the hook chain.
     */
    @SuppressWarnings("unchecked")
    public static <T> T callMethod(Object obj, String methodName, Object... args) {
        try {
            Class<?>[] paramTypes = new Class<?>[args.length];
            for (int i = 0; i < args.length; i++) {
                paramTypes[i] = args[i] != null ? args[i].getClass() : Object.class;
            }
            Method method = findMethod(obj.getClass(), methodName, paramTypes);
            return (T) method.invoke(obj, args);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Replacement for HookCompat.findClass. */
    public static Class<?> findClass(String className, ClassLoader cl) {
        try {
            return cl != null ? Class.forName(className, false, cl) : Class.forName(className);
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    // ---- internal ----

    /**
     * Find a declared method, with count-only fallback.
     *
     * <p>The exact match can fail when {@link #callMethod} passes runtime
     * types (e.g. {@code KeyEvent.class}) but the method is declared with a
     * parent type (e.g. {@code injectInputEvent(InputEvent, int)}).  The
     * count-only fallback handles this mismatch.
     */
    private static Method findMethod(Class<?> clazz, String name, Class<?>[] paramTypes)
            throws NoSuchMethodException {
        try {
            Method m = clazz.getDeclaredMethod(name, paramTypes);
            m.setAccessible(true);
            return m;
        } catch (NoSuchMethodException e) {
            // DO NOT REMOVE: count-only fallback is essential.
            // callMethod() derives param types from args[i].getClass() at runtime
            // (e.g. KeyEvent.class), which won't exactly match declared parameter
            // types (e.g. InputEvent.class) or primitive wrappers (Integer vs int).
            // Removing this fallback silently breaks injectInputEvent and all
            // other reflection-based calls — no compile error, no runtime crash,
            // just null returns and silently dropped events.
            for (Method m : clazz.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == paramTypes.length) {
                    m.setAccessible(true);
                    return m;
                }
            }
            throw e;
        }
    }

    private static Constructor<?> findConstructor(Class<?> clazz, Class<?>[] paramTypes)
            throws NoSuchMethodException {
        try {
            Constructor<?> c = clazz.getDeclaredConstructor(paramTypes);
            c.setAccessible(true);
            return c;
        } catch (NoSuchMethodException e) {
            for (Constructor<?> c : clazz.getDeclaredConstructors()) {
                if (c.getParameterCount() == paramTypes.length) {
                    c.setAccessible(true);
                    return c;
                }
            }
            throw e;
        }
    }
}
