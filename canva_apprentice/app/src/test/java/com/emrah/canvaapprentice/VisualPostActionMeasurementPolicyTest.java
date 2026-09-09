package com.emrah.canvaapprentice;

import org.junit.Test;
import static org.junit.Assert.*;

public class VisualPostActionMeasurementPolicyTest {
    @Test public void productionPostActionMeasurementRequiresOriginalOwnerAndLiveCanvaDesignIdentity() {
        assertFalse(VisualEvidenceLease.mayArmPostActionMeasurement(true,false,true,true,true,true));
        assertFalse(VisualEvidenceLease.mayArmPostActionMeasurement(true,true,false,true,true,true));
        assertFalse(VisualEvidenceLease.mayArmPostActionMeasurement(true,true,true,false,true,true));
        assertFalse(VisualEvidenceLease.mayArmPostActionMeasurement(true,true,true,true,false,true));
        assertFalse(VisualEvidenceLease.mayArmPostActionMeasurement(true,true,true,true,true,false));
        assertTrue(VisualEvidenceLease.mayArmPostActionMeasurement(true,true,true,true,true,true));
    }

    @Test public void jvmPolicyRemainsContextNeutralForExistingLeaseTests() {
        assertTrue(VisualEvidenceLease.mayArmPostActionMeasurement(false,false,false,false,false,false));
    }
}