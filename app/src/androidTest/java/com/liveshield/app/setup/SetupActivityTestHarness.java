package com.liveshield.app.setup;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/** Payload-free bridge to SetupActivity's package-private instrumentation seam. */
public final class SetupActivityTestHarness {
    private SetupActivityTestHarness() {
    }

    public static void install(
            SetupActivity activity,
            SetupUiListener listener) {
        assertTrue(
                "UI-contract instrumentation must not bypass scope disclosure",
                activity.isScopeDisclosureAcceptedForTest());
        activity.installUiContractHarnessForTest(listener);
    }

    public static boolean hasDetachedProductionGraph(SetupActivity activity) {
        return !activity.hasSessionCoordinatorForTest()
                && activity.readinessSnapshotForTest() == null;
    }

    public static void installCameraPermission(SetupActivity activity, boolean granted) {
        activity.installCameraPermissionPortForTest(new SetupActivity.CameraPermissionPort() {
            @Override
            public boolean isGranted(SetupActivity ignored) {
                return granted;
            }

            @Override
            public void request(SetupActivity ignored, int requestCode) {
                // Deterministic test permission never invokes a system dialog.
            }
        });
    }

    /** Mechanically checks that the release seam is inert, package-private, and payload-free. */
    public static void assertReleaseBoundary(SetupActivity activity) {
        try {
            Method install = SetupActivity.class.getDeclaredMethod(
                    "installUiContractHarnessForTest", SetupUiListener.class);
            int access = install.getModifiers()
                    & (Modifier.PUBLIC | Modifier.PROTECTED | Modifier.PRIVATE);
            assertEquals("Harness installer must remain package-private", 0, access);
            assertEquals(Void.TYPE, install.getReturnType());
            assertArrayEquals(
                    "Harness installer must accept only payload-free UI callbacks",
                    new Class<?>[]{SetupUiListener.class},
                    install.getParameterTypes());

            Field enabled = SetupActivity.class.getDeclaredField(
                    "uiContractHarnessInstalledForTest");
            assertTrue(
                    "Harness state must remain private",
                    Modifier.isPrivate(enabled.getModifiers()));
            enabled.setAccessible(true);
            assertFalse(
                    "Harness must remain off for normal and externally launched Activities",
                    enabled.getBoolean(activity));
            assertTrue(activity.usesSystemCameraPermissionPortForTest());
            Method permissionInstall = SetupActivity.class.getDeclaredMethod(
                    "installCameraPermissionPortForTest",
                    SetupActivity.CameraPermissionPort.class);
            int permissionAccess = permissionInstall.getModifiers()
                    & (Modifier.PUBLIC | Modifier.PROTECTED | Modifier.PRIVATE);
            assertEquals("Permission seam must remain package-private", 0, permissionAccess);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Setup UI-contract harness boundary changed", exception);
        }
    }
}
