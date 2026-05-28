package ac.grim.grimac.utils.math;

import com.github.retrooper.packetevents.util.Vector3d;

public class GazeAngleUtil {
    public static double calculateDeviation(Vector3d origin, Vector3d lookDir, Vector3d targetCenter) {
        double dx = targetCenter.x - origin.x;
        double dy = targetCenter.y - origin.y;
        double dz = targetCenter.z - origin.z;
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1e-7) return 0;
        double dot = (lookDir.x * dx + lookDir.y * dy + lookDir.z * dz) / len;
        return Math.toDegrees(Math.acos(clamp(dot)));
    }

    private static double clamp(double v) {
        return v < -1 ? -1 : (v > 1 ? 1 : v);
    }
}
