package ac.grim.grimac.utils.data;

import ac.grim.grimac.utils.collisions.datatypes.SimpleCollisionBox;

public class EntityPositionHistory {
    private static final int SIZE = 30;
    private final Entry[] buffer = new Entry[SIZE];
    private int writeIdx = 0;
    private int count = 0;
    private SimpleCollisionBox unionBox;

    public record Entry(int tick, SimpleCollisionBox hitbox) {}

    public void record(int tick, SimpleCollisionBox hitbox) {
        buffer[writeIdx] = new Entry(tick, hitbox.copy());
        writeIdx = (writeIdx + 1) % SIZE;
        if (count < SIZE) {
            count++;
        }
        recomputeUnionBox();
    }

    public Entry get(int ticksAgo) {
        if (ticksAgo < 0 || ticksAgo >= count) return null;
        int idx = (writeIdx - 1 - ticksAgo + SIZE) % SIZE;
        return buffer[idx];
    }

    public int size() {
        return count;
    }

    public SimpleCollisionBox getUnionBox() {
        return unionBox;
    }

    private void recomputeUnionBox() {
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, minZ = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
        boolean found = false;
        for (int i = 0; i < count; i++) {
            Entry e = buffer[i];
            if (e == null) continue;
            SimpleCollisionBox box = e.hitbox();
            if (box.minX < minX) minX = box.minX;
            if (box.minY < minY) minY = box.minY;
            if (box.minZ < minZ) minZ = box.minZ;
            if (box.maxX > maxX) maxX = box.maxX;
            if (box.maxY > maxY) maxY = box.maxY;
            if (box.maxZ > maxZ) maxZ = box.maxZ;
            found = true;
        }
        if (found) {
            if (unionBox == null) {
                unionBox = new SimpleCollisionBox(minX, minY, minZ, maxX, maxY, maxZ);
            } else {
                unionBox.minX = minX;
                unionBox.minY = minY;
                unionBox.minZ = minZ;
                unionBox.maxX = maxX;
                unionBox.maxY = maxY;
                unionBox.maxZ = maxZ;
            }
        } else {
            unionBox = null;
        }
    }
}
