package com.videofabrikasi.app;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ProjectStoreTest {
    @Test public void oneHundredTwentyProjectsRemainSeparatedAndNavigable() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ProjectStore store = new ProjectStore(context);
        store.clearForTests();

        for (int i = 0; i < 120; i++) {
            store.save("user", "slug-" + i, "title-" + i, "idea-" + i, "KUYRUKTA", i + 1);
        }

        assertEquals(120, store.historyCount());
        assertEquals(1, store.historyPosition());
        assertEquals("slug-119", store.slug());
        assertEquals("idea-119", store.idea());

        assertTrue(store.move(1));
        assertEquals("slug-118", store.slug());
        assertEquals("idea-118", store.idea());
        store.updateStatus("AI TAMAMLANDI");
        assertEquals("AI TAMAMLANDI", store.status());

        assertTrue(store.move(-1));
        assertEquals("slug-119", store.slug());
        assertEquals("KUYRUKTA", store.status());

        assertTrue(store.move(1));
        assertEquals("slug-118", store.slug());
        assertEquals("AI TAMAMLANDI", store.status());
        store.clearForTests();
    }

    @Test public void historyCapsAtFiveHundredAndEvictsOnlyOldest() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ProjectStore store = new ProjectStore(context);
        store.clearForTests();

        for (int i = 0; i <= 500; i++) {
            store.save("user", "project-" + i, "title-" + i, "idea-" + i, "KUYRUKTA", i + 1);
        }

        assertEquals(500, store.historyCount());
        assertEquals("project-500", store.slug());
        assertFalse(store.historySummary(500).contains("project-0 —"));
        assertTrue(store.historySummary(500).contains("project-1 —"));

        assertTrue(store.move(-1));
        assertEquals("project-1", store.slug());
        assertTrue(store.move(1));
        assertEquals("project-500", store.slug());
        store.clearForTests();
    }

    @Test public void backgroundDownloadUpdatesOnlyItsOwnProject() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ProjectStore store = new ProjectStore(context);
        store.clearForTests();

        store.save("user", "project-a", "project-a", "idea-a", "AI TAMAMLANDI", 1);
        store.save("user", "project-b", "project-b", "idea-b", "AI TAMAMLANDI", 1);
        assertEquals("project-b", store.slug());

        // Simulate project A finishing a download while project B is selected.
        store.updateStatusForSlug("project-a", "AI TAMAMLANDI — İNDİRİLDİ");
        assertEquals("project-b", store.slug());
        assertEquals("AI TAMAMLANDI", store.status());

        assertTrue(store.move(1));
        assertEquals("project-a", store.slug());
        assertEquals("AI TAMAMLANDI — İNDİRİLDİ", store.status());

        assertTrue(store.move(-1));
        assertEquals("project-b", store.slug());
        assertEquals("AI TAMAMLANDI", store.status());
        store.clearForTests();
    }
}
