package com.videofabrikasi.app;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class SecureStoreTest {
    @Test public void tokenRoundTripsAndIsNeverStoredAsPlaintext() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences raw = context.getSharedPreferences("secure_store", Context.MODE_PRIVATE);
        raw.edit().clear().commit();

        SecureStore store = new SecureStore(context);
        String secret = "KGAT_TEST_SECRET_123456789";
        store.put("kaggle_token_test", secret);

        assertEquals(secret, store.get("kaggle_token_test"));
        String iv = raw.getString("kaggle_token_test_iv", "");
        String data = raw.getString("kaggle_token_test_data", "");
        assertNotNull(iv);
        assertNotNull(data);
        assertFalse(iv.isEmpty());
        assertFalse(data.isEmpty());
        assertFalse(iv.contains(secret));
        assertFalse(data.contains(secret));
        assertFalse(raw.getAll().toString().contains(secret));

        raw.edit().putString("kaggle_token_test_data", "not-valid-ciphertext").commit();
        assertEquals("", store.get("kaggle_token_test"));
        raw.edit().clear().commit();
    }
}
