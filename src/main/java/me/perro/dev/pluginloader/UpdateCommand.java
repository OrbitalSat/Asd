package me.perro.dev.pluginloader;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class UpdateCommand implements CommandExecutor, TabCompleter {

    private final MainLoader plugin;

    public UpdateCommand(MainLoader plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("pluginloader.update")) {
            sender.sendMessage("§cNo tienes permiso para usar este comando.");
            return true;
        }

        if (args.length == 0) {
            sender.sendMessage("§e--- PluginLoader Help ---");
            sender.sendMessage("§e/pluginloader check §7- Comprobar actualizaciones");
            sender.sendMessage("§e/pluginloader update §7- Descargar todas las actualizaciones pendientes");
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "check":
                sender.sendMessage("§aComprobando actualizaciones...");
                // Ejecutar en un hilo separado
                plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                    Map<String, MainLoader.UpdateInfo> updates = plugin.checkForUpdates(false);

                    // Mensaje al jugador con la lista de actualizaciones
                    if (updates.isEmpty()) {
                        sender.sendMessage("§aTodos los plugins están actualizados.");
                    } else {
                        sender.sendMessage("§e--- Actualizaciones disponibles ---");
                        for (Map.Entry<String, MainLoader.UpdateInfo> entry : updates.entrySet()) {
                            String repoKey = entry.getKey();
                            MainLoader.UpdateInfo updateInfo = entry.getValue();
                            String version = "(ID: " + updateInfo.artifactId + ")";

                            sender.sendMessage(String.format("§a* §f%s: §a%s", repoKey, version));
                        }
                        sender.sendMessage("§eUsa §6/pluginloader update §epara instalar estas actualizaciones.");
                    }

                    sender.sendMessage("§aComprobación de actualizaciones completada. Revisa la consola para más detalles.");
                });
                return true;

            case "update":
                sender.sendMessage("§aDescargando actualizaciones pendientes...");
                // Ejecutar en un hilo separado
                plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                    plugin.downloadAllPendingUpdates();
                    sender.sendMessage("§aDescarga de actualizaciones completada. Revisa la consola para más detalles.");
                });
                return true;

            default:
                sender.sendMessage("§cComando desconocido. Usa /pluginloader para ver los comandos disponibles.");
                return true;
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> completions = new ArrayList<>(Arrays.asList("check", "update"));
            completions.removeIf(s -> !s.toLowerCase().startsWith(args[0].toLowerCase()));
            return completions;
        }
        return new ArrayList<>();
    }
}