/*
 * Copyright 2022-2025 Noah Ross
 *
 * This file is part of PerPlayerKit.
 *
 * PerPlayerKit is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or (at your
 * option) any later version.
 *
 * PerPlayerKit is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for
 * more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with PerPlayerKit. If not, see <https://www.gnu.org/licenses/>.
 */
package dev.noah.perplayerkit.commands.admin;

import dev.noah.perplayerkit.KitManager;
import dev.noah.perplayerkit.KitRoomDataManager;
import dev.noah.perplayerkit.PerPlayerKit;
import dev.noah.perplayerkit.PublicKit;
import dev.noah.perplayerkit.kitdata.InvalidKitDataException;
import dev.noah.perplayerkit.kitdata.KitDataFile;
import dev.noah.perplayerkit.kitdata.KitDataService;
import dev.noah.perplayerkit.kitdata.KitDataService.ImportPlan;
import dev.noah.perplayerkit.kitdata.KitDataService.Scope;
import dev.noah.perplayerkit.kitdata.KitDataSnapshot;
import dev.noah.perplayerkit.kitdata.KitDataSnapshot.PublicKitEntry;
import dev.noah.perplayerkit.util.Lang;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * /kitdata export &lt;all|kitroom|publickits|playerkits&gt; &lt;file&gt; —
 * writes the selected data to a .ppk file in the plugin folder, so a finished
 * kit room / public kit setup can be shared with another server.
 * <p>
 * /kitdata import &lt;file&gt; — imports a .ppk file from the plugin folder.
 * The file is fully validated before anything is written, and if the import
 * would overwrite existing data the command has to be re-run with
 * {@code confirm}. Public kits bring their config entry (name, icon) with
 * them.
 */
public class KitDataCommand implements CommandExecutor, TabCompleter {

    private static final String EXPORT = "export";
    private static final String IMPORT = "import";
    private static final String CONFIRM = "confirm";

    private final Plugin plugin;
    private final AtomicBoolean operationInProgress = new AtomicBoolean(false);

    public KitDataCommand(Plugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (args.length >= 3 && args[0].equalsIgnoreCase(EXPORT)) {
            handleExport(sender, args);
            return true;
        }
        if (args.length >= 2 && args[0].equalsIgnoreCase(IMPORT)) {
            handleImport(sender, args);
            return true;
        }
        Lang.get().send(sender, "command.kitdata-usage");
        return true;
    }

    private void handleExport(CommandSender sender, String[] args) {
        Scope scope = Scope.fromString(args[1]);
        if (scope == null) {
            Lang.get().send(sender, "error.kitdata-invalid-scope", "scope", args[1]);
            return;
        }

        String fileName = KitDataFile.sanitizeFileName(args[2]);
        if (fileName == null) {
            Lang.get().send(sender, "error.kitdata-invalid-filename");
            return;
        }

        boolean confirmed = args.length >= 4 && args[3].equalsIgnoreCase(CONFIRM);
        File file = new File(plugin.getDataFolder(), fileName);
        if (file.exists() && !confirmed) {
            Lang.get().send(sender, "info.kitdata-file-exists",
                    "file", fileName,
                    "command", "/kitdata export " + scope.lowercase() + " " + fileName + " confirm");
            return;
        }

        if (!operationInProgress.compareAndSet(false, true)) {
            Lang.get().send(sender, "error.kitdata-in-progress");
            return;
        }

        Lang.get().send(sender, "info.kitdata-export-starting", "scope", scope.lowercase(), "file", fileName);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> runExport(sender, scope, file, fileName));
    }

    private void runExport(CommandSender sender, Scope scope, File file, String fileName) {
        try {
            KitDataService service = new KitDataService(PerPlayerKit.storageManager, KitManager.get());
            KitDataSnapshot snapshot = service.buildSnapshot(scope);
            if (snapshot.isEmpty()) {
                runOnMain(sender, current -> Lang.get().send(current,
                        "error.kitdata-nothing-to-export", "scope", scope.lowercase()));
                return;
            }

            // Write to a temp file first so a failed export never leaves a
            // half-written .ppk behind.
            Path target = file.toPath();
            Path temp = target.resolveSibling(fileName + ".tmp");
            try {
                try (OutputStream out = Files.newOutputStream(temp)) {
                    KitDataFile.write(out, snapshot, plugin.getDescription().getVersion(), System.currentTimeMillis());
                }
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(temp);
            }

            int entries = snapshot.totalEntries();
            plugin.getLogger().info("[KitData] Exported " + entries + " entries (" + scope.lowercase()
                    + ") to " + fileName);
            runOnMain(sender, current -> Lang.get().send(current, "success.kitdata-exported",
                    "entries", String.valueOf(entries), "file", fileName));
        } catch (IOException | InvalidKitDataException | RuntimeException e) {
            plugin.getLogger().severe("[KitData] Export to " + fileName + " failed: " + e);
            runOnMain(sender, current -> Lang.get().send(current, "error.kitdata-export-failed"));
        } finally {
            operationInProgress.set(false);
        }
    }

    private void handleImport(CommandSender sender, String[] args) {
        String fileName = KitDataFile.sanitizeFileName(args[1]);
        if (fileName == null) {
            Lang.get().send(sender, "error.kitdata-invalid-filename");
            return;
        }

        File file = new File(plugin.getDataFolder(), fileName);
        if (!file.isFile()) {
            Lang.get().send(sender, "error.kitdata-file-not-found", "file", fileName);
            return;
        }

        boolean confirmed = args.length >= 3 && args[2].equalsIgnoreCase(CONFIRM);

        if (!operationInProgress.compareAndSet(false, true)) {
            Lang.get().send(sender, "error.kitdata-in-progress");
            return;
        }

        Lang.get().send(sender, "info.kitdata-import-starting", "file", fileName);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> runImport(sender, file, fileName, confirmed));
    }

    private void runImport(CommandSender sender, File file, String fileName, boolean confirmed) {
        boolean releaseInFinally = true;
        try {
            KitDataFile.ReadResult result;
            try (InputStream in = Files.newInputStream(file.toPath())) {
                result = KitDataFile.read(in);
            }

            KitDataService service = new KitDataService(PerPlayerKit.storageManager, KitManager.get());
            KitDataSnapshot snapshot = result.snapshot();
            service.validateItemData(snapshot);

            ImportPlan plan = service.analyzeImport(snapshot);
            if (plan.overwritesAnything() && !confirmed) {
                runOnMain(sender, current -> Lang.get().send(current, "info.kitdata-import-overwrites",
                        "file", fileName,
                        "kitroom", String.valueOf(plan.kitRoomPagesOverwritten()),
                        "publickits", String.valueOf(plan.publicKitsOverwritten()),
                        "playerkits", String.valueOf(plan.playerKitsOverwritten()),
                        "command", "/kitdata import " + fileName + " confirm"));
                return;
            }

            service.applyToStorage(snapshot);

            plugin.getLogger().info("[KitData] Imported " + snapshot.totalEntries() + " entries from "
                    + fileName + " (created by plugin version " + result.pluginVersion() + ")");

            // Config changes and cache reloads have to happen on the main thread.
            releaseInFinally = false;
            Bukkit.getScheduler().runTask(plugin, () -> {
                try {
                    applyBukkitSideEffects(snapshot);
                    sendImportSuccess(sender, fileName, snapshot);
                } finally {
                    operationInProgress.set(false);
                }
            });
        } catch (InvalidKitDataException e) {
            plugin.getLogger().warning("[KitData] Rejected import of " + fileName + ": " + e.getMessage());
            runOnMain(sender, current -> Lang.get().send(current, "error.kitdata-invalid-file",
                    "error", e.getMessage()));
        } catch (IOException | RuntimeException e) {
            plugin.getLogger().severe("[KitData] Import of " + fileName + " failed: " + e);
            runOnMain(sender, current -> Lang.get().send(current, "error.unexpected"));
        } finally {
            if (releaseInFinally) {
                operationInProgress.set(false);
            }
        }
    }

    /**
     * Registers imported public kits in the config and the live kit list,
     * then reloads the affected caches from storage the same way /kitroom
     * load and startup do.
     */
    private void applyBukkitSideEffects(KitDataSnapshot snapshot) {
        if (!snapshot.getPublicKits().isEmpty()) {
            for (PublicKitEntry entry : snapshot.getPublicKits().values()) {
                Material icon = Material.matchMaterial(entry.icon());
                if (icon == null) {
                    plugin.getLogger().warning("[KitData] Unknown icon " + entry.icon()
                            + " for public kit " + entry.id() + ", using CHEST");
                    icon = Material.CHEST;
                }

                plugin.getConfig().set("publickits." + entry.id() + ".name", entry.name());
                plugin.getConfig().set("publickits." + entry.id() + ".icon", icon.name());

                Material finalIcon = icon;
                KitManager.get().getPublicKitList().stream()
                        .filter(kit -> kit.id.equals(entry.id()))
                        .findFirst()
                        .ifPresentOrElse(kit -> {
                            kit.name = entry.name();
                            kit.icon = finalIcon;
                        }, () -> KitManager.get().getPublicKitList()
                                .add(new PublicKit(entry.id(), entry.name(), finalIcon)));

                if (entry.data() != null) {
                    KitManager.get().loadPublicKitFromDB(entry.id());
                }
            }
            plugin.saveConfig();
        }

        if (!snapshot.getKitRoomPages().isEmpty()) {
            KitRoomDataManager.get().loadFromDB();
        }

        if (!snapshot.getPlayerKits().isEmpty()) {
            refreshOnlinePlayers(snapshot);
        }
    }

    /** Reloads imported kits of online players so they don't keep stale caches. */
    private void refreshOnlinePlayers(KitDataSnapshot snapshot) {
        Set<UUID> importedOwners = new HashSet<>();
        for (String id : snapshot.getPlayerKits().keySet()) {
            try {
                importedOwners.add(UUID.fromString(id.substring(0, 36)));
            } catch (IllegalArgumentException ignored) {
            }
        }

        List<UUID> toRefresh = Bukkit.getOnlinePlayers().stream()
                .map(Player::getUniqueId)
                .filter(importedOwners::contains)
                .toList();
        if (!toRefresh.isEmpty()) {
            Bukkit.getScheduler().runTaskAsynchronously(plugin,
                    () -> toRefresh.forEach(uuid -> KitManager.get().loadPlayerDataFromDB(uuid)));
        }
    }

    private void sendImportSuccess(CommandSender sender, String fileName, KitDataSnapshot snapshot) {
        runOnMain(sender, current -> {
            Lang.get().send(current, "success.kitdata-imported", "file", fileName);
            Lang.get().send(current, "info.kitdata-import-summary",
                    "kitroom", String.valueOf(snapshot.getKitRoomPages().size()),
                    "publickits", String.valueOf(snapshot.getPublicKits().size()),
                    "playerkits", String.valueOf(snapshot.getPlayerKits().size()));
        });
    }

    /**
     * Runs the action on the main thread. For player senders the player is
     * re-fetched so nothing is sent to someone who logged off mid-operation.
     */
    private void runOnMain(CommandSender sender, Consumer<CommandSender> action) {
        if (sender instanceof Player player) {
            UUID uuid = player.getUniqueId();
            Bukkit.getScheduler().runTask(plugin, () -> {
                Player current = Bukkit.getPlayer(uuid);
                if (current != null) {
                    action.accept(current);
                }
            });
        } else {
            Bukkit.getScheduler().runTask(plugin, () -> action.accept(sender));
        }
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            return filterPrefix(List.of(EXPORT, IMPORT), args[0]);
        }

        if (args[0].equalsIgnoreCase(EXPORT)) {
            if (args.length == 2) {
                return filterPrefix(Arrays.stream(Scope.values()).map(Scope::lowercase).toList(), args[1]);
            }
            if (args.length == 4) {
                return filterPrefix(List.of(CONFIRM), args[3]);
            }
            return List.of();
        }

        if (args[0].equalsIgnoreCase(IMPORT)) {
            if (args.length == 2) {
                return filterPrefix(listExportFiles(), args[1]);
            }
            if (args.length == 3) {
                return filterPrefix(List.of(CONFIRM), args[2]);
            }
            return List.of();
        }

        return List.of();
    }

    private List<String> listExportFiles() {
        File[] files = plugin.getDataFolder()
                .listFiles((dir, name) -> name.toLowerCase(Locale.ROOT).endsWith(KitDataFile.FILE_EXTENSION));
        if (files == null) {
            return List.of();
        }
        return Stream.of(files).map(File::getName).sorted().limit(50).toList();
    }

    private List<String> filterPrefix(List<String> options, String input) {
        String prefix = input.toLowerCase(Locale.ROOT);
        return options.stream().filter(option -> option.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
    }
}
