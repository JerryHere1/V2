package com.ego.plugin.data;

import net.citizensnpcs.api.npc.NPC;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.UUID;

/**
 * Holds the runtime state of a single Ego bot.
 * Thread-safe reads on `dirty` and `backpackFull` via volatile.
 */
public class BotInstance {

    private final UUID ownerUuid;
    private final int npcId;
    private NPC npc;
    private BotMode mode;
    private final ItemStack[] backpack; // 27-slot virtual chest
    private volatile boolean backpackFull;
    private String statusLabel;
    /** Set to true whenever the backpack changes; cleared after a DB save. */
    private volatile boolean dirty;

    public BotInstance(UUID ownerUuid, int npcId, NPC npc) {
        this.ownerUuid   = ownerUuid;
        this.npcId       = npcId;
        this.npc         = npc;
        this.mode        = BotMode.IDLE;
        this.backpack    = new ItemStack[27];
        this.backpackFull = false;
        this.statusLabel = "Idle";
        this.dirty       = false;
    }

    // ── Getters & setters ──────────────────────────────────────────

    public UUID getOwnerUuid()          { return ownerUuid; }
    public int getNpcId()               { return npcId; }
    public NPC getNpc()                 { return npc; }
    public void setNpc(NPC npc)         { this.npc = npc; }

    public BotMode getMode()            { return mode; }
    public void setMode(BotMode mode)   { this.mode = mode; }

    public ItemStack[] getBackpack()    { return backpack; }
    public boolean isBackpackFull()     { return backpackFull; }
    public void setBackpackFull(boolean full) { this.backpackFull = full; }

    public String getStatusLabel()                  { return statusLabel; }
    public void setStatusLabel(String statusLabel)  { this.statusLabel = statusLabel; }

    public boolean isDirty()            { return dirty; }
    public void clearDirty()            { this.dirty = false; }

    // ── Backpack helpers ───────────────────────────────────────────

    /**
     * Adds an item to the backpack using proper stacking.
     * @return true if the item was fully added, false if backpack is full.
     */
    public boolean addToBackpack(ItemStack item) {
        if (item == null) return true;
        int remaining = item.getAmount();

        // Pass 1: stack onto matching existing stacks
        for (ItemStack slot : backpack) {
            if (remaining <= 0) break;
            if (slot != null && slot.isSimilar(item)) {
                int canFit = slot.getMaxStackSize() - slot.getAmount();
                int take   = Math.min(canFit, remaining);
                slot.setAmount(slot.getAmount() + take);
                remaining -= take;
            }
        }

        // Pass 2: fill empty slots
        for (int i = 0; i < backpack.length; i++) {
            if (remaining <= 0) break;
            if (backpack[i] == null) {
                ItemStack copy = item.clone();
                int take       = Math.min(item.getMaxStackSize(), remaining);
                copy.setAmount(take);
                backpack[i]    = copy;
                remaining     -= take;
            }
        }

        backpackFull = isActuallyFull();
        dirty = true;

        return remaining <= 0;
    }

    /**
     * Drains the entire backpack into a player's inventory (or drops overflow).
     * Clears backpack after transfer.
     */
    public void unloadBackpackTo(Player player) {
        for (int i = 0; i < backpack.length; i++) {
            if (backpack[i] == null) continue;
            var overflow = player.getInventory().addItem(backpack[i]);
            // Drop anything that didn't fit
            overflow.values().forEach(leftover ->
                player.getWorld().dropItemNaturally(player.getLocation(), leftover));
            backpack[i] = null;
        }
        backpackFull = false;
        dirty = true;
    }

    /** True when every slot is occupied and every stack is at max size. */
    public boolean isActuallyFull() {
        for (ItemStack slot : backpack) {
            if (slot == null) return false;
            if (slot.getAmount() < slot.getMaxStackSize()) return false;
        }
        return true;
    }

    /** Number of non-null slots. */
    public int getBackpackUsed() {
        int count = 0;
        for (ItemStack slot : backpack) {
            if (slot != null) count++;
        }
        return count;
    }
}
