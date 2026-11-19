package me.nik.resourceworld.tasks;

import me.nik.resourceworld.ResourceWorld;
import me.nik.resourceworld.api.ResourceWorldType;
import me.nik.resourceworld.managers.custom.CustomWorld;
import me.nik.resourceworld.managers.custom.ResourceWorldException;
import me.nik.resourceworld.utils.ChatUtils;
import org.bukkit.scheduler.BukkitRunnable;

public class ResetWorld extends BukkitRunnable {

    private final ResourceWorld plugin;

    public ResetWorld(ResourceWorld plugin) {
        this.plugin = plugin;
    }

    @Override
    public void run() {
        CustomWorld rw = this.plugin.getResourceWorld(ResourceWorldType.RESOURCE_WORLD);
        if (rw != null) {
            rw.reset();
        } else {
            throw new ResourceWorldException("Resource World failed to load for reset task!");
        }
    }
}