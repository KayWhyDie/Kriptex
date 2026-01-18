package com.ivor.kriptex.tor.chunked;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

/**
 * Small round-robin queue for fairness across mediaIds.
 *
 * Pure-JVM (no Android deps) so it can be unit tested.
 */
public final class RoundRobinMediaQueue {

    private final ArrayDeque<String> queue = new ArrayDeque<>();
    private final HashSet<String> set = new HashSet<>();

    public synchronized void offer(String mediaId) {
        if (mediaId == null || mediaId.trim().isEmpty()) return;
        if (set.add(mediaId)) {
            queue.addLast(mediaId);
        }
    }

    public synchronized void remove(String mediaId) {
        if (mediaId == null) return;
        if (!set.remove(mediaId)) return;
        queue.remove(mediaId);
    }

    public synchronized int size() {
        return queue.size();
    }

    /**
     * Picks the next eligible mediaId not in inFlight.
     * Rotates the queue to maintain round-robin fairness.
     */
    public synchronized String next(Set<String> inFlight) {
        if (queue.isEmpty()) return null;
        int n = queue.size();
        for (int i = 0; i < n; i++) {
            String id = queue.pollFirst();
            if (id == null) break;
            queue.addLast(id);
            if (inFlight == null || !inFlight.contains(id)) {
                return id;
            }
        }
        return null;
    }

    /**
     * Snapshot of currently enqueued ids.
     */
    public synchronized Set<String> snapshot() {
        return new HashSet<>(set);
    }
}
