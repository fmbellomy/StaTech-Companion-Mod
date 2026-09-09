package dev.thestaticvoid.stcm.mixin;

import de.dafuqs.spectrum.blocks.DeeperDownPortalBlock;
import de.dafuqs.spectrum.blocks.decay.RuinBlock;
import de.dafuqs.spectrum.config.SpectrumConfig;
import de.dafuqs.spectrum.items.DecayPlacerItem;
import de.dafuqs.spectrum.progression.SpectrumAdvancementCriteria;
import de.dafuqs.spectrum.registries.SpectrumBlocks;
import de.dafuqs.spectrum.registries.SpectrumDimensionKeys;
import de.dafuqs.spectrum.registries.SpectrumItems;
import dev.thestaticvoid.stcm.STCMConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemNameBlockItem;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import javax.annotation.Nullable;

@Mixin(DecayPlacerItem.class)
public abstract class DecayPlacerItemMixin extends ItemNameBlockItem {
    public DecayPlacerItemMixin(Block block, Properties properties) {
        super(block, properties);
    }

    @Inject(method = "useOn", at = @At(value = "HEAD"), remap = false, cancellable = true)
    private void useOnMixin(
            UseOnContext context,
            CallbackInfoReturnable<InteractionResult> cir) {

        if (this.getBlock() instanceof RuinBlock && STCMConfig.CONFIG.preventRuinPlacement.get()) {
            Level level = context.getLevel();
            BlockPos targetPos = context.getClickedPos();
            BlockState targetBlock = level.getBlockState(targetPos);
            Player player = context.getPlayer();

            if (targetBlock.is(Blocks.BEDROCK)) {
                if (!level.isClientSide()) {
                    level.destroyBlock(targetPos, false);
                    if (player != null) {
                        player.getInventory().placeItemBackInInventory(SpectrumItems.BEDROCK_DUST.toStack());
                    }

                    // Place the portal if it should be placed after breaking
                    BlockState portal = staTech_Companion_Mod$createPortal(level, targetPos);
                    if (portal != null) {
                        if (player instanceof ServerPlayer serverPlayer) {
                            SpectrumAdvancementCriteria.DEEPER_DOWN_PORTAL_OPENING.trigger(serverPlayer);
                        }
                        level.setBlockAndUpdate(targetPos, portal);
                    }
                }
                cir.setReturnValue(InteractionResult.SUCCESS);
            } else {
                if (player != null && !level.isClientSide()) {
                    player.displayClientMessage(Component.translatable("chat.stcm.ruin_prevented"), true);
                }
                cir.setReturnValue(InteractionResult.FAIL);
            }
        }
    }

    /*
        This is pretty much 1:1 copy of Spectrum's RuinBlock "shouldCreatePortalFacingUp" method
     */
    @Unique
    @Nullable
    private BlockState staTech_Companion_Mod$createPortal(Level level, BlockPos pos) {
        if (SpectrumConfig.CONFIG.DimensionPortals.get() != SpectrumConfig.DimensionPortalSetting.NO_PORTALS) {
            if (level.dimension() == Level.NETHER) {
                if (pos.getY() == level.getMinBuildHeight() + level.dimensionType().logicalHeight() - 1) {
                    return (BlockState) ((DeeperDownPortalBlock) SpectrumBlocks.DEEPER_DOWN_PORTAL.get()).defaultBlockState().setValue(DeeperDownPortalBlock.FACING_UP, true);
                }

                if (pos.getY() == level.getMinBuildHeight()) {
                    return (BlockState) ((DeeperDownPortalBlock) SpectrumBlocks.DEEPER_DOWN_PORTAL.get()).defaultBlockState().setValue(DeeperDownPortalBlock.FACING_UP, false);
                }
            } else {
                if (level.dimension() == Level.OVERWORLD && pos.getY() == level.getMinBuildHeight()) {
                    return (BlockState) ((DeeperDownPortalBlock) SpectrumBlocks.DEEPER_DOWN_PORTAL.get()).defaultBlockState().setValue(DeeperDownPortalBlock.FACING_UP, false);
                }

                if (level.dimension() == SpectrumDimensionKeys.DIMENSION_KEY && pos.getY() == level.getMaxBuildHeight() - 1) {
                    return (BlockState) ((DeeperDownPortalBlock) SpectrumBlocks.DEEPER_DOWN_PORTAL.get()).defaultBlockState().setValue(DeeperDownPortalBlock.FACING_UP, true);
                }
            }

        }
        return null;
    }
}
