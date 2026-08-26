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
}
