package org.openridesync.adapter;

import java.text.Normalizer;

/** Display-only cleanup; language identification is not a glyph-availability test. */
public final class MusicDisplayText {
    private MusicDisplayText() {}

    public static boolean hasText(String title, String artist) {
        return !prepare(title).isEmpty() || !prepare(artist).isEmpty();
    }

    public static String prepare(String text) {
        if (text == null) return "";
        StringBuilder out = new StringBuilder(text.length());
        boolean space = true;
        boolean visible = false;
        for (int offset = 0; offset < text.length();) {
            int point = text.codePointAt(offset);
            offset += Character.charCount(point);
            if (Character.isWhitespace(point) || Character.isSpaceChar(point)) {
                if (!space) out.append(' ');
                space = true;
                continue;
            }
            int type = Character.getType(point);
            if (Character.isISOControl(point)) continue;
            // Preserve joiners used in valid Arabic/Indic text and emoji sequences.
            if (type == Character.FORMAT && point != 0x200c && point != 0x200d) continue;
            if (type == Character.SURROGATE || type == Character.PRIVATE_USE
                    || point == 0xfffd || (point >= 0xfdd0 && point <= 0xfdef)
                    || (point & 0xffff) >= 0xfffe) point = '?';
            out.appendCodePoint(point);
            space = false;
            visible |= Character.getType(point) != Character.FORMAT;
        }
        if (!visible) return "";
        if (out.length() > 0 && out.charAt(out.length() - 1) == ' ') out.setLength(out.length() - 1);
        return Normalizer.normalize(out, Normalizer.Form.NFC);
    }
}
