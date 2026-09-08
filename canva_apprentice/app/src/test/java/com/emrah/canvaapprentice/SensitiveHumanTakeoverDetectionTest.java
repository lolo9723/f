package com.emrah.canvaapprentice;

import android.graphics.Rect;
import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;

public class SensitiveHumanTakeoverDetectionTest {
    private static UiTreeSnapshot snapshot(String text, String description) {
        UiTreeSnapshot.Node node = new UiTreeSnapshot.Node(
                "","android.widget.TextView",text,description,new Rect(0,0,100,40),
                false,false,false,true
        );
        return new UiTreeSnapshot(AgentConstants.CANVA_PACKAGE, Arrays.asList(node), 0L);
    }

    @Test public void detectsIdentityVerificationPromptsWithoutPasswordField() {
        assertTrue(snapshot("Verify it's you", "Check your phone for a code").containsSensitiveInput());
        assertTrue(snapshot("Verify your identity", "Enter the code we sent").containsSensitiveInput());
    }

    @Test public void detectsTurkishIdentityAndSentCodePrompts() {
        assertTrue(snapshot("Kimliğinizi doğrulayın", "Telefonunuza gelen kodu girin").containsSensitiveInput());
        assertTrue(snapshot("Sen olduğunu doğrula", "Gönderdiğimiz kodu gir").containsSensitiveInput());
    }

    @Test public void doesNotTreatOrdinaryCanvaCopyAsAuthentication() {
        assertFalse(snapshot("Design verification checklist", "Add a code block to this page").containsSensitiveInput());
        assertFalse(snapshot("Confirm layout", "Check your phone mockup spacing").containsSensitiveInput());
    }
}
