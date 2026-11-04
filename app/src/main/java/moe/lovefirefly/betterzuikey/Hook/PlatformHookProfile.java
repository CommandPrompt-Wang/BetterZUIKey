package moe.lovefirefly.betterzuikey.Hook;

/** SDK routing is deliberately finite; ROM version strings are diagnostic only.
 * All selected entry points still require an exact runtime capability check.
 */
public final class PlatformHookProfile {
    public final int sdk;
    public final String name;

    private PlatformHookProfile(int sdk, String name) {
        this.sdk = sdk;
        this.name = name;
    }

    public static PlatformHookProfile forSdk(int sdk) {
        switch (sdk) {
            case 34: return new PlatformHookProfile(sdk, "sdk34-unsupported-unverified");
            case 35: return new PlatformHookProfile(sdk, "sdk35-legacy-input-chain");
            case 36: return new PlatformHookProfile(sdk, "sdk36-legacy");
            case 37: return new PlatformHookProfile(sdk, "sdk37-split-key-capture");
            default: return new PlatformHookProfile(sdk, "unsupported-sdk");
        }
    }

    public boolean supportsVersionDependentHooks() { return sdk >= 35 && sdk <= 37; }
    public boolean usesLegacyInputChain() { return sdk == 35; }
    public boolean supportsLegacyAssistantRunnable() { return sdk >= 35 && sdk <= 36; }
    public boolean hasPolicyFlagsInPwmDispatch() { return sdk != 37; }

    /** SDK37 services DEX: 117/118 branch, mPendingMetaAction, gesture 21, true return.
     * Other split interception stages are intentionally not suppressed.
     */
    public String keyGestureMetaMethod() {
        if (usesLegacyInputChain()) return null;
        if (!supportsVersionDependentHooks()) return null;
        return sdk == 37 ? "interceptShortcutsAfterKeyCapture"
                : "interceptSystemKeysAndShortcuts";
    }
}
