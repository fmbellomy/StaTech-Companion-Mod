package dev.thestaticvoid.stcm.item;

import com.endertech.minecraft.forge.math.Percentage;
import com.endertech.minecraft.mods.adlods.ore.AbstractOre;
import com.endertech.minecraft.mods.adlods.target.TargetGenResult;
import com.endertech.minecraft.mods.adlods.world.WorldTargets;
import dev.thestaticvoid.stcm.STCMConfig;
import dev.thestaticvoid.stcm.client.compat.journeymap.STCMJMPlugin;
import dev.thestaticvoid.stcm.data.MaterialLoader;
import journeymap.api.v2.common.waypoint.Waypoint;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;

public class ProspectorPick extends Item {
    private static final Map<String, Integer> MaterialMap = new HashMap<>();
    private final int PICK_COOLDOWN = 100; // 5 seconds
    private final List<TargetGenResult> depositsFound = new ArrayList<>();
    private long lastPickUseTime = 0;
    private Level level;

    public ProspectorPick(Properties properties) {
        super(properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltipComponents, TooltipFlag tooltipFlag) {
        if (!Screen.hasShiftDown()) {
            tooltipComponents.add(Component.translatable("tooltip.stcm.prospector_tooltip"));
        } else {
            tooltipComponents.add(Component.translatable("tooltip.stcm.prospector_tooltip_shift",
                    STCMConfig.CONFIG.prospectorHorizontalRange.get()
            ));
        }
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        level = context.getLevel();
        Player player = context.getPlayer();
        BlockPos pos = context.getClickedPos();
        BlockState state = level.getBlockState(pos);
        Block targetedBlock = state.getBlock();
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(targetedBlock);

        if (id.getNamespace().equals("kubejs") && id.getPath().contains("ore_sample")) {
            if (level.isClientSide()) {
                return doSampleInteraction(level, player, pos, state, id);
            }
        } else {
            if (!level.isClientSide() && !player.isCrouching()) {
                if (!player.isCrouching()) {
                    return doDepositScan(level, player, context, pos);
                }
            }
        }
        return super.useOn(context);
    }

    private InteractionResult doSampleInteraction(Level level, Player player, BlockPos blockPos, BlockState state, ResourceLocation id) {
        boolean isPlayerPlaced = false;
        for (Property p : state.getProperties()) {
            if (p.getName().equals("player_placed")) {
                isPlayerPlaced = state.getValue((BooleanProperty) p);
            }
        }

        if (isPlayerPlaced) {
            player.displayClientMessage(Component.translatable("chat.stcm.waypoint_failed_player_placed"), true);
            return InteractionResult.FAIL;
        }

        String material = id.getPath().substring(0, id.getPath().indexOf("_ore"));
        ChatFormatting waypointColor = ChatFormatting.getByName(MaterialLoader.get(material));
        if (waypointColor == null) {
            waypointColor = ChatFormatting.WHITE;
        }

        String formattedMatName = capitalizeFirstLetter(material);
        String formattedPosition = String.format("(%s, %s, %s)", blockPos.getX(), blockPos.getY(), blockPos.getZ());

        // Check to make sure there are no other waypoints of the same material type in the vicinity
        if (STCMJMPlugin.proximityCheck(blockPos, level.dimension(), formattedMatName)) {
            Waypoint waypoint = STCMJMPlugin.createOreSampleWaypoint(
                    blockPos,
                    level,
                    waypointColor.getColor(),
                    formattedMatName,
                    player.isCrouching());

            if (waypoint != null) {
                player.displayClientMessage(Component.translatable("chat.stcm.waypoint_success", formattedMatName, formattedPosition), true);
                level.playSound(player, blockPos, SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.BLOCKS, 1.0F, 1.0F);
                return InteractionResult.SUCCESS;
            } else {
                player.displayClientMessage(Component.translatable("chat.stcm.waypoint_failed"), true);
                return InteractionResult.FAIL;
            }
        } else {
            // Otherwise need to check if it's an inworld waypoint toggle
            if (player.isCrouching()) {
                if (STCMJMPlugin.isShownInWorld(blockPos, level.dimension(), formattedMatName)) {
                    STCMJMPlugin.showInWorld(blockPos, level.dimension(), formattedMatName, false);
                    player.displayClientMessage(Component.translatable("chat.stcm.waypoint_updated_hide_in_world"), true);
                } else {
                    STCMJMPlugin.showInWorld(blockPos, level.dimension(), formattedMatName, true);
                    player.displayClientMessage(Component.translatable("chat.stcm.waypoint_updated_show_in_world"), true);
                }
                level.playSound(player, blockPos, SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.BLOCKS, 1.0F, 1.0F);
                return InteractionResult.SUCCESS;
            } else {
                player.displayClientMessage(Component.translatable("chat.stcm.waypoint_proximity_fail", formattedMatName), true);
                return InteractionResult.FAIL;
            }
        }
    }

    private InteractionResult doDepositScan(Level level, Player player, UseOnContext context, BlockPos blockPos) {
        if (level.getGameTime() > lastPickUseTime + PICK_COOLDOWN) {
            lastPickUseTime = level.getGameTime();
            checkBlocksInArea(blockPos, level);

            if (!this.depositsFound.isEmpty()) {
                player.sendSystemMessage(Component.translatable("chat.stcm.prospector_success"));

                Map<String, BlockPos> oreNameMap = new HashMap<>();
                this.depositsFound.forEach((result) -> {
                    oreNameMap.put(result.name, result.pos);
                });

                SortedSet<String> sortedKeys = new TreeSet<>(oreNameMap.keySet());
                for (String key : sortedKeys) {
                    int distance = (int) Math.sqrt(oreNameMap.get(key).distSqr(blockPos));
                    player.sendSystemMessage(Component.translatable("chat.stcm.prospector_deposit_info", key, distance));
                }
            } else {
                player.sendSystemMessage(Component.translatable("chat.stcm.prospector_no_deposits"));
            }

            level.playSound(null, blockPos, SoundEvents.SHOVEL_FLATTEN, SoundSource.BLOCKS, 1.0F, 1.0F);
            context.getItemInHand().hurtAndBreak(1, player, LivingEntity.getSlotForHand(context.getHand()));
            return InteractionResult.SUCCESS;
        } else {
            player.displayClientMessage(Component.translatable("chat.stcm.prospector_cooldown", ((lastPickUseTime + PICK_COOLDOWN - level.getGameTime()) / 20.0)), true);
            return InteractionResult.FAIL;
        }
    }

    private void checkBlocksInArea(BlockPos startPosition, Level level) {
        this.depositsFound.clear();
        depositsFound.addAll(
                WorldTargets.get((ServerLevel) level).generated().values().stream()
                        .filter(deposit ->
                                AbstractOre.withinRadius(
                                        new ChunkPos(deposit.pos),
                                        new ChunkPos(startPosition),
                                        STCMConfig.CONFIG.prospectorHorizontalRange.get())
                        ).filter(deposit ->
                                deposit.completeness()
                                        .isGreaterOrEqualTo(Percentage.from(STCMConfig.CONFIG.prospectorMinDepositCompleteness.get())))
                        .toList());
    }

    private String capitalizeFirstLetter(String word) {
        String[] separated = word.split(" ");
        StringBuilder formatted = new StringBuilder();
        for (int i = 0; i < separated.length; i++) {
            if (separated[i].length() > 1) {
                formatted.append(separated[i].toUpperCase().charAt(0)).append(separated[i].substring(1)).append(" ");
            }
        }

        return formatted.toString().trim();
    }
}
