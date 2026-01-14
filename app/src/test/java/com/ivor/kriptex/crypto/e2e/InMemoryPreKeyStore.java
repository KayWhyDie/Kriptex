package com.ivor.kriptex.crypto.e2e;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Test-only in-memory PreKeyStore.
 */
final class InMemoryPreKeyStore implements PreKeyStore {

    private final Map<Integer, PreKey> byId = new HashMap<>();
    private final Set<Integer> unused = new HashSet<>();

    @Override
    public synchronized void put(PreKey preKey) {
        if (preKey == null) {
            throw new IllegalArgumentException("preKey is null");
        }
        if (byId.containsKey(preKey.preKeyId)) {
            throw new IllegalStateException("preKeyId already exists");
        }
        byId.put(preKey.preKeyId, preKey);
        unused.add(preKey.preKeyId);
    }

    @Override
    public synchronized PreKey get(int preKeyId) {
        return byId.get(preKeyId);
    }

    @Override
    public synchronized PreKey consume(int preKeyId) {
        if (!unused.remove(preKeyId)) {
            return null;
        }
        return byId.remove(preKeyId);
    }

    @Override
    public synchronized int unusedCount() {
        return unused.size();
    }

    synchronized int anyUnusedPreKeyIdForTest() {
        if (unused.isEmpty()) {
            throw new IllegalStateException("no unused prekeys");
        }
        return unused.iterator().next();
    }
}
