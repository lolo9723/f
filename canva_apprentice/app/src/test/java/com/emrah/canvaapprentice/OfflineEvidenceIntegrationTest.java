package com.emrah.canvaapprentice;

import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import org.junit.Test;

/** Guards the production teacher tree against regressing to raw stale-offline presentation. */
public final class OfflineEvidenceIntegrationTest {
    @Test public void compactTeacherTreeUsesAndroidValidatedCrossCheck() throws Exception {
        String source = new String(Files.readAllBytes(Paths.get(
                "src/main/java/com/emrah/canvaapprentice/UiTreeSnapshot.java")), StandardCharsets.UTF_8);
        assertTrue(source.contains("AndroidNetworkEvidence.currentValidated()"));
        assertTrue(source.contains("OfflineEvidencePolicy.teacherSafeText"));
        assertTrue(source.contains("OfflineEvidencePolicy.teacherSafeDescription"));
    }

    @Test public void manifestDeclaresNetworkStatePermission() throws Exception {
        String manifest = new String(Files.readAllBytes(Paths.get("src/main/AndroidManifest.xml")),
                StandardCharsets.UTF_8);
        assertTrue(manifest.contains("android.permission.ACCESS_NETWORK_STATE"));
    }
}
