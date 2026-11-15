package com.hbm.tileentity.network;

import com.hbm.tileentity.ModConveyorBlockEntities;
import com.hbm.tileentity.TileEntityInventoryBase;
import dev.golem.core.world.ItemStacks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * Buffer of one cell of a wide conveyor splitter. One slot, filled by belts (through the block) or by hoppers, chutes
 * and pipes (through the {@link WorldlyContainer} faces); nothing can be pulled back out. The block drains it onto its
 * lane with scheduled ticks, so this entity itself never ticks.
 *
 * <p>The cells of a row act as one shared buffer: the capacity grows by {@link #LIMIT} per cell, a stack that enters is
 * dealt evenly across the cells, and neighbouring cells level out between themselves.
 */
public class TileEntityCraneSplitterWide extends TileEntityInventoryBase implements WorldlyContainer {

    public static final int LIMIT = 64;
    private static final int[] SLOTS = {0};

    public TileEntityCraneSplitterWide(BlockPos pos, BlockState state) {
        super(ModConveyorBlockEntities.CRANE_SPLITTER_WIDE.get(), pos, state, 1);
    }

    @Override
    public int getMaxStackSize() {
        return LIMIT;
    }

    /** How many more of this item the cell takes: nothing if it holds something else. */
    public int room(ItemStack stack) {
        ItemStack held = getItem(0);
        int limit = Math.min(LIMIT, stack.getMaxStackSize());
        if (held.isEmpty()) return limit;
        if (!ItemStacks.sameItemAndData(held, stack)) return 0;
        return Math.max(0, limit - held.getCount());
    }

    /** Adds up to {@code stack.getCount()} items and returns how many went in. */
    public int insert(ItemStack stack, int amount) {
        int moved = Math.min(amount, room(stack));
        if (moved <= 0) return 0;
        ItemStack held = getItem(0);
        if (held.isEmpty()) {
            super.setItem(0, stack.copyWithCount(moved));
        } else {
            held.grow(moved);
            setChanged();
        }
        wake();
        return moved;
    }

    /** Takes up to {@code amount} items out of the buffer. */
    public ItemStack drain(int amount) {
        ItemStack held = getItem(0);
        if (held.isEmpty()) return ItemStack.EMPTY;
        ItemStack out = held.split(Math.min(amount, held.getCount()));
        if (held.isEmpty()) super.setItem(0, ItemStack.EMPTY);
        setChanged();
        return out;
    }

    /** Asks the block to run its drain tick. */
    public void wake() {
        if (level != null && !level.isClientSide()) {
            level.scheduleTick(worldPosition, getBlockState().getBlock(), 1);
        }
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        super.setItem(slot, stack);
        wake();
    }

    // --- WorldlyContainer: insert from every side, never extract ---

    @Override
    public int[] getSlotsForFace(Direction side) {
        return SLOTS;
    }

    @Override
    public boolean canPlaceItemThroughFace(int slot, ItemStack stack, @Nullable Direction side) {
        return room(stack) > 0;
    }

    @Override
    public boolean canTakeItemThroughFace(int slot, ItemStack stack, Direction side) {
        return false;
    }

    @Override
    public boolean canPlaceItem(int slot, ItemStack stack) {
        return room(stack) > 0;
    }
}
