package com.liveshield.app.debug;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.liveshield.app.debug.FaultInjectionController.Bindings;
import com.liveshield.app.debug.FaultInjectionController.FaultSignal;
import com.liveshield.app.debug.FaultInjectionController.FaultTarget;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public final class FaultInjectionControllerTest {
    @Test
    public void everyRequiredPathDispatchesOneTypedPayloadFreeSignal() {
        EnumMap<FaultTarget, List<FaultSignal>> events = emptyEvents();
        FaultInjectionController controller = controller(events);

        for (FaultTarget target : FaultTarget.values()) {
            controller.arm(target, 0);
            assertTrue(controller.checkpoint(target));
            assertFalse(controller.checkpoint(target));
        }

        long expectedSequence = 0;
        for (FaultTarget target : FaultTarget.values()) {
            assertEquals(List.of(new FaultSignal(target, expectedSequence++)), events.get(target));
        }
    }

    @Test
    public void checkpointCountdownIsExactAndIndependentPerPath() {
        EnumMap<FaultTarget, List<FaultSignal>> events = emptyEvents();
        FaultInjectionController controller = controller(events);
        controller.arm(FaultTarget.DETECTOR_STALL, 2);
        controller.arm(FaultTarget.NETWORK_LOSS, 0);

        assertFalse(controller.checkpoint(FaultTarget.DETECTOR_STALL));
        assertTrue(controller.checkpoint(FaultTarget.NETWORK_LOSS));
        assertFalse(controller.checkpoint(FaultTarget.DETECTOR_STALL));
        assertTrue(controller.checkpoint(FaultTarget.DETECTOR_STALL));

        assertEquals(1, events.get(FaultTarget.DETECTOR_STALL).size());
        assertEquals(1, events.get(FaultTarget.NETWORK_LOSS).size());
    }

    @Test
    public void rearmReplacesCountdownWithoutDuplicateInjection() {
        EnumMap<FaultTarget, List<FaultSignal>> events = emptyEvents();
        FaultInjectionController controller = controller(events);
        controller.arm(FaultTarget.QUEUE_CAPACITY, 4);
        controller.arm(FaultTarget.QUEUE_CAPACITY, 0);

        assertTrue(controller.checkpoint(FaultTarget.QUEUE_CAPACITY));
        assertFalse(controller.checkpoint(FaultTarget.QUEUE_CAPACITY));
        assertEquals(1, events.get(FaultTarget.QUEUE_CAPACITY).size());
    }

    @Test
    public void disarmAndClearNeverInvokeHandlers() {
        EnumMap<FaultTarget, List<FaultSignal>> events = emptyEvents();
        FaultInjectionController controller = controller(events);
        controller.arm(FaultTarget.GL_FAILURE, 0);
        controller.disarm(FaultTarget.GL_FAILURE);
        controller.arm(FaultTarget.SURFACE_LOSS, 0);
        controller.clear();

        assertFalse(controller.checkpoint(FaultTarget.GL_FAILURE));
        assertFalse(controller.checkpoint(FaultTarget.SURFACE_LOSS));
        assertTrue(events.values().stream().allMatch(List::isEmpty));
    }

    @Test
    public void incompleteBindingsAndInvalidCountdownAreRejected() {
        assertThrows(IllegalStateException.class,
                () -> new FaultInjectionController(new Bindings()));
        FaultInjectionController controller = controller(emptyEvents());
        assertThrows(IllegalArgumentException.class,
                () -> controller.arm(FaultTarget.CAMERA_FAILURE, -1));
    }

    @Test
    public void callbackMayRearmWithoutDeadlockOrSameCheckpointRecursion() {
        List<FaultSignal> events = new ArrayList<>();
        FaultInjectionController[] reference = new FaultInjectionController[1];
        Bindings bindings = bindings(signal -> {
            events.add(signal);
            reference[0].arm(signal.target(), 0);
        }, FaultTarget.ENCODER_FAILURE);
        reference[0] = new FaultInjectionController(bindings);
        reference[0].arm(FaultTarget.ENCODER_FAILURE, 0);

        assertTrue(reference[0].checkpoint(FaultTarget.ENCODER_FAILURE));
        assertTrue(reference[0].isArmed(FaultTarget.ENCODER_FAILURE));
        assertEquals(1, events.size());
        assertTrue(reference[0].checkpoint(FaultTarget.ENCODER_FAILURE));
        assertEquals(2, events.size());
    }

    @Test
    public void signalSchemaContainsOnlyEnumTargetAndNumericSequence() {
        try {
            assertEquals(FaultTarget.class,
                    FaultSignal.class.getDeclaredMethod("target").getReturnType());
            assertEquals(long.class,
                    FaultSignal.class.getDeclaredMethod("sequence").getReturnType());
            assertEquals(2, FaultSignal.class.getDeclaredFields().length);
        } catch (NoSuchMethodException exception) {
            throw new AssertionError(exception);
        }
    }

    @Test
    public void publicControlApiCannotAcceptArbitraryPayloadTypes() {
        for (Method method : FaultInjectionController.class.getDeclaredMethods()) {
            if (method.isSynthetic()) {
                continue;
            }
            for (Class<?> parameter : method.getParameterTypes()) {
                assertFalse(parameter == String.class);
                assertFalse(parameter == byte[].class);
                assertFalse(Throwable.class.isAssignableFrom(parameter));
            }
        }
    }

    private static FaultInjectionController controller(
            Map<FaultTarget, List<FaultSignal>> events) {
        Bindings bindings = new Bindings();
        for (FaultTarget target : FaultTarget.values()) {
            bindings.on(target, events.get(target)::add);
        }
        return new FaultInjectionController(bindings);
    }

    private static EnumMap<FaultTarget, List<FaultSignal>> emptyEvents() {
        EnumMap<FaultTarget, List<FaultSignal>> events = new EnumMap<>(FaultTarget.class);
        for (FaultTarget target : FaultTarget.values()) {
            events.put(target, new ArrayList<>());
        }
        return events;
    }

    private static Bindings bindings(
            FaultInjectionController.FaultHandler special,
            FaultTarget specialTarget) {
        Bindings bindings = new Bindings();
        for (FaultTarget target : FaultTarget.values()) {
            bindings.on(target, target == specialTarget ? special : ignored -> { });
        }
        return bindings;
    }
}
