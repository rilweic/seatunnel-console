package com.dataintegration.console.engine;

import com.dataintegration.console.engine.EngineClient.LogContent;

import java.nio.charset.StandardCharsets;

/** Keeps the last {@code capacity} bytes written to it, however many arrive. */
public final class TailBuffer {
    private final byte[] ring;
    private long total;

    public TailBuffer(int capacity) {
        this.ring = new byte[Math.max(1, capacity)];
    }

    public synchronized void write(byte[] chunk) {
        int offset = 0;
        while (offset < chunk.length) {
            int pos = (int) (total % ring.length);
            int len = Math.min(chunk.length - offset, ring.length - pos);
            System.arraycopy(chunk, offset, ring, pos, len);
            offset += len;
            total += len;
        }
    }

    public synchronized LogContent result() {
        if (total <= ring.length) {
            return new LogContent(new String(ring, 0, (int) total, StandardCharsets.UTF_8), false);
        }
        int start = (int) (total % ring.length);
        byte[] ordered = new byte[ring.length];
        System.arraycopy(ring, start, ordered, 0, ring.length - start);
        System.arraycopy(ring, 0, ordered, ring.length - start, start);
        return new LogContent(new String(ordered, StandardCharsets.UTF_8), true);
    }
}
