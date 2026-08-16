package com.liveshield.app.setup;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

/** Responsive sizing contract for the scrollable protected preview. */
public final class SetupViewportLayoutTest {
    @Test
    public void previewUsesThreeQuartersOfTheAvailableViewport() {
        assertEquals(750, SetupViewportLayout.previewHeight(1000));
        assertEquals(600, SetupViewportLayout.previewHeight(800));
        assertEquals(0, SetupViewportLayout.previewHeight(0));
    }

    @Test
    public void setupStartsWithPreviewAndHasNoHeaderOrSeparateCameraButton()
            throws IOException {
        String layout = readProjectFile("src/main/res/layout/activity_setup.xml");

        assertTrue(layout.contains("android:id=\"@+id/setup_content\""));
        assertTrue(layout.contains("android:id=\"@+id/sanitized_preview_container\""));
        assertFalse(layout.contains("android:id=\"@+id/setup_back\""));
        assertFalse(layout.contains("android:id=\"@+id/request_camera_permission\""));
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
