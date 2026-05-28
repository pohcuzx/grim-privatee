package ac.grim.grimac.command.commands;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.command.BuildableCommand;
import ac.grim.grimac.command.CloudCommandService;
import ac.grim.grimac.command.requirements.PlayerSenderRequirement;
import ac.grim.grimac.platform.api.manager.cloud.CloudCommandAdapter;
import ac.grim.grimac.platform.api.player.PlatformPlayer;
import ac.grim.grimac.platform.api.sender.Sender;
import ac.grim.grimac.utils.anticheat.MessageUtil;
import org.incendo.cloud.CommandManager;
import org.incendo.cloud.context.CommandContext;
import org.incendo.cloud.parser.standard.StringParser;
import org.jetbrains.annotations.NotNull;

import java.util.Set;

/**
 * Lệnh /flag — Hệ thống theo dõi player bị flag bởi anticheat.
 * <p>
 * Sub-commands:
 * <ul>
 *   <li>/flag — Mở menu hiển thị danh sách player bị flag (OP hoặc admin được cấp quyền)</li>
 *   <li>/flag add &lt;player&gt; — Thêm player vào danh sách admin được dùng /flag (cần quyền grim.flag.admin)</li>
 *   <li>/flag remove &lt;player&gt; — Xóa player khỏi danh sách admin (cần quyền grim.flag.admin)</li>
 *   <li>/flag list — Xem danh sách admin đã được cấp quyền (cần quyền grim.flag.admin)</li>
 * </ul>
 * </p>
 */
public class FlagMenuCommand implements BuildableCommand {
    @Override
    public void register(CommandManager<Sender> commandManager, CloudCommandAdapter adapter) {
        // /flag — Mở menu flag (OP hoặc admin được cấp quyền)
        commandManager.command(
                commandManager.commandBuilder("flag")
                        .handler(this::handleFlagMenu)
                        .apply(CloudCommandService.REQUIREMENT_FACTORY.create(PlayerSenderRequirement.PLAYER_SENDER_REQUIREMENT))
        );

        // /flag add <player> — Thêm admin vào danh sách quyền flag (cần grim.flag.admin)
        commandManager.command(
                commandManager.commandBuilder("flag")
                        .literal("add")
                        .permission("grim.flag.admin")
                        .required("player", StringParser.stringParser())
                        .handler(this::handleFlagAdd)
        );

        // /flag remove <player> — Xóa admin khỏi danh sách quyền flag (cần grim.flag.admin)
        commandManager.command(
                commandManager.commandBuilder("flag")
                        .literal("remove")
                        .permission("grim.flag.admin")
                        .required("player", StringParser.stringParser())
                        .handler(this::handleFlagRemove)
        );

        // /flag list — Xem danh sách admin đã được cấp quyền (cần grim.flag.admin)
        commandManager.command(
                commandManager.commandBuilder("flag")
                        .literal("list")
                        .permission("grim.flag.admin")
                        .handler(this::handleFlagList)
        );
    }

    /**
     * Mở menu flag — Hiển thị danh sách player bị anticheat flag.
     * Quyền truy cập được kiểm tra bên trong BukkitFlagMenuHandler.openMenu().
     */
    private void handleFlagMenu(@NotNull CommandContext<Sender> context) {
        Sender sender = context.sender();
        PlatformPlayer platformPlayer = sender.getPlatformPlayer();
        if (platformPlayer == null) return;

        if (!GrimAPI.INSTANCE.getFlagMenuHandler().openMenu(platformPlayer)) {
            sender.sendMessage(MessageUtil.getParsedComponent(sender, "flag-menu-unavailable", "%prefix% &cMenu /flag không khả dụng trên nền tảng này."));
        }
    }

    /**
     * /flag add &lt;player&gt; — Thêm player vào danh sách admin flag.
     * Yêu cầu quyền grim.flag.admin (mặc định chỉ OP có).
     */
    private void handleFlagAdd(@NotNull CommandContext<Sender> context) {
        Sender sender = context.sender();
        String playerName = context.get("player");
        boolean added = GrimAPI.INSTANCE.getFlagPermissionManager().addAdmin(playerName);

        if (added) {
            sender.sendMessage(MessageUtil.getParsedComponent(sender, "flag-admin-added",
                    "%prefix% &aĐã thêm &e" + playerName + " &avào danh sách admin flag."));
        } else {
            sender.sendMessage(MessageUtil.getParsedComponent(sender, "flag-admin-exists",
                    "%prefix% &c" + playerName + " &cđã có trong danh sách admin flag rồi."));
        }
    }

    /**
     * /flag remove &lt;player&gt; — Xóa player khỏi danh sách admin flag.
     * Yêu cầu quyền grim.flag.admin (mặc định chỉ OP có).
     */
    private void handleFlagRemove(@NotNull CommandContext<Sender> context) {
        Sender sender = context.sender();
        String playerName = context.get("player");
        boolean removed = GrimAPI.INSTANCE.getFlagPermissionManager().removeAdmin(playerName);

        if (removed) {
            sender.sendMessage(MessageUtil.getParsedComponent(sender, "flag-admin-removed",
                    "%prefix% &aĐã xóa &e" + playerName + " &akhỏi danh sách admin flag."));
        } else {
            sender.sendMessage(MessageUtil.getParsedComponent(sender, "flag-admin-not-found",
                    "%prefix% &c" + playerName + " &ckhông có trong danh sách admin flag."));
        }
    }

    /**
     * /flag list — Hiển thị danh sách tất cả admin đã được cấp quyền dùng /flag.
     * Yêu cầu quyền grim.flag.admin (mặc định chỉ OP có).
     */
    private void handleFlagList(@NotNull CommandContext<Sender> context) {
        Sender sender = context.sender();
        Set<String> admins = GrimAPI.INSTANCE.getFlagPermissionManager().getAdmins();

        if (admins.isEmpty()) {
            sender.sendMessage(MessageUtil.getParsedComponent(sender, "flag-admin-list-empty",
                    "%prefix% &7Chưa có admin nào được cấp quyền /flag."));
        } else {
            sender.sendMessage(MessageUtil.getParsedComponent(sender, "flag-admin-list-header",
                    "%prefix% &e━━━ Danh sách admin flag ━━━"));
            for (String admin : admins) {
                sender.sendMessage(MessageUtil.getParsedComponent(sender, "flag-admin-list-entry",
                        "%prefix% &7▸ &f" + admin));
            }
            sender.sendMessage(MessageUtil.getParsedComponent(sender, "flag-admin-list-footer",
                    "%prefix% &7Tổng: &e" + admins.size() + " &7admin."));
        }
    }
}
