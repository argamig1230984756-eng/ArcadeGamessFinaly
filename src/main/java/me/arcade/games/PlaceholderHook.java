package me.arcade.games;

/**
 * The only class ArcadePlugin touches for PlaceholderAPI. All PlaceholderAPI types live in
 * {@link ArcadePlaceholder}, which is loaded lazily from here, so the plugin starts normally
 * (and never throws NoClassDefFoundError) when PlaceholderAPI is not installed.
 */
final class PlaceholderHook {
    private static ArcadePlaceholder expansion;

    private PlaceholderHook() {}

    /** @return true if the %arcadegames_...% expansion is registered. */
    static boolean register(ArcadePlugin plugin) {
        if (!plugin.getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            plugin.getLogger().info("PlaceholderAPI not found - %arcadegames_...% placeholders are disabled.");
            return false;
        }
        try {
            unregister(); // e.g. after /arcade reload or a plugin reload
            ArcadePlaceholder created = new ArcadePlaceholder(plugin);
            if (created.register()) {
                expansion = created;
                plugin.getLogger().info("Registered PlaceholderAPI expansion 'arcadegames'.");
                return true;
            }
            plugin.getLogger().warning("PlaceholderAPI refused to register the 'arcadegames' expansion (identifier already in use?).");
        } catch (LinkageError | RuntimeException ex) {
            plugin.getLogger().warning("Could not hook into PlaceholderAPI: " + ex);
        }
        return false;
    }

    static void unregister() {
        if (expansion == null) return;
        try {
            expansion.unregister();
        } catch (LinkageError | RuntimeException ignored) {
        }
        expansion = null;
    }
}
