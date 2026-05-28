package ac.grim.grimac.platform.bukkit.flagmenu;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.feature.FlagMenuHandler;
import ac.grim.grimac.manager.init.start.StartableInitable;
import ac.grim.grimac.manager.init.stop.StoppableInitable;
import ac.grim.grimac.platform.api.player.PlatformPlayer;
import ac.grim.grimac.platform.bukkit.GrimACBukkitLoaderPlugin;
import ac.grim.grimac.platform.bukkit.entity.BukkitGrimEntity;
import ac.grim.grimac.platform.bukkit.player.BukkitPlatformPlayer;
import ac.grim.grimac.utils.anticheat.LogUtil;
import ac.grim.grimac.utils.anticheat.MessageUtil;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityTeleport;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import java.util.ArrayList;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public final class BukkitFlagMenuHandler implements FlagMenuHandler, StartableInitable, StoppableInitable, Listener {
    private static final long ENTRY_TTL_MILLIS = TimeUnit.SECONDS.toMillis(60);
    private static final int MENU_SIZE = 54;
    private static final int PAGE_SIZE = 45;
    private static final int PREVIOUS_SLOT = 45;
    private static final int CLOSE_SLOT = 49;
    private static final int NEXT_SLOT = 53;
    private static final double MARKER_Y_OFFSET = 0.7D;
    private static final AtomicInteger NEXT_MARKER_ENTITY_ID = new AtomicInteger(Integer.MAX_VALUE);
    private static final Component MARKER_NAME = Component.text("!", NamedTextColor.RED, TextDecoration.BOLD);

    private final GrimACBukkitLoaderPlugin plugin;
    private final Map<UUID, FlagMenuState> entries = new ConcurrentHashMap<>();
    private final Map<UUID, ActiveMarkerState> activeMarkers = new ConcurrentHashMap<>();

    public BukkitFlagMenuHandler(GrimACBukkitLoaderPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void start() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        GrimAPI.INSTANCE.getScheduler().getGlobalRegionScheduler().runAtFixedRate(
                GrimAPI.INSTANCE.getGrimPlugin(),
                () -> {
                    refreshOpenMenus();
                    refreshActiveMarkers();
                },
                20L,
                20L
        );
    }

    @Override
    public void stop() {
        List<ActiveMarkerState> markersToRemove = new ArrayList<>(activeMarkers.values());
        activeMarkers.clear();
        for (ActiveMarkerState state : markersToRemove) {
            removeMarker(state);
        }
    }

    @Override
    public void recordAlert(@NotNull ac.grim.grimac.player.GrimPlayer player, @NotNull Check check, int violations, @NotNull String verbose) {
        long now = System.currentTimeMillis();
        String playerName = player.getName();
        String latestCheck = cleanLoreValue(check.getDisplayName(), "unknown", 64);
        int ping = player.getTransactionPing();
        String tps = formatTps(GrimAPI.INSTANCE.getPlatformServer().getTPS());
        String worldName = cleanLoreValue(player.getWorldName(), "unknown", 48);
        String clientVersion = cleanLoreValue(player.getVersionName(), "unknown", 48);
        String clientBrand = cleanLoreValue(player.getBrand(), "unknown", 48);
        String context = cleanLoreValue(verbose, "none", 96);

        entries.compute(player.getUniqueId(), (uuid, current) -> {
            if (current == null || current.isExpired(now)) {
                return new FlagMenuState(playerName, latestCheck, 1, violations, ping, tps, worldName, clientVersion, clientBrand, context, now + ENTRY_TTL_MILLIS);
            }

            return new FlagMenuState(playerName, latestCheck, current.flagCount() + 1, violations, ping, tps, worldName, clientVersion, clientBrand, context, now + ENTRY_TTL_MILLIS);
        });
    }

    @Override
    public boolean openMenu(@NotNull PlatformPlayer viewer) {
        Player player = asBukkitPlayer(viewer);
        if (player == null) return false;

        // Kiểm tra quyền: OP, có permission grim.flag, hoặc admin đã được cấp quyền qua /flag add
        boolean hasPerm = player.isOp() || player.hasPermission("grim.flag");
        boolean isAuthorized = GrimAPI.INSTANCE.getFlagPermissionManager().isAdmin(player.getName());
        if (!hasPerm && !isAuthorized) {
            sendMessage(player, "%prefix% &cBạn không có quyền dùng /flag.");
            return true;
        }

        openMenuPage(player, 0);
        return true;
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        entries.remove(playerId);
        removeMarker(playerId);
        removeMarkersForTarget(playerId);
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        syncMarkersForViewer(event.getPlayer());
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        Player target = event.getPlayer();
        if (!hasActiveMarkerForTarget(target.getUniqueId())) return;

        Location to = event.getTo();
        if (to == null || samePosition(event.getFrom(), to)) return;

        updateMarkerPositionsForTarget(target, to);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        Location to = event.getTo();
        if (to == null) return;

        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();

        ActiveMarkerState markerForViewer = activeMarkers.get(playerId);
        if (markerForViewer != null && event.getFrom().getWorld() != to.getWorld()) {
            markerForViewer.markDespawned();
            runDelayedForPlayer(player, () -> refreshMarkerForViewer(player), 1L);
        }

        if (!hasActiveMarkerForTarget(playerId)) return;
        updateMarkerPositionsForTarget(player, to);
    }

    @EventHandler
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();

        ActiveMarkerState markerForViewer = activeMarkers.get(playerId);
        if (markerForViewer != null) {
            markerForViewer.markDespawned();
            runDelayedForPlayer(player, () -> refreshMarkerForViewer(player), 1L);
        }

        if (!hasActiveMarkerForTarget(playerId)) return;
        runDelayedForPlayer(player, () -> updateMarkerPositionsForTarget(player, event.getRespawnLocation()), 1L);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlayerToggleSneak(PlayerToggleSneakEvent event) {
        Player target = event.getPlayer();
        if (!hasActiveMarkerForTarget(target.getUniqueId())) return;

        runDelayedForPlayer(target, () -> updateMarkerPositionsForTarget(target), 1L);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        Inventory topInventory = event.getView().getTopInventory();
        if (!(topInventory.getHolder() instanceof FlagMenuInventoryHolder holder)) return;

        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;

        int rawSlot = event.getRawSlot();
        if (rawSlot < 0 || rawSlot >= topInventory.getSize()) return;

        switch (rawSlot) {
            case PREVIOUS_SLOT -> {
                if (holder.page() > 0) {
                    reopenPage(player, holder.page() - 1);
                }
            }
            case CLOSE_SLOT -> closeLater(player);
            case NEXT_SLOT -> {
                if (holder.page() < getLastPage(snapshotEntries())) {
                    reopenPage(player, holder.page() + 1);
                }
            }
            default -> {
                if (rawSlot < PAGE_SIZE) {
                    handleEntryClick(player, holder.page(), rawSlot);
                }
            }
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof FlagMenuInventoryHolder) {
            event.setCancelled(true);
        }
    }

    /**
     * Xử lý khi admin click vào player head trong menu flag.
     * Tự động chuyển admin sang Spectator Mode và teleport đến player bị flag.
     * Dùng /sfmode để thoát spectator về survival.
     */
    private void handleEntryClick(Player viewer, int page, int slot) {
        FlagMenuEntry entry = getEntryForSlot(page, slot);
        if (entry == null) return;

        Player target = Bukkit.getPlayer(entry.uuid());
        if (target == null) {
            entries.remove(entry.uuid());
            sendMessage(viewer, "%prefix% &cNgười chơi này không còn online.");
            reopenPage(viewer, page);
            return;
        }

        // Bật Spectator Mode cho admin để theo dõi player
        viewer.setGameMode(org.bukkit.GameMode.SPECTATOR);

        // Đánh dấu mục tiêu bằng marker (dấu ! đỏ trên đầu)
        activateOrRefreshMarker(viewer, target);

        // Teleport admin đến vị trí player bị flag
        String teleportCommand = "minecraft:tp " + viewer.getName() + " " + target.getName();
        GrimAPI.INSTANCE.getScheduler().getGlobalRegionScheduler().run(
                GrimAPI.INSTANCE.getGrimPlugin(),
                () -> GrimAPI.INSTANCE.getPlatformServer().dispatchCommand(
                        GrimAPI.INSTANCE.getPlatformServer().getConsoleSender(),
                        teleportCommand
                )
        );

        viewer.closeInventory();
        sendMessage(viewer, "%prefix% &fĐang theo dõi &d" + target.getName() + " &f(Spectator Mode). Dùng &a/sfmode &fđể thoát.");
    }

    private void openMenuPage(Player viewer, int requestedPage) {
        List<FlagMenuEntry> snapshot = snapshotEntries();
        int page = clampPage(requestedPage, snapshot);
        FlagMenuInventoryHolder holder = new FlagMenuInventoryHolder(page);
        Inventory inventory = Bukkit.createInventory(holder, MENU_SIZE, color("&cFlags"));
        holder.setInventory(inventory);
        populateInventory(inventory, snapshot, page);
        viewer.openInventory(inventory);
    }

    private void populateInventory(Inventory inventory, List<FlagMenuEntry> snapshot, int page) {
        ItemStack filler = createMenuItem(Material.GRAY_STAINED_GLASS_PANE, " ", List.of());
        for (int slot = PAGE_SIZE; slot < MENU_SIZE; slot++) {
            inventory.setItem(slot, filler);
        }

        int startIndex = page * PAGE_SIZE;
        int endIndex = Math.min(startIndex + PAGE_SIZE, snapshot.size());
        for (int slot = 0; slot < PAGE_SIZE; slot++) {
            inventory.setItem(slot, null);
        }

        for (int index = startIndex; index < endIndex; index++) {
            inventory.setItem(index - startIndex, createPlayerHead(snapshot.get(index)));
        }

        if (snapshot.isEmpty()) {
            inventory.setItem(22, createMenuItem(
                    Material.BARRIER,
                    color("&cKhông có flag"),
                    List.of(color("&7Người chơi bị flag sẽ hiện trong 60 giây."))
            ));
        }

        inventory.setItem(PREVIOUS_SLOT, createPageButton(
                Material.ARROW,
                color(page > 0 ? "&cTrang trước" : "&8Trang trước"),
                page > 0,
                page,
                getLastPage(snapshot) + 1
        ));
        inventory.setItem(CLOSE_SLOT, createMenuItem(
                Material.BARRIER,
                color("&cĐóng"),
                List.of(color("&7Đóng menu."))
        ));
        inventory.setItem(NEXT_SLOT, createPageButton(
                Material.ARROW,
                color(page < getLastPage(snapshot) ? "&cTrang sau" : "&8Trang sau"),
                page < getLastPage(snapshot),
                page,
                getLastPage(snapshot) + 1
        ));
    }

    private void refreshOpenMenus() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            runForPlayer(player, () -> refreshOpenMenu(player));
        }
    }

    private void refreshOpenMenu(Player player) {
        Inventory topInventory = player.getOpenInventory().getTopInventory();
        if (!(topInventory.getHolder() instanceof FlagMenuInventoryHolder holder)) return;

        List<FlagMenuEntry> snapshot = snapshotEntries();
        int page = clampPage(holder.page(), snapshot);
        if (page != holder.page()) {
            openMenuPage(player, page);
            return;
        }

        populateInventory(topInventory, snapshot, page);
        player.updateInventory();
    }

    private void refreshActiveMarkers() {
        Set<UUID> activeTargetIds = snapshotEntries().stream()
                .map(FlagMenuEntry::uuid)
                .collect(Collectors.toSet());

        for (ActiveMarkerState state : new ArrayList<>(activeMarkers.values())) {
            UUID viewerId = state.viewerId();
            if (!activeTargetIds.contains(state.targetId())) {
                removeMarker(viewerId);
                continue;
            }

            Player viewer = Bukkit.getPlayer(viewerId);
            if (viewer == null) {
                removeMarker(viewerId);
                continue;
            }

            Player target = Bukkit.getPlayer(state.targetId());
            if (target == null) {
                removeMarker(viewerId);
                continue;
            }

            runForPlayer(target, () -> refreshActiveMarker(viewerId, target));
        }
    }

    private void refreshActiveMarker(UUID viewerId, Player target) {
        ActiveMarkerState state = activeMarkers.get(viewerId);
        if (state == null) return;
        if (!state.targetId().equals(target.getUniqueId())) return;

        updateMarkerPosition(state, target);
    }

    private void activateOrRefreshMarker(Player viewer, Player target) {
        UUID viewerId = viewer.getUniqueId();
        UUID targetId = target.getUniqueId();

        runForPlayer(target, () -> {
            ActiveMarkerState current = activeMarkers.get(viewerId);
            if (current != null && current.targetId().equals(targetId)) {
                updateMarkerPosition(current, target);
                return;
            }

            if (current != null) {
                activeMarkers.remove(viewerId, current);
                removeMarker(current);
            }

            ActiveMarkerState created = new ActiveMarkerState(
                    viewerId,
                    targetId,
                    NEXT_MARKER_ENTITY_ID.getAndDecrement(),
                    UUID.randomUUID()
            );
            activeMarkers.put(viewerId, created);
            updateMarkerPosition(created, target);
        });
    }

    private boolean hasActiveMarkerForTarget(UUID targetId) {
        for (ActiveMarkerState state : activeMarkers.values()) {
            if (state.targetId().equals(targetId)) {
                return true;
            }
        }
        return false;
    }

    private void updateMarkerPositionsForTarget(Player target) {
        updateMarkerPositionsForTarget(target, target.getLocation());
    }

    private void updateMarkerPositionsForTarget(Player target, Location targetLocation) {
        for (ActiveMarkerState state : new ArrayList<>(activeMarkers.values())) {
            if (!state.targetId().equals(target.getUniqueId())) continue;
            updateMarkerPosition(state, target, targetLocation);
        }
    }

    private void updateMarkerPosition(ActiveMarkerState state, Player target) {
        updateMarkerPosition(state, target, target.getLocation());
    }

    private void updateMarkerPosition(ActiveMarkerState state, Player target, Location targetLocation) {
        ActiveMarkerState current = activeMarkers.get(state.viewerId());
        if (current == null || !current.targetId().equals(target.getUniqueId())) return;

        Location markerLocation = getMarkerLocation(targetLocation, target.getHeight());
        sendMarkerSpawnOrTeleport(current, markerLocation);
    }

    private boolean isActiveMarkerState(ActiveMarkerState state) {
        return activeMarkers.get(state.viewerId()) == state;
    }

    private void syncMarkersForViewer(Player viewer) {
        refreshMarkerForViewer(viewer);
    }

    private void removeMarker(UUID viewerId) {
        ActiveMarkerState removed = activeMarkers.remove(viewerId);
        if (removed != null) {
            removeMarker(removed);
        }
    }

    private void removeMarkersForTarget(UUID targetId) {
        for (ActiveMarkerState state : new ArrayList<>(activeMarkers.values())) {
            if (!state.targetId().equals(targetId)) continue;
            removeMarker(state.viewerId());
        }
    }

    private void removeMarker(ActiveMarkerState state) {
        runForViewer(state.viewerId(), viewer -> {
            if (state.isSpawned()) {
                sendMarkerPacket(viewer, new WrapperPlayServerDestroyEntities(state.entityId()));
                state.markDespawned();
            }
        });
    }

    private void refreshMarkerForViewer(Player viewer) {
        ActiveMarkerState state = activeMarkers.get(viewer.getUniqueId());
        if (state == null) return;

        Player target = Bukkit.getPlayer(state.targetId());
        if (target == null) {
            removeMarker(viewer.getUniqueId());
            return;
        }

        runForPlayer(target, () -> updateMarkerPosition(state, target));
    }

    private void sendMarkerSpawnOrTeleport(ActiveMarkerState state, Location markerLocation) {
        Location desiredLocation = markerLocation.clone();

        runForViewer(state.viewerId(), viewer -> {
            if (!isActiveMarkerState(state)) return;

            if (desiredLocation.getWorld() != null && viewer.getWorld() != desiredLocation.getWorld()) {
                if (state.isSpawned()) {
                    sendMarkerPacket(viewer, new WrapperPlayServerDestroyEntities(state.entityId()));
                    state.markDespawned();
                }
                return;
            }

            if (state.isSpawned()) {
                sendMarkerPacket(viewer, new WrapperPlayServerEntityTeleport(
                        state.entityId(),
                        toPacketVector(desiredLocation),
                        0.0F,
                        0.0F,
                        false
                ));
                return;
            }

            sendMarkerPacket(viewer, new WrapperPlayServerSpawnEntity(
                    state.entityId(),
                    state.markerUuid(),
                    EntityTypes.ARMOR_STAND,
                    toPacketLocation(desiredLocation),
                    0.0F,
                    0,
                    Vector3d.zero()
            ));
            sendMarkerPacket(viewer, new WrapperPlayServerEntityMetadata(state.entityId(), createMarkerMetadata()));
            state.markSpawned();
        });
    }

    private List<EntityData<?>> createMarkerMetadata() {
        return List.of(
                new EntityData<>(0, EntityDataTypes.BYTE, (byte) 0x20),
                new EntityData<>(2, EntityDataTypes.OPTIONAL_ADV_COMPONENT, Optional.of(MARKER_NAME)),
                new EntityData<>(3, EntityDataTypes.BOOLEAN, true),
                new EntityData<>(4, EntityDataTypes.BOOLEAN, true),
                new EntityData<>(5, EntityDataTypes.BOOLEAN, true),
                new EntityData<>(15, EntityDataTypes.BYTE, (byte) 0x18)
        );
    }

    private com.github.retrooper.packetevents.protocol.world.Location toPacketLocation(Location location) {
        return new com.github.retrooper.packetevents.protocol.world.Location(toPacketVector(location), 0.0F, 0.0F);
    }

    private Vector3d toPacketVector(Location location) {
        return new Vector3d(location.getX(), location.getY(), location.getZ());
    }

    private void sendMarkerPacket(Player viewer, PacketWrapper<?> packet) {
        try {
            PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, packet);
        } catch (Throwable throwable) {
            LogUtil.warn("Failed to send /flag marker packet", throwable);
        }
    }

    private Location getMarkerLocation(Player target) {
        return getMarkerLocation(target.getLocation(), target.getHeight());
    }

    private Location getMarkerLocation(Location baseLocation, double targetHeight) {
        return baseLocation.clone().add(0.0D, targetHeight + MARKER_Y_OFFSET, 0.0D);
    }

    private boolean samePosition(Location first, Location second) {
        return first.getWorld() == second.getWorld()
                && Double.compare(first.getX(), second.getX()) == 0
                && Double.compare(first.getY(), second.getY()) == 0
                && Double.compare(first.getZ(), second.getZ()) == 0;
    }

    /**
     * Tạo player head cho menu flag với thông tin chi tiết về player bị flag.
     * Hiển thị thông tin check, mạng, client và hướng dẫn sử dụng.
     */
    private ItemStack createPlayerHead(FlagMenuEntry entry) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) item.getItemMeta();
        OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(entry.uuid());
        meta.setOwningPlayer(offlinePlayer);
        meta.setDisplayName(color("&c✦ &d" + entry.playerName() + " &c✦"));

        List<String> lore = new ArrayList<>();
        lore.add(color("&8&m                                     "));

        // ── Thông tin phát hiện ──
        lore.add(color("&c&l⚡ THÔNG TIN PHÁT HIỆN"));
        lore.add(color("&7 ▪ Check mới nhất: &f" + entry.latestCheck()));
        lore.add(color("&7 ▪ Mức vi phạm (VL): &c" + entry.checkViolations()));
        lore.add(color("&7 ▪ Số lần flag: &c" + entry.flagCount()));
        lore.add("");

        // ── Thông tin mạng & server ──
        lore.add(color("&e&l🌐 MẠNG & MÔI TRƯỜNG"));
        lore.add(color("&7 ▪ Ping: &a" + entry.ping() + "ms &7| TPS: &a" + entry.tps()));
        lore.add(color("&7 ▪ World: &f" + entry.worldName()));
        lore.add("");

        // ── Thông tin client ──
        lore.add(color("&b&l💻 THÔNG TIN CLIENT"));
        lore.add(color("&7 ▪ Phiên bản: &f" + entry.clientVersion()));
        lore.add(color("&7 ▪ Brand: &f" + entry.clientBrand()));
        lore.add(color("&7 ▪ Chi tiết: &f" + entry.context()));

        lore.add(color("&8&m                                     "));

        // ── Thời gian & hành động ──
        lore.add(color("&7⏳ Thời gian hiển thị còn lại: &e" + entry.remainingSeconds() + "s"));
        lore.add("");
        lore.add(color("&a▶ &lCLICK CHUỘT TRÁI &ađể theo dõi &d(Spectator)"));
        lore.add(color("&7  (Dùng lệnh &f/sfmode &7để thoát về sinh tồn)"));
        lore.add(color("&8&m                                     "));

        meta.setLore(lore);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createPageButton(Material material, String title, boolean enabled, int currentPage, int totalPages) {
        List<String> lore = new ArrayList<>();
        lore.add(color("&7Trang " + (currentPage + 1) + "/" + Math.max(totalPages, 1)));
        lore.add(color(enabled ? "&cBấm để chuyển trang." : "&8Không có thêm trang."));
        return createMenuItem(material, title, lore);
    }

    private ItemStack createMenuItem(Material material, String title, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(title);
        meta.setLore(lore);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        item.setItemMeta(meta);
        return item;
    }

    private List<FlagMenuEntry> snapshotEntries() {
        long now = System.currentTimeMillis();
        entries.entrySet().removeIf(entry -> entry.getValue().isExpired(now) || Bukkit.getPlayer(entry.getKey()) == null);

        return entries.entrySet().stream()
                .map(entry -> new FlagMenuEntry(
                        entry.getKey(),
                        entry.getValue().playerName(),
                        entry.getValue().latestCheck(),
                        entry.getValue().flagCount(),
                        entry.getValue().checkViolations(),
                        entry.getValue().ping(),
                        entry.getValue().tps(),
                        entry.getValue().worldName(),
                        entry.getValue().clientVersion(),
                        entry.getValue().clientBrand(),
                        entry.getValue().context(),
                        Math.max(1L, TimeUnit.MILLISECONDS.toSeconds(entry.getValue().expiresAt() - now))
                ))
                .sorted(Comparator
                        .comparingLong(FlagMenuEntry::remainingSeconds)
                        .reversed()
                        .thenComparing(Comparator.comparingInt(FlagMenuEntry::flagCount).reversed())
                        .thenComparing(FlagMenuEntry::playerName, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    private @Nullable FlagMenuEntry getEntryForSlot(int page, int slot) {
        List<FlagMenuEntry> snapshot = snapshotEntries();
        int index = (page * PAGE_SIZE) + slot;
        if (index < 0 || index >= snapshot.size()) {
            return null;
        }
        return snapshot.get(index);
    }

    private int clampPage(int requestedPage, List<FlagMenuEntry> snapshot) {
        return Math.max(0, Math.min(requestedPage, getLastPage(snapshot)));
    }

    private int getLastPage(List<FlagMenuEntry> snapshot) {
        if (snapshot.isEmpty()) return 0;
        return (snapshot.size() - 1) / PAGE_SIZE;
    }

    private void reopenPage(Player player, int page) {
        runForPlayer(player, () -> openMenuPage(player, page));
    }

    private void closeLater(Player player) {
        runForPlayer(player, player::closeInventory);
    }

    private void runForPlayer(Player player, Runnable task) {
        GrimAPI.INSTANCE.getScheduler().getEntityScheduler().run(
                new BukkitGrimEntity(player),
                GrimAPI.INSTANCE.getGrimPlugin(),
                task,
                null
        );
    }

    private void runDelayedForPlayer(Player player, Runnable task, long delayTicks) {
        GrimAPI.INSTANCE.getScheduler().getEntityScheduler().runDelayed(
                new BukkitGrimEntity(player),
                GrimAPI.INSTANCE.getGrimPlugin(),
                task,
                null,
                delayTicks
        );
    }

    private void runForViewer(UUID viewerId, Consumer<Player> task) {
        Player viewer = Bukkit.getPlayer(viewerId);
        if (viewer == null) return;

        runForPlayer(viewer, () -> {
            if (viewer.isOnline()) {
                task.accept(viewer);
            }
        });
    }



    private void sendMessage(Player player, String message) {
        String parsed = MessageUtil.replacePlaceholders((PlatformPlayer) null, message);
        player.sendMessage(color(parsed == null ? message : parsed));
    }

    private @Nullable Player asBukkitPlayer(PlatformPlayer viewer) {
        if (viewer instanceof BukkitPlatformPlayer bukkitPlatformPlayer) {
            return bukkitPlatformPlayer.getBukkitPlayer();
        }

        Object nativePlayer = viewer.getNative();
        return nativePlayer instanceof Player player ? player : null;
    }

    private String color(String message) {
        return ChatColor.translateAlternateColorCodes('&', message);
    }

    private String formatTps(double tps) {
        if (Double.isNaN(tps) || Double.isInfinite(tps)) {
            return "N/A";
        }
        return String.format(Locale.ROOT, "%.2f", tps);
    }

    private String cleanLoreValue(@Nullable String value, String fallback, int maxLength) {
        String normalized = value == null || value.isBlank() ? fallback : value;
        normalized = normalized
                .replace('\n', ' ')
                .replace('\r', ' ')
                .replace('\t', ' ')
                .trim();
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, Math.max(0, maxLength - 3)) + "...";
    }

    private record FlagMenuState(
            String playerName,
            String latestCheck,
            int flagCount,
            int checkViolations,
            int ping,
            String tps,
            String worldName,
            String clientVersion,
            String clientBrand,
            String context,
            long expiresAt
    ) {
        private boolean isExpired(long now) {
            return expiresAt <= now;
        }
    }

    private record FlagMenuEntry(
            UUID uuid,
            String playerName,
            String latestCheck,
            int flagCount,
            int checkViolations,
            int ping,
            String tps,
            String worldName,
            String clientVersion,
            String clientBrand,
            String context,
            long remainingSeconds
    ) {
    }

    private static final class ActiveMarkerState {
        private final UUID viewerId;
        private final UUID targetId;
        private final int entityId;
        private final UUID markerUuid;
        private volatile boolean spawned = false;

        private ActiveMarkerState(UUID viewerId, UUID targetId, int entityId, UUID markerUuid) {
            this.viewerId = viewerId;
            this.targetId = targetId;
            this.entityId = entityId;
            this.markerUuid = markerUuid;
        }

        private UUID viewerId() {
            return viewerId;
        }

        private UUID targetId() {
            return targetId;
        }

        private int entityId() {
            return entityId;
        }

        private UUID markerUuid() {
            return markerUuid;
        }

        private boolean isSpawned() {
            return spawned;
        }

        private void markSpawned() {
            spawned = true;
        }

        private void markDespawned() {
            spawned = false;
        }
    }

    private static final class FlagMenuInventoryHolder implements InventoryHolder {
        private final int page;
        private Inventory inventory;

        private FlagMenuInventoryHolder(int page) {
            this.page = page;
        }

        private int page() {
            return page;
        }

        @Override
        public @NotNull Inventory getInventory() {
            return inventory;
        }

        private void setInventory(Inventory inventory) {
            this.inventory = inventory;
        }
    }
}
