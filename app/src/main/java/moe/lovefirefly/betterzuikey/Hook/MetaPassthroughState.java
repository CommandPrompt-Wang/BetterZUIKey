package moe.lovefirefly.betterzuikey.Hook;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Orders a deferred Meta press and the physical keys that accompany it.
 * The caller supplies immutable event snapshots and performs the resulting replay.
 */
final class MetaPassthroughState<T> {
    static final class Event<T> {
        final T value;
        final int deviceId;
        final int keyCode;
        final boolean down;
        final boolean modifier;

        Event(T value, int deviceId, int keyCode, boolean down, boolean modifier) {
            this.value = value;
            this.deviceId = deviceId;
            this.keyCode = keyCode;
            this.down = down;
            this.modifier = modifier;
        }

        Key key() {
            return new Key(deviceId, keyCode);
        }
    }

    static final class Output<T> {
        final Event<T> event;
        final boolean stripMeta;

        Output(Event<T> event, boolean stripMeta) {
            this.event = event;
            this.stripMeta = stripMeta;
        }
    }

    static final class Result<T> {
        final boolean consumed;
        final List<Output<T>> output;

        Result(boolean consumed, List<Output<T>> output) {
            this.consumed = consumed;
            this.output = output;
        }
    }

    private static final class Key {
        final int deviceId;
        final int keyCode;

        Key(int deviceId, int keyCode) {
            this.deviceId = deviceId;
            this.keyCode = keyCode;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Key)) return false;
            Key key = (Key) other;
            return deviceId == key.deviceId && keyCode == key.keyCode;
        }

        @Override
        public int hashCode() {
            return Objects.hash(deviceId, keyCode);
        }
    }

    private final Map<Key, Event<T>> metaHeld = new LinkedHashMap<>();
    private enum Capture { PENDING, WITH_META, WITHOUT_META }

    private final Map<Key, Capture> captured = new HashMap<>();
    private final List<Event<T>> pendingModifiers = new ArrayList<>();
    private boolean outputActive;

    synchronized Result<T> metaDown(Event<T> event) {
        List<Output<T>> output = new ArrayList<>();
        if (!metaHeld.containsKey(event.key())) {
            metaHeld.put(event.key(), event);
            if (outputActive) output.add(new Output<>(event, false));
        }
        return new Result<>(false, output);
    }

    synchronized Result<T> companion(Event<T> event, boolean priority) {
        List<Output<T>> output = new ArrayList<>();
        Key key = event.key();
        Capture capture = captured.get(key);
        if (capture != null) {
            if (capture == Capture.PENDING) {
                pendingModifiers.add(event);
            } else {
                output.add(new Output<>(event, capture == Capture.WITHOUT_META && !outputActive));
            }
            if (!event.down) {
                captured.remove(key);
                capturedDownEvents.remove(key);
            }
            return new Result<>(true, output);
        }
        if (metaHeld.isEmpty() || !event.down) return new Result<>(false, output);
        if (priority) {
            flushModifiers(output, !outputActive);
            return new Result<>(false, output);
        }
        if (event.modifier && !outputActive) {
            pendingModifiers.add(event);
            captured.put(key, Capture.PENDING);
            capturedDownEvents.put(key, event);
            return new Result<>(true, output);
        }
        startOutput(output);
        flushModifiers(output, false);
        output.add(new Output<>(event, false));
        captured.put(key, Capture.WITH_META);
        capturedDownEvents.put(key, event);
        return new Result<>(true, output);
    }

    synchronized Result<T> metaUp(Event<T> event) {
        List<Output<T>> output = new ArrayList<>();
        if (!metaHeld.containsKey(event.key())) return new Result<>(false, output);
        if (!pendingModifiers.isEmpty()) {
            startOutput(output);
            flushModifiers(output, false);
        }
        if (outputActive) output.add(new Output<>(event, false));
        metaHeld.remove(event.key());
        if (metaHeld.isEmpty()) outputActive = false;
        return new Result<>(true, output);
    }

    static final class ResetResult<T> {
        final boolean wasOutputActive;
        final List<Event<T>> heldCompanions;
        final List<Event<T>> heldMetas;

        ResetResult(boolean wasOutputActive, List<Event<T>> heldCompanions, List<Event<T>> heldMetas) {
            this.wasOutputActive = wasOutputActive;
            this.heldCompanions = heldCompanions;
            this.heldMetas = heldMetas;
        }
    }

    private final Map<Key, Event<T>> capturedDownEvents = new LinkedHashMap<>();

    synchronized boolean isOutputActive() {
        return outputActive;
    }

    synchronized boolean hasActiveOrCaptured() {
        return !metaHeld.isEmpty() || !captured.isEmpty() || !pendingModifiers.isEmpty();
    }

    synchronized ResetResult<T> reset() {
        boolean wasActive = outputActive;
        List<Event<T>> companions = new ArrayList<>();
        List<Event<T>> metas = new ArrayList<>();
        if (wasActive) {
            for (Map.Entry<Key, Capture> entry : captured.entrySet()) {
                if (entry.getValue() != Capture.PENDING) {
                    Event<T> ev = capturedDownEvents.get(entry.getKey());
                    if (ev != null) companions.add(ev);
                }
            }
            metas.addAll(metaHeld.values());
        }
        metaHeld.clear();
        captured.clear();
        capturedDownEvents.clear();
        pendingModifiers.clear();
        outputActive = false;
        return new ResetResult<>(wasActive, companions, metas);
    }

    private void startOutput(List<Output<T>> output) {
        if (outputActive) return;
        for (Event<T> event : metaHeld.values()) output.add(new Output<>(event, false));
        outputActive = true;
    }

    private void flushModifiers(List<Output<T>> output, boolean stripMeta) {
        for (Event<T> event : pendingModifiers) {
            output.add(new Output<>(event, stripMeta));
        }
        pendingModifiers.clear();
        for (Map.Entry<Key, Capture> entry : captured.entrySet()) {
            if (entry.getValue() == Capture.PENDING) {
                entry.setValue(stripMeta ? Capture.WITHOUT_META : Capture.WITH_META);
            }
        }
    }
}
