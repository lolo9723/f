package com.emrah.canvaapprentice;

import java.util.ArrayList;
import java.util.List;

public final class ProtocolCodec {
    private ProtocolCodec() {}

    public static List<String> splitEscaped(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean escaped = false;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (escaped) {
                switch (c) {
                    case 'n': field.append('\n'); break;
                    case 't': field.append('\t'); break;
                    case '|': field.append('|'); break;
                    case '\\': field.append('\\'); break;
                    default:
                        field.append('\\').append(c);
                        break;
                }
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else if (c == '|') {
                out.add(field.toString());
                field.setLength(0);
            } else {
                field.append(c);
            }
        }

        if (escaped) field.append('\\');
        out.add(field.toString());
        return out;
    }
}
