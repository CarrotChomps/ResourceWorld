package me.nik.resourceworld.commands.subcommands;

import me.nik.resourceworld.ResourceWorld;
import me.nik.resourceworld.api.ResourceWorldType;
import me.nik.resourceworld.commands.SubCommand;
import me.nik.resourceworld.managers.Permissions;
import me.nik.resourceworld.managers.custom.CustomWorld;
import me.nik.resourceworld.utils.ChatUtils;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.logging.Level;

public class Package extends SubCommand {

    private final ResourceWorld plugin;

    public Package(ResourceWorld plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getName() {
        return "package";
    }

    @Override
    public String getDescription() {
        return "Package the Resource World, with optional delivery";
    }

    @Override
    public String getSyntax() {
        return "/resource package";
    }

    @Override
    public String getPermission() {
        return Permissions.ADMIN.getPermission();
    }

    @Override
    protected int maxArguments() {
        return 1;
    }

    @Override
    public boolean canConsoleExecute() {
        return false;
    }

    @Override
    public void perform(CommandSender sender, String[] args) {

        // faster boolean check for if the delivery arg is used.
        boolean deliver = args.length == 2 && args[1].equalsIgnoreCase("deliver");

        if (args.length > 2 || (args.length == 2 && !deliver)) {
            sender.sendMessage("Usage: " + getSyntax());
            return;
        }

        //TODO add support for dimensions
        CustomWorld resourceWorld = plugin.getResourceWorld(ResourceWorldType.RESOURCE_WORLD);

        if (resourceWorld == null) {
            sender.sendMessage("Resource World hasn't been created yet.");
            return;
        }

        sender.sendMessage(deliver ? "Requested Resource World packaging and delivery." : "Requested Resource World packaging.");

        resourceWorld.packageWorld(deliver).whenComplete((archive, error) -> {
            if (error != null) {
                ChatUtils.log("Resource World package command failed=" + error);
            }

            if (error == null) {
                sender.sendMessage(deliver ? "Resource World packaged and delivered." : "Resource World packaged.");
                return;
            }

            sender.sendMessage("Resource World operation failed. Check the server log.");
        });
    }

    @Override
    public List<String> getSubcommandArguments(CommandSender sender, String[] args) {
        List<String> subArgs = new ArrayList<>();
        subArgs.add("deliver");
        return subArgs;
    }
}