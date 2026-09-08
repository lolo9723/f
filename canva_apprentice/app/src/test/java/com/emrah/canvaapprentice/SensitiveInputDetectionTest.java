package com.emrah.canvaapprentice;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import org.junit.Test;

public final class SensitiveInputDetectionTest {
    private static UiTreeSnapshot snapshot(String text, String description, boolean password) {
        UiTreeSnapshot.Node node = new UiTreeSnapshot.Node(
                "", "EditText", text, description, null,
                false, true, password, true);
        return new UiTreeSnapshot(
                AgentConstants.CANVA_PACKAGE,
                Arrays.asList(node),
                0L);
    }

    @Test public void detectsOtpAndOneTimeCodes() {
        assertTrue(snapshot("Enter OTP", "", false).containsSensitiveInput());
        assertTrue(snapshot("", "One-time code", false).containsSensitiveInput());
        assertTrue(snapshot("Tek kullanımlık kod", "", false).containsSensitiveInput());
    }

    @Test public void detectsAuthenticatorPasscodeAndSecurityCode() {
        assertTrue(snapshot("Authenticator code", "", false).containsSensitiveInput());
        assertTrue(snapshot("Passcode", "", false).containsSensitiveInput());
        assertTrue(snapshot("Güvenlik kodu", "", false).containsSensitiveInput());
    }

    @Test public void passwordFlagStillFailsClosedWithoutLabel() {
        assertTrue(snapshot("", "", true).containsSensitiveInput());
    }

    @Test public void ordinaryCanvaEditableTextIsNotSensitive() {
        assertFalse(snapshot("Başlığı düzenle", "Text box", false).containsSensitiveInput());
    }
}
