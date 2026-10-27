package org.openridesync.authbridge;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

public final class LoopbackTest {
    static String request(Loopback server, String message) throws Exception {
        try (Socket client = new Socket("127.0.0.1", server.socket.getLocalPort())) {
            client.setSoTimeout(5000);
            client.getOutputStream().write(message.getBytes(StandardCharsets.UTF_8));
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(); byte[] block = new byte[1024]; int count;
            while ((count = client.getInputStream().read(block)) != -1) bytes.write(block, 0, count);
            return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        }
    }
    static String post(Loopback s, String origin, String nonce, String size) {
        return "POST " + s.path + "credential HTTP/1.1\r\nHost: " + s.host + "\r\nOrigin: " + origin
            + "\r\nX-Local-Nonce: " + nonce + "\r\nContent-Type: application/json\r\nContent-Length: " + size + "\r\n\r\n{}";
    }
    static void check(boolean passed) { if (!passed) throw new AssertionError(); }
    public static void main(String[] args) throws Exception {
        AtomicInteger accepted = new AtomicInteger();
        Loopback s = new Loopback("page".getBytes(StandardCharsets.UTF_8), body -> { accepted.incrementAndGet(); return body.equals("{}") ? Loopback.Result.SUCCEEDED : Loopback.Result.INVALID; });
        Thread worker = new Thread(s::serve); worker.start();
        check(s.socket.getInetAddress().isLoopbackAddress());
        check(s.nonce.length() == 64);
        check(request(s, "GET " + s.path + " HTTP/1.1\r\nHost: " + s.host + "\r\n\r\n").contains("200 Result"));
        check(request(s, "GET / HTTP/1.1\r\nHost: " + s.host + "\r\n\r\n").contains("403 Result"));
        check(request(s, post(s, "https://attacker.invalid", s.nonce, "2")).contains("403 Result"));
        check(request(s, post(s, s.origin, "wrong", "2")).contains("403 Result"));
        check(request(s, post(s, s.origin, s.nonce, "16385")).contains("400 Result"));
        check(request(s, post(s, s.origin, s.nonce, "2").replace("Content-Type: application/json", "Content-Type: text/plain")).contains("403 Result"));
        check(request(s, post(s, s.origin, s.nonce, "2").replace("Content-Length: 2", "Transfer-Encoding: chunked\r\nContent-Length: 2")).contains("403 Result"));
        check(request(s, post(s, s.origin, s.nonce, "2").replace(s.host, "attacker.invalid")).contains("403 Result"));
        check(request(s, post(s, s.origin, s.nonce, "2").replace("Host: " + s.host, "Host: " + s.host + "\r\nHost: " + s.host)).isEmpty());
        check(accepted.get() == 0);
        check(request(s, post(s, s.origin, s.nonce, "2")).contains("{\"status\":\"SUCCEEDED\"}"));
        worker.join(6000); check(s.closed && !worker.isAlive() && accepted.get() == 1);
        try { new Socket("127.0.0.1", s.socket.getLocalPort()); throw new AssertionError(); } catch (IOException expected) { }
        for (Loopback.Result outcome : new Loopback.Result[] {Loopback.Result.FAILED, Loopback.Result.CANCELLED, Loopback.Result.TIMEOUT}) {
            CountDownLatch entered = new CountDownLatch(1), complete = new CountDownLatch(1), replied = new CountDownLatch(1);
            AtomicInteger receipts = new AtomicInteger();
            Loopback terminal = new Loopback("page".getBytes(StandardCharsets.UTF_8), body -> {
                receipts.incrementAndGet(); entered.countDown();
                try { complete.await(outcome == Loopback.Result.TIMEOUT ? 40 : 1000, TimeUnit.MILLISECONDS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); return Loopback.Result.CANCELLED; }
                return outcome;
            }, value -> { check(value == outcome); replied.countDown(); });
            Thread serving = new Thread(terminal::serve); serving.start();
            FutureTask<String> response = new FutureTask<>(() -> request(terminal, post(terminal, terminal.origin, terminal.nonce, "2")));
            new Thread(response).start();
            check(entered.await(1000, TimeUnit.MILLISECONDS));
            if (outcome != Loopback.Result.TIMEOUT) {
                check(!response.isDone() && replied.getCount() == 1); // Receipt cannot claim completion before native result.
                complete.countDown();
            }
            String reply = response.get(2000, TimeUnit.MILLISECONDS);
            check(reply.contains("application/json") && reply.contains("{\"status\":\"" + outcome + "\"}"));
            check(replied.await(1000, TimeUnit.MILLISECONDS));
            serving.join(1000); check(terminal.closed && !serving.isAlive() && receipts.get() == 1);
        }
        System.out.println("PASS loopback boundaries, single-use shutdown, delayed native result, failure/cancel/timeout JSON and after-reply callback; no authentication network calls");
    }
}
