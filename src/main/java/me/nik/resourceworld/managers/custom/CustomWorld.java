package me.nik.resourceworld.managers.custom;

import me.nik.resourceworld.ResourceWorld;
import me.nik.resourceworld.api.ResourceWorldType;
import me.nik.resourceworld.files.Config;
import me.nik.resourceworld.managers.ChunkyHook;
import me.nik.resourceworld.managers.MsgType;
import me.nik.resourceworld.utils.ChatUtils;
import me.nik.resourceworld.utils.MiscUtils;
import me.nik.resourceworld.utils.TaskUtils;
import me.nik.resourceworld.utils.TarGzUtil;
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

    //TODO
    // 1. Pregen a world once it's loaded using chunky
    // 2. Detect chunky task is finished
    // 3. Package world folder into tar.gz
    // 4. Send over to other boxes
    public CustomWorld generate() {
        boolean lazyInit = Config.Setting.WORLD_USE_LAZY_INIT.getBoolean();
        NamespacedKey worldKey = new NamespacedKey("minecraft", this.name);
        boolean unpackFirst = Config.Setting.WORLD_UNPACK_FIRST.getBoolean();

        // Phase 1 = Unpack World First (if applicable)

        if (unpackFirst) {
            unpackWorldFirst();
            ChatUtils.log("Generation Phase 1 Complete...");
        } else {
            ChatUtils.log("Generation Phase 1 Skipped...");
        }

        // Phase 2 = Apply pre-generation settings.

        // Bukkit trick to import world folder.
        WorldCreator wc = lazyInit ? new WorldCreator(worldKey) : new WorldCreator(this.name);

        // configure world creator
        configurePreGeneration(wc, unpackFirst);

        // Check if the world is already loaded in memory
        World rw = lazyInit ? Bukkit.getWorld(worldKey) : Bukkit.getWorld(this.name);

        ChatUtils.log("Generation Phase 2 Complete...");

        // Skip Phase 3 if rw already exists
        if (rw != null) {
            ChatUtils.log("A world already exists with this name, skipping generation...");
            return configurePostGeneration(rw);
        }

        // Phase 3 = World Generation

        // Try to create the world
        try {
            rw = wc.createWorld();
        } catch (Exception e) {
            ChatUtils.log("Error while trying to create world: " + e.getMessage());
            e.printStackTrace();
        }

        ChatUtils.log("Generation Phase 3 Complete");

        // Phase 4 = Apply post-generation settings.

        CustomWorld customWorld = configurePostGeneration(rw);

        ChatUtils.log("Generation Phase 4 Complete");

        // Phase 5 = Optional pregeneration of chunks using Chunky
        if (Config.Setting.WORLD_PREGEN.getBoolean()) {
            World rwSnapshot = rw;
            // call async pregeneration task; method wont hang trust
            int renderDistancePadding = (16 * 10); // players generate chunks pass world border, this pregens past that range
            ResourceWorld.getInstance().getChunky().pregenerate(rw, 0, 0, (borderSize / 2) + renderDistancePadding, () -> {
                ChatUtils.log("Generation Phase 5 Complete");
                Bukkit.getScheduler().runTaskAsynchronously(ResourceWorld.getInstance(), () -> {
                    // start packaging if config true
                    if (Config.Setting.WORLD_PACKAGE_AFTER.getBoolean()) {
                        packageWorld(rwSnapshot);
                    }
                });
            });
        } else if(Config.Setting.WORLD_PACKAGE_AFTER.getBoolean()) {
            ChatUtils.log("Generation Phase 5 Skipped");
            packageWorld(rw);
        }

        return customWorld;
    }

    private void packageWorld(World rw) {
        Bukkit.unloadWorld(rw, true);

        Path worldFolder = rw.getWorldFolder().toPath();

        Path packagedPath = ResourceWorld.getInstance().getDataPath()
                .resolve("packaged")
                .resolve(this.name + ".tar.gz");

        ChatUtils.log("Packaging World...");
        try {
            TarGzUtil.zip(worldFolder, packagedPath);
        } catch (Exception e) {
            ChatUtils.log("Error while trying to pregenerate world: " + e.getMessage());
        }
    }

    private void unpackWorldFirst() {

        ChatUtils.log("Unpacking First Enabled...");
        // look up if there is a zipped world to use
        Path packagedPath = ResourceWorld.getInstance().getDataPath()
                .resolve("packaged")
                .resolve(this.name + ".tar.gz");

        // if so, unpack it
        if (packagedPath.toFile().exists()) {
            try {
                // create the destination
                Path destinationPath = Bukkit.getWorldContainer().toPath()
                        .resolve("world")
                        .resolve("dimensions")
                        .resolve("minecraft")
                        .toAbsolutePath()
                        .normalize();

                // Ensure parent directories exist before unpacking
                if (!Files.exists(destinationPath.getParent())) {
                    Files.createDirectories(destinationPath.getParent());
                }

                // unpack using zip util
                TarGzUtil.unzip(packagedPath, destinationPath);

                ChatUtils.log("Unzipped Packaged World at=" + destinationPath.toString());
            } catch (Exception e) {
                ChatUtils.log("An error occurred while unzipping packaged world.");
                e.printStackTrace();
            }
        } else {
            ChatUtils.log("Failed to find packaged world path=" + packagedPath.toString());
        }
        // if not, fallback to normal generation
    }

    /**
     * (Pre-generation)
     * Apply settings to the world creator based on config; skips certain stage if unpackFirst is true.
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
        // Grab config defined biome providing datapack and demision name.
        try {
            String datapack = Config.Setting.WORLD_DATAPACK_DATAPACK_NAME.getString();
            String dimensionDef = Config.Setting.WORLD_DATAPACK_DIMENSION_NAME.getString();
            if (datapack.isEmpty() || dimensionDef.isEmpty()) {
                ChatUtils.log("Invalid config for datapack or dimension, check config.");
            } else {
                // Inject custom datapack biome provider
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
     * Apply settings to the world based no the settings in config
     */
    private CustomWorld configurePostGeneration(World rw) {
        if (rw != null) {
            if (this.useBorder) {

                WorldBorder wb = rw.getWorldBorder();

                wb.setCenter(0, 0);

                wb.setSize(this.borderSize);
            }

            rw.setPVP(this.pvp);

            rw.setDifficulty(this.difficulty);

            if (!Bukkit.getWorlds().contains(rw)) {
                Bukkit.getWorlds().add(rw);
            }

            if (!MiscUtils.isLegacy() && this.keepInventory) {
                rw.setGameRule(GameRule.KEEP_INVENTORY, true);
            }
        } else {
            ChatUtils.log("Error while initializing world instance for " + this.name);
        }
        ChatUtils.log("World Config Applied...");
        return this;
    }

    public void reset() {

        ChatUtils.log("Executing world reset...");

        //Phase 1

        boolean lazyInit = Config.Setting.WORLD_USE_LAZY_INIT.getBoolean();

        // make sure we are grabbing the world key if we are lazy init
        World world = lazyInit ? Bukkit.getWorld(new NamespacedKey("minecraft", this.name)) : Bukkit.getWorld(this.name);

        if (world == null) return;

        Location spawnLocation = Bukkit.getWorld(Config.Setting.SETTINGS_SPAWN_WORLD.getString()).getSpawnLocation();

        switch (this.resourceWorldType) {
            case RESOURCE_WORLD:
                if (Config.Setting.WORLD_RESET_ON_STARTUP.getBoolean()) {
                    spawnLocation = MiscUtils.stringToLocation(ResourceWorld.getInstance().getData().getString("spawn_location"));
                }
                break;
            case RESOURCE_NETHER:
                if (Config.Setting.NETHER_RESET_ON_STARTUP.getBoolean()) {
                    spawnLocation = MiscUtils.stringToLocation(ResourceWorld.getInstance().getData().getString("spawn_location"));
                }
                break;
            case RESOURCE_END:
                if (Config.Setting.END_RESET_ON_STARTUP.getBoolean()) {
                    spawnLocation = MiscUtils.stringToLocation(ResourceWorld.getInstance().getData().getString("spawn_location"));
                }
                break;
        }

        ResourceWorld plugin = ResourceWorld.getInstance();

        Bukkit.getPluginManager().registerEvents(this, plugin);

        ChatUtils.log("Phase 1 completed...");

        //Phase 2
        TaskUtils.taskLater(() -> {

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

            Bukkit.unloadWorld(world, false);

            MiscUtils.deleteDirectory(world.getWorldFolder());

            ChatUtils.log("Phase 2 completed...");

            //Phase 3
            TaskUtils.taskLater(() -> {

                generate();

                ChatUtils.log("Phase 3 completed...");

                //Phase 4
                TaskUtils.taskLater(() -> {

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

                    HandlerList.unregisterAll(this);

                    ChatUtils.log("All phases completed!");

                }, 160L);

            }, 80L);

        }, 80L);
    }

    private void emptyWorld(World world, Location spawnLocation) {

        for (Player player : Bukkit.getOnlinePlayers()) {

            if (!player.getWorld().getUID().equals(world.getUID())) continue;

            player.teleport(spawnLocation);

            player.sendMessage(MsgType.TELEPORTED_MESSAGE.getMessage());
        }

    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onWorldTeleport(PlayerTeleportEvent e) {
        if (e.getTo().getWorld().getName().equals(this.name)) e.setCancelled(true);
    }
}