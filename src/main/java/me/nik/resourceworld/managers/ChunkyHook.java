package me.nik.resourceworld.managers;

import me.nik.resourceworld.ResourceWorld;
import me.nik.resourceworld.utils.ChatUtils;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.popcraft.chunky.api.ChunkyAPI;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Simple Hook listening to the Chunky API
 * <p>
 * <a href="https://github.com/pop4959/Chunky/wiki/Developer-API">...</a>
 * </p>
 */
public class ChunkyHook {

    private boolean isLoaded = false;

    private ChunkyAPI chunky = null;

    // lock which worlds are pregenerating to prevent watch dog crashes
    private final ConcurrentMap<String, Runnable> pendingCompletions = new ConcurrentHashMap<>();

    public ChunkyHook() {}

    public void verifyChunky() {
        try {
            if (isLoaded) {
                ChatUtils.log("Successfully hooked into Chunky API!");
            }

            ChunkyAPI api = Bukkit.getServicesManager().load(ChunkyAPI.class);
            if (api == null || api.version() != 0) {
                return;
            }

            // lock the generation session to prevent resets from making watch dog mad
            api.onGenerationComplete(event -> {
                Runnable onComplete = pendingCompletions.remove(event.world());
                if (onComplete != null) {
                    ChatUtils.log("Generation completed for " + event.world());
                    onComplete.run();
                }
            });

            chunky = api;
            isLoaded = true;
        } catch (Exception e) {
            ChatUtils.log("An exception occurred when verifying chunky...");
        }
    }

    public void pregenerate(World world, int centerX, int centerY, int radius, Runnable onComplete) {
        // guarded class design pattern
        if (world == null) {
            ChatUtils.log("World is null!");
            return;
        }

        if(!isLoaded || chunky == null) {
            ChatUtils.log("Skipping pregeneration: Chunky API is not available.");
            return;
        }

        // pregen session lock logic
        String worldName = world.getName();
        if (pendingCompletions.putIfAbsent(worldName, onComplete) != null) {
            ChatUtils.log("Already waiting for Chunky to finish " + worldName);
            return;
        }

        boolean started = false;
        try {
            started = chunky.startTask(worldName, "square", centerX, centerY, radius, radius, "concentric");
            if (!started) {
                ChatUtils.log("Chunky declined to start pregeneration for " + worldName);
            }
        } finally {
            if (!started) {
                pendingCompletions.remove(worldName, onComplete);
            }
        }
    }

    public boolean isLoaded() {
        return isLoaded;
    }
}
