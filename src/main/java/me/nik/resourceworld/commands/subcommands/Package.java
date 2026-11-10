package me.nik.resourceworld.commands.subcommands;

import me.nik.resourceworld.ResourceWorld;
import me.nik.resourceworld.api.ResourceWorldType;
import me.nik.resourceworld.commands.SubCommand;
import me.nik.resourceworld.managers.Permissions;
import me.nik.resourceworld.managers.custom.CustomWorld;
import me.nik.resourceworld.utils.WorldDeliveryService;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

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
        return "Package the current resource world you are standing in";
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
        if(sender instanceof Player ){
            Player player = (Player) sender;

            //TODO only sends overworld rw atm
            CustomWorld resourceWorld = ResourceWorld.getInstance().getResourceWorld(ResourceWorldType.RESOURCE_WORLD);

            if (resourceWorld != null) {
                if(resourceWorld.world() != null) {

                    resourceWorld.packageWorld();

                    player.sendMessage("Resource World packaged!");
                } else {
                    player.sendMessage("Resource World isn't loaded...");
                }

                // delivery arg
                if(args.length == 2) {

                    if(args[1].equalsIgnoreCase("deliver")) {
                        player.sendMessage("Delivering Packaged World...");
                        Path source = resourceWorld.getPackagePath();
                        if(source == null) {
                            player.sendMessage("Resource World package not found...");
                            return;
                        }
                        WorldDeliveryService.deliverPackagedWorld(source);
                    }
                }
            } else {
                player.sendMessage("Resource World hasn't been created yet...");
            }
        }
    }

    @Override
    public List<String> getSubcommandArguments(CommandSender sender, String[] args) {
        List<String> subArgs = new ArrayList<>();
        subArgs.add("deliver");
        return subArgs;
    }
}