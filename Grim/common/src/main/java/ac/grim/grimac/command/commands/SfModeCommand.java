package ac.grim.grimac.command.commands;

import ac.grim.grimac.command.BuildableCommand;
import ac.grim.grimac.command.CloudCommandService;
import ac.grim.grimac.command.requirements.PlayerSenderRequirement;
import ac.grim.grimac.platform.api.manager.cloud.CloudCommandAdapter;
import ac.grim.grimac.platform.api.player.PlatformPlayer;
import ac.grim.grimac.platform.api.sender.Sender;
import ac.grim.grimac.utils.anticheat.MessageUtil;
import com.github.retrooper.packetevents.protocol.player.GameMode;
import org.incendo.cloud.CommandManager;
import org.incendo.cloud.context.CommandContext;
import org.jetbrains.annotations.NotNull;

/**
 * Lệnh /sfmode — Chuyển admin từ Spectator Mode về Survival Mode.
 * <p>
 * Dùng sau khi admin đã click player trong /flag menu (tự động bật spectator).
 * Nếu admin đã ở chế độ sinh tồn, sẽ thông báo thay vì chuyển lại.
 * </p>
 */
public class SfModeCommand implements BuildableCommand {

    @Override
    public void register(CommandManager<Sender> commandManager, CloudCommandAdapter adapter) {
        commandManager.command(
                commandManager.commandBuilder("sfmode")
                        .handler(this::handleSfMode)
                        .apply(CloudCommandService.REQUIREMENT_FACTORY.create(PlayerSenderRequirement.PLAYER_SENDER_REQUIREMENT))
        );
    }

    /**
     * Xử lý lệnh /sfmode:
     * - Nếu đang ở Survival → thông báo đã ở survival
     * - Nếu đang ở chế độ khác → chuyển về Survival
     */
    private void handleSfMode(@NotNull CommandContext<Sender> context) {
        Sender sender = context.sender();
        PlatformPlayer platformPlayer = sender.getPlatformPlayer();
        if (platformPlayer == null) return;

        // Kiểm tra quyền (chỉ cho phép OP hoặc admin đã được thêm qua /flag add)
        boolean isOp = sender.hasPermission("grim.flag.admin") || platformPlayer.hasPermission("grim.admin"); // Hoặc dùng hasPermission("grim.flag.admin")
        boolean isAuthorized = ac.grim.grimac.GrimAPI.INSTANCE.getFlagPermissionManager().isAdmin(platformPlayer.getName());
        if (!isOp && !isAuthorized && !platformPlayer.hasPermission("grim.flag")) {
            sender.sendMessage(MessageUtil.getParsedComponent(
                    sender,
                    "sfmode-no-permission",
                    "%prefix% &cBạn không có quyền sử dụng lệnh này."
            ));
            return;
        }

        // Kiểm tra nếu player đã ở chế độ sinh tồn
        if (platformPlayer.getGameMode() == GameMode.SURVIVAL) {
            sender.sendMessage(MessageUtil.getParsedComponent(
                    sender,
                    "sfmode-already-survival",
                    "%prefix% &aBạn đã ở chế độ sinh tồn rồi."
            ));
            return;
        }

        // Chuyển về chế độ sinh tồn
        platformPlayer.setGameMode(GameMode.SURVIVAL);
        sender.sendMessage(MessageUtil.getParsedComponent(
                sender,
                "sfmode-switched",
                "%prefix% &aĐã chuyển về chế độ sinh tồn thành công."
        ));
    }
}
