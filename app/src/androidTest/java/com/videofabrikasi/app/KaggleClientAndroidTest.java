package com.videofabrikasi.app;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class KaggleClientAndroidTest {
    @Test public void outputListingFindsOnlyExactRequestedFileOnAndroid() throws Exception {
        String json = "{\"files\":["
                + "{\"fileName\":\"FINAL.mp4.bak\",\"url\":\"https://storage.example/a\"},"
                + "{\"fileName\":\"FINAL.mp4\",\"url\":\"https://storage.example/final\"},"
                + "{\"fileName\":\"status.json\",\"url\":\"https://storage.example/status\"}"
                + "]}";
        KaggleClient.DownloadTarget finalTarget = KaggleClient.outputTargetFromListJson(json, "FINAL.mp4");
        assertNotNull(finalTarget);
        assertEquals("https://storage.example/final", finalTarget.url);
        assertFalse(finalTarget.authRequired);

        KaggleClient.DownloadTarget statusTarget = KaggleClient.outputTargetFromListJson(json, "status.json");
        assertNotNull(statusTarget);
        assertEquals("https://storage.example/status", statusTarget.url);
        assertNull(KaggleClient.outputTargetFromListJson(json, "missing.mp4"));
    }

    @Test public void importedTokenFormatsAndIntrospectionIdentityWorkOnAndroid() throws Exception {
        assertEquals("KGAT_ABC123", KaggleClient.tokenFromImportedText("KGAT_ABC123"));
        assertEquals("KGAT_JSON456", KaggleClient.tokenFromImportedText("{\"token\":\"KGAT_JSON456\"}"));
        assertEquals("KGAT_ACCESS789", KaggleClient.tokenFromImportedText("{\"access_token\":\"KGAT_ACCESS789\"}"));
        assertEquals("KGAT_WRAP999", KaggleClient.tokenFromImportedText("copied token: KGAT_WRAP999 end"));

        KaggleClient.AccountIdentity id = KaggleClient.accountIdentityFromIntrospectionJson(
                "{\"active\":true,\"username\":\"demo_user\"}");
        assertTrue(id.active);
        assertEquals("demo_user", id.username);

        KaggleClient.AccountIdentity inactive = KaggleClient.accountIdentityFromIntrospectionJson(
                "{\"active\":false}");
        assertFalse(inactive.active);
        assertEquals("", inactive.username);
    }

    @Test public void snakeCaseOutputFieldsAlsoWorkOnAndroid() throws Exception {
        String json = "{\"files\":[{\"file_name\":\"FINAL.mp4\",\"url\":\"https://storage.example/snake\"}]}";
        KaggleClient.DownloadTarget target = KaggleClient.outputTargetFromListJson(json, "FINAL.mp4");
        assertNotNull(target);
        assertEquals("https://storage.example/snake", target.url);
    }
}
