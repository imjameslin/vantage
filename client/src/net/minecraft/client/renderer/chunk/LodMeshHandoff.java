package net.minecraft.client.renderer.chunk;

import java.util.ArrayDeque;
import java.util.IdentityHashMap;
import java.util.Objects;

/** Worker-to-render mailbox. One pending result per node identity; a replacement keeps that node's FIFO position.
 * The short monitor protects only references and counters. No I/O, meshing, payload copying or GPU work runs inside.
 * Payloads must be completely built and remain read-only after offer (sharing with the disk cache is permitted).
 * requestedStamp is the scheduler's tag, NOT proof of a coherent input snapshot; that needs a separate data protocol.
 */
final class LodMeshHandoff<K, V> implements AutoCloseable {
    record Result<K, V>(K key, V payload, long requestedStamp, long bytes) {}
    record Stats(boolean closed, int pending, long pendingBytes, long offers, long coalesced, long claims, long rejected) {}

    private final IdentityHashMap<K, Result<K, V>> pending = new IdentityHashMap<>();
    private final ArrayDeque<K> order = new ArrayDeque<>();
    private boolean closed;
    private long pendingBytes, offers, coalesced, claims, rejected;

    synchronized boolean offer(K key, V payload, long requestedStamp, long bytes) {
        Objects.requireNonNull(key);
        Objects.requireNonNull(payload);
        if (bytes < 0) throw new IllegalArgumentException("Negative mesh size");
        if (this.closed) { this.rejected++; return false; }
        Result<K, V> old = this.pending.get(key);
        long nextBytes = Math.addExact(this.pendingBytes - (old == null ? 0 : old.bytes()), bytes);
        Result<K, V> next = new Result<>(key, payload, requestedStamp, bytes);
        this.pending.put(key, next);
        if (old == null) this.order.addLast(key); else this.coalesced++;
        this.pendingBytes = nextBytes;
        this.offers++;
        return true;
    }

    /** Atomically takes the exact payload/tag pair. A later offer remains queued even while this result uploads. */
    synchronized Result<K, V> poll() {
        K key = this.order.pollFirst();
        if (key == null) return null;
        Result<K, V> result = this.pending.remove(key);
        if (result == null) throw new IllegalStateException("Missing mesh handoff");
        this.pendingBytes -= result.bytes();
        this.claims++;
        return result;
    }

    synchronized boolean hasStamp(K key, long requestedStamp) {
        Result<K, V> result = this.pending.get(key);
        return result != null && result.requestedStamp() == requestedStamp;
    }

    synchronized boolean isEmpty() { return this.pending.isEmpty(); }
    synchronized Stats stats() {
        return new Stats(this.closed, this.pending.size(), this.pendingBytes, this.offers, this.coalesced, this.claims, this.rejected);
    }

    /** Revokes publication and releases all unclaimed CPU references. Already claimed results belong to the consumer. */
    @Override public synchronized void close() {
        this.closed = true;
        this.pending.clear();
        this.order.clear();
        this.pendingBytes = 0;
    }
}
