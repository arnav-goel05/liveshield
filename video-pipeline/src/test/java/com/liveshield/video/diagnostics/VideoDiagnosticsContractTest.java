package com.liveshield.video.diagnostics;

import static org.junit.Assert.assertFalse;

import java.lang.reflect.Method;
import org.junit.Test;

public final class VideoDiagnosticsContractTest {
    @Test
    public void publicLoggingBoundaryAcceptsNoPayloadStringsArraysOrMediaObjects() {
        for (Method method : VideoDiagnostics.class.getDeclaredMethods()) {
            if (!java.lang.reflect.Modifier.isPublic(method.getModifiers())) {
                continue;
            }
            for (Class<?> parameter : method.getParameterTypes()) {
                assertFalse(parameter == String.class);
                assertFalse(parameter == CharSequence.class);
                assertFalse(parameter == byte[].class);
                assertFalse(parameter == char[].class);
                assertFalse(parameter == Object.class);
                assertFalse(parameter.getName().startsWith("android.graphics"));
                assertFalse(parameter.getName().startsWith("android.view"));
            }
        }
    }
}
