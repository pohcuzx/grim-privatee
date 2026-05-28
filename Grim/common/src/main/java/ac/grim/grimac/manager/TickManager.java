package ac.grim.grimac.manager;

import ac.grim.grimac.manager.tick.Tickable;
import ac.grim.grimac.manager.tick.impl.ClearRecentlyUpdatedBlocks;
import ac.grim.grimac.manager.tick.impl.ClientVersionSetter;
import ac.grim.grimac.manager.tick.impl.ResetTick;
import ac.grim.grimac.manager.tick.impl.TickInventory;
import com.google.common.collect.ClassToInstanceMap;
import com.google.common.collect.ImmutableClassToInstanceMap;

import java.util.concurrent.TimeUnit;

public class TickManager {
    // Overflows after 4 years of uptime
    public int currentTick;
    public long serverMsPt = 50L;
    private long lastTickNanos = System.nanoTime();
    private final ClassToInstanceMap<Tickable> syncTick;
    private final ClassToInstanceMap<Tickable> asyncTick;

    public TickManager() {
        syncTick = new ImmutableClassToInstanceMap.Builder<Tickable>()
                .put(ResetTick.class, new ResetTick())
                .build();

        asyncTick = new ImmutableClassToInstanceMap.Builder<Tickable>()
                .put(ClientVersionSetter.class, new ClientVersionSetter()) // Async because permission lookups might take a while, depending on the plugin
                .put(TickInventory.class, new TickInventory()) // Async because I've never gotten an exception from this.  It's probably safe.
                .put(ClearRecentlyUpdatedBlocks.class, new ClearRecentlyUpdatedBlocks())
                .build();
    }

    public void tickSync() {
        long now = System.nanoTime();
        serverMsPt = TimeUnit.NANOSECONDS.toMillis(now - lastTickNanos);
        lastTickNanos = now;
        currentTick++;
        for (Tickable tickable : syncTick.values()) {
            tickable.tick();
        }
    }

    public void tickAsync() {
        for (Tickable tickable : asyncTick.values()) {
            tickable.tick();
        }
    }
}
