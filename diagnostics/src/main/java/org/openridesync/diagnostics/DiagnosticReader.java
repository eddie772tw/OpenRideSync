package org.openridesync.diagnostics;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Strict reader for the new schema. Existing research trace readers remain separate. */
public final class DiagnosticReader {
    private DiagnosticReader() {}
    public static final class Result {
        public final long rows, sessions;
        public final boolean complete;
        private Result(long rows, long sessions, boolean complete) { this.rows=rows; this.sessions=sessions; this.complete=complete; }
    }
    public static Result read(File file) throws IOException {
        Map<String, Long> sequence = new HashMap<>();
        Map<String, String> latestStatus = new HashMap<>();
        Map<String, Long> observed = new HashMap<>();
        Map<String, long[]> counters = new HashMap<>();
        java.util.Set<String> gaps = new java.util.HashSet<>();
        long rows = 0;
        if (file.length() > 8L * 1024 * 1024) throw new IOException("journal exceeds reader limit");
        try (RandomAccessFile end = new RandomAccessFile(file, "r")) {
            if (end.length() == 0) throw new IOException("empty journal");
            end.seek(end.length()-1); if (end.read() != '\n') throw new IOException("partial row");
        }
        try (BufferedReader in = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            if (!DiagnosticJournal.HEADER.equals(in.readLine())) throw new IOException("unexpected schema");
            String line;
            while ((line = in.readLine()) != null) {
                if (line.length() > 2048) throw new IOException("oversize row");
                String[] f = line.split("\t", -1);
                try {
                    if (f.length != 15 || !f[0].equals("1")) throw new IllegalArgumentException();
                    if (!UUID.fromString(f[1]).toString().equals(f[1])) throw new IllegalArgumentException();
                    if (!f[6].equals("-") && !UUID.fromString(f[6]).toString().equals(f[6])) throw new IllegalArgumentException();
                    long seq = Long.parseLong(f[4]);
                    if (seq <= sequence.getOrDefault(f[1], 0L)) throw new IllegalArgumentException();
                    if (seq != sequence.getOrDefault(f[1], 0L) + 1) gaps.add(f[1]);
                    for (int col : new int[] {2,3,10,11,12,13}) if (Long.parseLong(f[col]) < 0) throw new IllegalArgumentException();
                    if (Long.parseLong(f[11]) > Long.parseLong(f[10])) throw new IllegalArgumentException();
                    DiagnosticJournal.Component.valueOf(f[5]); DiagnosticJournal.Event.valueOf(f[7]);
                    DiagnosticJournal.Outcome.valueOf(f[8]); DiagnosticJournal.ErrorCode.valueOf(f[9]);
                    if (!f[14].matches("OK|DROPPED|IO_ERROR|SIZE_LIMIT")) throw new IllegalArgumentException();
                    if (f[14].equals("OK") && (Long.parseLong(f[12]) != 0 || Long.parseLong(f[13]) != 0)) throw new IllegalArgumentException();
                    long[] now = {Long.parseLong(f[3]), Long.parseLong(f[10]), Long.parseLong(f[11]), Long.parseLong(f[12]), Long.parseLong(f[13])};
                    long[] before = counters.get(f[1]);
                    if (before != null) for (int i=0;i<now.length;i++) if (now[i]<before[i]) throw new IllegalArgumentException();
                    counters.put(f[1], now);
                    if (!f[7].equals("CHECKPOINT")) observed.merge(f[1], 1L, Long::sum);
                    sequence.put(f[1], seq);
                    latestStatus.put(f[1], f[14].equals("OK") && f[5].equals("DIAGNOSTICS") &&
                        f[7].equals("CHECKPOINT") && f[10].equals(f[11]) &&
                        observed.getOrDefault(f[1], 0L) == Long.parseLong(f[11]) && !gaps.contains(f[1]) ? "OK" : "INCOMPLETE"); rows++;
                } catch (IllegalArgumentException e) { throw new IOException("invalid journal row " + (rows+2)); }
            }
        }
        if (rows == 0) throw new IOException("journal has no checkpoint");
        return new Result(rows, sequence.size(), latestStatus.values().stream().allMatch("OK"::equals));
    }
}
