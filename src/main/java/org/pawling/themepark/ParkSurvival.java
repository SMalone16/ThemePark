package org.pawling.themepark;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
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
import org.bukkit.event.world.ChunkLoadEvent;
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
    /** Absolute upper bound per park mob type, even if config requests more. */
    static final int HARD_CAP = 5;
    private static final EntityType[] ENEMY_TYPES = {
            EntityType.ZOMBIE, EntityType.SKELETON, EntityType.WITCH
    };

    private final ThemeParkPlugin plugin;
    private final ParkArea area;
    private final Random random = new Random();
    /** Direct references prevent wandering mobs outside the park from being forgotten. */
    private final Set<UUID> owned = new HashSet<>();
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
        // Looking up ~14 UUIDs is much cheaper than scanning every entity in
        // the park many times each second; entities wandering outside the park
        // remain tracked and still count toward the caps.
        for (UUID id : List.copyOf(owned)) {
            Entity entity = Bukkit.getEntity(id);
            if (entity == null || !entity.isValid()) {
                owned.remove(id);
            } else if (entity.getScoreboardTags().contains(tag)) {
                matches.add(entity);
            }
        }
        return matches;
    }

    private void cleanupTagged() {
        // One full loaded-world sweep per plugin start/stop also cleans up
        // escaped mobs left behind by previous versions of the plugin.
        for (Entity entity : area.world().getEntities()) {
            if (isOurs(entity)) entity.remove();
        }
        owned.clear();
    }

    private boolean isOurs(Entity entity) {
        return entity.getScoreboardTags().contains(VILLAGER)
                || entity.getScoreboardTags().contains(GOLEM)
                || entity.getScoreboardTags().contains(MONSTER);
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        if (!event.getWorld().equals(area.world())) return;
        // Old escaped entities may have been in unloaded chunks at startup.
        // Remove them when their chunk is next loaded, never let them pile up.
        for (Entity entity : event.getChunk().getEntities()) {
            if (isOurs(entity) && !owned.contains(entity.getUniqueId())) {
                entity.remove();
            }
        }
    }

    private void track(Entity entity) {
        owned.add(entity.getUniqueId());
    }

    private void removeTagged(String tag) {
        for (Entity entity : tagged(tag)) {
            owned.remove(entity.getUniqueId());
            entity.remove();
        }
    }

    private void transition() {
        night = isNight();
        seconds = 0;
        if (night) {
            removeTagged(VILLAGER);
            if (hasVisitors()) spawnEnemies(targetEnemies());
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

    static int clampCap(int requested) {
        return Math.max(0, Math.min(HARD_CAP, requested));
    }

    private int villagerCap() {
        return clampCap(plugin.getConfig().getInt("park-villager-cap", 5));
    }

    private int guardCap() {
        return clampCap(plugin.getConfig().getInt("park-security-cap", 5));
    }

    private int typeCap(EntityType type) {
        String key = switch (type) {
            case ZOMBIE -> "park-zombie-cap";
            case SKELETON -> "park-skeleton-cap";
            case WITCH -> "park-witch-cap";
            default -> throw new IllegalArgumentException("Unexpected park mob: " + type);
        };
        return clampCap(plugin.getConfig().getInt(key, 5));
    }

    private int targetEnemies() {
        int maximum = 0;
        for (EntityType type : ENEMY_TYPES) maximum += typeCap(type);
        // Total default 9 (3 per type) is deliberately below the 15 possible
        // individual slots to reduce pathfinding / combat on classroom servers.
        return Math.max(0, Math.min(maximum,
                plugin.getConfig().getInt("night-total-enemies", 9)));
    }

    private boolean hasVisitors() {
        for (Player player : area.world().getPlayers()) {
            if (area.contains(player.getLocation()) && player.isOnline()
                    && player.getGameMode() != org.bukkit.GameMode.SPECTATOR) return true;
        }
        return false;
    }

    private void trim(List<Entity> entities, int cap) {
        for (int i = cap; i < entities.size(); i++) {
            Entity extra = entities.get(i);
            owned.remove(extra.getUniqueId());
            extra.remove();
        }
    }

    private void enforceCaps() {
        trim(tagged(GOLEM), guardCap());
        trim(tagged(VILLAGER), villagerCap());
        Map<EntityType, List<Entity>> monsters = new EnumMap<>(EntityType.class);
        for (EntityType type : ENEMY_TYPES) monsters.put(type, new ArrayList<>());
        for (Entity entity : tagged(MONSTER)) {
            List<Entity> group = monsters.get(entity.getType());
            if (group == null) {
                owned.remove(entity.getUniqueId());
                entity.remove();
            } else {
                group.add(entity);
            }
        }
        for (EntityType type : ENEMY_TYPES) trim(monsters.get(type), typeCap(type));
        trim(tagged(MONSTER), targetEnemies());
    }

    private void tick() {
        if (night != isNight()) transition();
        seconds++;
        enforceCaps();
        if (night) {
            // No player in the park: no hostile AI work or replacement spawns.
            if (!hasVisitors()) {
                removeTagged(MONSTER);
                for (Entity entity : tagged(GOLEM)) {
                    if (entity instanceof IronGolem golem) golem.setTarget(null);
                }
                bossBar.removeAll();
                bossBar.setVisible(false);
                return;
            }
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
        int[][] positions = {{-12, -6}, {-12, 6}, {0, 12}, {0, -12}, {12, -7}};
        int existingVillagers = tagged(VILLAGER).size();
        for (int i = existingVillagers; i < Math.min(positions.length, villagerCap()); i++) {
            Location location = ground(positions[i][0], positions[i][1]);
            if (!location.getBlock().isPassable()) continue;
            Villager villager = area.world().spawn(location, Villager.class);
            villager.addScoreboardTag(VILLAGER);
            track(villager);
            villager.setProfession(Villager.Profession.NONE);
            villager.setRemoveWhenFarAway(false);
        }
        int[][] guards = {{0, 8}, {0, -8}, {-9, 0}, {22, 0}, {-22, 0}};
        int existingGolems = tagged(GOLEM).size();
        for (int i = existingGolems; i < Math.min(guards.length, guardCap()); i++) {
            IronGolem golem = area.world().spawn(ground(guards[i][0], guards[i][1]), IronGolem.class);
            golem.addScoreboardTag(GOLEM);
            track(golem);
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
        Map<EntityType, Integer> counts = new EnumMap<>(EntityType.class);
        for (EntityType type : ENEMY_TYPES) counts.put(type, 0);
        for (Entity entity : tagged(MONSTER)) {
            counts.computeIfPresent(entity.getType(), (type, count) -> count + 1);
        }
        int room = Math.max(0, targetEnemies() - monsterCount());
        for (int i = 0; i < Math.min(amount, room); i++) {
            List<EntityType> candidates = new ArrayList<>();
            int least = Integer.MAX_VALUE;
            for (EntityType type : ENEMY_TYPES) {
                int current = counts.get(type);
                if (current >= typeCap(type)) continue;
                if (current < least) {
                    least = current;
                    candidates.clear();
                }
                if (current == least) candidates.add(type);
            }
            if (candidates.isEmpty()) break;
            EntityType type = candidates.get(random.nextInt(candidates.size()));
            Location location = findEnemySpawn();
            if (location == null) break;
            Entity spawned = area.world().spawnEntity(location, type);
            spawned.addScoreboardTag(MONSTER);
            track(spawned);
            counts.put(type, counts.get(type) + 1);
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
