package org.pawling.themepark;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

/** Two self-contained midway minigames with limited, tracked equipment. */
public final class ParkGames implements Listener {
    private static final String STRENGTH_TAG = "themepark_strength_target";
    private final ThemeParkPlugin plugin;
    private final ParkArea area;
    private final NamespacedKey temporaryKey;
    private final Map<UUID, Integer> strengthStarts = new HashMap<>();
    private final Map<UUID, ArcherySession> archery = new HashMap<>();
    private final Map<UUID, UUID> shotOwners = new HashMap<>();
    private ArmorStand strengthTarget;
    private BukkitTask task;

    private static final class ArcherySession {
        int shots;
        int resolved;
        int bestScore;
        final int startTick;
        ArcherySession(int startTick) { this.startTick = startTick; }
    }

    public ParkGames(ThemeParkPlugin plugin, ParkArea area) {
        this.plugin = plugin;
        this.area = area;
        this.temporaryKey = new NamespacedKey(plugin, "arcade_loaner");
    }

    public void start() {
        for (Entity entity : area.world().getNearbyEntities(area.spawn(), 40, 18, 40)) {
            if (entity.getScoreboardTags().contains(STRENGTH_TAG)) entity.remove();
        }
        strengthTarget = area.world().spawn(point(-9, 14, 1.0), ArmorStand.class);
        strengthTarget.addScoreboardTag(STRENGTH_TAG);
        strengthTarget.setGravity(false);
        strengthTarget.setVisible(true);
        strengthTarget.setBasePlate(false);
        strengthTarget.setCustomName("§6§lSTRENGTH TEST §eHIT ME!");
        strengthTarget.setCustomNameVisible(true);
        strengthTarget.getEquipment().setHelmet(new ItemStack(Material.ANVIL));
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    public void stop() {
        if (task != null) task.cancel();
        if (strengthTarget != null && strengthTarget.isValid()) strengthTarget.remove();
        for (UUID id : strengthStarts.keySet()) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) removeLoaners(player);
        }
        for (UUID id : archery.keySet()) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) removeLoaners(player);
        }
        strengthStarts.clear();
        archery.clear();
        shotOwners.clear();
    }

    private Location point(int dx, int dz, double dy) {
        return new Location(area.world(), area.centerX() + dx + 0.5,
                area.floorY() + dy, area.centerZ() + dz + 0.5);
    }

    private boolean near(Player player, int dx, int dz, double radius) {
        if (!player.getWorld().equals(area.world())) return false;
        Location loc = player.getLocation();
        double x = loc.getX() - area.centerX() - dx - 0.5;
        double z = loc.getZ() - area.centerZ() - dz - 0.5;
        return Math.hypot(x, z) <= radius && Math.abs(loc.getY() - area.floorY()) <= 8;
    }

    private ItemStack loaner(Material material, String name, int amount) {
        ItemStack item = new ItemStack(material, amount);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(name);
        meta.getPersistentDataContainer().set(temporaryKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    private boolean isLoaner(ItemStack item) {
        return item != null && item.hasItemMeta() && item.getItemMeta()
                .getPersistentDataContainer().has(temporaryKey, PersistentDataType.BYTE);
    }

    private void removeLoaners(Player player) {
        for (int slot = 0; slot < player.getInventory().getSize(); slot++) {
            if (isLoaner(player.getInventory().getItem(slot))) {
                player.getInventory().setItem(slot, null);
            }
        }
    }

    public boolean startStrength(Player player) {
        if (!near(player, -9, 12, 4)) {
            player.sendMessage("§eStand on the iron strength-testing platform.");
            return false;
        }
        if (strengthStarts.containsKey(player.getUniqueId())) {
            player.sendMessage("§eSwing your loaner mallet at the target!");
            return true;
        }
        if (archery.containsKey(player.getUniqueId())) {
            player.sendMessage("§eFinish your archery challenge first.");
            return false;
        }
        if (player.getInventory().firstEmpty() == -1) {
            player.sendMessage("§cFree an inventory slot for the mallet.");
            return false;
        }
        player.getInventory().addItem(loaner(Material.IRON_AXE, "§6§lFairground Mallet", 1));
        strengthStarts.put(player.getUniqueId(), Bukkit.getCurrentTick());
        player.sendTitle("§6§lPICKAXE POWER", "§eMake your strongest hit!", 5, 45, 10);
        player.sendMessage("§eStrike the anvil mannequin within 25 seconds. Critical hits win better pickaxes!");
        return true;
    }

    public boolean startArchery(Player player) {
        if (!near(player, -22, -8, 4)) {
            player.sendMessage("§eStand on the blue archery firing platform.");
            return false;
        }
        if (archery.containsKey(player.getUniqueId())) {
            player.sendMessage("§eYou have " + (3 - archery.get(player.getUniqueId()).shots)
                    + " shots remaining.");
            return true;
        }
        if (strengthStarts.containsKey(player.getUniqueId())) {
            player.sendMessage("§eComplete your strength test first.");
            return false;
        }
        if (player.getInventory().firstEmpty() == -1) {
            player.sendMessage("§cFree space for your practice bow.");
            return false;
        }
        player.getInventory().addItem(loaner(Material.BOW, "§b§lArchery Trial Bow", 1));
        player.getInventory().addItem(new ItemStack(Material.ARROW, 3));
        archery.put(player.getUniqueId(), new ArcherySession(Bukkit.getCurrentTick()));
        Location firing = point(-22, -8, 1.1);
        firing.setYaw(-90);
        firing.setPitch(-8);
        player.teleport(firing);
        player.sendTitle("§b§lLONG SHOT", "§f3 arrows • 43 block target", 5, 50, 10);
        player.sendMessage("§bBullseye = Power III bow. Inner ring = Power II. Outer = Power I.");
        return true;
    }

    private void finishArchery(Player player, ArcherySession session) {
        if (archery.remove(player.getUniqueId()) != session) return;
        removeLoaners(player);
        ItemStack bow = new ItemStack(Material.BOW);
        if (session.bestScore >= 1) bow.addUnsafeEnchantment(Enchantment.POWER, session.bestScore);
        if (session.bestScore >= 2) bow.addUnsafeEnchantment(Enchantment.UNBREAKING, session.bestScore - 1);
        if (session.bestScore >= 3) bow.addUnsafeEnchantment(Enchantment.PUNCH, 1);
        player.getInventory().addItem(bow, new ItemStack(Material.ARROW, 3));
        player.sendTitle(session.bestScore == 0 ? "§7§lNO HITS" : "§a§lPRIZE WON",
                session.bestScore == 0 ? "§fBow + 3 arrows"
                        : "§ePower " + session.bestScore + " Bow + 3 arrows", 5, 55, 15);
    }

    private void finishStrength(Player player, double damage) {
        strengthStarts.remove(player.getUniqueId());
        removeLoaners(player);
        int tier = damage >= 13 ? 5 : damage >= 10 ? 4 : damage >= 7 ? 3
                : damage >= 4 ? 2 : 1;
        Material prizeMaterial = switch (tier) {
            case 5 -> Material.NETHERITE_PICKAXE;
            case 4 -> Material.DIAMOND_PICKAXE;
            case 3 -> Material.IRON_PICKAXE;
            case 2 -> Material.STONE_PICKAXE;
            default -> Material.WOODEN_PICKAXE;
        };
        ItemStack prize = new ItemStack(prizeMaterial);
        if (tier >= 4) prize.addUnsafeEnchantment(Enchantment.EFFICIENCY, tier - 2);
        player.getInventory().addItem(prize);
        player.sendTitle("§6§l" + String.format("%.1f", damage) + " DAMAGE",
                "§aYou won a " + prizeMaterial.name().replace('_', ' ') + "!", 5, 55, 15);
    }

    @EventHandler(ignoreCancelled = true)
    public void onStrengthHit(EntityDamageByEntityEvent event) {
        if (!event.getEntity().getScoreboardTags().contains(STRENGTH_TAG)) return;
        event.setCancelled(true);
        if (!(event.getDamager() instanceof Player player)) return;
        if (!strengthStarts.containsKey(player.getUniqueId())) {
            player.sendMessage("§ePress R on the iron platform to begin the strength challenge.");
            return;
        }
        if (!isLoaner(player.getInventory().getItemInMainHand())) {
            player.sendMessage("§eHit using your Fairground Mallet!");
            return;
        }
        finishStrength(player, Math.max(0, event.getDamage()));
    }

    @EventHandler(ignoreCancelled = true)
    public void onShoot(EntityShootBowEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        ArcherySession session = archery.get(player.getUniqueId());
        if (session == null || !isLoaner(event.getBow())) return;
        if (!near(player, -22, -8, 4) || session.shots >= 3) {
            event.setCancelled(true);
            player.sendMessage("§eShoot from the blue platform. Only 3 shots are allowed.");
            return;
        }
        session.shots++;
        shotOwners.put(event.getProjectile().getUniqueId(), player.getUniqueId());
        player.sendMessage("§bShot " + session.shots + "/3 fired!");
    }

    @EventHandler(ignoreCancelled = true)
    public void onProjectileHit(ProjectileHitEvent event) {
        UUID owner = shotOwners.remove(event.getEntity().getUniqueId());
        if (owner == null) return;
        Player player = Bukkit.getPlayer(owner);
        ArcherySession session = archery.get(owner);
        if (session != null) {
            session.resolved++;
            int result = 0;
            if (event.getHitBlock() != null) {
                org.bukkit.block.Block hit = event.getHitBlock();
                int dz = Math.abs(hit.getZ() - (area.centerZ() - 8));
                int dy = Math.abs(hit.getY() - (area.floorY() + 5));
                if (hit.getX() == area.centerX() + 21 && dz <= 3 && dy <= 3) {
                    int ring = Math.max(dz, dy);
                    result = ring == 0 ? 3 : ring == 1 ? 2 : 1;
                }
            }
            session.bestScore = Math.max(session.bestScore, result);
            if (player != null) {
                player.sendMessage(result == 0 ? "§7Miss!"
                        : "§aHit! " + (result == 3 ? "BULLSEYE!" : "Ring " + result));
                if (session.shots >= 3 && session.resolved >= 3) finishArchery(player, session);
            }
        }
        event.getEntity().remove();
    }

    private void tick() {
        int tick = Bukkit.getCurrentTick();
        for (UUID id : java.util.List.copyOf(strengthStarts.keySet())) {
            if (tick - strengthStarts.get(id) < 25 * 20) continue;
            strengthStarts.remove(id);
            Player player = Bukkit.getPlayer(id);
            if (player != null) {
                removeLoaners(player);
                player.sendMessage("§eStrength challenge expired. Press R to retry.");
            }
        }
        for (Map.Entry<UUID, ArcherySession> entry : java.util.List.copyOf(archery.entrySet())) {
            ArcherySession session = entry.getValue();
            if (tick - session.startTick < 55 * 20) continue;
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player != null) finishArchery(player, session);
            else archery.remove(entry.getKey());
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onSwap(PlayerSwapHandItemsEvent event) {
        Player player = event.getPlayer();
        if (near(player, -9, 12, 4)) {
            event.setCancelled(true);
            startStrength(player);
        } else if (near(player, -22, -8, 4)) {
            event.setCancelled(true);
            startArchery(player);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND
                || event.getAction() != Action.RIGHT_CLICK_BLOCK
                || event.getClickedBlock() == null) return;
        Material material = event.getClickedBlock().getType();
        if (material == Material.IRON_BLOCK && near(event.getPlayer(), -9, 12, 4)) {
            event.setCancelled(true);
            startStrength(event.getPlayer());
        } else if (material == Material.LAPIS_BLOCK && near(event.getPlayer(), -22, -8, 4)) {
            event.setCancelled(true);
            startArchery(event.getPlayer());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        strengthStarts.remove(id);
        archery.remove(id);
        shotOwners.values().removeIf(v -> v.equals(id));
        removeLoaners(event.getPlayer());
    }
}
