package at.Airushmeos.autobansync;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import net.kyori.adventure.text.Component;
import org.bukkit.BanList;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.logging.Level;

public final class AutoBanSync extends JavaPlugin implements Listener {

    private SyncService syncService;
    private BukkitTask syncTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        this.syncService = new SyncService(this);

        getServer().getPluginManager().registerEvents(this, this);

        AutoBanSyncCommand cmdHandler = new AutoBanSyncCommand(this);
        if (getCommand("autobansync") != null) {
            getCommand("autobansync").setExecutor(cmdHandler);
            getCommand("autobansync").setTabCompleter(cmdHandler);
        }

        restartSyncTask();

        if (getConfig().getBoolean("sync-on-startup", true)) {
            syncService.runSync(false);
        }

        getLogger().info("AutoBanSync v1.2.0 erfolgreich aktiviert.");
    }

    @Override
    public void onDisable() {
        if (syncTask != null) {
            syncTask.cancel();
        }
        getLogger().info("AutoBanSync deaktiviert.");
    }

    public void restartSyncTask() {
        if (syncTask != null) {
            syncTask.cancel();
        }

        int intervalMinutes = getConfig().getInt("sync-interval-minutes", 10);
        if (intervalMinutes < 1) intervalMinutes = 1;
        long intervalTicks = intervalMinutes * 20L * 60L;

        syncTask = Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> {
            syncService.runSync(false);
        }, intervalTicks, intervalTicks);
    }

    public SyncService getSyncService() {
        return syncService;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        List<String> blacklist = getConfig().getStringList("blacklist-players");

        if (blacklist.contains(player.getName())) {
            String reason = getConfig().getString("ban-reason-prefix", "[AutoBanSync] ") + "Auf der globalen Blacklist!";
            BanList banList = Bukkit.getBanList(BanList.Type.NAME);

            if (!banList.isBanned(player.getName())) {
                banList.addBan(player.getName(), reason, (Date) null, "AutoBanSync-Blacklist");
            }
            player.kick(Component.text("Du wurdest aufgrund eines Blacklist-Eintrags permanent gebannt."));
        }
    }

    public record ApiBanEntry(UUID uuid, String username, String reason, long timestamp, String entry_id, String ban_type, long duration_seconds, String server_name) {
        public boolean isValid() {
            return username != null && !username.isEmpty();
        }
    }

    public record ApiResponse(int version, String generated_at, List<ApiBanEntry> entries) {
        public List<ApiBanEntry> bans() {
            return entries != null ? entries : new ArrayList<>();
        }
    }

    public static class ApiClient {
        private final AutoBanSync plugin;
        private final HttpClient httpClient;
        private final Gson gson;

        public ApiClient(AutoBanSync plugin) {
            this.plugin = plugin;
            this.httpClient = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .connectTimeout(Duration.ofSeconds(plugin.getConfig().getInt("connect-timeout-seconds", 10)))
                    .build();
            this.gson = new Gson();
        }

        public ApiResponse fetchBans() throws Exception {
            String url = plugin.getConfig().getString("api-url");
            if (url == null || url.isEmpty() || url.contains("example.com")) {
                throw new IllegalStateException("API-URL ist nicht korrekt in der config.yml konfiguriert!");
            }

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Accept", "application/json")
                    .header("User-Agent", "AutoBanSync-PaperPlugin")
                    .timeout(Duration.ofSeconds(plugin.getConfig().getInt("read-timeout-seconds", 10)))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new ApiException("HTTP-Fehlerstatus erhalten: " + response.statusCode(), response.statusCode());
            }

            try {
                ApiResponse apiResponse = gson.fromJson(response.body(), ApiResponse.class);
                if (apiResponse == null || apiResponse.bans() == null) {
                    throw new ApiException("Ungültige JSON-Struktur.", response.statusCode());
                }
                return apiResponse;
            } catch (JsonSyntaxException e) {
                throw new ApiException("JSON-Parsing-Fehler: " + e.getMessage(), response.statusCode());
            }
        }

        public boolean sendBanToApi(String username, String uuid, String banType, long durationSeconds, String reason) {
            try {
                String url = plugin.getConfig().getString("api-create-url");
                String token = plugin.getConfig().getString("api-token");

                if (url == null || url.isEmpty()) return false;

                Map<String, Object> payload = new HashMap<>();
                payload.put("username", username);
                payload.put("uuid", uuid != null ? uuid : "");
                payload.put("ban_type", banType);
                payload.put("duration_seconds", durationSeconds);
                payload.put("reason", reason);
                payload.put("server_name", "In-Game");

                String jsonBody = gson.toJson(payload);

                HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("Content-Type", "application/json")
                        .header("User-Agent", "AutoBanSync-PaperPlugin")
                        .POST(HttpRequest.BodyPublishers.ofString(jsonBody));

                // Token nur mitschicken, wenn einer konfiguriert ist und kein Platzhalter ist
                if (token != null && !token.isEmpty() && !token.equals("DEIN_PERSOENLICHER_TOKEN_HIER")) {
                    reqBuilder.header("Authorization", "Bearer " + token);
                }

                HttpResponse<String> response = httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());
                return response.statusCode() == 200;
            } catch (Exception e) {
                plugin.getLogger().warning("Fehler beim Live-Sync zur API: " + e.getMessage());
                return false;
            }
        }

        public static class ApiException extends Exception {
            private final int statusCode;
            public ApiException(String message, int statusCode) {
                super(message);
                this.statusCode = statusCode;
            }
            public int getStatusCode() { return statusCode; }
        }
    }

    public static class SyncService {
        private final AutoBanSync plugin;
        private final ApiClient apiClient;

        private long lastSuccessfulSync = 0;
        private int lastProcessedCount = 0;
        private String lastStatus = "Noch nicht synchronisiert";
        private boolean isSyncing = false;

        public SyncService(AutoBanSync plugin) {
            this.plugin = plugin;
            this.apiClient = new ApiClient(plugin);
        }

        public ApiClient getApiClient() {
            return apiClient;
        }

        public synchronized void runSync(boolean manual) {
            if (!plugin.getConfig().getBoolean("enabled", true)) return;
            if (isSyncing) return;
            isSyncing = true;

            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    ApiResponse response = apiClient.fetchBans();
                    List<ApiBanEntry> entries = response.bans();

                    int maxEntries = plugin.getConfig().getInt("max-entries", 1000);
                    if (entries.size() > maxEntries) entries = entries.subList(0, maxEntries);

                    int newBansCount = processBans(entries);

                    long now = System.currentTimeMillis();
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        lastSuccessfulSync = now;
                        lastProcessedCount = newBansCount;
                        lastStatus = "Erfolgreich (" + newBansCount + " Aktionen verarbeitet)";
                    });
                } catch (Exception e) {
                    lastStatus = "Fehler: " + e.getMessage();
                } finally {
                    isSyncing = false;
                }
            });
        }

        private int processBans(List<ApiBanEntry> entries) {
            int processedCount = 0;
            String reasonPrefix = plugin.getConfig().getString("ban-reason-prefix", "[AutoBanSync] ");
            List<String> passbyList = plugin.getConfig().getStringList("passby-players");

            BanList banList = Bukkit.getBanList(BanList.Type.NAME);

            Set<String> apiBannedUsernames = new HashSet<>();
            for (ApiBanEntry entry : entries) {
                if (entry != null && entry.isValid()) {
                    String type = entry.ban_type() != null ? entry.ban_type().toLowerCase() : "ban";
                    if (type.equals("ban") || type.equals("tempban")) {
                        apiBannedUsernames.add(entry.username().toLowerCase());
                    }
                }
            }

            try {
                Bukkit.getScheduler().callSyncMethod(plugin, () -> {
                    for (Object obj : banList.getEntries()) {
                        if (obj instanceof org.bukkit.BanEntry banEntry) {
                            if ("AutoBanSync".equals(banEntry.getSource()) || (banEntry.getReason() != null && banEntry.getReason().startsWith(reasonPrefix))) {
                                String name = banEntry.getTarget();
                                if (name != null) {
                                    boolean isPassby = passbyList.stream().anyMatch(p -> p.equalsIgnoreCase(name));
                                    if (!isPassby && !apiBannedUsernames.contains(name.toLowerCase())) {
                                        banList.pardon(name);
                                        plugin.getLogger().info("Spieler " + name + " wurde entbannt, da er nicht mehr in der API ist.");
                                    }
                                }
                            }
                        }
                    }
                    return null;
                }).get();
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Fehler beim automatischen Unbannen", e);
            }

            for (ApiBanEntry entry : entries) {
                if (entry == null || !entry.isValid()) continue;

                String name = entry.username();
                UUID uuid = entry.uuid();
                String banType = entry.ban_type() != null ? entry.ban_type().toLowerCase() : "ban";

                if (passbyList.contains(name) || (uuid != null && passbyList.contains(uuid.toString()))) {
                    continue;
                }

                String reason = entry.reason() != null ? entry.reason() : "Kein Grund";
                String fullReason = reasonPrefix + reason;

                try {
                    boolean success = Bukkit.getScheduler().callSyncMethod(plugin, () -> {
                        if (banType.equals("mute")) {
                            return true;
                        }

                        if (banList.isBanned(name)) {
                            return false;
                        }

                        Date expiry = null;
                        if (banType.equals("tempban") && entry.duration_seconds() > 0) {
                            expiry = Date.from(Instant.now().plusSeconds(entry.duration_seconds()));
                        }

                        banList.addBan(name, fullReason, expiry, "AutoBanSync");

                        Player onlinePlayer = Bukkit.getPlayer(name);
                        if (onlinePlayer != null && onlinePlayer.isOnline()) {
                            onlinePlayer.kick(Component.text(plugin.getConfig().getString("kick-message", "Du wurdest gebannt.")));
                        }

                        return true;
                    }).get();

                    if (success) processedCount++;
                } catch (Exception e) {
                    plugin.getLogger().log(Level.WARNING, "Fehler bei Verarbeitung für Spieler " + name, e);
                }
            }
            return processedCount;
        }

        public long getLastSuccessfulSync() { return lastSuccessfulSync; }
        public int getLastProcessedCount() { return lastProcessedCount; }
        public String getLastStatus() { return lastStatus; }
    }

    public static class AutoBanSyncCommand implements CommandExecutor, TabCompleter {
        private final AutoBanSync plugin;

        public AutoBanSyncCommand(AutoBanSync plugin) {
            this.plugin = plugin;
        }

        @Override
        public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
            if (!sender.hasPermission("autobansync.admin")) {
                sender.sendMessage(Component.text("§cDazu hast du keine Berechtigung."));
                return true;
            }

            if (args.length == 0) {
                sendHelp(sender);
                return true;
            }

            switch (args[0].toLowerCase()) {
                case "status":
                    long lastSync = plugin.getSyncService().getLastSuccessfulSync();
                    String timeAgo = lastSync == 0 ? "Nie" : Duration.between(Instant.ofEpochMilli(lastSync), Instant.now()).toMinutes() + " Minuten";
                    sender.sendMessage(Component.text("§6--- AutoBanSync Status ---"));
                    sender.sendMessage(Component.text("§7Plugin aktiv: §a" + plugin.getConfig().getBoolean("enabled")));
                    sender.sendMessage(Component.text("§7Letzter Status: §e" + plugin.getSyncService().getLastStatus()));
                    sender.sendMessage(Component.text("§7Letzter Abruf vor: §e" + timeAgo));
                    break;

                case "sync":
                    sender.sendMessage(Component.text("§eManuelle Synchronisierung wird gestartet..."));
                    plugin.getSyncService().runSync(true);
                    break;

                case "reload":
                    plugin.reloadConfig();
                    plugin.restartSyncTask();
                    sender.sendMessage(Component.text("§aAutoBanSync Konfiguration neu geladen!"));
                    break;

                case "ban":
                    if (args.length < 2) {
                        sender.sendMessage(Component.text("§cVerwendung: /autobansync ban <Spieler> [Grund]"));
                        return true;
                    }
                    String targetName = args[1];
                    String banReason = args.length > 2 ? String.join(" ", Arrays.copyOfRange(args, 2, args.length)) : "Kein Grund angegeben";

                    OfflinePlayer targetBan = Bukkit.getOfflinePlayer(targetName);
                    String nameToBan = targetBan.getName() != null ? targetBan.getName() : targetName;
                    String uuidToBan = targetBan.getUniqueId().toString();

                    BanList banList = Bukkit.getBanList(BanList.Type.NAME);
                    banList.addBan(nameToBan, banReason, (Date) null, sender.getName());
                    if (targetBan.isOnline() && targetBan.getPlayer() != null) {
                        targetBan.getPlayer().kick(Component.text("Du wurdest gebannt: " + banReason));
                    }

                    Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                        plugin.getSyncService().getApiClient().sendBanToApi(nameToBan, uuidToBan, "ban", 0, banReason);
                    });

                    sender.sendMessage(Component.text("§aSpieler " + nameToBan + " wurde permanent gebannt und an die API gesynct."));
                    break;

                case "tempban":
                    if (args.length < 3) {
                        sender.sendMessage(Component.text("§cVerwendung: /autobansync tempban <Spieler> <Minuten> [Grund]"));
                        return true;
                    }
                    String tbTarget = args[1];
                    long minutes;
                    try {
                        minutes = Long.parseLong(args[2]);
                    } catch (NumberFormatException e) {
                        sender.sendMessage(Component.text("§cUngültige Minutenanzahl."));
                        return true;
                    }
                    String tbReason = args.length > 3 ? String.join(" ", Arrays.copyOfRange(args, 3, args.length)) : "Zeitlicher Bann";
                    long durationSeconds = minutes * 60;
                    Date expiry = Date.from(Instant.now().plus(Duration.ofMinutes(minutes)));

                    OfflinePlayer targetTb = Bukkit.getOfflinePlayer(tbTarget);
                    String nameToTb = targetTb.getName() != null ? targetTb.getName() : tbTarget;
                    String uuidToTb = targetTb.getUniqueId().toString();

                    BanList tempBanList = Bukkit.getBanList(BanList.Type.NAME);
                    tempBanList.addBan(nameToTb, tbReason, expiry, sender.getName());
                    if (targetTb.isOnline() && targetTb.getPlayer() != null) {
                        targetTb.getPlayer().kick(Component.text("Temporär gebannt für " + minutes + " Minuten."));
                    }

                    Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                        plugin.getSyncService().getApiClient().sendBanToApi(nameToTb, uuidToTb, "tempban", durationSeconds, tbReason);
                    });

                    sender.sendMessage(Component.text("§aSpieler " + nameToTb + " wurde für " + minutes + " Minuten gebannt und an die API gesynct."));
                    break;

                case "mute":
                    if (args.length < 2) {
                        sender.sendMessage(Component.text("§cVerwendung: /autobansync mute <Spieler> [Grund]"));
                        return true;
                    }
                    String muteTarget = args[1];
                    String muteReason = args.length > 2 ? String.join(" ", Arrays.copyOfRange(args, 2, args.length)) : "Kein Grund angegeben";

                    OfflinePlayer targetMute = Bukkit.getOfflinePlayer(muteTarget);
                    String nameToMute = targetMute.getName() != null ? targetMute.getName() : muteTarget;
                    String uuidToMute = targetMute.getUniqueId().toString();

                    Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                        plugin.getSyncService().getApiClient().sendBanToApi(nameToMute, uuidToMute, "mute", 0, muteReason);
                    });

                    sender.sendMessage(Component.text("§aMute für " + nameToMute + " wurde an die API gesynct."));
                    break;

                case "token":
                    sender.sendMessage(Component.text("§6--- AutoBanSync API Info ---"));
                    sender.sendMessage(Component.text("§7API-URL: §e" + plugin.getConfig().getString("api-url")));
                    break;

                case "help":
                default:
                    sendHelp(sender);
                    break;
            }
            return true;
        }

        private void sendHelp(CommandSender sender) {
            sender.sendMessage(Component.text("§6--- AutoBanSync Befehle ---"));
            sender.sendMessage(Component.text("§e/autobansync status §7- Status anzeigen"));
            sender.sendMessage(Component.text("§e/autobansync sync §7- Manuell synchronisieren"));
            sender.sendMessage(Component.text("§e/autobansync reload §7- Config neu laden"));
            sender.sendMessage(Component.text("§e/autobansync ban <Spieler> [Grund] §7- Permanent bannen & syncen"));
            sender.sendMessage(Component.text("§e/autobansync tempban <Spieler> <Min> [Grund] §7- Temporär bannen & syncen"));
            sender.sendMessage(Component.text("§e/autobansync mute <Spieler> [Grund] §7- Mute an die API senden"));
            sender.sendMessage(Component.text("§e/autobansync token §7- API-Informationen anzeigen"));
        }

        @Override
        public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
            List<String> completions = new ArrayList<>();
            if (sender.hasPermission("autobansync.admin")) {
                if (args.length == 1) {
                    List.of("status", "sync", "reload", "ban", "tempban", "mute", "token", "help").forEach(sub -> {
                        if (sub.startsWith(args[0].toLowerCase())) completions.add(sub);
                    });
                } else if (args.length == 2 && (args[0].equalsIgnoreCase("ban") || args[0].equalsIgnoreCase("tempban") || args[0].equalsIgnoreCase("mute"))) {
                    for (Player p : Bukkit.getOnlinePlayers()) {
                        if (p.getName().toLowerCase().startsWith(args[1].toLowerCase())) {
                            completions.add(p.getName());
                        }
                    }
                }
            }
            return completions;
        }
    }
}