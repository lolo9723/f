package com.emrah.canvaapprentice;

import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class ProtocolCodecTest {
    @Test public void decodesEscapedPipeNewlineTabAndBackslash() {
        List<String> p = ProtocolCodec.splitEscaped(
                "SET_TEXT|Title|Hello\\|World\\nLine 2\\tX\\\\Y|0.99|reason"
        );
        assertEquals(5,p.size());
        assertEquals("SET_TEXT",p.get(0));
        assertEquals("Title",p.get(1));
        assertEquals("Hello|World\nLine 2\tX\\Y",p.get(2));
        assertEquals("0.99",p.get(3));
    }

    @Test public void preservesEmptyFields() {
        List<String> p = ProtocolCodec.splitEscaped("BACK|||0.99|reason");
        assertEquals(5,p.size());
        assertEquals("",p.get(1));
        assertEquals("",p.get(2));
    }
}
