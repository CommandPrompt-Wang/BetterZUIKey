package moe.lovefirefly.betterzuikey.Hook;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MetaPassthroughStateTest {
    private static MetaPassthroughState.Event<String> event(
            String value, int key, boolean down, boolean modifier) {
        return new MetaPassthroughState.Event<>(value, 1, key, down, modifier);
    }

    private static List<String> values(MetaPassthroughState.Result<String> result) {
        return result.output.stream()
                .map(item -> item.event.value + (item.stripMeta ? " clean" : ""))
                .collect(Collectors.toList());
    }

    @Test
    public void unhandledKeyIsReplayedBetweenMetaDownAndUp() {
        MetaPassthroughState<String> state = new MetaPassthroughState<>();
        state.metaDown(event("meta-down", 117, true, true));

        MetaPassthroughState.Result<String> down =
                state.companion(event("x-down", 52, true, false), false);
        assertTrue(down.consumed);
        assertEquals(Arrays.asList("meta-down", "x-down"), values(down));

        assertEquals(Arrays.asList("x-repeat"),
                values(state.companion(event("x-repeat", 52, true, false), false)));
        assertEquals(Arrays.asList("meta-up"),
                values(state.metaUp(event("meta-up", 117, false, true))));
        MetaPassthroughState.Result<String> up =
                state.companion(event("x-up", 52, false, false), false);
        assertTrue(up.consumed);
        assertEquals(Arrays.asList("x-up"), values(up));
    }

    @Test
    public void priorityShortcutDoesNotEmitMeta() {
        MetaPassthroughState<String> state = new MetaPassthroughState<>();
        state.metaDown(event("meta-down", 117, true, true));
        MetaPassthroughState.Result<String> result =
                state.companion(event("system-down", 33, true, false), true);
        assertFalse(result.consumed);
        assertTrue(result.output.isEmpty());
        assertTrue(state.metaUp(event("meta-up", 117, false, true)).output.isEmpty());
    }

    @Test
    public void modifiersAreBufferedUntilPriorityDecision() {
        MetaPassthroughState<String> state = new MetaPassthroughState<>();
        state.metaDown(event("meta-down", 117, true, true));
        assertTrue(state.companion(event("ctrl-down", 113, true, true), false).consumed);
        MetaPassthroughState.Result<String> result =
                state.companion(event("x-down", 52, true, false), false);
        assertEquals(Arrays.asList("meta-down", "ctrl-down", "x-down"), values(result));
        assertEquals(Arrays.asList("ctrl-up"),
                values(state.companion(event("ctrl-up", 113, false, true), false)));
    }

    @Test
    public void priorityFlushesBufferedModifiersWithoutMeta() {
        MetaPassthroughState<String> state = new MetaPassthroughState<>();
        state.metaDown(event("meta-down", 117, true, true));
        state.companion(event("alt-down", 57, true, true), false);
        MetaPassthroughState.Result<String> result =
                state.companion(event("system-down", 10, true, false), true);
        assertFalse(result.consumed);
        assertEquals(Arrays.asList("alt-down clean"), values(result));
        assertEquals(Arrays.asList("alt-up clean"),
                values(state.companion(event("alt-up", 57, false, true), false)));
    }

    @Test
    public void releasingOneMetaSideOnlyReleasesThatSide() {
        MetaPassthroughState<String> state = new MetaPassthroughState<>();
        state.metaDown(event("left-down", 117, true, true));
        state.metaDown(event("right-down", 118, true, true));
        assertEquals(Arrays.asList("left-down", "right-down", "x-down"),
                values(state.companion(event("x-down", 52, true, false), false)));
        assertEquals(Arrays.asList("left-up"),
                values(state.metaUp(event("left-up", 117, false, true))));
        assertEquals(Arrays.asList("right-up"),
                values(state.metaUp(event("right-up", 118, false, true))));
    }

    @Test
    public void releasedModifierIsReplayedInOrderBeforeTrigger() {
        MetaPassthroughState<String> state = new MetaPassthroughState<>();
        state.metaDown(event("meta-down", 117, true, true));
        state.companion(event("ctrl-down", 113, true, true), false);
        state.companion(event("ctrl-up", 113, false, true), false);

        assertEquals(Arrays.asList("meta-down", "ctrl-down", "ctrl-up", "x-down"),
                values(state.companion(event("x-down", 52, true, false), false)));
    }

    @Test
    public void sameKeyOnAnotherDeviceIsNotMistakenForCapturedKey() {
        MetaPassthroughState<String> state = new MetaPassthroughState<>();
        state.metaDown(event("meta-down", 117, true, true));
        state.companion(event("x-down", 52, true, false), false);
        MetaPassthroughState.Result<String> unrelatedUp = state.companion(
                new MetaPassthroughState.Event<>("other-x-up", 2, 52, false, false), false);

        assertFalse(unrelatedUp.consumed);
        assertTrue(unrelatedUp.output.isEmpty());
        assertEquals(Arrays.asList("x-up"),
                values(state.companion(event("x-up", 52, false, false), false)));
    }

    @Test
    public void resetCollectsHeldCompanionsAndMetasWhenOutputActive() {
        MetaPassthroughState<String> state = new MetaPassthroughState<>();
        state.metaDown(event("meta-down", 117, true, true));
        state.companion(event("x-down", 52, true, false), false);

        MetaPassthroughState.ResetResult<String> reset = state.reset();
        assertTrue(reset.wasOutputActive);
        assertEquals(1, reset.heldCompanions.size());
        assertEquals("x-down", reset.heldCompanions.get(0).value);
        assertEquals(1, reset.heldMetas.size());
        assertEquals("meta-down", reset.heldMetas.get(0).value);
        assertFalse(state.isOutputActive());
        assertFalse(state.hasActiveOrCaptured());
    }
}
