package org.garsooon.jstatsbridge;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.UUID;
import java.util.HashMap;
import java.util.logging.Level;
import java.util.List;
import java.util.ArrayList;

import com.projectposeidon.api.PoseidonUUID;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.config.Configuration;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import static org.bukkit.Bukkit.getLogger;

@SuppressWarnings("deprecation")
public class UserStatsPlugin extends JavaPlugin {

    private String apiBaseUrl;
    private final HashMap<String, Long> cooldowns = new HashMap<String, Long>();

    @SuppressWarnings("StatementWithEmptyBody")
    @Override
    public void onEnable() {
        File dataFolder = getDataFolder();
        if (!dataFolder.exists()) {
            boolean created = dataFolder.mkdirs();
            // getLogger().info("Data folder created: " + created + " at " + dataFolder.getAbsolutePath());
        } else {
            // getLogger().info("Data folder already exists at " + dataFolder.getAbsolutePath());
        }

        File configFile = new File(dataFolder, "config.yml");
        if (!configFile.exists()) {
            try (PrintWriter out = new PrintWriter(new FileWriter(configFile))) {
                out.println("api-url: \"\"");
                // getLogger().info("Created new config.yml at " + configFile.getAbsolutePath());
            } catch (IOException e) {
                getLogger().warning("Could not create default config.yml: " + e.getMessage());
            }
        } else {
            // getLogger().info("Config file already exists at " + configFile.getAbsolutePath());
        }

        Configuration config = getConfiguration();
        config.load();
        this.apiBaseUrl = config.getString("api-url", "");
        if (this.apiBaseUrl == null || this.apiBaseUrl.trim().isEmpty()) {
            getLogger().severe("No api-url set in config.yml! Stats commands will not work.");
        } else {
            getLogger().info("Loaded API url: " + apiBaseUrl);
        }

        if (getCommand("stats") != null) getCommand("stats").setExecutor(this);
        getLogger().info("UserStats plugin has been enabled!");
    }

    @Override
    public void onDisable() {
        getLogger().info("UserStats plugin has been disabled!");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (apiBaseUrl == null || apiBaseUrl.trim().isEmpty()) {
            sender.sendMessage(ChatColor.RED + "Stats API URL is not set. Please configure api-url in config.yml.");
            return true;
        }

        if (!sender.hasPermission("userstats.view")) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to use this command.");
            return true;
        }

        String senderKey = (sender instanceof Player) ? ((Player) sender).getName().toLowerCase() : sender.getName().toLowerCase();
        long currentTime = System.currentTimeMillis();
        if (cooldowns.containsKey(senderKey)) {
            long lastUsed = cooldowns.get(senderKey);
            if ((currentTime - lastUsed) < 5000) {
                long secondsLeft = 5 - ((currentTime - lastUsed) / 1000);
                if (secondsLeft < 1) secondsLeft = 1;
                sender.sendMessage(ChatColor.RED + "Please wait " + secondsLeft + " more seconds before using this command again.");
                return true;
            }
        }
        cooldowns.put(senderKey, currentTime);

        if (command.getName().equalsIgnoreCase("stats")) {
            if (args.length == 0) {
                if (!(sender instanceof Player)) {
                    sender.sendMessage(ChatColor.RED + "Please specify a player name!");
                    return true;
                }
                Player player = (Player) sender;
                fetchUserStats(player, player.getName(), true);
            } else {
                String targetPlayer = args[0];
                fetchUserStats(sender, targetPlayer, false);
            }
            return true;
        }

        return false;
    }

    private void fetchUserStats(final CommandSender sender, final String playerName, final boolean isOwnStats) {
        final String uuid = getPlayerUUID(playerName);

        if (uuid == null) {
            sender.sendMessage(ChatColor.RED + "Could not find UUID for player: " + playerName);
            sender.sendMessage(ChatColor.YELLOW + "Make sure the player name is spelled correctly and the player has joined the server before.");
            return;
        }

        sender.sendMessage(ChatColor.YELLOW + "Fetching stats for " + playerName + "...");

        getServer().getScheduler().scheduleAsyncDelayedTask(this, () -> {
            try {
                String response = makeHttpRequest(apiBaseUrl + uuid);
                final String[] formattedStats = parseStatsResponse(response, playerName);

                getServer().getScheduler().scheduleSyncDelayedTask(UserStatsPlugin.this, () -> {
                    for (String line : formattedStats) {
                        sender.sendMessage(line);
                    }
                });

            } catch (Exception e) {
                getLogger().log(Level.WARNING, "Failed to fetch stats for " + playerName, e);
                getServer().getScheduler().scheduleSyncDelayedTask(UserStatsPlugin.this, () -> {
                    sender.sendMessage(ChatColor.RED + "Failed to fetch stats for " + playerName + ".");
                    sender.sendMessage(ChatColor.YELLOW + "This could mean the player hasn't joined this server or isn't in the statistics database.");
                });
            }
        });
    }

    private String makeHttpRequest(String urlString) throws Exception {
        URL url = new URL(urlString);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();

        connection.setRequestMethod("GET");
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(10000);
        connection.setRequestProperty("User-Agent", "UserStats-Plugin/1.0");
        connection.setRequestProperty("Accept", "application/json");

        int responseCode = connection.getResponseCode();

        if (responseCode != 200) {
            String errorResponse = "";
            try {
                BufferedReader errorReader = new BufferedReader(new InputStreamReader(connection.getErrorStream()));
                StringBuilder errorBuilder = new StringBuilder();
                String line;
                while ((line = errorReader.readLine()) != null) {
                    errorBuilder.append(line);
                }
                errorReader.close();
                errorResponse = errorBuilder.toString();
                getLogger().warning("API error response: " + errorResponse);
            } catch (Exception e) {}
            throw new RuntimeException("HTTP Error: " + responseCode + " - " + errorResponse);
        }

        BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
        StringBuilder response = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            response.append(line);
        }
        reader.close();
        connection.disconnect();

        String responseBody = response.toString();

        return responseBody;
    }

    private String[] parseStatsResponse(String jsonResponse, String playerName) {
        try {
            JsonParser parser = new JsonParser();
            JsonObject json = parser.parse(jsonResponse).getAsJsonObject();

            if (json.has("error") && json.get("error").getAsBoolean()) {
                return new String[] { ChatColor.RED + "Error: " + json.get("error_message").getAsString() };
            }
            if (!json.has("found") || !json.get("found").getAsBoolean()) {
                return new String[] { ChatColor.RED + "Player not found in statistics database." };
            }

            String rank = json.has("groups") ? json.get("groups").toString().replaceAll("[\\[\\]\"']", "") : "N/A";
            String joins = json.has("joinCount") ? json.get("joinCount").getAsString() : "0";
            long playTimeSeconds = json.has("playTime") ? json.get("playTime").getAsLong() : 0L;
            String playTime = (playTimeSeconds / 3600) + "h";
            String money = json.has("money") ? String.format("$%.0f", json.get("money").getAsDouble()) : "$0";
            String blocksPlaced = json.has("blocksPlaced") ? formatNumber(json.get("blocksPlaced").getAsLong()) : "0";
            String blocksDestroyed = json.has("blocksDestroyed") ? formatNumber(json.get("blocksDestroyed").getAsLong()) : "0";
            String mobsKilled = json.has("creaturesKilled") ? formatNumber(json.get("creaturesKilled").getAsLong()) : "0";
            String playersKilled = json.has("playersKilled") ? json.get("playersKilled").getAsString() : "0";
            String deaths = json.has("playerDeaths") ? json.get("playerDeaths").getAsString() : "0";
            String distance = "0";
            if (json.has("metersTraveled")) {
                long blocks = json.get("metersTraveled").getAsLong();
                distance = formatNumber(blocks);
            }

            List<String> lines = new ArrayList<>();
            String header = ChatColor.YELLOW + repeat("=", 29);
            String title = ChatColor.GOLD + "=== " + playerName + " ===";
            lines.add(header);
            lines.add(title);
            lines.add(header);

            lines.add(ChatColor.GREEN + "Rank: " + ChatColor.WHITE + rank);
            lines.add(ChatColor.GREEN + "Play Time: " + ChatColor.WHITE + playTime);
            lines.add(ChatColor.GREEN + "Money: " + ChatColor.WHITE + money);
            lines.add(ChatColor.GREEN + "Blocks Placed: " + ChatColor.WHITE + blocksPlaced);
            lines.add(ChatColor.GREEN + "Blocks Destroyed: " + ChatColor.WHITE + blocksDestroyed);
            lines.add(ChatColor.GREEN + "Creatures Killed: " + ChatColor.WHITE + mobsKilled);
            lines.add(ChatColor.GREEN + "Players Killed: " + ChatColor.WHITE + playersKilled);
            lines.add(ChatColor.GREEN + "Distance Traveled: " + ChatColor.WHITE + distance);
            lines.add(ChatColor.GREEN + "Deaths: " + ChatColor.WHITE + deaths);

            lines.add(header);

            return lines.toArray(new String[0]);
        } catch (Exception e) {
            getLogger().log(Level.WARNING, "Failed to parse JSON response", e);
            return new String[] { ChatColor.RED + "Failed to parse statistics data: " + e.getMessage() };
        }
    }

    private String repeat(String s, int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) sb.append(s);
        return sb.toString();
    }

    private String formatNumber(long number) {
        if (number >= 1000000) {
            return String.format("%.1fM", number / 1000000.0);
        } else if (number >= 1000) {
            return String.format("%.1fK", number / 1000.0);
        } else {
            return String.valueOf(number);
        }
    }

    private String getPlayerUUID(String playerName) {
        try {
            UUID uuid = PoseidonUUID.getPlayerGracefulUUID(playerName);
            if (uuid != null) return uuid.toString();
        } catch (Exception e) {
            getLogger().warning("PoseidonUUID lookup failed for " + playerName + ": " + e.getMessage());
        }
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + playerName).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
    }
}