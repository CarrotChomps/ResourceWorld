package me.nik.resourceworld.managers;

import me.nik.resourceworld.ResourceWorld;
import me.nik.resourceworld.utils.ChatUtils;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.popcraft.chunky.api.ChunkyAPI;

/**
 * Simple Hook listening to the Chunky API
 * <p>
 * <a href="https://github.com/pop4959/Chunky/wiki/Developer-API">...</a>
 * </p>
 */
public class ChunkyHook {

    private final ResourceWorld plugin;

    private boolean isLoaded = false;

    private ChunkyAPI chunky = null;

    public ChunkyHook(ResourceWorld plugin) {
        this.plugin = plugin;
    }

    public void verifyChunky() {
        try {
            this.chunky = Bukkit.getServicesManager().load(ChunkyAPI.class);
            this.isLoaded = (this.chunky != null);

            if (isLoaded) {
                ChatUtils.log("Successfully hooked into Chunky API!");
            }
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

        if (!isLoaded || chunky == null) {
            ChatUtils.log("Skipping pregeneration: Chunky API is not available.");
            return;
        }

        if (chunky.version() == 0) {
            chunky.startTask(world.getName(), "square", centerX, centerY, radius, radius, "concentric");
            chunky.onGenerationComplete(event -> {
                ChatUtils.log("Generation completed for " + event.world()); // would be nice to have time to generate...
                // check if config has package after pregenerate.
                if(true) {
                    // packaging process.
                    onComplete.run();
                }
            });
        }
    }

    public boolean isLoaded() {
        return isLoaded;
    }
}
