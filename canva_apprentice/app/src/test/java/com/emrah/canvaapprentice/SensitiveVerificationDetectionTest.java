package com.emrah.canvaapprentice;

import org.junit.Test;
import java.util.Collections;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SensitiveVerificationDetectionTest {
    private static UiTreeSnapshot snap(String text) {
        UiTreeSnapshot.Node node = new UiTreeSnapshot.Node(
                "", "android.view.View", text, "", null,
                false, false, false, true);
        return new UiTreeSnapshot(
                AgentConstants.CANVA_PACKAGE,
                Collections.singletonList(node),
                0L);
    }

    @Test public void recoveryCodeRequiresHumanTakeover() {
        assertTrue(snap("Enter a recovery code").containsSensitiveInput());
    }

    @Test public void smsCodeRequiresHumanTakeover() {
        assertTrue(snap("Enter the SMS code").containsSensitiveInput());
    }

    @Test public void turkishBackupCodeRequiresHumanTakeover() {
        assertTrue(snap("Yedek kod ile doğrula").containsSensitiveInput());
    }

    @Test public void passkeyRequiresHumanTakeover() {
        assertTrue(snap("Sign in with a passkey").containsSensitiveInput());
    }

    @Test public void physicalSecurityKeyRequiresHumanTakeover() {
        assertTrue(snap("Use your security key to continue").containsSensitiveInput());
    }

    @Test public void turkishPasskeyRequiresHumanTakeover() {
        assertTrue(snap("Geçiş anahtarı ile devam et").containsSensitiveInput());
    }

    @Test public void turkishSecurityKeyRequiresHumanTakeover() {
        assertTrue(snap("Güvenlik anahtarı kullan").containsSensitiveInput());
    }

    @Test public void ordinaryCanvaCodeTextDoesNotTriggerTakeover() {
        assertFalse(snap("Brand color code").containsSensitiveInput());
    }

    @Test public void ordinaryCanvaKeyboardShortcutTextDoesNotTriggerTakeover() {
        assertFalse(snap("Keyboard shortcuts").containsSensitiveInput());
    }
}
