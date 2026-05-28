// This file was designed and is an original check for GrimAC
// Copyright (C) 2021 DefineOutside
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with this program.  If not, see <http://www.gnu.org/licenses/>.
package ac.grim.grimac.checks.impl.combat;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.api.config.ConfigManager;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.type.PacketCheck;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.grim.grimac.utils.data.EntityPositionHistory;
import ac.grim.grimac.utils.data.packetentity.PacketEntity;
import ac.grim.grimac.utils.data.packetentity.PacketEntitySizeable;
import ac.grim.grimac.utils.data.packetentity.dragon.PacketEntityEnderDragonPart;
import ac.grim.grimac.utils.math.GazeAngleUtil;
import ac.grim.grimac.utils.math.Vector3dm;
import ac.grim.grimac.utils.nmsutil.ReachUtils;
import ac.grim.grimac.utils.viaversion.ViaVersionUtil;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.attribute.Attributes;
import com.github.retrooper.packetevents.protocol.component.ComponentTypes;
import com.github.retrooper.packetevents.protocol.component.builtin.item.ItemAttackRange;
import com.github.retrooper.packetevents.protocol.entity.type.EntityType;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.player.GameMode;
import com.github.retrooper.packetevents.protocol.player.InteractionHand;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientAttack;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import com.viaversion.viaversion.api.Via;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

// You may not copy the check unless you are licensed under GPL
@CheckData(name = "Reach", stableKey = "grim.combat.reach", setback = 10)
public class Reach extends Check implements PacketCheck {

    private static final List<EntityType> blacklisted = Arrays.asList(
            EntityTypes.BOAT,
            EntityTypes.CHEST_BOAT,
            EntityTypes.SHULKER);
    // Only one flag per reach attack, per entity, per tick.
    // We store position because lastX isn't reliable on teleports.
    private final Int2ObjectMap<InteractionData> playerAttackQueue = new Int2ObjectOpenHashMap<>();
    private final ConvergenceController convergenceController = new ConvergenceController();
    private boolean cancelImpossibleHits;
    private double threshold;
    private double cancelBuffer; // For the next 4 hits after using reach, we aggressively cancel reach

    public Reach(GrimPlayer player) {
        super(player);
    }

    @Override
    public void onPacketReceive(final PacketReceiveEvent event) {
        if (!player.disableGrim && event.getPacketType() == PacketType.Play.Client.ATTACK) {
            WrapperPlayClientAttack packet = new WrapperPlayClientAttack(event);
            onInteract(event, packet.getEntityId(), InteractionHand.MAIN_HAND);
        }

        if (!player.disableGrim && event.getPacketType() == PacketType.Play.Client.INTERACT_ENTITY) {
            WrapperPlayClientInteractEntity packet = new WrapperPlayClientInteractEntity(event);
            onInteract(event, packet.getEntityId(), packet.getAction() == WrapperPlayClientInteractEntity.InteractAction.ATTACK ? InteractionHand.MAIN_HAND : packet.getHand());
        }

        // If the player set their look, or we know they have a new tick
        if (isUpdate(event.getPacketType())) {
            tickBetterReachCheckWithAngle();
        }
    }

    private void onInteract(PacketReceiveEvent event, int entityId, InteractionHand hand) {
        // Don't let the player teleport to bypass reach
        if (player.getSetbackTeleportUtil().shouldBlockMovement()) {
            event.setCancelled(true);
            player.onPacketCancel();
            return;
        }

        PacketEntity entity = player.compensatedEntities.entityMap.get(entityId);
        // Stop people from freezing transactions before an entity spawns to bypass reach
        // TODO: implement dragon parts?
        if (entity == null || entity instanceof PacketEntityEnderDragonPart) {
            // Only cancel if and only if we are tracking this entity
            // This is because we don't track paintings.
            if (shouldModifyPackets() && player.compensatedEntities.serverPositionsMap.containsKey(entityId)) {
                event.setCancelled(true);
                player.onPacketCancel();
            }
            return;
        }

        // Dead entities cause false flags (https://github.com/GrimAnticheat/Grim/issues/546)
        if (entity.isDead) return;

        // TODO: Remove when in front of via
        if (entity.type == EntityTypes.ARMOR_STAND && player.getClientVersion().isOlderThan(ClientVersion.V_1_8))
            return;
        // Prevents Happy Ghast Reach false on 1.21.6+ servers with ViaBackwards set up
        if (entity.type == EntityTypes.HAPPY_GHAST && player.getClientVersion().isOlderThan(ClientVersion.V_1_21_6))
            return;
        if (player.gamemode == GameMode.CREATIVE || player.gamemode == GameMode.SPECTATOR)
            return;
        if (player.inVehicle()) return;
        if (entity.riding != null) return;

        ItemStack currentStack = player.inventory.getItemInHand(hand);
        ItemStack startStack = player.inventory.getStartOfTickStack();

        boolean hasRange = false;
        float maxReach = 0f;
        float hitboxMargin = 0f;

        boolean clientAttackRangeExists = player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_21_11);
        boolean clientAndServerAgrees = clientAttackRangeExists && ATTACK_RANGE_COMPONENT_EXISTS;

        boolean viaVersionAvailable = false;
        if (USE_1_8_HITBOX_MARGIN && ViaVersionUtil.isAvailable) {
            viaVersionAvailable = Via.getConfig().getValues().containsKey("use-1_8-hitbox-margin") && Via.getConfig().use1_8HitboxMargin();
        }

        boolean clientAndViaVersion = clientAttackRangeExists && viaVersionAvailable;
        if (clientAndServerAgrees || clientAndViaVersion) {
            ItemAttackRange startRange = startStack.getComponentOr(ComponentTypes.ATTACK_RANGE, null);
            ItemAttackRange currentRange = currentStack.getComponentOr(ComponentTypes.ATTACK_RANGE, null);

            if (clientAndViaVersion) {
                if (startStack != ItemStack.EMPTY) {
                    startRange = new ItemAttackRange(0F, 3F, 0F, 4F, 0.1F, 1F);
                }

                if (currentStack != ItemStack.EMPTY) {
                    currentRange = new ItemAttackRange(0F, 3F, 0F, 4F, 0.1F, 1F);
                }
            }

            // If the start stack has no range component, the client defaults to vanilla reach behavior,
            // regardless of what the current stack is (No Range -> X = No Range used).
            if (startRange != null) {
                hasRange = true;
                if (currentRange == null) {
                    // Range (Start) -> No Range (Current)
                    // Client logic uses Start Range
                    maxReach = startRange.getMaxRange();
                    hitboxMargin = startRange.getHitboxMargin();
                } else {
                    // Range (Start) -> Range (Current)
                    // Client logic requires satisfying BOTH constraints
                    maxReach = Math.min(startRange.getMaxRange(), currentRange.getMaxRange());
                    hitboxMargin = Math.min(startRange.getHitboxMargin(), currentRange.getHitboxMargin());
                }
            }
        }

        boolean tooManyAttacks = playerAttackQueue.size() > 10;
        if (!tooManyAttacks) {
            playerAttackQueue.put(entityId, new InteractionData(
                    player.x, player.y, player.z,
                    hasRange, maxReach, hitboxMargin
            )); // Queue for next tick for very precise check
        }

        boolean knownInvalid = isKnownInvalid(entity, hasRange, maxReach, hitboxMargin);

        if ((shouldModifyPackets() && cancelImpossibleHits && knownInvalid) || tooManyAttacks) {
            event.setCancelled(true);
            player.onPacketCancel();
        }
    }

    // This method finds the most optimal point at which the user should be aiming at
    // and then measures the distance between the player's eyes and this target point
    //
    // It will not cancel every invalid attack but should cancel 3.05+ or so in real-time
    // Let the post look check measure the distance, as it will always return equal or higher
    // than this method.  If this method flags, the other method WILL flag.
    //
    // Meaning that the other check should be the only one that flags.
    private boolean isKnownInvalid(PacketEntity reachEntity, boolean hasAttackRange, float itemMaxReach, float itemHitboxMargin) {
        // If the entity doesn't exist, or if it is exempt, or if it is dead
        if ((blacklisted.contains(reachEntity.type) || !reachEntity.isLivingEntity) && reachEntity.type != EntityTypes.END_CRYSTAL)
            return false; // exempt

        if (player.gamemode == GameMode.CREATIVE || player.gamemode == GameMode.SPECTATOR)
            return false;
        if (player.inVehicle()) return false;

        // Filter out what we assume to be cheats
        if (cancelBuffer != 0) {
            BacktrackResult result = checkReach(reachEntity, player.x, player.y, player.z, hasAttackRange, itemMaxReach, itemHitboxMargin, true);
            return result.isFlag();
        } else {
            SimpleCollisionBox targetBox = getTargetBox(reachEntity);

            double maxReach = applyReachModifiers(targetBox, hasAttackRange, itemMaxReach, itemHitboxMargin, !player.packetStateData.didLastMovementIncludePosition);
            return ReachUtils.getMinReachToBox(player, targetBox) > maxReach;
        }
    }

    private void tickBetterReachCheckWithAngle() {
        for (Int2ObjectMap.Entry<InteractionData> attack : playerAttackQueue.int2ObjectEntrySet()) {
            PacketEntity reachEntity = player.compensatedEntities.entityMap.get(attack.getIntKey());
            if (reachEntity == null) continue;

            InteractionData interactionData = attack.getValue();
            BacktrackResult result = checkReach(reachEntity, interactionData.x, interactionData.y, interactionData.z, interactionData.hasAttackRange, interactionData.maxReach, interactionData.hitboxMargin, false);

            switch (result.type()) {
                case HITBOX -> {
                    String added = "type=" + reachEntity.type.getName().getKey();
                    if (reachEntity instanceof PacketEntitySizeable sizeable) {
                        added += ", size=" + sizeable.size;
                    }
                    player.checkManager.getCheck(Hitboxes.class).flagAndAlert(result.verbose() + added);
                }
                case REACH, BACKTRACK -> {
                    if (convergenceController.recordAndCheck(attack.getIntKey(), result)) {
                        String added = ", type=" + reachEntity.type.getName().getKey();
                        if (reachEntity instanceof PacketEntitySizeable sizeable) {
                            added += ", size=" + sizeable.size;
                        }
                        flagAndAlert(result.verbose() + added);
                    }
                }
            }
        }

        playerAttackQueue.clear();
    }

    @NotNull
    private BacktrackResult checkReach(PacketEntity reachEntity, double x, double y, double z, boolean hasAttackRange, float itemMaxReach, float itemHitboxMargin, boolean isPrediction) {
        SimpleCollisionBox targetBox = getTargetBox(reachEntity);

        // Capture edge distance BEFORE expand
        double edgeDistance = ReachUtils.getMinReachToBox(player, targetBox);

        // Method 1: Ray Origin Short-Circuit — eye inside unexpanded box → auto-pass
        for (double eye : player.getPossibleEyeHeights()) {
            if (ReachUtils.isVecInside(targetBox, new Vector3d(x, y + eye, z))) {
                return BacktrackResult.NONE;
            }
        }

        // Use getReachModifiers helper to avoid code duplication
        boolean giveMovementThreshold = !player.packetStateData.didLastLastMovementIncludePosition;
        ReachModifiers modifiers = getReachModifiers(hasAttackRange, itemMaxReach, itemHitboxMargin, giveMovementThreshold);
        double hitboxMargin = modifiers.hitboxMargin();
        double maxReach = modifiers.maxReach();

        // Method 2: Dynamic Margin — nới thêm khi áp sát (< 1.5 blocks)
        if (edgeDistance < 1.5) {
            hitboxMargin += 0.1 * (1.5 - edgeDistance) / 1.5;
        }

        targetBox.expand(hitboxMargin);

        double minDistance = Double.MAX_VALUE;
        double bestGazeDeviation = Double.MAX_VALUE;
        double bestDynamicThreshold = 0;
        Vector3d bestExactHitPoint = null;
        int actualTickDelta = 0;

        // https://bugs.mojang.com/browse/MC-67665
        List<Vector3dm> possibleLookDirs = new ArrayList<>(Collections.singletonList(ReachUtils.getLook(player, player.yaw, player.pitch)));

        if (!isPrediction) {
            if (player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_8)) {
                possibleLookDirs.add(ReachUtils.getLook(player, player.lastYaw, player.pitch));
                if (player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_9)) {
                    possibleLookDirs.add(ReachUtils.getLook(player, player.lastYaw, player.lastPitch));
                }
            }
        }

        final double distance = maxReach + 3;
        final Vector3d boxCenter = targetBox.getCenter();
        final double[] possibleEyeHeights = player.getPossibleEyeHeights();
        final double baseThreshold = player.isSprinting ? 12 : 8;
        final double absPitch = Math.abs(player.pitch);

        for (Vector3dm lookVec : possibleLookDirs) {
            Vector3dm scaledLook = lookVec.clone().multiply(distance);

            for (double eye : possibleEyeHeights) {
                final Vector3d eyePos = new Vector3d(x, y + eye, z);
                Vector3d endReachPos = eyePos.add(scaledLook.getX(), scaledLook.getY(), scaledLook.getZ());
                Vector3d intercept = null;
                boolean skipCurrentIntercept = false;

                // Method 3: Steep Pitch Compensation — nới Y khi pitch cực đoan
                SimpleCollisionBox workingBox = targetBox;
                if (absPitch > 60 && edgeDistance < 1.5) {
                    workingBox = targetBox.copy();
                    double pitchBonus = (absPitch - 60) / 30.0 * 0.1;
                    workingBox.minY -= pitchBonus;
                    workingBox.maxY += pitchBonus;
                }

                if (ReachUtils.isVecInside(workingBox, eyePos)) {
                    double gazeDev = GazeAngleUtil.calculateDeviation(
                            eyePos,
                            new Vector3d(lookVec.getX(), lookVec.getY(), lookVec.getZ()),
                            boxCenter
                    );
                    bestGazeDeviation = gazeDev;
                    bestDynamicThreshold = baseThreshold;
                    if (gazeDev <= baseThreshold) {
                        minDistance = 0;
                        bestExactHitPoint = eyePos;
                        break;
                    }
                    skipCurrentIntercept = true;
                }

                // Try current expanded box first
                if (!skipCurrentIntercept) {
                    intercept = ReachUtils.calculateIntercept(workingBox, eyePos, endReachPos).first();
                }
                if (intercept != null) {
                    double dist = eyePos.distance(intercept);
                    if (dist < minDistance) {
                        minDistance = dist;
                        actualTickDelta = 0;
                        bestExactHitPoint = intercept;
                        bestGazeDeviation = GazeAngleUtil.calculateDeviation(
                                eyePos,
                                new Vector3d(lookVec.getX(), lookVec.getY(), lookVec.getZ()),
                                boxCenter
                        );
                        bestDynamicThreshold = baseThreshold + Math.toDegrees(0.3 / Math.max(dist, 0.1));
                    }
                }

                // Walk back through position history (non-prediction only)
                if (!isPrediction && intercept == null) {
                    // Giant AABB filter: skip 30-tick loop if ray misses union of all past boxes
                    SimpleCollisionBox unionBox = reachEntity.positionHistory.getUnionBox();
                    if (unionBox != null) {
                        SimpleCollisionBox expandedUnion = unionBox.copy().expand(hitboxMargin);
                        if (ReachUtils.calculateIntercept(expandedUnion, eyePos, endReachPos).first() == null) {
                            continue;
                        }
                    }
                    int pingMs = player.getTransactionPing();
                    if (pingMs <= 0) {
                        pingMs = player.getKeepAlivePing();
                    }
                    int maxBacktrackTicks = 30;
                    if (pingMs > 0) {
                        double msPerTick = Math.max(50.0, GrimAPI.INSTANCE.getTickManager().serverMsPt);
                        maxBacktrackTicks = (int) Math.ceil(pingMs / msPerTick) + 3; // +3 tick buffer
                        maxBacktrackTicks = Math.min(30, Math.max(3, maxBacktrackTicks));
                    } else {
                        maxBacktrackTicks = 8; // fallback
                    }
                    int limit = Math.min(reachEntity.positionHistory.size(), maxBacktrackTicks);
                    for (int ago = 0; ago < limit; ago++) {
                        EntityPositionHistory.Entry entry = reachEntity.positionHistory.get(ago);
                        if (entry == null) continue;
                        SimpleCollisionBox pastBox = entry.hitbox().copy().expand(hitboxMargin);

                        // Exit point guard for past box
                        if (ReachUtils.isVecInside(pastBox, eyePos)) {
                            double pastGazeDev = GazeAngleUtil.calculateDeviation(
                                    eyePos,
                                    new Vector3d(lookVec.getX(), lookVec.getY(), lookVec.getZ()),
                                    pastBox.getCenter()
                            );
                            if (pastGazeDev <= baseThreshold) {
                                minDistance = 0;
                                actualTickDelta = ago;
                                bestExactHitPoint = eyePos;
                                break;
                            }
                            continue;
                        }

                        // Normal intercept — find closest past box (no break)
                        Vector3d pastIntercept = ReachUtils.calculateIntercept(pastBox, eyePos, endReachPos).first();
                        if (pastIntercept != null) {
                            double dist = eyePos.distance(pastIntercept);
                            if (dist < minDistance) {
                                minDistance = dist;
                                actualTickDelta = ago;
                                bestExactHitPoint = pastIntercept;
                                bestGazeDeviation = GazeAngleUtil.calculateDeviation(
                                        eyePos,
                                        new Vector3d(lookVec.getX(), lookVec.getY(), lookVec.getZ()),
                                        pastBox.getCenter()
                                );
                                bestDynamicThreshold = baseThreshold + Math.toDegrees(0.3 / Math.max(dist, 0.1));
                            }
                        }
                    }
                }
            }
        }

        // Compute tick delta with MS-PT compensation
        long serverMsPt = GrimAPI.INSTANCE.getTickManager().serverMsPt;
        int compensatedTickDelta = (int) (actualTickDelta * (50.0 / Math.max(serverMsPt, 1)));
        BacktrackResult.TickDelta tickDelta = new BacktrackResult.TickDelta(actualTickDelta, compensatedTickDelta, serverMsPt);

        if ((!blacklisted.contains(reachEntity.type) && reachEntity.isLivingEntity) || reachEntity.type == EntityTypes.END_CRYSTAL) {
            if (minDistance == Double.MAX_VALUE) {
                cancelBuffer = 1;
                return new BacktrackResult(BacktrackResult.Type.HITBOX, "", tickDelta, new BacktrackResult.GazeData(bestGazeDeviation, bestDynamicThreshold), edgeDistance, bestExactHitPoint);
            } else if (minDistance > maxReach) {
                cancelBuffer = 1;
                BacktrackResult.Type type = actualTickDelta > 0 ? BacktrackResult.Type.BACKTRACK : BacktrackResult.Type.REACH;
                String verbose = type == BacktrackResult.Type.BACKTRACK
                        ? String.format("%.5f blocks (backtrack %d ticks)", minDistance, actualTickDelta)
                        : String.format("%.5f blocks", minDistance);
                return new BacktrackResult(type, verbose, tickDelta, new BacktrackResult.GazeData(bestGazeDeviation, bestDynamicThreshold), edgeDistance, bestExactHitPoint);
            } else {
                cancelBuffer = Math.max(0, cancelBuffer - 0.25);
            }
        }

        return BacktrackResult.NONE;
    }

    private SimpleCollisionBox getTargetBox(PacketEntity reachEntity) {
        if (reachEntity.type == EntityTypes.END_CRYSTAL) { // Hardcode end crystal box
            return new SimpleCollisionBox(reachEntity.trackedServerPosition.getPos().subtract(1, 0, 1), reachEntity.trackedServerPosition.getPos().add(1, 2, 1));
        }
        return reachEntity.getPossibleCollisionBoxes();
    }

    private static final boolean ATTACK_RANGE_COMPONENT_EXISTS = PacketEvents.getAPI().getServerManager().getVersion().isNewerThanOrEquals(ServerVersion.V_1_21_11);
    private static final boolean USE_1_8_HITBOX_MARGIN = PacketEvents.getAPI().getServerManager().getVersion().isOlderThanOrEquals(ServerVersion.V_1_8_8);

    private ReachModifiers getReachModifiers(boolean hasAttackRange, float itemMaxReach, float itemHitboxMargin, boolean giveMovementThreshold) {
        double maxReach;
        double hitboxMargin = threshold;

        if (hasAttackRange) {
            maxReach = itemMaxReach;
            hitboxMargin += itemHitboxMargin;
        } else {
            maxReach = player.compensatedEntities.self.getAttributeValue(Attributes.ENTITY_INTERACTION_RANGE);
            // 1.7 and 1.8 players get a bit of extra hitbox (this is why you should use 1.8 on cross version servers)
            // Yes, this is vanilla and not uncertainty.  All reach checks have this or they are wrong.
            if (player.getClientVersion().isOlderThan(ClientVersion.V_1_9)) {
                hitboxMargin += 0.1f;
            }
        }

        // This is better than adding to the reach, as 0.03 can cause a player to miss their target
        // Adds some more than 0.03 uncertainty in some cases, but a good trade off for simplicity
        //
        // Just give the uncertainty on 1.9+ clients as we have no way of knowing whether they had 0.03 movement
        // However, on 1.21.2+ we do know if they had 0.03 movement
        if (giveMovementThreshold || player.canSkipTicks()) {
            hitboxMargin += player.getMovementThreshold();
        }

        return new ReachModifiers(maxReach, hitboxMargin);
    }

    private double applyReachModifiers(SimpleCollisionBox targetBox, boolean hasAttackRange, float itemMaxReach, float itemHitboxMargin, boolean giveMovementThreshold) {
        ReachModifiers modifiers = getReachModifiers(hasAttackRange, itemMaxReach, itemHitboxMargin, giveMovementThreshold);
        targetBox.expand(modifiers.hitboxMargin());
        return modifiers.maxReach();
    }

    public void resetConvergence(int entityId) {
        convergenceController.reset(entityId);
    }

    @Override
    public void onReload(ConfigManager config) {
        this.cancelImpossibleHits = config.getBooleanElse("Reach.block-impossible-hits", true);
        this.threshold = config.getDoubleElse("Reach.threshold", 0.0005);
    }

    private record InteractionData(double x, double y, double z, boolean hasAttackRange,
                                   float maxReach, float hitboxMargin) {}

    private record ReachModifiers(double maxReach, double hitboxMargin) {}
}
