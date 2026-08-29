package com.videofabrikasi.app;

import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.os.Bundle;

import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Assume;
import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Verifies the exact FINAL.mp4 produced by the live Kaggle certificate using
 * Android's real media framework, not ffprobe or a mocked contract.
 *
 * The live GitHub workflow pushes FINAL.mp4 into the target app's external-files
 * directory before invoking only this test on an API 36 emulator.
 */
public class LiveFinalArtifactTest {
    @Test public void liveFinalMp4PassesRealAndroidMediaFramework() throws Exception {
        Bundle args = InstrumentationRegistry.getArguments();
        Assume.assumeTrue("Live FINAL artifact test is enabled only by the certified E2E workflow",
                "true".equalsIgnoreCase(args.getString("liveArtifact", "false")));

        File root = InstrumentationRegistry.getInstrumentation()
                .getTargetContext().getExternalFilesDir(null);
        assertNotNull("External files directory is unavailable", root);

        File finalMp4 = new File(root, "FINAL.mp4");
        assertTrue("Live FINAL.mp4 was not pushed to Android: " + finalMp4, finalMp4.isFile());
        assertTrue("Live FINAL.mp4 is unexpectedly small: " + finalMp4.length(),
                finalMp4.length() >= 100_000L);

        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        try {
            mmr.setDataSource(finalMp4.getAbsolutePath());
            int width = parseInt(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
            int height = parseInt(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
            long durationMs = parseLong(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
            assertEquals("Android decoded width", 1080, width);
            assertEquals("Android decoded height", 1920, height);
            assertTrue("Android decoded duration must be >= 8s, got " + durationMs,
                    durationMs >= 8_000L);
        } finally {
            try { mmr.release(); } catch (Exception ignored) {}
        }

        MediaExtractor extractor = new MediaExtractor();
        List<String> mimes = new ArrayList<>();
        try {
            extractor.setDataSource(finalMp4.getAbsolutePath());
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.containsKey(MediaFormat.KEY_MIME)
                        ? format.getString(MediaFormat.KEY_MIME) : "";
                mimes.add(mime == null ? "" : mime);
            }
        } finally {
            try { extractor.release(); } catch (Exception ignored) {}
        }

        assertTrue("Android track list missing H.264/AVC: " + mimes,
                containsIgnoreCase(mimes, "video/avc"));
        assertTrue("Android track list missing AAC: " + mimes,
                containsIgnoreCase(mimes, "audio/mp4a-latm"));
    }

    private static boolean containsIgnoreCase(List<String> values, String expected) {
        for (String value : values) {
            if (expected.equalsIgnoreCase(value)) return true;
        }
        return false;
    }

    private static int parseInt(String value) {
        try { return Integer.parseInt(value == null ? "0" : value); }
        catch (Exception e) { return 0; }
    }

    private static long parseLong(String value) {
        try { return Long.parseLong(value == null ? "0" : value); }
        catch (Exception e) { return 0L; }
    }
}
