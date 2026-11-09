package org.pawling.themepark;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.event.vehicle.VehicleExitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

/**
 * Scripted one-shot rides. All cart positions are sampled from the exact
 * blocks built by CoasterCourse, avoiding vanilla speed stalls at corners.
 * The vanilla minecart is visible to older Eaglercraft protocol clients.
 */
public final class ParkRides implements Listener {
    private static final String SEAT_TAG = "themepark_carousel_seat";
    private static final String COASTER_TAG = "themepark_coaster_cart";
    private static final int SEAT_COUNT = 4;
    private static final double RADIUS = 3.3;

    private final ThemeParkPlugin plugin;
    private final ParkArea area;
    private final CoasterCourse course;
    private final List<ArmorStand> seats = new ArrayList<>();
    private final Map<UUID, Double> carouselStarts = new HashMap<>();
    private final Map<UUID, Integer> lastClick = new HashMap<>();
    private final Set<UUID> dismounting = new HashSet<>();
    private BukkitTask task;
    private double rotation;
    private Minecart coaster;
    private UUID coasterRider;
    private double coasterProgress;

    public ParkRides(ThemeParkPlugin plugin, ParkArea area) {
        this.plugin = plugin;
        this.area = area;
        this.course = new CoasterCourse(area);
    }

    public void start() {
        removeOldEntities();
        for (int i = 0; i < SEAT_COUNT; i++) {
            ArmorStand seat = area.world().spawn(seatPosition(i), ArmorStand.class);
            seat.addScoreboardTag(SEAT_TAG);
            seat.setVisible(false);
            seat.setSmall(true);
            seat.setMarker(false);
            seat.setGravity(false);
            seat.setInvulnerable(true);
            seat.setCollidable(false);
            seat.setBasePlate(false);
            seat.setCustomName("§6Carousel §e(press R)");
            seat.setCustomNameVisible(true);
            seat.getEquipment().setHelmet(new ItemStack(
                    i % 2 == 0 ? Material.OAK_STAIRS : Material.BIRCH_STAIRS));
            seats.add(seat);
        }
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    public void stop() {
        if (task != null) task.cancel();
        if (coasterRider != null) finishCoaster(false);
        for (ArmorStand seat : seats) {
            if (!seat.isValid()) continue;
            for (Entity entity : new ArrayList<>(seat.getPassengers())) {
                if (entity instanceof Player player) exit(player, false);
            }
            seat.remove();
        }
        seats.clear();
        carouselStarts.clear();
        lastClick.clear();
        removeOldEntities();
    }

    private void removeOldEntities() {
        for (Entity entity : area.world().getNearbyEntities(area.spawn(), 42, 32, 42)) {
            if (entity.getScoreboardTags().contains(SEAT_TAG)
                    || entity.getScoreboardTags().contains(COASTER_TAG)) entity.remove();
        }
    }

    private Location seatPosition(int index) {
        double theta = rotation + Math.PI * 2 * index / SEAT_COUNT;
        return new Location(area.world(),
                area.centerX() + 11.5 + Math.cos(theta) * RADIUS,
                area.floorY() + 1.15,
                area.centerZ() + 0.5 + Math.sin(theta) * RADIUS,
                (float) Math.toDegrees(theta + Math.PI / 2), 0);
    }

    private void tick() {
        double speed = Math.max(5, Math.min(90,
                plugin.getConfig().getDouble("carousel-degrees-per-second", 24)));
        rotation += Math.toRadians(speed) / 20.0;
        for (int i = 0; i < seats.size(); i++) {
            ArmorStand seat = seats.get(i);
            if (seat.isValid()) seat.teleport(seatPosition(i));
        }
        List<UUID> finished = new ArrayList<>();
        for (Map.Entry<UUID, Double> entry : carouselStarts.entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null || !isCarouselSeat(player.getVehicle())) {
                finished.add(entry.getKey());
            } else if (rotation - entry.getValue() >= Math.PI * 2) {
                finished.add(entry.getKey());
                exit(player, true);
            }
        }
        for (UUID id : finished) carouselStarts.remove(id);
        advanceCoaster();
    }

    private boolean isCarouselSeat(Entity entity) {
        return entity != null && entity.getScoreboardTags().contains(SEAT_TAG);
    }

    private void advanceCoaster() {
        if (coasterRider == null) return;
        Player player = Bukkit.getPlayer(coasterRider);
        if (player == null || coaster == null || !coaster.isValid()
                || !coaster.equals(player.getVehicle())) {
            finishCoaster(false);
            return;
        }
        int node = Math.min(course.length() - 2, (int) coasterProgress);
        List<CoasterCourse.Node> points = course.nodes();
        int slope = points.get(node + 1).y() - points.get(node).y();
        double baseSpeed = Math.max(0.25, Math.min(0.75,
                plugin.getConfig().getDouble("coaster-blocks-per-tick", 0.46)));
        double speed = Math.max(0.24, Math.min(0.85, baseSpeed - slope * 0.12));
        coasterProgress = Math.min(course.length() - 1, coasterProgress + speed);
        Location next = course.sample(area, coasterProgress);
        coaster.setVelocity(new Vector(0, 0, 0));
        if (!coaster.teleport(next)) {
            player.sendMessage("§cThe coaster paused unexpectedly; exiting safely.");
            finishCoaster(false);
            return;
        }
        if (coasterProgress >= course.length() - 1) {
            finishCoaster(true);
        }
    }

    public boolean boardCoaster(Player player) {
        if (!near(player, -3, -17, 4)) {
            player.sendMessage("§eStand on the gold coaster entrance platform, then press R.");
            return false;
        }
        if (!eligible(player)) return false;
        if (coasterRider != null) {
            player.sendMessage("§eCoaster occupied! One rider at a time.");
            return false;
        }
        coaster = area.world().spawn(course.sample(area, 0), Minecart.class);
        coaster.addScoreboardTag(COASTER_TAG);
        coaster.setGravity(false);
        coaster.setMaxSpeed(0.85);
        coaster.setSlowWhenEmpty(false);
        coasterRider = player.getUniqueId();
        coasterProgress = 0;
        if (!coaster.addPassenger(player)) {
            finishCoaster(false);
            return false;
        }
        player.sendTitle("§6§lWILD TRACKS", "§eONE RIDE • AUTOMATIC EXIT", 5, 35, 10);
        return true;
    }

    private void finishCoaster(boolean completed) {
        UUID id = coasterRider;
        Minecart cart = coaster;
        coasterRider = null;
        coaster = null;
        coasterProgress = 0;
        if (id != null) {
            Player player = Bukkit.getPlayer(id);
            if (player != null && player.isOnline()) {
                dismounting.add(id);
                if (cart != null && cart.isValid()) cart.removePassenger(player);
                player.teleport(coasterExit());
                dismounting.remove(id);
                if (completed) {
                    player.sendTitle("§a§lRIDE COMPLETE", "§eThanks for riding!", 5, 35, 10);
                }
            }
        }
        if (cart != null && cart.isValid()) cart.remove();
    }

    public boolean boardCarousel(Player player) {
        if (!near(player, 11, 0, 8)) {
            player.sendMessage("§eStand by the carousel to begin a ride.");
            return false;
        }
        if (!eligible(player)) return false;
        for (ArmorStand seat : seats) {
            if (!seat.isValid() || !seat.getPassengers().isEmpty()) continue;
            if (seat.addPassenger(player)) {
                carouselStarts.put(player.getUniqueId(), rotation);
                player.sendTitle("§6§lCAROUSEL", "§eOne revolution, then automatic exit", 5, 30, 10);
                return true;
            }
        }
        player.sendMessage("§eAll carousel seats are occupied.");
        return false;
    }

    private boolean eligible(Player player) {
        if (!area.contains(player.getLocation())) return false;
        if (player.getVehicle() != null) {
            player.sendMessage("§eFinish your current ride first.");
            return false;
        }
        return true;
    }

    private boolean near(Player player, int dx, int dz, double distance) {
        if (!area.world().equals(player.getWorld())) return false;
        Location loc = player.getLocation();
        double x = loc.getX() - area.centerX() - dx - 0.5;
        double z = loc.getZ() - area.centerZ() - dz - 0.5;
        return Math.hypot(x, z) <= distance && Math.abs(loc.getY() - area.floorY()) < 9;
    }

    public boolean isRiding(Player player) {
        Entity mount = player.getVehicle();
        return mount != null && (mount.getScoreboardTags().contains(SEAT_TAG)
                || mount.getScoreboardTags().contains(COASTER_TAG));
    }

    public boolean exit(Player player) {
        return exit(player, false);
    }

    private boolean exit(Player player, boolean completed) {
        if (!isRiding(player)) return false;
        if (player.getUniqueId().equals(coasterRider)) {
            finishCoaster(completed);
            return true;
        }
        Entity seat = player.getVehicle();
        carouselStarts.remove(player.getUniqueId());
        dismounting.add(player.getUniqueId());
        seat.removePassenger(player);
        player.teleport(carouselExit());
        dismounting.remove(player.getUniqueId());
        if (completed) player.sendMessage("§aCarousel revolution completed!");
        return true;
    }

    private Location coasterExit() {
        return new Location(area.world(), area.centerX() - 3 + 0.5,
                area.floorY() + 1.3, area.centerZ() - 25 + 0.5);
    }

    private Location carouselExit() {
        return new Location(area.world(), area.centerX() + 18 + 0.5,
                area.floorY() + 1.3, area.centerZ() + 0.5);
    }

    private boolean duplicate(Player player) {
        int tick = Bukkit.getCurrentTick();
        Integer previous = lastClick.put(player.getUniqueId(), tick);
        return previous != null && previous == tick;
    }

    /** Swap Item With Offhand is mapped to R in Eagler's Controls screen. */
    @EventHandler(ignoreCancelled = true)
    public void onSwap(PlayerSwapHandItemsEvent event) {
        Player player = event.getPlayer();
        if (isRiding(player)) {
            event.setCancelled(true);
            exit(player);
        } else if (near(player, -3, -17, 4)) {
            event.setCancelled(true);
            boardCoaster(player);
        } else if (near(player, 11, 0, 8)) {
            event.setCancelled(true);
            boardCarousel(player);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onArmorStandClick(PlayerInteractAtEntityEvent event) { onEntityClick(event); }

    @EventHandler(ignoreCancelled = true)
    public void onEntityClick(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        Player player = event.getPlayer();
        Entity entity = event.getRightClicked();
        if (isRiding(player)) {
            event.setCancelled(true);
            if (!duplicate(player)) exit(player);
        } else if (entity.getScoreboardTags().contains(SEAT_TAG)) {
            event.setCancelled(true);
            if (!duplicate(player)) boardCarousel(player);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onRightClick(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK
                && event.getAction() != Action.RIGHT_CLICK_AIR) return;
        Player player = event.getPlayer();
        if (isRiding(player)) {
            event.setCancelled(true);
            if (!duplicate(player)) exit(player);
            return;
        }
        if (event.getClickedBlock() == null || !area.contains(event.getClickedBlock())) return;
        // No personal minecarts on the now-scripted course.
        Material material = event.getClickedBlock().getType();
        ItemStack held = player.getInventory().getItemInMainHand();
        if ((material == Material.RAIL || material == Material.POWERED_RAIL)
                && held.getType() == Material.MINECART) {
            event.setCancelled(true);
            player.sendMessage("§eUse the coaster entrance. Personal minecarts aren't supported here.");
            return;
        }
        if (material == Material.GOLD_BLOCK && near(player, -3, -17, 5)) {
            event.setCancelled(true);
            if (!duplicate(player)) boardCoaster(player);
        } else if ((material == Material.RED_CONCRETE || material == Material.WHITE_CONCRETE)
                && near(player, 11, 0, 8)) {
            event.setCancelled(true);
            if (!duplicate(player)) boardCarousel(player);
        }
    }

    @EventHandler
    public void onSneak(PlayerToggleSneakEvent event) {
        if (event.isSneaking() && isRiding(event.getPlayer())) exit(event.getPlayer());
    }

    @EventHandler
    public void onVehicleExit(VehicleExitEvent event) {
        if (!(event.getExited() instanceof Player player) || !isParkRide(event.getVehicle())) return;
        UUID id = player.getUniqueId();
        if (dismounting.contains(id)) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (dismounting.contains(id) || !player.isOnline()) return;
            if (id.equals(coasterRider)) {
                finishCoaster(false);
            } else {
                carouselStarts.remove(id);
                if (player.getVehicle() == null) player.teleport(carouselExit());
            }
        });
    }

    private boolean isParkRide(Entity entity) {
        return entity.getScoreboardTags().contains(SEAT_TAG)
                || entity.getScoreboardTags().contains(COASTER_TAG);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        carouselStarts.remove(id);
        lastClick.remove(id);
        if (id.equals(coasterRider)) finishCoaster(false);
    }

    public int coasterLength() {
        return course.length();
    }
}
