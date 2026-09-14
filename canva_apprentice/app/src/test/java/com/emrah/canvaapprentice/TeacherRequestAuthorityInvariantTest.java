package com.emrah.canvaapprentice;

import java.lang.reflect.Constructor;
import org.junit.Test;

import static org.junit.Assert.assertFalse;

public final class TeacherRequestAuthorityInvariantTest {
    @Test public void syntacticallyCompleteButUnboundAuthorityFailsClosed() throws Exception {
        Constructor<TeacherRequestAuthority> constructor = TeacherRequestAuthority.class.getDeclaredConstructor(
                String.class, String.class, String.class, String.class, boolean.class, boolean.class);
        constructor.setAccessible(true);

        TeacherRequestAuthority forged = constructor.newInstance(
                "req123",
                "CAA1_REPLY_req123|",
                "lease-looking-token",
                "snapshot-A",
                false,
                false
        );

        assertFalse(forged.isValid());
        assertFalse(forged.stillOwnsTransport());
    }

    @Test public void visualFlagCannotMakeAnUnboundAuthorityValid() throws Exception {
        Constructor<TeacherRequestAuthority> constructor = TeacherRequestAuthority.class.getDeclaredConstructor(
                String.class, String.class, String.class, String.class, boolean.class, boolean.class);
        constructor.setAccessible(true);

        TeacherRequestAuthority forged = constructor.newInstance(
                "visual123",
                "CAA1_REPLY_visual123|",
                "lease-looking-token",
                "snapshot-V",
                false,
                true
        );

        assertFalse(forged.isValid());
        assertFalse(forged.isVisualGrounded());
        assertFalse(forged.stillOwnsTransport());
    }
}
