package me.arcade.games;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import me.arcade.games.arena.GameType;
import me.arcade.games.game.Game;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;

public final class ArcadePlaceholder extends PlaceholderExpansion {
    private final ArcadePlugin plugin;
    public ArcadePlaceholder(ArcadePlugin plugin) { this.plugin = plugin; }
    @Override public @NotNull String getIdentifier() { return "arcadegames"; }
    @Override public @NotNull String getAuthor() { return "NO MERCY"; }
    @Override public @NotNull String getVersion() { return plugin.getPluginMeta().getVersion(); }
    @Override public boolean persist() { return true; }

    /**
     * The id of the minigame this player is in (GameType.key, e.g. "prophunt"), or "" if they are not in one.
     * Uses the same tracking the rest of the plugin uses: ArenaManager.gameOf (players, including the waiting
     * lobby and eliminated players) and ArenaManager.spectatorGame (people watching a running game).
     */
    private String gameId(Player player) {
        Game game = plugin.manager().gameOf(player);
        if (game == null) game = plugin.manager().spectatorGame(player);
        if (game == null || game.arena() == null || game.arena().type == null) return "";
        return game.arena().type.key;
    }

    @Override public String onRequest(OfflinePlayer player, @NotNull String params) {
        String key = params.toLowerCase(java.util.Locale.ROOT);
        if (key.equals("game")) {
            if (player == null) return "";
            Player online = player.getPlayer();
            return online == null ? "" : gameId(online);
        }
        if (key.startsWith("players_") && !key.equals("players_left") && !key.equals("players_total")) {
            String gameKey = key.substring("players_".length());
            GameType type = GameType.parse(gameKey);
            if (type == null) return "0";
            int count = 0;
            for (Game game : plugin.manager().all()) if (game.arena().type == type) count += game.playerCount();
            return String.valueOf(count);
        }
        if (player == null) return "0";
        Player online = Bukkit.getPlayer(player.getUniqueId());
        if (online != null) {
            Game game = plugin.manager().gameOf(online);
            if (game != null) {
                switch (key) {
                    case "time", "time_left" -> { return game.remainingTimeText(); }
                    case "alive", "players_left" -> { return String.valueOf(game.aliveCount()); }
                    case "total", "players_total", "total_players" -> { return String.valueOf(game.totalCount()); }
                    default -> {
                        // Game specific values: coins, it, top1-3, place, round, team_red, role, phase ...
                        String value = game.scoreboardValue(online, key);
                        if (value != null && !value.isEmpty()) return value;
                    }
                }
            }
        }
        return switch (key) {
            case "wins" -> String.valueOf(plugin.stat(player.getUniqueId(), "wins"));
            case "losses" -> String.valueOf(plugin.stat(player.getUniqueId(), "losses"));
            default -> "";
        };
    }
}
