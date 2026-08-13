package com.liveshield.app.diagnostics;

import static org.junit.Assert.assertFalse;

import java.lang.reflect.Method;
import org.junit.Test;

public final class AppDiagnosticsContractTest {
    @Test
    public void publicLoggingBoundaryAcceptsNoPayloadStringsOrArrays() {
        for (Method method : AppDiagnostics.class.getDeclaredMethods()) {
            if (!java.lang.reflect.Modifier.isPublic(method.getModifiers())) {
                continue;
            }
            for (Class<?> parameter : method.getParameterTypes()) {
                assertFalse(parameter == String.class);
                assertFalse(parameter == CharSequence.class);
                assertFalse(parameter == byte[].class);
                assertFalse(parameter == char[].class);
                assertFalse(parameter == Object.class);
            }
        }
    }
}
