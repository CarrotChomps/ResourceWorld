
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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;

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
    private boolean resetting;
    private World pendingAutomaticPackage;

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
        if (resetting) {
            throw new IllegalStateException("Cannot generate while resetting world: " + this.name);
        }
        return generateWorld();
    }

    private CustomWorld generateWorld() {

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
            ChatUtils.log("Error while trying to create world: " + e.getMessage());
            throw new IllegalStateException("Failed to load world: " + this.name, e);
        }

        if (rw == null) {
            throw new IllegalStateException("WorldCreator returned null for " + this.name);
        }

        ChatUtils.log("Generation Phase 3 Complete");

        // Phase 4 = Apply post-generation settings.

        CustomWorld customWorld = configurePostGeneration(rw);

        ChatUtils.log("Generation Phase 4 Complete");

        World generatedWorld = rw;

        // Phase 5 = Optional pregeneration of chunks using Chunky

        if (Config.Setting.WORLD_PREGEN.getBoolean()) {

            int renderDistancePadding = (16 * 10); // players generate chunks past world border, this pregens past that range

            ResourceWorld.getInstance().getChunky().pregenerate(rw, 0, 0, (borderSize / 2) + renderDistancePadding, () -> {

                ChatUtils.log("Generation Phase 5 Complete");

                // Allow pending chunk generation tasks to settle before unloading.
                Bukkit.getScheduler().runTaskLater(ResourceWorld.getInstance(), () -> {

                    if (Config.Setting.WORLD_PACKAGE_AFTER.getBoolean()) {
                        packageGeneratedWorld(generatedWorld);
                    }

                }, 20L * 10);
            });

        } else if (Config.Setting.WORLD_PACKAGE_AFTER.getBoolean()) {

            ChatUtils.log("Generation Phase 5 Skipped");
            packageGeneratedWorld(generatedWorld);
        }

        return customWorld;
    }

    public void packageWorld() {
        packageWorld(Config.Setting.WORLD_DELIVERY_ENABLED.getBoolean())
                .whenComplete((archive, error) -> {
                    if (error != null) {
                        ChatUtils.log("Automatic world packaging failed=" + error.getMessage());
                    }
                });
    }

    private void packageGeneratedWorld(World generatedWorld) {
        if (world() != generatedWorld) {
            return;
        }
        if (resetting) {
            pendingAutomaticPackage = generatedWorld;
            return;
        }
        packageWorld();
    }

    public CompletableFuture<Path> packageWorld(boolean deliver) {
        CompletableFuture<Path> result = new CompletableFuture<>();

        ResourceWorld plugin = ResourceWorld.getInstance();

        Executor serverThread = task -> Bukkit.getScheduler().runTask(plugin, task);

        try {
            if (Bukkit.isPrimaryThread()) {
                queuePackaging(deliver, result, serverThread);
            } else {
                serverThread.execute(() -> queuePackaging(deliver, result, serverThread));
            }
        } catch (RuntimeException e) {
            result.completeExceptionally(e);
        }
        return result;
    }

    private void queuePackaging(boolean deliver, CompletableFuture<Path> result, Executor serverThread) {
        if (packaging || resetting) {
            result.completeExceptionally(new IllegalStateException(
                    "World " + (packaging ? "packaging" : "reset") + " is already in progress."));
            return;
        }

        try {
            World rw = world();
            if (rw == null) {
                result.completeExceptionally(new IllegalStateException("Resource World is not loaded."));
                return;
            }

            // Reserve the world now, but unload on the next server tick.
            packaging = true;
            serverThread.execute(() -> startPackaging(rw, deliver, result, serverThread));
        } catch (RuntimeException e) {
            packaging = false;
            result.completeExceptionally(e);
        }
    }

    private void startPackaging(World rw, boolean deliver, CompletableFuture<Path> result, Executor serverThread) {
        if (Bukkit.getWorld(rw.getUID()) != rw) {
            packaging = false;
            result.completeExceptionally(new IllegalStateException("Resource World changed before packaging could start."));
            return;
        }

        try {
            Path archive = getPackagePath().toAbsolutePath().normalize();
            Path worldFolder = rw.getWorldFolder().toPath().toAbsolutePath().normalize();
            unloadWorld(rw, true);

            ResourceWorld plugin = ResourceWorld.getInstance();
            CompletableFuture.runAsync(() -> {
                        try {
                            writeArchive(worldFolder, archive);
                        } catch (IOException | RuntimeException e) {
                            throw new CompletionException("Could not create the world archive.", e);
                        }
                    }, task -> Bukkit.getScheduler().runTaskAsynchronously(plugin, task))
                    .thenComposeAsync(ignored -> {
                        ChatUtils.log("World packaged successfully: " + archive);
                        if (!deliver) {
                            return CompletableFuture.completedFuture(null);
                        }
                        return WorldDeliveryService.deliverRequestedArchive(archive)
                                .handle((deliveryResult, error) -> {
                                    if (error != null) {
                                        throw new CompletionException("Archive created, but delivery failed: "
                                                + error.getMessage(), error);
                                    }
                                    return null;
                                });
                    }, serverThread)
                    .handleAsync((ignored, error) -> {
                        if (deliver && error == null) {
                            ChatUtils.log("World delivery completed successfully.");
                        }
                        finishPackaging(result, archive, error);
                        return null;
                    }, serverThread)
                    .exceptionally(error -> {
                        // The scheduler can reject the reload during plugin shutdown.
                        result.completeExceptionally(new IllegalStateException(
                                "Could not schedule the world reload. Check the server log.", error));
                        return null;
                    });
        } catch (RuntimeException e) {
            finishPackaging(result, null, e);
        }
    }

    private void writeArchive(Path worldFolder, Path archive) throws IOException {
        Files.createDirectories(archive.getParent());
        Path temporary = Files.createTempFile(archive.getParent(), archive.getFileName().toString(), ".tmp");
        try {
            TarGzUtil.zip(worldFolder, temporary);
            Files.move(temporary, archive, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private void finishPackaging(CompletableFuture<Path> result, Path archive, Throwable error) {
        try {
            loadWorld();
            ChatUtils.log("World available after packaging: " + this.name);
        } catch (RuntimeException reloadError) {
            IllegalStateException failure = new IllegalStateException((error == null ? "Archive created. " : error.getMessage() + " ") + "World reload failed: " + reloadError.getMessage(), reloadError);
            if (error != null) {
                failure.addSuppressed(error);
            }
            error = failure;
        } finally {
            packaging = false;
        }

        if (error == null) {
            result.complete(archive);
        } else {
            result.completeExceptionally(error);
        }
    }

    private void unpackWorldFirst() {

        ChatUtils.log("Unpacking First Enabled...");

        // Look up if there is a packaged world to use.
        Path packagedPath = getPackagePath();

        if (!Files.isRegularFile(packagedPath)) {
            throw new IllegalStateException("Failed to find packaged world path=" + packagedPath);
        }

        try {

            // Create the destination so zip() can write a containing directory.
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
            return Bukkit.getWorlds().get(0).getWorldFolder().toPath()
                    .resolve("dimensions")
                    .resolve(key().getNamespace())
                    .resolve(key().getKey())
                    .toAbsolutePath().normalize();
        }
        return Bukkit.getWorldContainer().toPath().resolve(this.name).toAbsolutePath().normalize();
    }

    /**
     * (Pre-generation)
     * Apply settings to the world creator based on config;
     * skips biome provider setup if an archive was unpacked.
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

        if (packaging || resetting) {
            ChatUtils.log("A world operation is already in progress: " + this.name);
            return;
        }

        ChatUtils.log("Executing world reset...");

        // Reset Phase 1 = Event Registration

        World rw = world();

        if (rw == null) return;

        ResourceWorld plugin = ResourceWorld.getInstance();

        // Register listener before the reset starts.
        resetting = true;
        try {
            Bukkit.getPluginManager().registerEvents(this, plugin);
        } catch (RuntimeException e) {
            finishReset();
            throw e;
        }

        ChatUtils.log("Phase 1 completed...");

        // Reset Phase 2 = World Deletion
        scheduleResetPhase(() -> {

            // Ensure the world still exists before proceeding.
            World currentWorld = world();

            if (currentWorld == null) {
                ChatUtils.log("Cannot reset while packaging world: " + this.name);
                finishReset();
                return;
            }

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

                // Do not regenerate over an undeleted world.
                if (Files.exists(worldFolder)) {
                    throw new IllegalStateException("Failed to delete world folder: " + worldFolder);
                }

                ChatUtils.log("Reset Phase 2 completed...");

            } catch (Exception e) {

                ChatUtils.log("World reset failed: " + e.getMessage());
                e.printStackTrace();

                finishReset();
                return;
            }

            // Reset Phase 3 = Re-generation
            scheduleResetPhase(() -> {

                try {

                    generateWorld();

                    ChatUtils.log("Reset Phase 3 completed...");

                } catch (Exception e) {

                    ChatUtils.log("Failed to regenerate world: " + e.getMessage());
                    e.printStackTrace();

                    finishReset();
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
                        finishReset();
                    }

                }, 160L);

            }, 80L);

        }, 80L);
    }

    private void scheduleResetPhase(Runnable phase, long delay) {
        try {
            TaskUtils.taskLater(() -> {
                try {
                    phase.run();
                } catch (RuntimeException e) {
                    finishReset();
                    throw e;
                }
            }, delay);
        } catch (RuntimeException e) {
            finishReset();
            throw e;
        }
    }

    private void finishReset() {
        HandlerList.unregisterAll(this);
        resetting = false;
        World generatedWorld = pendingAutomaticPackage;
        pendingAutomaticPackage = null;
        if (generatedWorld != null) {
            packageGeneratedWorld(generatedWorld);
        }
    }

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

        if (e.getTo() == null || e.getTo().getWorld() == null) return;

        World arrival =  e.getTo().getWorld();

        if (arrival.getKey().equals(key()) || arrival.getName().equals(this.name)) {
            e.setCancelled(true);
        }
    }
}
