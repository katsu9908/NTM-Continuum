package com.hbm.blocks.network;

import com.hbm.api.conveyor.IConveyorBelt;
import com.hbm.api.conveyor.IConveyorItem;
import com.hbm.api.conveyor.IConveyorPackage;
import com.hbm.api.conveyor.IEnterableBlock;
import com.hbm.entity.item.EntityMovingItem;
import com.hbm.interfaces.ILookOverlay;
import com.hbm.tileentity.network.TileEntityCraneSplitterWide;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Wide conveyor splitter: any number of single blocks placed side by side (same block, same facing) behave as one
 * splitter. Items enter through any one cell of the row and are dealt out evenly, item by item and round robin,
 * onto one belt lane per cell. A row of two is the ordinary two lane splitter with a 1:1 ratio; a row of eight splits
 * one input line onto eight output lines.
 *
 * <p>{@link #FACING} follows {@link CraneSplitter}: it is the side items arrive from, and travel runs against it. The
 * deal position is kept in memory per row, so it restarts on a server restart and when the row is reshaped; this
 * only changes which lane gets the next item, never how many items are lost (none are).
 *
 * <p>The {@link Express} variant moves items three times as fast, like {@link BlockConveyorExpress}.
 */
public class CraneSplitterWide extends Block implements IConveyorBelt, IEnterableBlock, ILookOverlay, EntityBlock {

    public static final EnumProperty<Direction> FACING = BlockStateProperties.HORIZONTAL_FACING;

    /** Hard cap on how far a row is scanned in each direction, so a long wall of splitters cannot stall the server. */
    public static final int MAX_WIDTH = 32;

    /** Items handed to a lane per drain tick. */
    private static final int OUTPUT_AMOUNT = 4;
    /** Ticks between drains at belt speed; the express tier divides it by its speed. */
    private static final int BASE_DELAY = 5;

    /** Next lane to receive an item, keyed by the row's first cell, per level. */
    private static final Map<Level, Map<Long, Integer>> DEAL_STATE = Collections.synchronizedMap(new WeakHashMap<>());

    private final double speedMultiplier;

    public CraneSplitterWide(Properties properties) {
        this(properties, 1D);
    }

    protected CraneSplitterWide(Properties properties, double speedMultiplier) {
        super(properties);
        this.speedMultiplier = speedMultiplier;
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    /** Three times the belt speed, the express tier of the wide splitter. */
    public static class Express extends CraneSplitterWide {
        public Express(Properties properties) {
            super(properties, 3D);
        }
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    /**
     * Faces away from the player, so items run in the direction they look. Placing next to an existing cell of the
     * same block copies its facing, which is what makes a row grow without any extra clicks.
     */
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction facing = context.getHorizontalDirection().getOpposite();
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();

        for (Direction side : Direction.Plane.HORIZONTAL) {
            BlockState neighbour = level.getBlockState(pos.relative(side));
            if (neighbour.getBlock() == this) {
                facing = neighbour.getValue(FACING);
                break;
            }
        }
        return defaultBlockState().setValue(FACING, facing);
    }

    private boolean sameRow(BlockState state, Direction facing) {
        return state.getBlock() == this && state.getValue(FACING) == facing;
    }

    /** All cells of the row containing {@code pos}, ordered from the clockwise end to the counter-clockwise end. */
    private List<BlockPos> rowOf(Level level, BlockPos pos, Direction facing) {
        Direction cw = facing.getClockWise();
        Direction ccw = facing.getCounterClockWise();

        BlockPos start = pos;
        for (int i = 0; i < MAX_WIDTH && sameRow(level.getBlockState(start.relative(cw)), facing); i++) {
            start = start.relative(cw);
        }

        List<BlockPos> row = new ArrayList<>();
        BlockPos cursor = start;
        while (row.size() < MAX_WIDTH && sameRow(level.getBlockState(cursor), facing)) {
            row.add(cursor);
            cursor = cursor.relative(ccw);
        }
        return row;
    }

    private @Nullable Direction facingAt(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.getBlock() == this ? state.getValue(FACING) : null;
    }

    public Direction getTravelDirection(Level level, BlockPos pos, @Nullable Vec3 itemPos) {
        Direction facing = facingAt(level, pos);
        return facing == null ? Direction.NORTH : facing;
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new TileEntityCraneSplitterWide(pos, state);
    }

    private List<TileEntityCraneSplitterWide> cellsOf(Level level, List<BlockPos> row) {
        List<TileEntityCraneSplitterWide> cells = new ArrayList<>(row.size());
        for (BlockPos cell : row) {
            if (level.getBlockEntity(cell) instanceof TileEntityCraneSplitterWide be) cells.add(be);
        }
        return cells;
    }

    /** The row accepts a stack only if the shared buffer can hold all of it, so nothing is ever lost or split mid-way. */
    @Override
    public boolean canItemEnter(Level level, BlockPos pos, @Nullable Direction dir, IConveyorItem entity) {
        Direction facing = facingAt(level, pos);
        if (facing == null || facing != dir) return false;
        ItemStack stack = entity.getItemStack();
        int room = 0;
        for (TileEntityCraneSplitterWide cell : cellsOf(level, rowOf(level, pos, facing))) {
            room += cell.room(stack);
        }
        return room >= stack.getCount();
    }

    @Override
    public boolean canPackageEnter(Level level, BlockPos pos, @Nullable Direction dir, IConveyorPackage entity) {
        return false;
    }

    @Override
    public void onPackageEnter(Level level, BlockPos pos, @Nullable Direction dir, IConveyorPackage entity) {}

    @Override
    public void onItemEnter(Level level, BlockPos pos, @Nullable Direction dir, IConveyorItem entity) {
        Direction facing = facingAt(level, pos);
        if (facing == null) return;

        List<BlockPos> row = rowOf(level, pos, facing);
        List<TileEntityCraneSplitterWide> cells = cellsOf(level, row);
        ItemStack stack = entity.getItemStack();
        int width = cells.size();
        if (width == 0) return;

        Map<Long, Integer> states = DEAL_STATE.computeIfAbsent(level, l -> Collections.synchronizedMap(new HashMap<>()));
        long key = row.get(0).asLong();
        int next = Math.floorMod(states.getOrDefault(key, 0), width);

        // Water filling: every item goes to the emptiest cell that can take it, ties broken round robin.
        int left = stack.getCount();
        while (left > 0) {
            int best = -1;
            int bestCount = Integer.MAX_VALUE;
            for (int k = 0; k < width; k++) {
                int idx = (next + k) % width;
                TileEntityCraneSplitterWide cell = cells.get(idx);
                if (cell.room(stack) <= 0) continue;
                int held = cell.getItem(0).getCount();
                if (held < bestCount) {
                    best = idx;
                    bestCount = held;
                }
            }
            if (best < 0) break;
            cells.get(best).insert(stack, 1);
            next = (best + 1) % width;
            left--;
        }
        states.put(key, next);

        if (left > 0) Block.popResource(level, pos, stack.copyWithCount(left));
    }

    /** Drains the cell onto its lane and levels it out with its neighbours; reschedules while anything is left. */
    @Override
    public void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (!(level.getBlockEntity(pos) instanceof TileEntityCraneSplitterWide be)) return;
        Direction facing = state.getValue(FACING);

        if (!be.isEmpty() && level.getEntitiesOfClass(EntityMovingItem.class, new AABB(pos)).isEmpty()) {
            spawnMovingItem(level, pos, be.drain(OUTPUT_AMOUNT));
        }

        for (Direction side : new Direction[]{facing.getClockWise(), facing.getCounterClockWise()}) {
            BlockPos otherPos = pos.relative(side);
            if (!sameRow(level.getBlockState(otherPos), facing)) continue;
            if (!(level.getBlockEntity(otherPos) instanceof TileEntityCraneSplitterWide other)) continue;

            ItemStack held = be.getItem(0);
            if (held.isEmpty()) break;
            int diff = held.getCount() - other.getItem(0).getCount();
            if (diff < 2) continue;
            int moved = other.insert(held, Math.min(diff / 2, other.room(held)));
            if (moved > 0) be.drain(moved);
        }

        if (!be.isEmpty()) level.scheduleTick(pos, this, Math.max(1, (int) Math.round(BASE_DELAY / speedMultiplier)));
    }

    private void spawnMovingItem(Level level, BlockPos pos, ItemStack stack) {
        if (stack.isEmpty()) return;

        Vec3 snap = getClosestSnappingPosition(level, pos, Vec3.atCenterOf(pos));
        EntityMovingItem moving = new EntityMovingItem(level);
        moving.snapTo(snap.x, snap.y, snap.z, 0F, 0F);
        moving.setItemStack(stack);
        level.addFreshEntity(moving);
    }

    @Override
    public boolean canItemStay(Level level, BlockPos pos, Vec3 itemPos) {
        return true;
    }

    @Override
    public Vec3 getTravelLocation(Level level, BlockPos pos, Vec3 itemPos, double speed) {
        double step = speed * speedMultiplier;
        Direction dir = getTravelDirection(level, pos, itemPos);
        Vec3 clamped = clamp(pos, itemPos);
        Vec3 snap = snap(pos, clamped, dir);

        Vec3 dest = snap.subtract(dir.getStepX() * step, dir.getStepY() * step, dir.getStepZ() * step);
        Vec3 motion = dest.subtract(clamped);
        double len = motion.length();
        if (len < 1.0E-6D) return clamped;
        return clamped.add(motion.x / len * step, motion.y / len * step, motion.z / len * step);
    }

    @Override
    public Vec3 getClosestSnappingPosition(Level level, BlockPos pos, Vec3 itemPos) {
        return snap(pos, clamp(pos, itemPos), getTravelDirection(level, pos, itemPos));
    }

    private static Vec3 snap(BlockPos pos, Vec3 clamped, Direction dir) {
        double posX = dir.getStepX() != 0 ? clamped.x : pos.getX() + 0.5;
        double posZ = dir.getStepZ() != 0 ? clamped.z : pos.getZ() + 0.5;
        return new Vec3(posX, pos.getY() + 0.25, posZ);
    }

    private static Vec3 clamp(BlockPos pos, Vec3 itemPos) {
        return new Vec3(Mth.clamp(itemPos.x, pos.getX(), pos.getX() + 1D), itemPos.y,
                Mth.clamp(itemPos.z, pos.getZ(), pos.getZ() + 1D));
    }

    @Override
    public ILookOverlay.Data getLookOverlay(Level level, BlockPos pos, Player player) {
        Direction facing = facingAt(level, pos);
        if (facing == null) return null;

        int width = rowOf(level, pos, facing).size();
        List<String> lines = new ArrayList<>();
        lines.add("Lanes: " + width + (width == 1 ? " (add blocks beside this one)" : ""));
        int stored = 0;
        List<TileEntityCraneSplitterWide> cells = cellsOf(level, rowOf(level, pos, facing));
        for (TileEntityCraneSplitterWide cell : cells) stored += cell.getItem(0).getCount();
        lines.add("Buffer: " + stored + " / " + cells.size() * TileEntityCraneSplitterWide.LIMIT);
        if (speedMultiplier > 1D) lines.add("Belt speed: x" + (int) speedMultiplier);
        return new ILookOverlay.Data(getName().getString(), 0xffff00, 0x404000, lines);
    }
}
