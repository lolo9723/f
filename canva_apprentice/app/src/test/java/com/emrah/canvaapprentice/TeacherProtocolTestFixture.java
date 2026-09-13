package com.emrah.canvaapprentice;

/** Test-only fixture that creates teacher authority through the same immutable path as production. */
final class TeacherProtocolTestFixture {
    private TeacherProtocolTestFixture() {}

    static TeacherRequestAuthority groundedAuthority(String requestId) {
        TeacherRequestAuthority authority = TeacherRequestAuthority.begin(
                requestId, "test-snapshot-" + requestId);
        if (!authority.isValid() || !authority.stillOwnsTransport()) {
            throw new AssertionError("failed to create immutable grounded teacher authority for test");
        }
        return authority;
    }

    static TeacherRequestAuthority groundedVisualAuthority(String requestId) {
        TeacherRequestAuthority authority = TeacherRequestAuthority.beginVisual(
                requestId, "test-snapshot-" + requestId);
        if (!authority.isValid() || !authority.stillOwnsTransport() || !authority.isVisualGrounded()) {
            throw new AssertionError("failed to create immutable visual teacher authority for test");
        }
        return authority;
    }

    static String groundedMarker(String requestId) {
        return groundedAuthority(requestId).marker;
    }

    static AgentAction parseStructural(String requestId, String payload) {
        TeacherRequestAuthority authority = groundedAuthority(requestId);
        return TeacherProtocol.parse(authority.marker + payload, authority);
    }

    static AgentAction parseVisual(String requestId, String payload) {
        TeacherRequestAuthority authority = groundedVisualAuthority(requestId);
        return TeacherProtocol.parse(authority.marker + payload, authority);
    }
}
