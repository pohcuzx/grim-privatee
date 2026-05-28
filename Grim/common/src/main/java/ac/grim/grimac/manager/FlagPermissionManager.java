package ac.grim.grimac.manager;

import ac.grim.grimac.utils.anticheat.LogUtil;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Quản lý danh sách admin được quyền sử dụng lệnh /flag.
 * <p>
 * Lưu ý: Player có quyền OP luôn được phép dùng /flag mà không cần thêm vào danh sách.
 * Danh sách này dùng cho những player không phải OP nhưng vẫn được admin cấp quyền.
 * </p>
 * <p>
 * Tên player được lưu dưới dạng lowercase để tránh phân biệt hoa/thường.
 * Dữ liệu được persist ra file flag-admins.yml trong thư mục dữ liệu của plugin,
 * giúp giữ danh sách admin qua các lần restart server.
 * </p>
 */
public final class FlagPermissionManager {

    private static final String FILE_NAME = "flag-admins.yml";

    /** Danh sách tên player (lowercase) được cấp quyền dùng /flag */
    private final Set<String> authorizedAdmins = ConcurrentHashMap.newKeySet();
    private volatile File dataFile;

    /**
     * Thêm player vào danh sách được quyền dùng /flag.
     *
     * @param playerName tên player cần thêm
     * @return {@code true} nếu thêm thành công, {@code false} nếu đã tồn tại
     */
    public boolean addAdmin(String playerName) {
        String name = playerName.toLowerCase(Locale.ROOT);
        if (authorizedAdmins.add(name)) {
            save();
            return true;
        }
        return false;
    }

    /**
     * Xóa player khỏi danh sách quyền /flag.
     *
     * @param playerName tên player cần xóa
     * @return {@code true} nếu xóa thành công, {@code false} nếu không tồn tại
     */
    public boolean removeAdmin(String playerName) {
        if (authorizedAdmins.remove(playerName.toLowerCase(Locale.ROOT))) {
            save();
            return true;
        }
        return false;
    }

    /**
     * Kiểm tra player có trong danh sách quyền /flag hay không.
     *
     * @param playerName tên player cần kiểm tra
     * @return {@code true} nếu player có quyền
     */
    public boolean isAdmin(String playerName) {
        return authorizedAdmins.contains(playerName.toLowerCase(Locale.ROOT));
    }

    /**
     * Lấy danh sách tất cả admin đã được cấp quyền (bản sao, không thay đổi được).
     *
     * @return set chứa tên các admin (lowercase)
     */
    public Set<String> getAdmins() {
        return Collections.unmodifiableSet(authorizedAdmins);
    }

    /**
     * Tải danh sách admin từ file flag-admins.yml trong thư mục dữ liệu.
     * Được gọi khi plugin khởi động.
     *
     * @param dataFolder thư mục dữ liệu của plugin (ví dụ: plugins/GrimAC/)
     */
    public void load(File dataFolder) {
        this.dataFile = new File(dataFolder, FILE_NAME);
        if (!dataFile.exists()) return;

        try {
            String content = Files.readString(dataFile.toPath(), StandardCharsets.UTF_8);
            if (content.isBlank()) return;

            Object loaded = new Yaml().load(content);
            if (loaded instanceof Map) {
                Map<?, ?> map = (Map<?, ?>) loaded;
                Object admins = map.get("admins");
                if (admins instanceof List) {
                    for (Object entry : (List<?>) admins) {
                        if (entry instanceof String) {
                            authorizedAdmins.add(((String) entry).toLowerCase(Locale.ROOT));
                        }
                    }
                }
            }
        } catch (Exception e) {
            LogUtil.warn("Failed to load " + FILE_NAME, e);
        }
    }

    /**
     * Ghi danh sách admin hiện tại ra file flag-admins.yml.
     * Được gọi tự động sau khi addAdmin() hoặc removeAdmin().
     */
    private void save() {
        File file = this.dataFile;
        if (file == null) return;

        synchronized (this) {
            try {
                file.getParentFile().mkdirs();
                StringBuilder sb = new StringBuilder();
                sb.append("# GrimAC authorized flag administrators\n");
                sb.append("# Managed via /flag add <player> and /flag remove <player>\n");
                sb.append("admins:\n");
                for (String admin : authorizedAdmins) {
                    sb.append("- ").append(admin).append("\n");
                }
                Files.writeString(file.toPath(), sb.toString(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                LogUtil.error("Failed to save " + FILE_NAME, e);
            }
        }
    }
}
