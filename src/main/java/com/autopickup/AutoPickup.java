package com.autopickup;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityCombatEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

public class AutoPickup implements ModInitializer {
    // Tugas "ambil item di sekitar titik ini" yang dijalankan beberapa tick setelah blok/mob hancur,
    // supaya item drop sudah sempat muncul.
    private static final class Pending {
        final ServerWorld world;
        final Vec3d center;
        final double radius;
        final UUID playerId;
        int ticksLeft;

        Pending(ServerWorld world, Vec3d center, double radius, UUID playerId, int ticksLeft) {
            this.world = world;
            this.center = center;
            this.radius = radius;
            this.playerId = playerId;
            this.ticksLeft = ticksLeft;
        }
    }

    private static final List<Pending> PENDING = new ArrayList<>();
    private static final int MAX_PENDING = 500;

    private static void schedule(ServerWorld world, Vec3d center, double radius, ServerPlayerEntity player) {
        if (PENDING.size() < MAX_PENDING) {
            PENDING.add(new Pending(world, center, radius, player.getUuid(), 2));
        }
    }

    private static void collect(ServerPlayerEntity player, Pending task) {
        Box area = new Box(
            task.center.x - task.radius, task.center.y - task.radius, task.center.z - task.radius,
            task.center.x + task.radius, task.center.y + task.radius, task.center.z + task.radius);

        List<ItemEntity> items = task.world.getEntitiesByClass(
            ItemEntity.class, area, item -> item.isAlive() && item.getItemAge() <= 10);

        for (ItemEntity item : items) {
            ItemStack stack = item.getStack();
            int count = stack.getCount();
            if (player.getInventory().insertStack(stack)) {
                player.sendPickup(item, count);
                item.discard();
            }
        }
    }

    @Override
    public void onInitialize() {
        // Blok yang dihancurkan pemain
        PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, blockEntity) -> {
            if (world.isClient() || !(player instanceof ServerPlayerEntity serverPlayer)) {
                return;
            }
            schedule((ServerWorld) world, Vec3d.ofCenter(pos), 2.0, serverPlayer);
        });

        // Mob yang dibunuh pemain (termasuk dengan panah)
        ServerEntityCombatEvents.AFTER_KILLED_OTHER_ENTITY.register((world, entity, killedEntity) -> {
            if (entity instanceof ServerPlayerEntity serverPlayer) {
                schedule(world, killedEntity.getPos(), 3.0, serverPlayer);
            }
        });

        // Jalankan tugas yang sudah waktunya
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            Iterator<Pending> iterator = PENDING.iterator();
            while (iterator.hasNext()) {
                Pending task = iterator.next();
                task.ticksLeft--;
                if (task.ticksLeft > 0) {
                    continue;
                }
                iterator.remove();
                ServerPlayerEntity player = server.getPlayerManager().getPlayer(task.playerId);
                if (player != null && player.isAlive()) {
                    collect(player, task);
                }
            }
        });
    }
}
