package org.pawling.themepark;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

/** Self-contained classroom park for the Paper backend, not the Velocity proxy. */
public final class ThemeParkPlugin extends JavaPlugin implements Listener, CommandExecutor {
    private static final String STATE_FILE = "park-state.yml";
    private ParkArea park;
    private boolean building;
    private Boolean barrierActive;
    private File stateFile;
    private YamlConfiguration state;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        stateFile = new File(getDataFolder(), STATE_FILE);
        state = YamlConfiguration.loadConfiguration(stateFile);
        Bukkit.getPluginManager().registerEvents(this, this);
        if (getCommand("themepark") != null) {
            getCommand("themepark").setExecutor(this);
        }

        // A late synchronous task ensures the normal Overworld spawn exists.
        Bukkit.getScheduler().runTaskLater(this, this::initialize, 40L);
        Bukkit.getScheduler().runTaskTimer(this, this::tickRiver, 5L, 5L);
        Bukkit.getScheduler().runTaskTimer(this, this::tickBarrier, 80L, 100L);
    }

    @Override
    public void onDisable() {
        // Never strand permanent barrier blocks when this optional plugin is disabled.
        if (park != null && !building) applyBarrier(false, true);
    }

    private void initialize() {
        String name = getConfig().getString("world", "world");
        World world = Bukkit.getWorld(name);
        if (world == null) {
            getLogger().severe("World '" + name + "' not loaded. ThemePark disabled.");
            return;
        }

        if (state.getBoolean("complete")
                && world.getUID().toString().equals(state.getString("world-uuid"))
                && state.getInt("size") == ParkArea.SIZE) {
            park = new ParkArea(world, state.getInt("center-x"),
                    state.getInt("center-z"), state.getInt("floor-y"));
            world.setSpawnLocation(park.centerX(), park.floorY() + 2, park.centerZ());
            getLogger().info("Restored built ThemePark without altering its terrain.");
            applyBarrier(getConfig().getBoolean("night-barrier", true) && isNight(world), true);
        } else if (getConfig().getBoolean("generate-on-first-enable", true)) {
            Location spawn = world.getSpawnLocation();
            int floor = Math.min(world.getHighestBlockYAt(spawn.getBlockX(), spawn.getBlockZ()),
                    spawn.getBlockY() - 1);
            floor = Math.max(world.getMinHeight() + 6, Math.min(floor, world.getMaxHeight() - 26));
            beginBuild(new ParkArea(world, spawn.getBlockX(), spawn.getBlockZ(), floor));
        } else {
            getLogger().warning("Park has not been built. Use /themepark rebuild confirm after taking a backup.");
        }
    }

    private void beginBuild(ParkArea area) {
        if (building) return;
        park = area;
        building = true;
        barrierActive = null;
        getLogger().warning("Generating ThemePark at spawn: a 70x70 area WILL BE FLATTENED."
                + " Back up an existing world before enabling this plugin.");
        new ParkBuilder(this).generate(area, () -> {
            building = false;
            state.set("complete", true);
            state.set("world-uuid", area.world().getUID().toString());
            state.set("center-x", area.centerX());
            state.set("center-z", area.centerZ());
            state.set("floor-y", area.floorY());
            state.set("size", ParkArea.SIZE);
            try {
                state.save(stateFile);
            } catch (IOException error) {
                getLogger().severe("Could not save park-state.yml; generation may repeat: " + error);
            }
            applyBarrier(getConfig().getBoolean("night-barrier", true) && isNight(area.world()), true);
        });
    }

    private boolean isNight(World world) {
        long time = world.getTime();
        return time >= 13000 && time < 23000;
    }

    private void tickBarrier() {
        if (park == null || building) return;
        applyBarrier(getConfig().getBoolean("night-barrier", true)
                && isNight(park.world()), false);
    }

    private void applyBarrier(boolean enabled, boolean force) {
        if (park == null || building) return;
        if (!force && barrierActive != null && barrierActive == enabled) return;

        World world = park.world();
        if (enabled) {
            // Move visitors standing in the soon-to-be-solid wall to the safe inner path.
            for (Player player : world.getPlayers()) {
                Location position = player.getLocation();
                int x = position.getBlockX();
                int z = position.getBlockZ();
                if (park.isNightBarrier(x, z) && position.getY() <= park.floorY() + 7) {
                    double dx = position.getX() - (park.centerX() + 0.5);
                    double dz = position.getZ() - (park.centerZ() + 0.5);
                    double radius = Math.max(1.0, Math.hypot(dx, dz));
                    player.teleport(new Location(world, park.centerX() + 0.5 + dx / radius * 23,
                            park.floorY() + 2, park.centerZ() + 0.5 + dz / radius * 23,
                            position.getYaw(), position.getPitch()));
                }
            }
        }

        for (int x = park.minX(); x <= park.maxX(); x++) {
            for (int z = park.minZ(); z <= park.maxZ(); z++) {
                if (!park.isNightBarrier(x, z)) continue;
                for (int y = park.floorY() + 1; y <= park.floorY() + 6; y++) {
                    Material current = world.getBlockAt(x, y, z).getType();
                    if (enabled) {
                        if (current == Material.AIR || current == Material.BARRIER) {
                            world.getBlockAt(x, y, z).setType(Material.BARRIER, false);
                        }
                    } else if (current == Material.BARRIER) {
                        world.getBlockAt(x, y, z).setType(Material.AIR, false);
                    }
                }
            }
        }
        barrierActive = enabled;
    }

    private void tickRiver() {
        if (park == null || building || !getConfig().getBoolean("river-current", true)) return;
        double speed = Math.min(0.25, Math.max(0.02,
                getConfig().getDouble("river-current-speed", 0.14)));
        for (Player player : park.world().getPlayers()) {
            Location loc = player.getLocation();
            if (loc.getBlockY() < park.floorY() - 1 || loc.getBlockY() > park.floorY() + 1) continue;
            if (!park.isRiver(loc.getBlockX(), loc.getBlockZ())) continue;
            if (loc.getBlock().getType() != Material.WATER
                    && loc.clone().subtract(0, 1, 0).getBlock().getType() != Material.WATER) continue;
            // Clockwise tangential motion; interpolation makes control gentle.
            double dx = loc.getX() - (park.centerX() + 0.5);
            double dz = loc.getZ() - (park.centerZ() + 0.5);
            double r = Math.max(1.0, Math.hypot(dx, dz));
            Vector old = player.getVelocity();
            player.setVelocity(new Vector(old.getX() * 0.7 - dz / r * speed * 0.3,
                    old.getY(), old.getZ() * 0.7 + dx / r * speed * 0.3));
        }
    }

    private boolean protectedBlock(org.bukkit.block.Block block) {
        return park != null && park.contains(block);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (protectedBlock(event.getBlock())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (protectedBlock(event.getBlockPlaced()) || protectedBlock(event.getBlockAgainst())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        if (protectedBlock(event.getBlockClicked())
                || protectedBlock(event.getBlockClicked().getRelative(event.getBlockFace()))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        if (protectedBlock(event.getBlockClicked())
                || protectedBlock(event.getBlockClicked().getRelative(event.getBlockFace()))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent event) {
        if (protectedBlock(event.getBlock())) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBurn(BlockBurnEvent event) {
        if (protectedBlock(event.getBlock())) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onFade(BlockFadeEvent event) {
        if (protectedBlock(event.getBlock())) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onFluid(BlockFromToEvent event) {
        if (protectedBlock(event.getBlock()) || protectedBlock(event.getToBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent event) {
        if (protectedBlock(event.getBlock())) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent event) {
        event.blockList().removeIf(this::protectedBlock);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        event.blockList().removeIf(this::protectedBlock);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (protectedBlock(event.getBlock())
                || event.getBlocks().stream().anyMatch(b -> protectedBlock(b)
                || protectedBlock(b.getRelative(event.getDirection())))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (protectedBlock(event.getBlock())
                || event.getBlocks().stream().anyMatch(b -> protectedBlock(b)
                || protectedBlock(b.getRelative(event.getDirection())))) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (player.isOnline()) giveTestCarts(player);
        }, 20L);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (player.isOnline()) giveTestCarts(player);
        }, 2L);
    }

    /** Replenish up to four minecarts; repeated logins never create unlimited stacks. */
    private void giveTestCarts(Player player) {
        if (!getConfig().getBoolean("give-test-minecarts", true)) return;
        int target = Math.max(0, Math.min(4, getConfig().getInt("test-minecarts", 4)));
        int has = 0;
        for (ItemStack stack : player.getInventory().getContents()) {
            if (stack != null && stack.getType() == Material.MINECART) has += stack.getAmount();
        }
        if (has >= target) return;
        Map<Integer, ItemStack> remaining = player.getInventory().addItem(
                new ItemStack(Material.MINECART, target - has));
        if (!remaining.isEmpty()) {
            player.sendMessage("§eThemePark: Free an inventory slot to receive your test minecarts.");
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "status" : args[0].toLowerCase();
        if (sub.equals("status")) {
            if (park == null) {
                sender.sendMessage("§eThemePark has not initialized yet.");
            } else {
                sender.sendMessage("§aThemePark: " + (building ? "generating" : "ready")
                        + " • 70x70 • world=" + park.world().getName()
                        + " • center=" + park.centerX() + "," + park.floorY() + "," + park.centerZ()
                        + " • night barrier=" + (Boolean.TRUE.equals(barrierActive) ? "ON" : "OFF"));
            }
            return true;
        }

        if (sub.equals("ride")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("Only a player can ride the coaster.");
                return true;
            }
            if (park == null || building) {
                player.sendMessage("§eThe roller coaster is not ready yet.");
                return true;
            }
            if (!park.world().equals(player.getWorld())) {
                player.sendMessage("§eEnter the ThemePark world first.");
                return true;
            }
            Location start = park.boardingPoint();
            Minecart cart = park.world().spawn(start, Minecart.class);
            cart.setMaxSpeed(0.7);
            cart.addPassenger(player);
            cart.setVelocity(new Vector(0.35, 0, 0));
            player.sendMessage("§aThemePark roller coaster: enjoy the ride!");
            return true;
        }

        if (sub.equals("rebuild")) {
            if (!sender.hasPermission("themepark.admin")) {
                sender.sendMessage("§cOnly an operator can rebuild the park.");
                return true;
            }
            if (args.length < 2 || !args[1].equalsIgnoreCase("confirm")) {
                sender.sendMessage("§cWARNING: This permanently flattens the park footprint."
                        + " Back up the world, then run /themepark rebuild confirm.");
                return true;
            }
            if (building) {
                sender.sendMessage("§ePark generation is already in progress.");
                return true;
            }
            if (park != null) {
                applyBarrier(false, true);
                beginBuild(park);
            } else {
                World world = Bukkit.getWorld(getConfig().getString("world", "world"));
                if (world == null) {
                    sender.sendMessage("§cConfigured world not loaded.");
                    return true;
                }
                Location spawn = world.getSpawnLocation();
                int floor = Math.max(world.getMinHeight() + 6, Math.min(
                        spawn.getBlockY() - 1, world.getMaxHeight() - 26));
                beginBuild(new ParkArea(world, spawn.getBlockX(), spawn.getBlockZ(), floor));
            }
            sender.sendMessage("§eThemePark rebuilding; existing terrain in the 70x70 footprint is replaced.");
            return true;
        }

        sender.sendMessage("§eUsage: /themepark [status|ride|rebuild confirm]");
        return true;
    }
}
