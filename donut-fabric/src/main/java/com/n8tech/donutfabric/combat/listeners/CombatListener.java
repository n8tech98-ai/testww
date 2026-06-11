package com.n8tech.donutfabric.combat.listeners;

import com.n8tech.donutfabric.DonutFabric;
import com.n8tech.donutfabric.combat.CombatManager;
import com.n8tech.donutfabric.shards.ShardManager;
import com.n8tech.donutfabric.utils.ChatUtil;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityCombatEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.entity.Entity;
import net.minecraft.entity.projectile.thrown.EnderPearlEntity;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;

/**
 * Hooks for combat system:
 * - Player-to-player damage → combat tag
 * - Player death → shard grant to killer
 * - Ender pearl throw → pearl cooldown check
 */
public class CombatListener {

    private final CombatManager combat;

    public CombatListener(CombatManager combat) {
        this.combat = combat;
    }

    public void register() {
        // Player damage (when a living entity is hurt, check if it's P vs P)
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
            if (!(entity instanceof ServerPlayerEntity victim)) return true;
            Entity attacker = source.getAttacker();
            if (!(attacker instanceof ServerPlayerEntity attackerPlayer)) return true;

            // Tag both players
            combat.tryTag(attackerPlayer, victim);
            return true; // allow damage
        });

        // Player kill → shard reward
        ServerEntityCombatEvents.AFTER_KILLED_OTHER_ENTITY.register((world, entity, killedEntity) -> {
            if (!(entity instanceof ServerPlayerEntity killer)) return;
            if (!(killedEntity instanceof ServerPlayerEntity)) return;

            ShardManager shards = DonutFabric.getInstance().getShardManager();
            shards.onPlayerKill(killer.getUuid());

            int reward = shards.getKillReward();
            killer.sendMessage(Text.literal(ChatUtil.color(
                "&a+&d" + reward + "✦ &7shards for the kill!")));
        });

        // Ender pearl cooldown
        UseItemCallback.EVENT.register((player, world, hand) -> {
            if (world.isClient()) return TypedActionResult.pass(player.getStackInHand(hand));
            if (!(player instanceof ServerPlayerEntity sp)) return TypedActionResult.pass(player.getStackInHand(hand));
            if (player.getStackInHand(hand).getItem() != Items.ENDER_PEARL)
                return TypedActionResult.pass(player.getStackInHand(hand));

            if (!combat.canThrowPearl(sp.getUuid())) {
                int remaining = combat.getPearlCooldownRemaining(sp.getUuid());
                sp.sendMessage(Text.literal(ChatUtil.color(
                    "&cPearl cooldown: &e" + remaining + "s")));
                return TypedActionResult.fail(player.getStackInHand(hand));
            }

            combat.setPearlCooldown(sp.getUuid());
            return TypedActionResult.pass(player.getStackInHand(hand));
        });
    }
}
