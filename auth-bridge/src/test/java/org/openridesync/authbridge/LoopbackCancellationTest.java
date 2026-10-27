package org.openridesync.authbridge;

import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Run independently: cancellation must release active reads, not just the accept socket. */
public final class LoopbackCancellationTest {
    private static boolean cancelledRead(boolean body) throws Exception {
        AtomicInteger receipts = new AtomicInteger();
        Loopback server = new Loopback(new byte[0], ignored -> {
            receipts.incrementAndGet(); return Loopback.Result.SUCCEEDED;
        });
        Thread worker = new Thread(server::serve, "LoopbackCancellationRegression");
        worker.start();
        try (Socket client = new Socket("127.0.0.1", server.socket.getLocalPort())) {
            String request = body ? LoopbackTest.post(server, server.origin, server.nonce, "100")
                : "GET " + server.path + " HTTP/1.1\r\nHost: " + server.host;
            client.getOutputStream().write(request.getBytes(StandardCharsets.UTF_8));
            client.getOutputStream().flush();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            boolean reading = false;
            while (!reading && System.nanoTime() < deadline) {
                boolean inHandle = false, inLine = false, inSocketRead = false;
                for (StackTraceElement frame : worker.getStackTrace()) {
                    if (frame.getClassName().equals(Loopback.class.getName())) {
                        inHandle |= frame.getMethodName().equals("handle");
                        inLine |= frame.getMethodName().equals("line");
                    }
                    String name = frame.getClassName(), method = frame.getMethodName();
                    inSocketRead |= (method.equals("read") || method.startsWith("socketRead"))
                        && (name.contains("InputStream") || name.contains("Socket") || name.startsWith("sun.nio.ch."));
                }
                // Body fixture declares 100 bytes but sends only two. No line frame
                // plus an active socket read proves headers have been fully consumed.
                reading = inHandle && inSocketRead && (body ? !inLine : inLine);
                if (!reading) Thread.sleep(5);
            }
            if (!reading) throw new AssertionError("test did not enter active " + (body ? "body" : "header") + " socket read");
            server.close();
            worker.join(500);
            boolean stopped = !worker.isAlive();
            if (receipts.get() != 0) throw new AssertionError("incomplete request reached receiver");
            System.out.println("active " + (body ? "body" : "header") + " read stopped after close: " + stopped);
            return stopped;
        } finally {
            server.close(); worker.join(5000);
            if (worker.isAlive()) throw new AssertionError("test worker cleanup failed");
        }
    }
    public static void main(String[] args) throws Exception {
        boolean header = cancelledRead(false);
        boolean body = cancelledRead(true);
        if (!header || !body) throw new AssertionError("close must interrupt active header/body reads");
        System.out.println("PASS cancellation closes active reads without credential delivery");
    }
}
