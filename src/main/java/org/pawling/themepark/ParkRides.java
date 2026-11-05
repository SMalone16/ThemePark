package org.pawling.themepark;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
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
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.event.vehicle.VehicleExitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

/**
 * Legacy-client-compatible rides: armor stands and vanilla minecarts rather
 * than modern display entities which the browser's 1.12.2 client cannot draw.
 */
public final class ParkRides implements Listener {
    private static final String SEAT_TAG = "themepark_carousel_seat";
    private static final String COASTER_TAG = "themepark_coaster_cart";
    private static final int SEAT_COUNT = 4;
    private static final double RADIUS = 3.3;

    private final ThemeParkPlugin plugin;
    private final ParkArea area;
    private final List<ArmorStand> seats = new ArrayList<>();
    private final Map<UUID, Integer> lastRideClick = new HashMap<>();
    private BukkitTask task;
    private double rotation;

    public ParkRides(ThemeParkPlugin plugin, ParkArea area) {
        this.plugin = plugin;
        this.area = area;
    }

    public void start() {
        // Recreate clean ride entities after a reload, never duplicate saved seats.
        for (Entity entity : nearby()) {
            if (entity.getScoreboardTags().contains(SEAT_TAG)
                    || entity.getScoreboardTags().contains(COASTER_TAG)) {
                entity.remove();
            }
        }
        for (int i = 0; i < SEAT_COUNT; i++) {
            final int index = i;
            ArmorStand seat = area.world().spawn(position(index), ArmorStand.class);
            seat.addScoreboardTag(SEAT_TAG);
            seat.setVisible(false);
            seat.setSmall(true);
            seat.setMarker(false); // Must retain a clickable hitbox in Eagler 1.12.2.
            seat.setGravity(false);
            seat.setInvulnerable(true);
            seat.setCollidable(false);
            seat.setBasePlate(false);
            seat.setCustomName("§6§lCarousel §e(right click)");
            seat.setCustomNameVisible(true);
            seat.getEquipment().setHelmet(new ItemStack(
                    i % 2 == 0 ? Material.OAK_STAIRS : Material.BIRCH_STAIRS));
            seats.add(seat);
        }
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::rotate, 1L, 1L);
    }

    public void stop() {
        if (task != null) task.cancel();
        for (Entity entity : nearby()) {
            if (!entity.getScoreboardTags().contains(SEAT_TAG)
                    && !entity.getScoreboardTags().contains(COASTER_TAG)) continue;
            for (Entity passenger : new ArrayList<>(entity.getPassengers())) {
                entity.removePassenger(passenger);
                if (passenger instanceof Player player && player.isOnline()) {
                    player.teleport(exitPoint(entity.getScoreboardTags().contains(COASTER_TAG)));
                }
            }
            entity.remove();
        }
        seats.clear();
        lastRideClick.clear();
    }

    private List<Entity> nearby() {
        return new ArrayList<>(area.world().getNearbyEntities(area.spawn(), 40, 30, 40));
    }

    private Location position(int index) {
        double angle = rotation + (Math.PI * 2 * index / SEAT_COUNT);
        return new Location(area.world(),
                area.centerX() + 11.5 + Math.cos(angle) * RADIUS,
                area.floorY() + 1.15,
                area.centerZ() + 0.5 + Math.sin(angle) * RADIUS,
                (float) Math.toDegrees(angle + Math.PI / 2), 0);
    }

    private void rotate() {
        double degrees = Math.max(5, Math.min(90,
                plugin.getConfig().getDouble("carousel-degrees-per-second", 35)));
        rotation = (rotation + Math.toRadians(degrees) / 20.0) % (Math.PI * 2);
        for (int i = 0; i < seats.size(); i++) {
            ArmorStand seat = seats.get(i);
            if (!seat.isValid()) continue;
            // Paper 1.21.11 retains vehicle passengers on same-world teleports.
            // A mount keeps the rider attached to the moving seat, not walking free.
            seat.teleport(position(i));
        }
    }

    public boolean boardCarousel(Player player) {
        if (!isEligible(player)) return false;
        if (isRiding(player)) {
            exit(player);
            return true;
        }
        for (ArmorStand seat : seats) {
            if (!seat.isValid() || !seat.getPassengers().isEmpty()) continue;
            if (seat.addPassenger(player)) {
                player.sendMessage("§6Carousel: §eRight-click to leave, or SNEAK / /themepark exit.");
                return true;
            }
        }
        player.sendMessage("§eThe carousel is full. Try again soon.");
        return false;
    }

    public boolean boardCoaster(Player player) {
        if (!isEligible(player)) return false;
        if (isRiding(player)) {
            exit(player);
            return true;
        }
        Minecart cart = area.world().spawn(area.boardingPoint(), Minecart.class);
        cart.addScoreboardTag(COASTER_TAG);
        cart.setMaxSpeed(0.7);
        if (!cart.addPassenger(player)) {
            cart.remove();
            return false;
        }
        cart.setVelocity(new Vector(0.35, 0, 0));
        player.sendMessage("§aCoaster: §eRight-click to exit safely, or SNEAK / /themepark exit.");
        return true;
    }

    private boolean isEligible(Player player) {
        if (!area.contains(player.getLocation())) {
            player.sendMessage("§eEnter the ThemePark before boarding a ride.");
            return false;
        }
        if (player.getVehicle() != null) {
            player.sendMessage("§eLeave your current vehicle first.");
            return false;
        }
        return true;
    }

    public boolean isRiding(Player player) {
        Entity mount = player.getVehicle();
        return mount != null && (mount.getScoreboardTags().contains(SEAT_TAG)
                || mount.getScoreboardTags().contains(COASTER_TAG));
    }

    public boolean exit(Player player) {
        if (!isRiding(player)) return false;
        Entity mount = player.getVehicle();
        boolean coaster = mount.getScoreboardTags().contains(COASTER_TAG);
        mount.removePassenger(player);
        if (coaster) mount.remove();
        player.teleport(exitPoint(coaster));
        player.sendMessage("§aSafely exited " + (coaster ? "the coaster" : "the carousel") + ".");
        return true;
    }

    private Location exitPoint(boolean coaster) {
        return coaster
                ? new Location(area.world(), area.centerX() + 0.5, area.floorY() + 11.2,
                        area.centerZ() - 30.5)
                : new Location(area.world(), area.centerX() + 17.5, area.floorY() + 2,
                        area.centerZ() + 0.5);
    }

    /** Eagler/Spigot may send both generic and armor-stand-specific clicks. */
    private boolean duplicateRideClick(Player player) {
        int tick = Bukkit.getCurrentTick();
        Integer previous = lastRideClick.put(player.getUniqueId(), tick);
        return previous != null && previous == tick;
    }

    @EventHandler(ignoreCancelled = true)
    public void onArmorStandClick(PlayerInteractAtEntityEvent event) {
        onEntityClick(event);
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityClick(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        Player player = event.getPlayer();
        Entity clicked = event.getRightClicked();
        boolean rideClicked = clicked.getScoreboardTags().contains(SEAT_TAG)
                || clicked.getScoreboardTags().contains(COASTER_TAG);
        if (!rideClicked && !isRiding(player)) return;
        event.setCancelled(true);
        if (duplicateRideClick(player)) return;
        if (isRiding(player)) {
            exit(player);
        } else if (clicked.getScoreboardTags().contains(SEAT_TAG)) {
            boardCarousel(player);
        } else if (clicked.getScoreboardTags().contains(COASTER_TAG)
                && player.getVehicle() == null) {
            clicked.addPassenger(player);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onRightClick(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (event.getAction() != Action.RIGHT_CLICK_AIR
                && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Player player = event.getPlayer();
        if (isRiding(player)) {
            event.setCancelled(true);
            if (!duplicateRideClick(player)) exit(player);
            return;
        }
        if (event.getClickedBlock() == null || !area.contains(event.getClickedBlock())) return;
        Location clicked = event.getClickedBlock().getLocation().add(0.5, 0, 0.5);
        double x = clicked.getX() - (area.centerX() + 11.5);
        double z = clicked.getZ() - (area.centerZ() + 0.5);
        Material material = event.getClickedBlock().getType();
        if (x * x + z * z <= 36 && (material == Material.RED_CONCRETE
                || material == Material.WHITE_CONCRETE || material == Material.GOLD_BLOCK)) {
            event.setCancelled(true);
            if (!duplicateRideClick(player)) boardCarousel(player);
        } else if (Math.abs(clicked.getX() - area.centerX()) < 3
                && clicked.getZ() >= area.centerZ() - 32
                && clicked.getZ() <= area.centerZ() - 29
                && clicked.getY() >= area.floorY() + 10
                && material == Material.STONE_BRICKS) {
            event.setCancelled(true);
            if (!duplicateRideClick(player)) boardCoaster(player);
        }
    }

    @EventHandler
    public void onSneak(PlayerToggleSneakEvent event) {
        if (event.isSneaking() && isRiding(event.getPlayer())) {
            exit(event.getPlayer());
        }
    }

    @EventHandler
    public void onVehicleExit(VehicleExitEvent event) {
        if (!(event.getExited() instanceof Player player)
                || !event.getVehicle().getScoreboardTags().contains(COASTER_TAG)) return;
        // Covers vanilla shift-to-dismount as well as right-click exits.
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline() && player.getVehicle() == null) {
                player.teleport(exitPoint(true));
            }
            if (event.getVehicle().isValid()) event.getVehicle().remove();
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Entity mount = event.getPlayer().getVehicle();
        if (mount != null && mount.getScoreboardTags().contains(COASTER_TAG)) {
            mount.remove();
        }
    }
}
