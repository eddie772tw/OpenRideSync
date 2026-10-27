package org.openridesync.gen1;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Semantic Gen1 projection encoder. Optional IDs use -1 for absent; 0 is present.
 * Text is already encoded by its caller; the wire length is its low eight bits.
 * This matches the fixed APK helper even for oversized/non-UTF8 byte arrays.
 */
public final class ScreenEncoder {
    private ScreenEncoder() {}
    public static final class Text {
        private final byte[] bytes;
        public final int color;
        public Text(byte[] bytes, int color) {
            this.bytes = Objects.requireNonNull(bytes, "bytes").clone();
            this.color = optional(color);
        }
        public static Text utf8(String text, int color) {
            return new Text(Objects.requireNonNull(text, "text").getBytes(StandardCharsets.UTF_8), color);
        }
    }
    public static final class Icon {
        public final int id, tint;
        public Icon(int id, int tint) { this.id = octet(id); this.tint = optional(tint); }
    }
    public static final class Action {
        public final int icon, tint, contentIcon, contentTint, background;
        public Action(int icon, int tint, int contentIcon, int contentTint, int background) {
            this.icon = optional(icon); this.tint = optional(tint);
            this.contentIcon = optional(contentIcon); this.contentTint = optional(contentTint);
            this.background = optional(background);
        }
    }
    private static int octet(int value) {
        if (value < 0 || value > 255) throw new IllegalArgumentException("ID outside 0..255");
        return value;
    }
    private static int optional(int value) { return value == -1 ? value : octet(value); }
    private static void put(ByteArrayOutputStream out, int value) { out.write(octet(value)); }
    private static void opt(ByteArrayOutputStream out, int value) { if (value != -1) put(out, value); }
    private static void text(ByteArrayOutputStream out, Text value) {
        Objects.requireNonNull(value, "text");
        out.write(value.bytes.length & 255);
        if (value.bytes.length != 0) {
            out.write(value.bytes, 0, value.bytes.length); opt(out, value.color);
        }
    }
    private static void icon(ByteArrayOutputStream out, Icon value) {
        Objects.requireNonNull(value, "icon"); put(out, value.id); opt(out, value.tint);
    }
    private static void action(ByteArrayOutputStream out, Action value) {
        Objects.requireNonNull(value, "action"); put(out, value.icon);
        opt(out, value.tint); opt(out, value.contentIcon); opt(out, value.contentTint); opt(out, value.background);
    }
    private static ByteArrayOutputStream header(int request, int background, Text text, int type, int bodyBackground) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        put(out, 1); put(out, request); put(out, background); text(out, text);
        put(out, type); put(out, bodyBackground); return out;
    }
    private static byte[] content(ByteArrayOutputStream out, Icon icon, Text line1, Text line2) {
        icon(out, icon); text(out, line1); text(out, line2); return out.toByteArray();
    }
    public static byte[] menu(int requestId, int headerBackground, Text headerText, int bodyBackground,
            Icon up, Icon down, Icon left, Icon right, Icon contentIcon, Text line1, Text line2) {
        ByteArrayOutputStream out = header(requestId, headerBackground, headerText, 1, bodyBackground);
        icon(out, up); icon(out, down); icon(out, left); icon(out, right);
        return content(out, contentIcon, line1, line2);
    }
    public static byte[] live(int requestId, int headerBackground, Text headerText, int bodyBackground,
            Action left, Action right, int contentBackground, Icon contentIcon, Text line1, Text line2) {
        ByteArrayOutputStream out = header(requestId, headerBackground, headerText, 2, bodyBackground);
        action(out, left); action(out, right); put(out, contentBackground);
        return content(out, contentIcon, line1, line2);
    }
    private static boolean present(Action action) { return action != null && action.icon > 0; }
    private static void popupGroup(ByteArrayOutputStream out, Action action, boolean details) {
        out.write(action == null || action.icon == -1 ? 0 : action.icon);
        if (details) {
            out.write(action.tint == -1 ? 0 : action.tint);
            out.write(action.contentIcon == -1 ? 0 : action.contentIcon);
            out.write(action.contentTint == -1 ? 0 : action.contentTint);
            out.write(action.background == -1 ? 0 : action.background);
        }
    }
    public static byte[] popup(int requestId, int background, Icon icon, Text text, Action left, Action right) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        put(out, 2); put(out, requestId); put(out, background); icon(out, icon); text(out, text);
        if (present(left)) { popupGroup(out, left, true); popupGroup(out, right, present(right)); }
        return out.toByteArray();
    }
}
