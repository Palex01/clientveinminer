package de.example.clientveinminer;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.event.client.player.ClientPlayerBlockBreakEvents;
import net.fabricmc.fabric.api.tag.convention.v2.ConventionalBlockTags;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class ClientVeinMinerMod implements ClientModInitializer {

    private static final int MAX_BLOCKS = 64;
    private static final double MAX_REACH = 5.0;
    private static final int TIMEOUT_TICKS = 200;

    private static KeyMapping toggleKey;
    private static boolean enabled = true;

    private static final ArrayDeque<BlockPos> queue = new ArrayDeque<>();
    private static final Set<BlockPos> queued = new HashSet<>();
    private static BlockPos current = null;
    private static int currentTicks = 0;
    private static String currentKey = null;

    @Override
    public void onInitializeClient() {
        toggleKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.clientveinminer.toggle",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_V,
                KeyMapping.Category.MISC
        ));

        ClientPlayerBlockBreakEvents.AFTER.register((level, player, pos, state) -> {
            if (pos.equals(current) || queued.contains(pos)) return;
            if (!enabled || !isOre(state)) return;

            reset();
            currentKey = oreKey(state);
            for (BlockPos p : collectVein(level, pos, currentKey)) {
                queue.add(p);
                queued.add(p);
            }
        });

        ClientTickEvents.END_CLIENT_TICK.register(ClientVeinMinerMod::tick);
    }

    private static void tick(Minecraft mc) {
        while (toggleKey.consumeClick()) {
            enabled = !enabled;
            if (!enabled) reset();
            if (mc.player != null) {
                mc.player.displayClientMessage(
                        Component.literal("Vein Miner: " + (enabled ? "AN" : "AUS")), true);
            }
        }

        if (current == null && queue.isEmpty()) return;

        LocalPlayer player = mc.player;
        MultiPlayerGameMode gm = mc.gameMode;
        ClientLevel level = mc.level;
        if (player == null || gm == null || level == null || !player.isAlive()) {
            reset();
            return;
        }
        if (mc.screen != null) return;

        mc.options.keyAttack.setDown(false);

        if (current == null) {
            current = nextValidBlock(player, level);
            currentTicks = 0;
            if (current == null) {
                reset();
                return;
            }
            gm.startDestroyBlock(current, Direction.UP);
        }

        BlockState state = level.getBlockState(current);
        boolean stillOre = !state.isAir() && isOre(state) && currentKey.equals(oreKey(state));
        if (!stillOre || ++currentTicks > TIMEOUT_TICKS || toolAboutToBreak(player)) {
            if (toolAboutToBreak(player)) {
                reset();
                player.displayClientMessage(
                        Component.literal("Vein Miner gestoppt: Werkzeug fast kaputt"), true);
            } else {
                current = null;
            }
            return;
        }

        gm.continueDestroyBlock(current, Direction.UP);
        player.swing(InteractionHand.MAIN_HAND);

        if (level.getBlockState(current).isAir()) {
            current = null;
        }
    }

    private static BlockPos nextValidBlock(LocalPlayer player, ClientLevel level) {
        Vec3 eye = player.getEyePosition();
        while (!queue.isEmpty()) {
            BlockPos pos = queue.poll();
            queued.remove(pos);
            BlockState state = level.getBlockState(pos);
            if (state.isAir() || !isOre(state) || !currentKey.equals(oreKey(state))) continue;
            if (eye.distanceToSqr(Vec3.atCenterOf(pos)) > MAX_REACH * MAX_REACH) continue;
            if (!player.hasCorrectToolForDrops(state)) continue;
            return pos;
        }
        return null;
    }

    private static List<BlockPos> collectVein(ClientLevel level, BlockPos origin, String key) {
        List<BlockPos> result = new ArrayList<>();
        Set<BlockPos> visited = new HashSet<>();
        ArrayDeque<BlockPos> open = new ArrayDeque<>();
        visited.add(origin);
        open.add(origin);

        while (!open.isEmpty() && result.size() < MAX_BLOCKS) {
            BlockPos cur = open.poll();
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) continue;
                        BlockPos n = cur.offset(dx, dy, dz);
                        if (!visited.add(n)) continue;
                        BlockState s = level.getBlockState(n);
                        if (isOre(s) && key.equals(oreKey(s))) {
                            result.add(n.immutable());
                            open.add(n.immutable());
                            if (result.size() >= MAX_BLOCKS) return sorted(result, origin);
                        }
                    }
                }
            }
        }
        return sorted(result, origin);
    }

    private static List<BlockPos> sorted(List<BlockPos> list, BlockPos origin) {
        list.sort(Comparator.comparingDouble(p -> p.distSqr(origin)));
        return list;
    }

    private static boolean isOre(BlockState state) {
        return state.is(ConventionalBlockTags.ORES);
    }

    private static String oreKey(BlockState state) {
        String path = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
        return path.startsWith("deepslate_") ? path.substring("deepslate_".length()) : path;
    }

    private static boolean toolAboutToBreak(LocalPlayer player) {
        ItemStack stack = player.getMainHandItem();
        return stack.isDamageableItem() && stack.getDamageValue() >= stack.getMaxDamage() - 1;
    }

    private static void reset() {
        queue.clear();
        queued.clear();
        current = null;
        currentTicks = 0;
        currentKey = null;
    }
}
