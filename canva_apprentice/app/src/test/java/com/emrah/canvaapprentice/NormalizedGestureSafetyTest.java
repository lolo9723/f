package com.emrah.canvaapprentice;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class NormalizedGestureSafetyTest {
    @Test public void coordinatesAcceptOnlyFiniteNormalizedRange() {
        assertTrue(ActionExecutor.normalizedCoordinate(0.0));
        assertTrue(ActionExecutor.normalizedCoordinate(500.0));
        assertTrue(ActionExecutor.normalizedCoordinate(1000.0));
        assertFalse(ActionExecutor.normalizedCoordinate(-0.0001));
        assertFalse(ActionExecutor.normalizedCoordinate(1000.0001));
        assertFalse(ActionExecutor.normalizedCoordinate(Double.NaN));
        assertFalse(ActionExecutor.normalizedCoordinate(Double.POSITIVE_INFINITY));
        assertFalse(ActionExecutor.normalizedCoordinate(Double.NEGATIVE_INFINITY));
    }

    @Test public void dragDurationAcceptsOnlyFiniteExecutorRange() {
        assertTrue(ActionExecutor.normalizedDuration(150.0));
        assertTrue(ActionExecutor.normalizedDuration(750.0));
        assertTrue(ActionExecutor.normalizedDuration(2000.0));
        assertFalse(ActionExecutor.normalizedDuration(149.999));
        assertFalse(ActionExecutor.normalizedDuration(2000.001));
        assertFalse(ActionExecutor.normalizedDuration(Double.NaN));
        assertFalse(ActionExecutor.normalizedDuration(Double.POSITIVE_INFINITY));
    }
}