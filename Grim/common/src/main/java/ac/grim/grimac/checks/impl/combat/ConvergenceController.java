package ac.grim.grimac.checks.impl.combat;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;

public class ConvergenceController {
    private static final int BUFFER_SIZE = 3;
    private static final double REQUIRED_RATIO = 2.0 / 3.0;
    private static final double BACKTRACK_TICK_THRESHOLD = 5;
    private static final double REACH_EDGE_THRESHOLD = 3.1;
    private static final long COOLDOWN_MS = 20_000;

    private final Int2ObjectMap<CircularBuffer> entityBuffers = new Int2ObjectOpenHashMap<>();
    private final Int2ObjectMap<Long> lastFlagTime = new Int2ObjectOpenHashMap<>();

    public boolean recordAndCheck(int entityId, BacktrackResult result) {
        Long last = lastFlagTime.get(entityId);
        long now = System.currentTimeMillis();
        if (last != null && now - last < COOLDOWN_MS) return false;

        CircularBuffer buf = entityBuffers.computeIfAbsent(entityId, k -> new CircularBuffer(BUFFER_SIZE));
        buf.add(result);
        boolean shouldFire = shouldOneShotFlag(buf);
        if (shouldFire) {
            lastFlagTime.put(Integer.valueOf(entityId), Long.valueOf(now));
        }
        return shouldFire;
    }

    public void reset(int entityId) {
        entityBuffers.remove(entityId);
    }

    public void cleanup() {
        entityBuffers.clear();
        lastFlagTime.clear();
    }

    private boolean shouldOneShotFlag(CircularBuffer buf) {
        int hits = buf.size();
        if (hits < 2) return false;
        int flagCount = 0;
        for (int i = 0; i < hits; i++) {
            BacktrackResult r = buf.get(i);
            if (r != null && isSuspicious(r)) flagCount++;
        }
        return (double) flagCount / hits >= REQUIRED_RATIO;
    }

    private boolean isSuspicious(BacktrackResult r) {
        if (r.type() == BacktrackResult.Type.BACKTRACK) {
            return r.tickDelta() != null
                    && r.tickDelta().compensatedTicks() > BACKTRACK_TICK_THRESHOLD;
        }
        if (r.type() == BacktrackResult.Type.REACH) {
            return r.edgeDistance() > REACH_EDGE_THRESHOLD;
        }
        return false;
    }

    private static class CircularBuffer {
        final BacktrackResult[] records;
        int writeIdx = 0;
        int count = 0;

        CircularBuffer(int size) {
            records = new BacktrackResult[size];
        }

        void add(BacktrackResult r) {
            records[writeIdx] = r;
            writeIdx = (writeIdx + 1) % records.length;
            if (count < records.length) count++;
        }

        BacktrackResult get(int i) {
            if (i < 0 || i >= count) return null;
            int start = (writeIdx - count + records.length) % records.length;
            return records[(start + i) % records.length];
        }

        int size() {
            return count;
        }
    }
}
