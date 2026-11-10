package me.nik.resourceworld.managers.custom;

import me.nik.resourceworld.ResourceWorld;
import me.nik.resourceworld.api.ResourceWorldType;
import me.nik.resourceworld.files.Config;
import me.nik.resourceworld.managers.MsgType;
import me.nik.resourceworld.utils.*;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.nio.file.Files;
import java.nio.file.Path;

@SuppressWarnings("all")
public class CustomWorld implements Listener {

    private final ResourceWorldType resourceWorldType;
    private final String name;
    private final Difficulty difficulty;
    private final WorldType type;
    private final World.Environment environment;
    private final boolean structures;
    private final boolean useSeed;
    private final long seed;
    private final boolean useBorder;
    private final int borderSize;
    private final boolean pvp;
    private final boolean keepInventory;

    // runtime variables to help track generation
    private Path path;
    private WorldCreator creator;
    private boolean packaging;

    public CustomWorld(String name, Difficulty difficulty, WorldType type, World.Environment environment, boolean structures, boolean useSeed, long seed, boolean useBorder, int borderSize, boolean pvp, boolean keepInventory, ResourceWorldType resourceWorldType) {
        this.name = name;
        this.difficulty = difficulty;
        this.type = type;
        this.environment = environment;
        this.structures = structures;
        this.useSeed = useSeed;
        this.seed = seed;
        this.useBorder = useBorder;
        this.borderSize = borderSize;
        this.pvp = pvp;
        this.keepInventory = keepInventory;
        this.resourceWorldType = resourceWorldType;
    }

    public String getName() {
        return name;
    }

    public CustomWorld generate() {

        // we have a mannual package command, so this can happen.
        if (packaging) {
            throw new IllegalStateException("Cannot generate while packaging world: " + this.name);
        }

        boolean lazyInit = Config.Setting.WORLD_USE_LAZY_INIT.getBoolean();
        boolean unpackFirst = Config.Setting.WORLD_UNPACK_FIRST.getBoolean();
        boolean hasPackage = unpackFirst && Files.isRegularFile(getPackagePath()); // make sure the package exists before unpacking

        World rw = world();

        // Phase 1 = Unpack World First (if applicable)

        if (hasPackage) {

            // Unload the existing world before replacing its files.
            if (rw != null) {
                ChatUtils.log("Unloading existing world before unpacking...");

                unloadWorld(rw, false);
                rw = null;
            }

            unpackWorldFirst();

            ChatUtils.log("Generation Phase 1 Complete...");

        } else {
            ChatUtils.log("Generation Phase 1 Skipped...");
        }

        // Reuse the existing world if no new archive was unpacked.
        if (rw != null) {
            ChatUtils.log("World already loaded, skipping generation...");
            return configurePostGeneration(rw);
        }

        // Phase 2 = Apply pre-generation settings.

        WorldCreator wc = lazyInit ? WorldCreator.ofKey(key()) : new WorldCreator(this.name);

        configurePreGeneration(wc, hasPackage);

        ChatUtils.log("Generation Phase 2 Complete...");

        // Phase 3 = World Generation

        try {
            rw = wc.createWorld();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to create world: " + this.name, e);
        }

        ChatUtils.log("Generation Phase 3 Complete");

        // Phase 4 = Apply post-generation settings.

        CustomWorld customWorld = configurePostGeneration(rw);

        ChatUtils.log("Generation Phase 4 Complete");

        // Phase 5 = Optional pregeneration of chunks using Chunky

        if (Config.Setting.WORLD_PREGEN.getBoolean()) {

            int renderDistancePadding = (16 * 10); // players generate chunks past world border, this pregens past that range

            ResourceWorld.getInstance().getChunky().pregenerate(rw, 0, 0, (borderSize / 2) + renderDistancePadding, () -> {

                ChatUtils.log("Generation Phase 5 Complete");

                // Allow pending chunk generation tasks to settle before unloading.
                Bukkit.getScheduler().runTaskLater(ResourceWorld.getInstance(), () -> {

                    if (Config.Setting.WORLD_PACKAGE_AFTER.getBoolean()) {
                        packageWorld();
                    }

                }, 20L * 10); // TODO make delay and the padding configurable
            });

        } else if (Config.Setting.WORLD_PACKAGE_AFTER.getBoolean()) {

            ChatUtils.log("Generation Phase 5 Skipped");
            packageWorld();
        }

        return customWorld;
    }

    public void packageWorld() {

        Bukkit.getScheduler().runTask(ResourceWorld.getInstance(), () -> {

            if (packaging) {
                ChatUtils.log("World packaging is already in progress: " + this.name);
                return;
            }

            World rw = world();

            if (rw == null) {
                ChatUtils.log("Cannot package world because it is not loaded.");
                return;
            }

            Path worldFolder = rw.getWorldFolder().toPath().toAbsolutePath().normalize();

            Path packagedPath = getPackagePath();

            try {
                // Empty world, save chunks and close world storage.
                unloadWorld(rw, true);
                packaging = true;

            } catch (Exception e) {
                ChatUtils.log("Failed to unload world before packaging: " + e.getMessage());
                return;
            }

            try {
                Bukkit.getScheduler().runTaskAsynchronously(ResourceWorld.getInstance(), () -> {

                    try {

                        ChatUtils.log("Packaging World...");

                        TarGzUtil.zip(worldFolder, packagedPath);

                        ChatUtils.log("World packaged successfully.");

                        // Check config if we are delivering.
                        if (Config.Setting.WORLD_DELIVERY_ENABLED.getBoolean()) {

                            // Call delivery and then any code that needs to follow.
                            WorldDeliveryService.deliverPackagedWorld(packagedPath).whenComplete((result, error) -> {
                                if (error != null) {
                                    ChatUtils.log("World delivery failed: " + error.getMessage());
                                } else {
                                    ChatUtils.log("World delivery completed successfully.");
                                }
                                finishPackaging(); // hey look, code that needs to follow!
                            });
                        } else {
                            finishPackaging();
                        }

                    } catch (Exception e) {
                        ChatUtils.log("Error while packaging world: " + e.getMessage());
                        e.printStackTrace();
                        // still end packaging
                        finishPackaging();
                    }
                });
            } catch (Exception e) {
                // Scheduling can fail after the world has already been unloaded.
                finishPackaging();
                throw e;
            }
        });
    }

    private void finishPackaging() {
        Bukkit.getScheduler().runTask(ResourceWorld.getInstance(), () -> {
            try {
                loadWorld();
                ChatUtils.log("World reloaded after packaging: " + this.name);
            } catch (Exception e) {
                ChatUtils.log("Failed to reload world after packaging: " + e.getMessage());
                e.printStackTrace();
            } finally {
                packaging = false;
            }
        });
    }

    private void unpackWorldFirst() {

        ChatUtils.log("Unpacking First Enabled...");

        // Look up if there is a packaged world to use.
        Path packagedPath = getPackagePath();

        if (!Files.isRegularFile(packagedPath)) {
            throw new IllegalStateException("Failed to find packaged world path=" + packagedPath);
        }

        try {
            // Create the destination.
            Path destinationPath = existingWorldFolder();

            // Ensure destination directories exist before unpacking.
            Files.createDirectories(destinationPath);

            // Unpack using tar.gz util.
            TarGzUtil.unzip(packagedPath, destinationPath);

            ChatUtils.log("Unzipped Packaged World at=" + destinationPath);

        } catch (Exception e) {

            ChatUtils.log("An error occurred while unzipping packaged world.");
            throw new IllegalStateException("Failed to unpack world: " + this.name, e);
        }
    }

    private Path existingWorldFolder() {
        if (path != null) return path;

        if (Config.Setting.WORLD_USE_LAZY_INIT.getBoolean()) {

            if (Bukkit.getWorlds().isEmpty()) {
                throw new IllegalStateException("No owning world loaded for dimension: " + key());
            }

            // proper world folder look up
            return Bukkit.getWorldContainer().toPath().resolve("dimensions").resolve(key().getNamespace()).resolve(key().getKey()).toAbsolutePath().normalize();
        }
        return Bukkit.getWorldContainer().toPath().resolve(this.name).toAbsolutePath().normalize();
    }

    /**
     * (Pre-generation)
     * Apply settings to the world creator based on config;
     * skips biome provider setup if an archive was unpacked.
     *
     * @param wc          The World Creator loading in the Resource World
     * @param unpackFirst Whether this was called with the world already unpacked. (no pre-generation needed)
     */
    private void configurePreGeneration(WorldCreator wc, boolean unpackFirst) {

        wc.type(this.type);

        wc.generateStructures(this.structures);

        wc.environment(this.environment);

        if (this.useSeed) wc.seed(this.seed);

        if (unpackFirst) {
            // skip generating world if prepacked
            ChatUtils.log("Placing packaged world...");
            return;
        }

        // Grab config defined biome providing datapack and dimension name.

        try {

            String datapack = Config.Setting.WORLD_DATAPACK_DATAPACK_NAME.getString();
            String dimensionDef = Config.Setting.WORLD_DATAPACK_DIMENSION_NAME.getString();

            if (datapack.isEmpty() || dimensionDef.isEmpty()) {

                ChatUtils.log("Invalid config for datapack or dimension, check config.");

            } else {

                // Inject custom datapack biome provider.
                wc.biomeProvider(new DatapackBiomeProvider(datapack, dimensionDef));

                ChatUtils.log("Datapack Biomes added...");
            }

        } catch (Exception e) {

            ChatUtils.log("Error while trying to configure biome provider: " + e.getMessage());
            e.printStackTrace();
        }

        ChatUtils.log("World Creator Settings Applied...");
    }

    /**
     * (Post generation)
     * Apply settings to the world based on the settings in config.
     */
    private CustomWorld configurePostGeneration(World rw) {

        if (rw == null) {
            throw new IllegalStateException("Error while initializing world instance for " + this.name);
        }

        if (this.useBorder) {

            WorldBorder wb = rw.getWorldBorder();

            wb.setCenter(0, 0);

            wb.setSize(this.borderSize);
        }

        rw.setPVP(this.pvp);

        rw.setDifficulty(this.difficulty);

        if (!MiscUtils.isLegacy() && this.keepInventory) {
            rw.setGameRule(GameRule.KEEP_INVENTORY, true);
        }

        ChatUtils.log("World Config Applied...");

        return this;
    }

    public void reset() {

        if (packaging) {
            ChatUtils.log("Cannot reset while packaging world: " + this.name);
            return;
        }

        ChatUtils.log("Executing world reset...");

        // Reset Phase 1 = Event Registration

        World rw = world();

        if (rw == null) return;

        ResourceWorld plugin = ResourceWorld.getInstance();

        // Register listener before the reset starts.
        Bukkit.getPluginManager().registerEvents(this, plugin);

        ChatUtils.log("Reset Phase 1 completed...");

        // Reset Phase 2 = World Deletion
        TaskUtils.taskLater(() -> {

            // Ensure the world still exists before proceeding.
            World currentWorld = world();

            try {

                switch (this.resourceWorldType) {

                    case RESOURCE_END:

                        Bukkit.broadcastMessage(MsgType.RESETTING_THE_END.getMessage());

                        if (Config.Setting.END_STORE_TIME.getBoolean()) {
                            plugin.getData().set("end.millis", System.currentTimeMillis());
                        }

                        plugin.getData().set("end.papi", System.currentTimeMillis());

                        break;

                    case RESOURCE_WORLD:

                        Bukkit.broadcastMessage(MsgType.RESETTING_THE_WORLD.getMessage());

                        if (Config.Setting.WORLD_STORE_TIME.getBoolean()) {
                            plugin.getData().set("world.millis", System.currentTimeMillis());
                        }

                        plugin.getData().set("world.papi", System.currentTimeMillis());

                        break;

                    case RESOURCE_NETHER:

                        Bukkit.broadcastMessage(MsgType.RESETTING_THE_NETHER.getMessage());

                        if (Config.Setting.NETHER_STORE_TIME.getBoolean()) {
                            plugin.getData().set("nether.millis", System.currentTimeMillis());
                        }

                        plugin.getData().set("nether.papi", System.currentTimeMillis());

                        break;
                }

                plugin.saveData();
                plugin.reloadData();
                plugin.saveData();

                // Capture world folder before unloading.
                Path worldFolder = currentWorld.getWorldFolder().toPath();

                // Empty world and unload before deleting files.
                unloadWorld(currentWorld, false);
                // Delete world folder.
                MiscUtils.deleteDirectory(worldFolder.toFile());

                ChatUtils.log("Reset Phase 2 completed...");

            } catch (Exception e) {

                ChatUtils.log("World reset failed: " + e.getMessage());
                e.printStackTrace();

                HandlerList.unregisterAll(this);
                return;
            }

            // Reset Phase 3 = Re-generation
            TaskUtils.taskLater(() -> {

                try {

                    generate();

                    ChatUtils.log("Reset Phase 3 completed...");

                } catch (Exception e) {

                    ChatUtils.log("Failed to regenerate world: " + e.getMessage());
                    e.printStackTrace();

                    HandlerList.unregisterAll(this);
                    return;
                }

                // Reset Phase 4 = Post Reset Handling
                TaskUtils.taskLater(() -> {

                    try {
                        switch (this.resourceWorldType) {

                            case RESOURCE_END:

                                if (Config.Setting.END_COMMANDS_ENABLED.getBoolean()) {

                                    for (String cmd : Config.Setting.END_COMMANDS_COMMANDS.getStringList()) {
                                        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
                                    }
                                }

                                Bukkit.broadcastMessage(MsgType.END_HAS_BEEN_RESET.getMessage());

                                break;

                            case RESOURCE_WORLD:

                                if (Config.Setting.WORLD_COMMANDS_ENABLED.getBoolean()) {

                                    for (String cmd : Config.Setting.WORLD_COMMANDS_COMMANDS.getStringList()) {
                                        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
                                    }
                                }

                                Bukkit.broadcastMessage(MsgType.WORLD_HAS_BEEN_RESET.getMessage());

                                break;

                            case RESOURCE_NETHER:

                                if (Config.Setting.NETHER_COMMANDS_ENABLED.getBoolean()) {

                                    for (String cmd : Config.Setting.NETHER_COMMANDS_COMMANDS.getStringList()) {
                                        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
                                    }
                                }

                                Bukkit.broadcastMessage(MsgType.NETHER_HAS_BEEN_RESET.getMessage());

                                break;
                        }

                        ChatUtils.log("All Reset phases completed!");
                    } finally {
                        HandlerList.unregisterAll(this);
                    }

                }, 160L);

            }, 80L);

        }, 80L);
    }

    /**
     * Attempts to load the world, if it already exists.
     */
    public World loadWorld() {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("World loading must run on the server thread.");
        }
        World loaded = world();

        if (loaded != null) {
            return loaded;
        }
        if (path == null || creator == null) {
            throw new IllegalStateException("No existing world: " + this.name);
        }

        World rw = creator.createWorld();

        if (rw == null) {
            throw new IllegalStateException("Failed to load world: " + this.name);
        }

        configurePostGeneration(rw);

        return rw;
    }

    /**
     * Safely unload a world before replacing, deleting or packaging its files.
     */
    private void unloadWorld(World rw, boolean save) {

        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("World unloading must run on the server thread.");
        }

        if (rw == null) {
            throw new IllegalStateException("Cannot unload a null world.");
        }

        if (Bukkit.getWorld(rw.getUID()) == null) {
            return;
        }

        path = rw.getWorldFolder().toPath().toAbsolutePath().normalize();

        creator = (Config.Setting.WORLD_USE_LAZY_INIT.getBoolean() ? WorldCreator.ofKey(rw.getKey()) : new WorldCreator(rw.getName())).copy(rw);

        // Move players out before attempting to unload.
        World spawnWorld = Bukkit.getWorld(Config.Setting.SETTINGS_SPAWN_WORLD.getString());

        if (spawnWorld == null) {
            throw new IllegalStateException("Spawn world is not loaded.");
        }

        if (spawnWorld.getUID().equals(rw.getUID())) {
            throw new IllegalStateException("Cannot return players into the world being unloaded.");
        }

        Location spawnLocation = getResetSpawnLocation(spawnWorld);

        emptyWorld(rw, spawnLocation);

        // Verify no players remain in the world.
        if (!rw.getPlayers().isEmpty()) {
            throw new IllegalStateException("Players remain in world: " + rw.getName());
        }

        // Unload the world, saving only when requested.
        if (!Bukkit.unloadWorld(rw, save)) {
            throw new IllegalStateException("Failed to unload world: " + rw.getName());
        }

        ChatUtils.log("Successfully unloaded world: " + rw.getName());
    }

    /**
     * Resolve the configured evacuation destination.
     */
    private Location getResetSpawnLocation(World spawnWorld) {

        Location spawnLocation = spawnWorld.getSpawnLocation();

        boolean useStoredSpawn = false;

        switch (this.resourceWorldType) {

            case RESOURCE_WORLD:
                useStoredSpawn = Config.Setting.WORLD_RESET_ON_STARTUP.getBoolean();
                break;

            case RESOURCE_NETHER:
                useStoredSpawn = Config.Setting.NETHER_RESET_ON_STARTUP.getBoolean();
                break;

            case RESOURCE_END:
                useStoredSpawn = Config.Setting.END_RESET_ON_STARTUP.getBoolean();
                break;
        }

        if (useStoredSpawn) {

            Location storedLocation = MiscUtils.stringToLocation(ResourceWorld.getInstance().getData().getString("spawn_location"));

            if (storedLocation != null && storedLocation.getWorld() != null) {

                if (!storedLocation.getWorld().getUID().equals(world().getUID())) {
                    spawnLocation = storedLocation;
                }
            }
        }

        return spawnLocation;
    }

    private void emptyWorld(World world, Location spawnLocation) {

        if (spawnLocation == null || spawnLocation.getWorld() == null) {
            throw new IllegalStateException("Invalid return location.");
        }

        for (Player player : world.getPlayers()) {

            if (!player.teleport(spawnLocation)) {
                throw new IllegalStateException("Failed to return player: " + player.getName());
            }

            player.sendMessage(MsgType.TELEPORTED_MESSAGE.getMessage());
        }
    }

    public Path getPackagePath() {
        return ResourceWorld.getInstance().getDataPath().resolve("packaged").resolve(this.name + ".tar.gz");
    }

    public NamespacedKey key() {
        return NamespacedKey.minecraft(this.name);
    }

    // world helper method.
    public World world() {
        boolean lazyInit = Config.Setting.WORLD_USE_LAZY_INIT.getBoolean();

        // Make sure we are grabbing the world key if we are lazy init.
        return lazyInit ? Bukkit.getWorld(key()) : Bukkit.getWorld(this.name);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onWorldTeleport(PlayerTeleportEvent e) {
        if (e.getTo().getWorld().getName().equals(this.name)) e.setCancelled(true);
    }
}