package org.pawling.themepark;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.IronGolem;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.scheduler.BukkitTask;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

/**
 * Park-only, tagged entities. No global spawn rules or gamerules are changed.
 * A server restart reconciles any surviving tagged entities before repopulating.
 */
public final class ParkSurvival implements Listener {
    private static final String VILLAGER = "themepark_day_villager";
    private static final String GOLEM = "themepark_guardian";
    private static final String MONSTER = "themepark_night_enemy";
    private static final int DAWN = 23000;

    private final ThemeParkPlugin plugin;
    private final ParkArea area;
    private final Random random = new Random();
    private final BossBar bossBar;
    private BukkitTask task;
    private boolean night;
    private boolean initialized;
    private int seconds;

    public ParkSurvival(ThemeParkPlugin plugin, ParkArea area) {
        this.plugin = plugin;
        this.area = area;
        this.bossBar = Bukkit.createBossBar("§4☠ THE PARK IS HUNTING YOU",
                BarColor.RED, BarStyle.SEGMENTED_10);
        this.bossBar.setVisible(false);
    }

    public void start() {
        cleanupTagged();
        night = !isNight();
        transition();
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    public void stop() {
        if (task != null) task.cancel();
        bossBar.removeAll();
        bossBar.setVisible(false);
        cleanupTagged();
    }

    public boolean isNight() {
        long time = area.world().getTime();
        return time >= 13000 && time < DAWN;
    }

    public int enemyCount() {
        // Golems themselves count as hostiles while they are hunting visitors.
        return tagged(MONSTER).size() + (night ? tagged(GOLEM).size() : 0);
    }

    private int monsterCount() {
        return tagged(MONSTER).size();
    }

    private List<Entity> tagged(String tag) {
        List<Entity> matches = new ArrayList<>();
        for (Entity entity : area.world().getNearbyEntities(area.spawn(), 40, 30, 40)) {
            if (entity.getScoreboardTags().contains(tag)) {
                matches.add(entity);
            }
        }
        return matches;
    }

    private void cleanupTagged() {
        for (String tag : new String[] {VILLAGER, GOLEM, MONSTER}) {
            for (Entity entity : tagged(tag)) {
                entity.remove();
            }
        }
    }

    private void removeTagged(String tag) {
        for (Entity entity : tagged(tag)) {
            entity.remove();
        }
    }

    private void transition() {
        night = isNight();
        seconds = 0;
        if (night) {
            removeTagged(VILLAGER);
            spawnEnemies(targetEnemies());
            if (area.world().getDifficulty() == Difficulty.PEACEFUL) {
                plugin.getLogger().warning("ThemePark night survival needs a non-PEACEFUL world difficulty.");
            }
            for (Player player : area.world().getPlayers()) {
                if (area.contains(player.getLocation())) {
                    player.sendTitle("§4§lNIGHTMARE PARK", "§cSURVIVE UNTIL DAWN!", 10, 70, 15);
                    player.playSound(player.getLocation(), "minecraft:entity.ender_dragon.growl", 0.8f, 0.8f);
                }
            }
        } else {
            removeTagged(MONSTER);
            new ParkBuilder(plugin).repairHideouts(area);
            for (Entity entity : tagged(GOLEM)) {
                if (entity instanceof IronGolem golem) golem.setTarget(null);
            }
            replenishDaytime();
            if (initialized) {
                for (Player player : area.world().getPlayers()) {
                    if (area.contains(player.getLocation())) {
                        player.sendTitle("§e§lDAWN", "§aYou survived the night!", 10, 50, 15);
                    }
                }
            }
            bossBar.removeAll();
            bossBar.setVisible(false);
        }
        initialized = true;
    }

    private int targetEnemies() {
        return Math.max(3, Math.min(32, plugin.getConfig().getInt("night-enemies", 16)));
    }

    private void tick() {
        if (night != isNight()) transition();
        seconds++;
        if (night) {
            if (seconds % 7 == 0 && monsterCount() < targetEnemies()) {
                // A limited trickle of replacements prevents an empty, boring final minute.
                spawnEnemies(Math.min(2, targetEnemies() - monsterCount()));
            }
            directGolems();
            if (seconds % 2 == 0) damageHideouts();
            updateHud();
        } else {
            if (seconds % 10 == 0) replenishDaytime();
            bossBar.removeAll();
            bossBar.setVisible(false);
        }
    }

    private void replenishDaytime() {
        int[][] positions = {{-12, -6}, {-12, 6}, {0, 12}, {0, -12}, {12, -7}, {12, 7}};
        int existingVillagers = tagged(VILLAGER).size();
        for (int i = existingVillagers; i < positions.length; i++) {
            Location location = ground(positions[i][0], positions[i][1]);
            if (!location.getBlock().isPassable()) continue;
            Villager villager = area.world().spawn(location, Villager.class);
            villager.addScoreboardTag(VILLAGER);
            villager.setProfession(Villager.Profession.NONE);
            villager.setRemoveWhenFarAway(false);
        }
        int[][] guards = {{0, 8}, {0, -8}, {-9, 0}, {22, 0}};
        int existingGolems = tagged(GOLEM).size();
        for (int i = existingGolems; i < guards.length; i++) {
            IronGolem golem = area.world().spawn(ground(guards[i][0], guards[i][1]), IronGolem.class);
            golem.addScoreboardTag(GOLEM);
            golem.setPlayerCreated(false);
            golem.setRemoveWhenFarAway(false);
            golem.setCustomName("§aPark Security");
            golem.setCustomNameVisible(true);
        }
    }

    private Location ground(int dx, int dz) {
        return new Location(area.world(), area.centerX() + dx + 0.5,
                area.floorY() + 1.0, area.centerZ() + dz + 0.5);
    }

    private void spawnEnemies(int amount) {
        for (int i = 0; i < amount; i++) {
            Location location = findEnemySpawn();
            if (location == null) break;
            // An opening wave always includes all three mob types.
            EntityType type = switch (i) {
                case 0 -> EntityType.ZOMBIE;
                case 1 -> EntityType.SKELETON;
                case 2 -> EntityType.WITCH;
                default -> switch (random.nextInt(5)) {
                    case 0 -> EntityType.WITCH;
                    case 1, 2 -> EntityType.SKELETON;
                    default -> EntityType.ZOMBIE;
                };
            };
            Entity spawned = area.world().spawnEntity(location, type);
            spawned.addScoreboardTag(MONSTER);
            if (spawned instanceof Mob mob) {
                mob.setRemoveWhenFarAway(false);
                mob.setCanPickupItems(false);
            }
        }
    }

    private Location findEnemySpawn() {
        for (int attempt = 0; attempt < 35; attempt++) {
            double angle = random.nextDouble() * Math.PI * 2.0;
            int radius = 16 + random.nextInt(8);
            int x = area.centerX() + (int) Math.round(Math.cos(angle) * radius);
            int z = area.centerZ() + (int) Math.round(Math.sin(angle) * radius);
            if (!area.contains(x, z) || area.nearHideout(x, z, 4)) continue;
            Block below = area.world().getBlockAt(x, area.floorY(), z);
            Block feet = area.world().getBlockAt(x, area.floorY() + 1, z);
            Block head = area.world().getBlockAt(x, area.floorY() + 2, z);
            if (below.getType().isSolid() && feet.isPassable() && head.isPassable()) {
                return new Location(area.world(), x + 0.5, area.floorY() + 1, z + 0.5);
            }
        }
        return null;
    }

    private void directGolems() {
        List<Player> visitors = new ArrayList<>();
        for (Player player : area.world().getPlayers()) {
            if (area.contains(player.getLocation()) && player.getLocation().getY() < area.floorY() + 23
                    && player.getGameMode() != org.bukkit.GameMode.SPECTATOR
                    && player.getGameMode() != org.bukkit.GameMode.CREATIVE) {
                visitors.add(player);
            }
        }
        for (Entity entity : tagged(GOLEM)) {
            if (!(entity instanceof IronGolem golem)) continue;
            Player nearest = null;
            double distance = 26 * 26;
            for (Player player : visitors) {
                double d = golem.getLocation().distanceSquared(player.getLocation());
                if (d < distance) {
                    nearest = player;
                    distance = d;
                }
            }
            if (nearest != null) {
                if (!nearest.equals(golem.getTarget())) golem.setTarget(nearest);
            } else {
                golem.setTarget(null);
            }
        }
    }

    private void updateHud() {
        int count = enemyCount();
        int remaining = (int) Math.ceil(Math.max(0, DAWN - area.world().getTime()) / 20.0);
        String time = String.format("%02d:%02d", remaining / 60, remaining % 60);
        bossBar.setTitle("§4☠ SURVIVE THE NIGHT  §c" + count
                + " HOSTILES  §6DAWN " + time);
        bossBar.setProgress(Math.max(0.0, Math.min(1.0,
                (DAWN - area.world().getTime()) / 10000.0)));
        bossBar.setVisible(true);
        for (Player player : area.world().getPlayers()) {
            boolean inPark = area.contains(player.getLocation());
            if (inPark) {
                if (!bossBar.getPlayers().contains(player)) bossBar.addPlayer(player);
                player.sendActionBar(Component.text("☠ " + count + " ENEMIES   •   DAWN IN " + time,
                        NamedTextColor.RED));
            } else {
                bossBar.removePlayer(player);
            }
        }
    }

    /**
     * Only the four generated wooden shelters are breakable. A monster must
     * reach the shelter while a player is actually hiding nearby.
     */
    private void damageHideouts() {
        List<Entity> enemies = tagged(MONSTER);
        for (int[] offset : ParkArea.HIDEOUTS) {
            int cx = area.centerX() + offset[0];
            int cz = area.centerZ() + offset[1];
            Location center = new Location(area.world(), cx + 0.5, area.floorY() + 1.5, cz + 0.5);
            boolean occupied = false;
            for (Player player : area.world().getPlayers()) {
                if (player.getLocation().getWorld().equals(area.world())
                        && player.getLocation().distanceSquared(center) < 20) {
                    occupied = true;
                    break;
                }
            }
            if (!occupied) continue;
            boolean approached = false;
            for (Entity enemy : enemies) {
                if (enemy.getLocation().distanceSquared(center) < 25) {
                    approached = true;
                    break;
                }
            }
            if (!approached) continue;
            List<Block> cover = new ArrayList<>();
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    for (int dy = 1; dy <= 3; dy++) {
                        Block block = area.world().getBlockAt(cx + dx, area.floorY() + dy, cz + dz);
                        if (block.getType() == Material.OAK_PLANKS
                                || block.getType() == Material.DARK_OAK_SLAB) {
                            cover.add(block);
                        }
                    }
                }
            }
            if (!cover.isEmpty()) {
                Block broken = cover.get(random.nextInt(cover.size()));
                broken.setType(Material.AIR, false);
                area.world().playSound(broken.getLocation(), "minecraft:block.wood.break", 0.9f, 0.7f);
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onTarget(EntityTargetLivingEntityEvent event) {
        if (event.getEntity().getScoreboardTags().contains(GOLEM)) {
            if (night) {
                // Null is a legitimate target reset when visitors leave the park.
                if (event.getTarget() != null
                        && (!(event.getTarget() instanceof Player player)
                        || !area.contains(player.getLocation()))) {
                    event.setCancelled(true);
                }
            } else if (event.getTarget() instanceof Player) {
                event.setCancelled(true);
            }
        }
        if (event.getEntity().getScoreboardTags().contains(MONSTER)) {
            if (event.getTarget() instanceof IronGolem) event.setCancelled(true);
            if (event.getTarget() instanceof Player player && !area.contains(player.getLocation())) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (event.getDamager().getScoreboardTags().contains(GOLEM)
                && event.getEntity() instanceof Player player
                && (!night || !area.contains(player.getLocation()))) {
            event.setCancelled(true);
        }
        if (event.getDamager().getScoreboardTags().contains(MONSTER)
                && event.getEntity().getScoreboardTags().contains(GOLEM)) {
            event.setCancelled(true);
        }
    }
}
