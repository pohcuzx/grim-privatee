package ac.grim.grimac.checks.impl.combat;

import com.github.retrooper.packetevents.util.Vector3d;

public record BacktrackResult(
    Type type,
    String verbose,
    TickDelta tickDelta,
    GazeData gazeData,
    double edgeDistance,
    Vector3d exactHitPoint
) {
    public enum Type { REACH, BACKTRACK, HITBOX, NONE }

    public boolean isFlag() {
        return type != Type.NONE;
    }

    public record TickDelta(int actualTicks, int compensatedTicks, long serverMsPt) {}

    public record GazeData(double deviationDegrees, double dynamicThreshold) {}

    public static final BacktrackResult NONE = new BacktrackResult(Type.NONE, "", null, null, 0, null);
}
