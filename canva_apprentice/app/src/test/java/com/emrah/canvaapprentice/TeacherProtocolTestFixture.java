package com.emrah.canvaapprentice;

/** Test-only fixture that satisfies the same fully-grounded teacher authority precondition as production. */
final class TeacherProtocolTestFixture {
    private TeacherProtocolTestFixture() {}

    static String groundedMarker(String requestId) {
        String marker = TeacherProtocol.markerFor(requestId);
        if (!CheckpointRequestGuard.bindSnapshot(marker, "test-snapshot-" + requestId)) {
            throw new AssertionError("failed to ground teacher marker for test");
        }
        return marker;
    }
}
