package moe.lovefirefly.betterzuikey.Hook;

import org.junit.Test;
import static org.junit.Assert.*;

public class PlatformHookProfileTest {
    @Test public void sdk36PreservesLegacyEntryPoints() {
        PlatformHookProfile p = PlatformHookProfile.forSdk(36);
        assertTrue(p.supportsVersionDependentHooks());
        assertTrue(p.supportsLegacyAssistantRunnable());
        assertTrue(p.hasPolicyFlagsInPwmDispatch());
        assertEquals("interceptSystemKeysAndShortcuts", p.keyGestureMetaMethod());
    }

    @Test public void sdk37SelectsOnlyProvenMetaConsumer() {
        PlatformHookProfile p = PlatformHookProfile.forSdk(37);
        assertTrue(p.supportsVersionDependentHooks());
        assertFalse(p.supportsLegacyAssistantRunnable());
        assertFalse(p.hasPolicyFlagsInPwmDispatch());
        assertEquals("interceptShortcutsAfterKeyCapture", p.keyGestureMetaMethod());
    }

    @Test         public void sdk35UsesDedicatedLegacyInputChain() {
        PlatformHookProfile p = PlatformHookProfile.forSdk(35);
        assertTrue(p.supportsVersionDependentHooks());
        assertTrue(p.usesLegacyInputChain());
        assertTrue(p.supportsLegacyAssistantRunnable());
        assertNull(p.keyGestureMetaMethod());
    }

    @Test public void sdk34RemainsUnsupportedUntilItsArchitectureIsVerified() {
        PlatformHookProfile p = PlatformHookProfile.forSdk(34);
        assertFalse(p.supportsVersionDependentHooks());
        assertFalse(p.usesLegacyInputChain());
        assertFalse(p.supportsLegacyAssistantRunnable());
        assertNull(p.keyGestureMetaMethod());
    }

    @Test public void legacyArchitecturesUseDedicatedInputChain() {
        for (int sdk : new int[]{35}) {
            PlatformHookProfile p = PlatformHookProfile.forSdk(sdk);
            assertTrue(p.supportsVersionDependentHooks());
            assertTrue(p.usesLegacyInputChain());
            assertTrue(p.supportsLegacyAssistantRunnable());
            assertNull(p.keyGestureMetaMethod());
        }
    }

    @Test public void unknownSdksNeverReuseKnownHooks() {
        for (int sdk : new int[]{Integer.MIN_VALUE, -1, 0, 33, 38, 99, Integer.MAX_VALUE}) {
            PlatformHookProfile p = PlatformHookProfile.forSdk(sdk);
            assertFalse(p.supportsVersionDependentHooks());
            assertFalse(p.supportsLegacyAssistantRunnable());
            assertNull(p.keyGestureMetaMethod());
        }
    }
}
