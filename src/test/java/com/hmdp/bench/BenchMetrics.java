package com.hmdp.bench;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

public class BenchMetrics {
    private final AtomicLong produced = new AtomicLong();
    private final AtomicLong consumed = new AtomicLong();
    private final AtomicLong firstSendTs = new AtomicLong(0);
    private final AtomicLong lastSendTs = new AtomicLong(0);
    private final AtomicLong firstConsumeTs = new AtomicLong(0);
    private final AtomicLong lastConsumeTs = new AtomicLong(0);
    private final List<Long> latencies = Collections.synchronizedList(new ArrayList<>());

    public void markProduced(long now) {
        produced.incrementAndGet();
        firstSendTs.compareAndSet(0, now);
        lastSendTs.set(now);
    }

    public void markConsumed(long now, long latencyMs) {
        long n = consumed.incrementAndGet();
        firstConsumeTs.compareAndSet(0, now);
        lastConsumeTs.set(now);
        latencies.add(latencyMs);
        if (n % 1000 == 0) {
            System.out.println("[bench] consumed=" + n + ", latestLatencyMs=" + latencyMs);
        }
    }

    public long percentile(double p) {
        if (latencies.isEmpty()) {
            return 0L;
        }
        List<Long> copy;
        synchronized (latencies) {
            copy = new ArrayList<>(latencies);
        }
        Collections.sort(copy);
        int idx = (int) Math.ceil(p * copy.size()) - 1;
        idx = Math.max(0, Math.min(copy.size() - 1, idx));
        return copy.get(idx);
    }

    public long produced() {
        return produced.get();
    }

    public long consumed() {
        return consumed.get();
    }

    public long firstSendTs() {
        return firstSendTs.get();
    }

    public long lastSendTs() {
        return lastSendTs.get();
    }

    public long firstConsumeTs() {
        return firstConsumeTs.get();
    }

    public long lastConsumeTs() {
        return lastConsumeTs.get();
    }
}
