package ac.grim.grimac.feature;

import ac.grim.grimac.checks.Check;
import ac.grim.grimac.platform.api.player.PlatformPlayer;
import ac.grim.grimac.player.GrimPlayer;
import org.jetbrains.annotations.NotNull;

public interface FlagMenuHandler {
    FlagMenuHandler NO_OP = new FlagMenuHandler() {
        @Override
        public void recordAlert(@NotNull GrimPlayer player, @NotNull Check check, int violations, @NotNull String verbose) {
        }

        @Override
        public boolean openMenu(@NotNull PlatformPlayer viewer) {
            return false;
        }
    };

    void recordAlert(@NotNull GrimPlayer player, @NotNull Check check, int violations, @NotNull String verbose);

    boolean openMenu(@NotNull PlatformPlayer viewer);

    static @NotNull FlagMenuHandler noOp() {
        return NO_OP;
    }
}
