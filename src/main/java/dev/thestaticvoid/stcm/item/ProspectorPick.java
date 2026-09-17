package dev.thestaticvoid.stcm.item;

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

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ProspectorPick extends Item {
    private final Map<BlockPos, Float> depositRichness = new HashMap<>();
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
        String formattedMatName = snakeCaseToDisplay(material);
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

    private float distXZ(BlockPos origin, BlockPos other) {
        final float distX = Math.abs(other.getX() - origin.getX());
        final float distZ = Math.abs(other.getZ() - origin.getZ());
        return (float) Math.sqrt(distX * distX + distZ * distZ);
    }

    private InteractionResult doDepositScan(Level level, Player player, UseOnContext context, BlockPos blockPos) {
            player.getCooldowns().addCooldown(this, STCMConfig.CONFIG.prospectorCooldown.get());
            List<TargetGenResult> depositsFound = getNearbyDeposits(blockPos, level);
            if (!depositsFound.isEmpty()) {
                player.sendSystemMessage(Component.translatable("chat.stcm.prospector_success"));

                Map<TargetGenResult, Integer> depositDistances = new HashMap<>();
                depositsFound.forEach((result) -> {

                    int distance = (int) distXZ(result.pos, blockPos);
                    depositDistances.put(result, distance);
                });

                int longestDepositNameLength = depositsFound.stream().map(deposit ->
                        deposit.name.length()).max(Comparator.comparingInt(a -> a)).orElse(0);
                ChatFormatting[] richnessColors = {ChatFormatting.OBFUSCATED, ChatFormatting.RED, ChatFormatting.GOLD, ChatFormatting.YELLOW, ChatFormatting.GREEN, ChatFormatting.AQUA, ChatFormatting.STRIKETHROUGH};
                depositDistances.entrySet().stream().sorted(Comparator.comparingInt(Map.Entry::getValue)).forEach(entry -> {
                    int richness = (int) Math.ceil(depositRichness.get(entry.getKey().pos) * 5);
                    String richnessDisplay =
                            String.format("%1$-9s", Component.translatable("chat.stcm.prospector_deposit_fullness_" + richness).getString()) +
                                    String.format(" (%1$5.1f%%)", depositRichness.get(entry.getKey().pos) * 100.0f);
                    String depositDisplay = String.format("%1$-" + longestDepositNameLength + "s", snakeCaseToDisplay(entry.getKey().name));
                    ResourceLocation monoFont = ResourceLocation.fromNamespaceAndPath("minecraft", "mono");
                    player.sendSystemMessage(Component.translatable("chat.stcm.prospector_deposit_info",
                            Component.literal(depositDisplay).withStyle(ChatFormatting.AQUA).withStyle(style ->
                                    style.withFont(monoFont)),
                            Component.literal(richnessDisplay).withStyle(richnessColors[richness]).withStyle(style -> style.withFont(monoFont)),
                            Component.literal(entry.getValue().toString()).withStyle(ChatFormatting.YELLOW)));
                });
            } else {
                player.sendSystemMessage(Component.translatable("chat.stcm.prospector_no_deposits"));
            }

            level.playSound(null, blockPos, SoundEvents.SHOVEL_FLATTEN, SoundSource.BLOCKS, 1.0F, 1.0F);
            context.getItemInHand().hurtAndBreak(1, player, LivingEntity.getSlotForHand(context.getHand()));
            depositRichness.clear();
            return InteractionResult.SUCCESS;

    }

    private List<TargetGenResult> getNearbyDeposits(BlockPos startPosition, Level level) {

        return WorldTargets.get((ServerLevel) level).generated().values().stream()
                .filter(deposit ->
                        AbstractOre.withinRadius(
                                new ChunkPos(deposit.pos),
                                new ChunkPos(startPosition),
                                STCMConfig.CONFIG.prospectorHorizontalRange.get())
                ).filter(deposit -> {
                    BlockState state = level.getBlockState(deposit.pos);
                    ResourceLocation center = BuiltInRegistries.BLOCK.getKey(state.getBlock());
                    Block oreBlock = null;
                    Block deepslateOreBlock = null;
                    if (center.getPath().contains("deepslate_")) {
                        String formattedPath = center.getPath().substring("deepslate_".length());
                        if (BuiltInRegistries.BLOCK.containsKey(ResourceLocation.fromNamespaceAndPath(center.getNamespace(), formattedPath))) {
                            oreBlock = BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath(center.getNamespace(), formattedPath));
                        }
                        deepslateOreBlock = state.getBlock();
                    } else {
                        String formattedPath = "deepslate_" + center.getPath();
                        if (BuiltInRegistries.BLOCK.containsKey(ResourceLocation.fromNamespaceAndPath(center.getNamespace(), formattedPath))) {
                            deepslateOreBlock = BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath(center.getNamespace(), formattedPath));
                        }
                        oreBlock = state.getBlock();
                    }


                    // the amount of ore blocks that must be found in order to not be considered "depleted"
                    int minCount = (int) (deposit.size * STCMConfig.CONFIG.prospectorMinDepositCompleteness.get());

                    // if the center block is air, just treat the deposit as if it is fully depleted.
                    int oresFound = 0;
                    if (!state.isAir()) {
                        Set<BlockPos> scannedPos = new HashSet<>();
                        oresFound = countNeighbors(oreBlock, deepslateOreBlock, level, deposit.pos, deposit.size, scannedPos);
                    }

                    depositRichness.put(deposit.pos, (float) oresFound / deposit.size);
                    return oresFound > minCount;
                })
                .toList();
    }

    private int countNeighbors(Block oreBlock, Block deepslateOreBlock, Level level, BlockPos pos, int maxCount, Set<BlockPos> scannedPos) {
        int count = 1;
        for (Direction direction : Direction.values()) {
            if (count >= maxCount) {
                break;
            }

            BlockPos adjPos = pos.relative(direction);
            boolean isDeepslate = deepslateOreBlock != null && level.getBlockState(adjPos).is(deepslateOreBlock);
            boolean isOre = oreBlock != null && level.getBlockState(adjPos).is(oreBlock);
            if (!scannedPos.contains(adjPos) && (isOre || isDeepslate)) {
                scannedPos.add(adjPos);
                count += countNeighbors(oreBlock, deepslateOreBlock, level, adjPos, maxCount - count, scannedPos);
            }
        }

        return count;
    }

    private String snakeCaseToDisplay(String word) {

        word = word.replace("_", " ");
        String[] separated = word.split(" ");
        StringBuilder formatted = new StringBuilder();
        for (String s : separated) {
            if (s.length() > 1) {
                formatted.append(s.toUpperCase().charAt(0)).append(s.substring(1)).append(" ");
            }
        }
        return formatted.toString().trim();
    }
}
