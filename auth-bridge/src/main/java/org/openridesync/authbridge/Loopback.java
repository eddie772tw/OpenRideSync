package org.openridesync.authbridge;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;
/** One session, exact same-origin POST, bounded bytes/time, no logs or disk credentials. */
public final class Loopback implements AutoCloseable {
    public enum Result { SUCCEEDED, FAILED, CANCELLED, TIMEOUT, INVALID }
    public interface Receiver { Result accept(String body); }
    final ServerSocket socket;
    final String nonce, path, host, origin;
    final byte[] page;
    final Receiver receiver;
    final java.util.function.Consumer<Result> afterReply;
    final long deadline = System.nanoTime() + 300_000_000_000L;
    volatile boolean closed;
    private Socket activeClient;

    public Loopback(byte[] page, Receiver receiver) throws Exception {
        this(page, receiver, result -> {});
    }
    public Loopback(byte[] page, Receiver receiver, java.util.function.Consumer<Result> afterReply) throws Exception {
        this.page = page; this.receiver = receiver; this.afterReply = afterReply;
        byte[] random = new byte[32]; new SecureRandom().nextBytes(random);
        StringBuilder text = new StringBuilder();
        for (byte b : random) text.append(String.format(Locale.ROOT, "%02x", b & 255));
        nonce = text.toString(); path = "/session/" + nonce + "/";
        socket = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
        socket.setSoTimeout(1000);
        host = "localhost:" + socket.getLocalPort(); origin = "http://" + host;
    }
    public String url() { return origin + path; }
    public synchronized void close() {
        closed = true;
        try { socket.close(); } catch (Exception ignored) { }
        if (activeClient != null) try { activeClient.close(); } catch (Exception ignored) { }
    }
    public void serve() {
        try {
            while (!closed && System.nanoTime() < deadline) {
                try (Socket client = socket.accept()) {
                    synchronized (this) {
                        if (closed) break;
                        activeClient = client;
                    }
                    client.setSoTimeout(4000);
                    handle(client);
                } catch (Exception ignored) { }
                finally { synchronized (this) { activeClient = null; } }
            }
        } finally { close(); }
    }
    private void handle(Socket client) throws Exception {
        InputStream input = client.getInputStream(); OutputStream output = client.getOutputStream();
        String start = line(input); Map<String, String> headers = new HashMap<>();
        int total = start.length();
        for (int i = 0; i < 32; i++) {
            String entry = line(input); total += entry.length();
            if (total > 8192) throw new IllegalArgumentException();
            if (entry.isEmpty()) break;
            int colon = entry.indexOf(':');
            if (colon <= 0 || i == 31) throw new IllegalArgumentException();
            String key = entry.substring(0, colon).toLowerCase(Locale.ROOT);
            if (headers.put(key, entry.substring(colon + 1).trim()) != null) throw new IllegalArgumentException();
        }
        if (!host.equals(headers.get("host")) || headers.containsKey("transfer-encoding")) { reply(output, 403, "Denied"); return; }
        if (start.equals("GET " + path + " HTTP/1.1")) { reply(output, 200, page, "text/html; charset=utf-8"); return; }
        if (!start.equals("POST " + path + "credential HTTP/1.1")
                || !origin.equals(headers.get("origin"))
                || !nonce.equals(headers.get("x-local-nonce"))
                || !"application/json".equals(headers.get("content-type"))) { reply(output, 403, "Denied"); return; }
        String size = headers.get("content-length");
        if (size == null || !size.matches("[0-9]{1,5}")) { reply(output, 400, "Invalid request"); return; }
        int length = Integer.parseInt(size);
        if (length < 1 || length > 16384) { reply(output, 400, "Invalid request"); return; }
        byte[] body = new byte[length]; int done = 0;
        while (done < length) { checkOpen(); int count = input.read(body, done, length - done); if (count < 0) throw new IllegalArgumentException(); done += count; }
        checkOpen();
        Result result = receiver.accept(new String(body, StandardCharsets.UTF_8));
        if (result == null || result == Result.INVALID) { reply(output, 400, "Credential not accepted"); return; }
        try {
            reply(output, 200, ("{\"status\":\"" + result.name() + "\"}").getBytes(StandardCharsets.UTF_8), "application/json; charset=utf-8");
        } finally {
            // Attempt the terminal reply before foregrounding/finishing the Android activity.
            close();
            afterReply.accept(result);
        }
    }
    private void checkOpen() throws SocketException {
        if (closed || System.nanoTime() >= deadline) throw new SocketException("Session closed");
    }
    private String line(InputStream input) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (int i = 0; i < 4096; i++) {
            checkOpen();
            int b = input.read(); if (b < 0) throw new IllegalArgumentException();
            if (b == '\n') {
                byte[] value = bytes.toByteArray();
                if (value.length == 0 || value[value.length - 1] != '\r') throw new IllegalArgumentException();
                return new String(value, 0, value.length - 1, StandardCharsets.US_ASCII);
            }
            bytes.write(b);
        }
        throw new IllegalArgumentException();
    }
    private static void reply(OutputStream output, int code, String body) throws Exception {
        reply(output, code, body.getBytes(StandardCharsets.UTF_8), "text/plain; charset=utf-8");
    }
    private static void reply(OutputStream output, int code, byte[] body, String type) throws Exception {
        String header = "HTTP/1.1 " + code + " Result\r\nContent-Type: " + type + "\r\nContent-Length: " + body.length
            + "\r\nCache-Control: no-store\r\nReferrer-Policy: no-referrer\r\nX-Content-Type-Options: nosniff\r\nConnection: close\r\n\r\n";
        output.write(header.getBytes(StandardCharsets.US_ASCII)); output.write(body); output.flush();
    }
}
