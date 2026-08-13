package com.liveshield.app.setup;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

/** Static release-boundary checks for the pre-session disclosure resource. */
public final class ScopeDisclosureResourceTest {
    @Test
    public void disclosureNamesEveryScopeBoundaryBeforeAcknowledgement() throws IOException {
        String layout = readProjectFile("src/main/res/layout/fragment_scope_disclosure.xml");
        String strings = readProjectFile("src/main/res/values/strings.xml");

        assertTrue(layout.contains("@string/scope_disclosure_supported"));
        assertTrue(layout.contains("@string/scope_disclosure_visual_only"));
        assertTrue(layout.contains("@string/scope_disclosure_no_anonymity"));
        assertTrue(layout.contains("@string/scope_disclosure_unsupported"));
        assertTrue(layout.contains("@string/acknowledge_scope_disclosure"));
        assertTrue(strings.contains("controlled indoor space"));
        assertTrue(strings.contains("captures no microphone audio"));
        assertTrue(strings.contains("does not guarantee anonymity"));
        assertTrue(strings.contains("public venues, moving outdoor scenes, or dense crowds"));
    }

    @Test
    public void disclosureHasNoCameraMediaOrPayloadSurface() throws IOException {
        String layout = readProjectFile("src/main/res/layout/fragment_scope_disclosure.xml");
        String fragment = readProjectFile(
                "src/main/java/com/liveshield/app/setup/ScopeDisclosureFragment.java");

        assertFalse(layout.contains("PreviewView"));
        assertFalse(layout.contains("SurfaceView"));
        assertFalse(layout.contains("TextureView"));
        assertFalse(layout.contains("VideoView"));
        assertFalse(fragment.contains("android.hardware.camera"));
        assertFalse(fragment.contains("android.media"));
        assertFalse(fragment.contains("android.util.Log"));
        assertFalse(fragment.contains("android.os.Parcelable"));
        assertFalse(fragment.contains("setArguments"));
    }

    private static String readProjectFile(String relativePath) throws IOException {
        File direct = new File(relativePath);
        File file = direct.isFile() ? direct : new File("app", relativePath);
        try (FileInputStream input = new FileInputStream(file);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }
}
