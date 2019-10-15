/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.process;

import baritone.Baritone;
import baritone.api.cache.IWaypoint;
import baritone.api.cache.Waypoint;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalComposite;
import baritone.api.process.IFarmProcess;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.RayTraceUtils;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.api.utils.input.Input;
import baritone.cache.WorldScanner;
import baritone.pathing.movement.MovementHelper;
import baritone.utils.BaritoneProcessHelper;
import net.minecraft.block.*;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.inventory.ClickType;
import net.minecraft.inventory.Slot;
import net.minecraft.item.EnumDyeColor;
import net.minecraft.item.Item;
import net.minecraft.item.ItemDye;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.NonNullList;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;


import java.util.*;
import java.util.function.Predicate;

public final class FarmProcess extends BaritoneProcessHelper implements IFarmProcess {

    private boolean active;

    private List<BlockPos> locations;
    private int tickCount;
    private boolean putInChest;

    private static final List<Item> FARMLAND_PLANTABLE = Arrays.asList(
            Items.BEETROOT_SEEDS,
            Items.MELON_SEEDS,
            Items.WHEAT_SEEDS,
            Items.PUMPKIN_SEEDS,
            Items.POTATO,
            Items.CARROT
    );

    private static final List<Item> PICKUP_DROPPED = Arrays.asList(
            Items.BEETROOT_SEEDS,
            Items.BEETROOT,
            Items.MELON_SEEDS,
            Items.MELON,
            Item.getItemFromBlock(Blocks.MELON_BLOCK),
            Items.WHEAT_SEEDS,
            Items.WHEAT,
            Items.PUMPKIN_SEEDS,
            Item.getItemFromBlock(Blocks.PUMPKIN),
            Items.POTATO,
            Items.CARROT,
            Items.NETHER_WART,
            Items.REEDS,
            Item.getItemFromBlock(Blocks.CACTUS)
    );

    public FarmProcess(Baritone baritone) {
        super(baritone);
    }

    @Override
    public boolean isActive() {
        return active;
    }

    @Override
    public void farm() {
        active = true;
        locations = null;
    }

    public void selectChest() {
        BetterBlockPos player = ctx.playerFeet();
        Optional<BlockPos> blockPos = ctx.getSelectedBlock();
        if (blockPos.isPresent()) {
            if (player.getDistance(blockPos.get().getX(), blockPos.get().getY(), blockPos.get().getZ()) < 6) {
                Block block = ctx.world().getBlockState(blockPos.get()).getBlock();
                if (block.equals(Blocks.CHEST) || block.equals(Blocks.ENDER_CHEST) || block.equals(Blocks.TRAPPED_CHEST)) {
                    baritone.getWorldProvider().getCurrentWorld().getWaypoints().addWaypoint(new Waypoint("", IWaypoint.Tag.CHEST, blockPos.get()));
                    baritone.getWorldProvider().getCurrentWorld().getWaypoints().addWaypoint(new Waypoint("", IWaypoint.Tag.USECHEST, player));
                } else {
                    logDirect("Block is not a Chest");
                }
            } else {
                logDirect("Block is not in Range");
            }
        } else {
            logDirect("Please look at a chest");
        }
    }

    private enum Harvest {
        WHEAT((BlockCrops) Blocks.WHEAT),
        CARROTS((BlockCrops) Blocks.CARROTS),
        POTATOES((BlockCrops) Blocks.POTATOES),
        BEETROOT((BlockCrops) Blocks.BEETROOTS),
        PUMPKIN(Blocks.PUMPKIN, state -> true),
        MELON(Blocks.MELON_BLOCK, state -> true),
        NETHERWART(Blocks.NETHER_WART, state -> state.getValue(BlockNetherWart.AGE) >= 3),
        SUGARCANE(Blocks.REEDS, null) {
            @Override
            public boolean readyToHarvest(World world, BlockPos pos, IBlockState state) {
                if (Baritone.settings().replantCrops.value) {
                    return world.getBlockState(pos.down()).getBlock() instanceof BlockReed;
                }
                return true;
            }
        },
        CACTUS(Blocks.CACTUS, null) {
            @Override
            public boolean readyToHarvest(World world, BlockPos pos, IBlockState state) {
                if (Baritone.settings().replantCrops.value) {
                    return world.getBlockState(pos.down()).getBlock() instanceof BlockCactus;
                }
                return true;
            }
        };
        public final Block block;
        public final Predicate<IBlockState> readyToHarvest;

        Harvest(BlockCrops blockCrops) {
            this(blockCrops, blockCrops::isMaxAge);
            // max age is 7 for wheat, carrots, and potatoes, but 3 for beetroot
        }

        Harvest(Block block, Predicate<IBlockState> readyToHarvest) {
            this.block = block;
            this.readyToHarvest = readyToHarvest;
        }

        public boolean readyToHarvest(World world, BlockPos pos, IBlockState state) {
            return readyToHarvest.test(state);
        }
    }

    private boolean readyForHarvest(World world, BlockPos pos, IBlockState state) {
        for (Harvest harvest : Harvest.values()) {
            if (harvest.block == state.getBlock()) {
                return harvest.readyToHarvest(world, pos, state);
            }
        }
        return false;
    }

    private boolean isPlantable(ItemStack stack) {
        return FARMLAND_PLANTABLE.contains(stack.getItem());
    }

    private boolean isBoneMeal(ItemStack stack) {
        return !stack.isEmpty() && stack.getItem() instanceof ItemDye && EnumDyeColor.byDyeDamage(stack.getMetadata()) == EnumDyeColor.WHITE;
    }

    private boolean isNetherWart(ItemStack stack) {
        return !stack.isEmpty() && stack.getItem().equals(Items.NETHER_WART);
    }

    private boolean putDropsInChest(NonNullList invy) {
        List<Slot> inv = ctx.player().openContainer.inventorySlots;
        NonNullList<ItemStack> invx = invy;
        for (int i = 0; i < invx.size(); i++) {
            if (!invx.isEmpty() && PICKUP_DROPPED.contains(invx.get(i).getItem())) {
                for (int j = 0; j < inv.size() - invx.size(); j++) {
                    if (inv.get(j).getStack().isEmpty()) {
                        ctx.playerController().windowClick(ctx.player().openContainer.windowId, i < 9 ? inv.size() - 9 + i : inv.size() - invx.size() + i - 9, 0, ClickType.QUICK_MOVE, ctx.player());
                        return true;
                    }
                }
            }
        }
        return false;
    }

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        if (Baritone.settings().checkInventory.value) {
            boolean inventoryFull = true;
            NonNullList<ItemStack> invy = ctx.player().inventory.mainInventory;
            HashMap<Item, ItemStack> smallestStack = new HashMap<>();
            for (ItemStack stack : invy) {
                if (stack.isEmpty()) {
                    inventoryFull = false;
                    break;
                }
                if (PICKUP_DROPPED.contains(stack.getItem())) {
                    if (smallestStack.containsKey(stack.getItem())) {
                        if (stack.getCount() < smallestStack.get(stack.getItem()).getCount()) {
                            smallestStack.put(stack.getItem(), stack);
                        }
                    } else {
                        smallestStack.put(stack.getItem(), stack);
                    }

                }

            }
            boolean allDropsHaveSpace = true;
            for (ItemStack stack : smallestStack.values()) {
                if (stack.getCount() == stack.getMaxStackSize())
                    allDropsHaveSpace = false;
            }
            if (allDropsHaveSpace) {
                inventoryFull = false;
            }

            if (putInChest) {
                if (!putDropsInChest(invy)) {
                    if (inventoryFull) {
                        ctx.player().closeScreen();
                        if (Baritone.settings().goHome.value) {
                            returnhome();
                        }
                        onLostControl();
                        logDirect("inventory and chest are full,cancel faring");
                    } else {
                        ctx.player().closeScreen();
                        putInChest = false;
                    }
                }
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);

            }

            if (inventoryFull) {
                if (baritone.settings().putDropsInChest.value) {
                    IWaypoint waypoint = baritone.getWorldProvider().getCurrentWorld().getWaypoints().getMostRecentByTag(IWaypoint.Tag.USECHEST);
                    IWaypoint chestLoc = baritone.getWorldProvider().getCurrentWorld().getWaypoints().getMostRecentByTag(IWaypoint.Tag.CHEST);
                    if (chestLoc != null && waypoint != null) {
                        BlockPos chest = chestLoc.getLocation();
                        if (waypoint.getLocation().getDistance(chest.getX(), chest.getY(), chest.getZ()) < 6) {
                            Goal goal = new GoalBlock(waypoint.getLocation());
                            if (goal.isInGoal(ctx.playerFeet()) && goal.isInGoal(baritone.getPathingBehavior().pathStart())) {
                                Optional<Rotation> rot = RotationUtils.reachable(ctx, chest);
                                if (rot.isPresent() && isSafeToCancel) {
                                    baritone.getLookBehavior().updateTarget(rot.get(), true);
                                    if (ctx.isLookingAt(chest)) {
                                        if (ctx.player().openContainer == ctx.player().inventoryContainer) {
                                            baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);
                                        } else {
                                            baritone.getInputOverrideHandler().clearAllKeys();
                                            putInChest = true;
                                        }
                                    }
                                    return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                                }
                            } else {
                                return new PathingCommand(goal, PathingCommandType.SET_GOAL_AND_PATH);
                            }
                        } else {
                            logDirect("Chest not properly set please use #setchest again");
                        }
                    } else {
                        logDirect("no chest set please use #setchest");
                    }


                } else {
                    logDirect("Cancel Mining Inventory Full");
                    if (Baritone.settings().goHome.value) {
                        returnhome();
                    }
                    onLostControl();
                    return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                }

                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);

            }
        }

        ArrayList<Block> scan = new ArrayList<>();
        for (Harvest harvest : Harvest.values()) {
            scan.add(harvest.block);
        }
        if (Baritone.settings().replantCrops.value) {
            scan.add(Blocks.FARMLAND);
            if (Baritone.settings().replantNetherWart.value) {
                scan.add(Blocks.SOUL_SAND);
            }
        }

        if (Baritone.settings().mineGoalUpdateInterval.value != 0 && tickCount++ % Baritone.settings().mineGoalUpdateInterval.value == 0) {
            Baritone.getExecutor().execute(() -> locations = WorldScanner.INSTANCE.scanChunkRadius(ctx, scan, 256, 10, 10));
        }
        if (locations == null) {


            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        List<BlockPos> toBreak = new ArrayList<>();
        List<BlockPos> openFarmland = new ArrayList<>();
        List<BlockPos> bonemealable = new ArrayList<>();
        List<BlockPos> openSoulsand = new ArrayList<>();
        for (BlockPos pos : locations) {
            IBlockState state = ctx.world().getBlockState(pos);
            boolean airAbove = ctx.world().getBlockState(pos.up()).getBlock() instanceof BlockAir;
            if (state.getBlock() == Blocks.FARMLAND) {
                if (airAbove) {
                    openFarmland.add(pos);
                }
                continue;
            }
            if (state.getBlock() == Blocks.SOUL_SAND) {
                if (airAbove) {
                    openSoulsand.add(pos);
                }
                continue;
            }
            if (readyForHarvest(ctx.world(), pos, state)) {
                toBreak.add(pos);
                continue;
            }
            if (state.getBlock() instanceof IGrowable) {
                IGrowable ig = (IGrowable) state.getBlock();
                if (ig.canGrow(ctx.world(), pos, state, true) && ig.canUseBonemeal(ctx.world(), ctx.world().rand, pos, state)) {
                    bonemealable.add(pos);
                }
            }
        }

        baritone.getInputOverrideHandler().clearAllKeys();
        for (BlockPos pos : toBreak) {
            Optional<Rotation> rot = RotationUtils.reachable(ctx, pos);
            if (rot.isPresent() && isSafeToCancel) {
                baritone.getLookBehavior().updateTarget(rot.get(), true);
                MovementHelper.switchToBestToolFor(ctx, ctx.world().getBlockState(pos));
                if (ctx.isLookingAt(pos)) {
                    baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, true);
                }

                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
        }
        ArrayList<BlockPos> both = new ArrayList<>(openFarmland);
        both.addAll(openSoulsand);
        for (BlockPos pos : both) {
            boolean soulsand = openSoulsand.contains(pos);
            Optional<Rotation> rot = RotationUtils.reachableOffset(ctx.player(), pos, new Vec3d(pos.getX() + 0.5, pos.getY() + 1, pos.getZ() + 0.5), ctx.playerController().getBlockReachDistance());
            if (rot.isPresent() && isSafeToCancel && baritone.getInventoryBehavior().throwaway(true, soulsand ? this::isNetherWart : this::isPlantable)) {
                RayTraceResult result = RayTraceUtils.rayTraceTowards(ctx.player(), rot.get(), ctx.playerController().getBlockReachDistance());
                if (result.typeOfHit == RayTraceResult.Type.BLOCK && result.sideHit == EnumFacing.UP) {
                    baritone.getLookBehavior().updateTarget(rot.get(), true);
                    if (ctx.isLookingAt(pos)) {
                        baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);
                    }

                    return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                }
            }
        }
        for (BlockPos pos : bonemealable) {
            Optional<Rotation> rot = RotationUtils.reachable(ctx, pos);
            if (rot.isPresent() && isSafeToCancel && baritone.getInventoryBehavior().throwaway(true, this::isBoneMeal)) {
                baritone.getLookBehavior().updateTarget(rot.get(), true);
                if (ctx.isLookingAt(pos)) {
                    baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);
                }

                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
        }

        if (calcFailed) {
            logDirect("Farm failed");
            onLostControl();
            if (Baritone.settings().goHome.value) {
                returnhome();
            }
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        List<Goal> goalz = new ArrayList<>();
        for (BlockPos pos : toBreak) {
            goalz.add(new BuilderProcess.GoalBreak(pos));
        }
        if (baritone.getInventoryBehavior().throwaway(false, this::isPlantable)) {
            for (BlockPos pos : openFarmland) {
                goalz.add(new GoalBlock(pos.up()));
            }
        }
        if (baritone.getInventoryBehavior().throwaway(false, this::isNetherWart)) {
            for (BlockPos pos : openSoulsand) {
                goalz.add(new GoalBlock(pos.up()));
            }
        }
        if (baritone.getInventoryBehavior().throwaway(false, this::isBoneMeal)) {
            for (BlockPos pos : bonemealable) {
                goalz.add(new GoalBlock(pos));
            }
        }
        for (Entity entity : ctx.world().loadedEntityList) {
            if (entity instanceof EntityItem && entity.onGround) {
                EntityItem ei = (EntityItem) entity;
                if (PICKUP_DROPPED.contains(ei.getItem().getItem())) {
                    // +0.1 because of farmland's 0.9375 dummy height lol
                    goalz.add(new GoalBlock(new BlockPos(entity.posX, entity.posY + 0.1, entity.posZ)));
                }
            }
        }
        return new PathingCommand(new GoalComposite(goalz.toArray(new Goal[0])), PathingCommandType.SET_GOAL_AND_PATH);
    }

    @Override
    public void onLostControl() {
        active = false;
    }

    @Override
    public String displayName0() {
        return "Farming";
    }
}
