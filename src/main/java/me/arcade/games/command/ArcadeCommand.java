package me.arcade.games.command;

import me.arcade.games.ArcadePlugin;
import me.arcade.games.arena.Arena;
import me.arcade.games.arena.ArenaManager;
import me.arcade.games.arena.GameType;
import me.arcade.games.game.Game;
import me.arcade.games.util.Msg;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.block.Block;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.*;

public class ArcadeCommand implements CommandExecutor, TabCompleter {
    private static final List<String> PLAYER_SUBS = List.of("join", "leave", "list", "gui", "menu", "arenas", "spectate", "party", "help");
    private static final List<String> ADMIN_SUBS = List.of("create", "delete", "setwaiting", "setspawn", "setwither",
            "pos1", "pos2", "setspectator", "setplayers", "info", "forcestart", "forcestop", "reload", "checkpoint", "cheakpoint", "coin", "end", "wall");
    private static final List<String> GAME_NAMES = Arrays.stream(GameType.values()).map(g -> g.key).toList();

    private final ArcadePlugin plugin;
    private final ArenaManager m;
    private final Map<UUID, WallPending> wallPending = new HashMap<>();
    private record WallPending(String arena, int team, Location first) {}

    public ArcadeCommand(ArcadePlugin plugin) {
        this.plugin = plugin;
        this.m = plugin.manager();
    }

    private String t(String key, Object... kv) {
        return plugin.settings().msg(key, kv);
    }

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (a.length == 0) {
            if (s instanceof Player p && plugin.settings().gui().getBoolean("open-on-arcade-command", true)) {
                gui(p, a);
            } else {
                help(s);
            }
            return true;
        }
        switch (a[0].toLowerCase()) {
            case "join" -> join(s, a);
            case "leave" -> leave(s);
            case "list" -> list(s);
            case "gui", "menu" -> gui(s, a);
            case "arenas" -> arenas(s, a);
            case "spectate", "spec", "watch" -> spectate(s, a);
            case "help" -> help(s);
            case "party" -> { if (s instanceof Player p) plugin.partyManager().command(p, a); else Msg.send(s, "&cOnly players can manage parties."); }
            case "checkpoint", "cheakpoint", "coin", "end", "wall" -> admin(s, a);
            default -> admin(s, a);
        }
        return true;
    }

    private void help(CommandSender s) {
        Msg.plain(s, "&8&m      &r &bArcade Games &8&m      ");
        Msg.plain(s, "&e/arcade join [arena|game] &7- join a random free arena (of a game, or any game); full arenas are skipped");
        Msg.plain(s, "&e/arcade gui &7- open the game menu (one item per game, click = random arena)");
        Msg.plain(s, "&e/arcade arenas [game] &7- open the list of arenas");
        Msg.plain(s, "&e/arcade spectate [arena|game] &7- watch a game (survival mode, flying)");
        Msg.plain(s, "&e/arcade leave &7- leave your game");
        Msg.plain(s, "&e/arcade list &7- list arenas");
        Msg.plain(s, "&7Games: &fprophunt, blockparty, tntrun, partygames, miniwalls");
        if (s.hasPermission("arcade.admin")) {
            Msg.plain(s, "&6Admin: &ecreate <name> <game>&7, &edelete <name>&7, &esetwaiting <name>");
            Msg.plain(s, "&esetspawn <name> [n]&7, &esetspectator <name>&7, &esetwither <name> <1-4>&7, &epos1/pos2 <name>");
            Msg.plain(s, "&esetplayers <name> <min> <max>&7, &einfo <name>&7, &eforcestart/forcestop <name>, &ereload");
        }
    }

    // ------------------------------------------------------------ player commands

    private void join(CommandSender s, String[] a) {
        if (!(s instanceof Player p)) {
            Msg.send(s, t("join.only-players"));
            return;
        }
        if (m.gameOf(p) != null) {
            Msg.send(p, t("join.already-in-game"));
            return;
        }
        Arena requested = null; // the player named an exact arena
        GameType type = null;   // the player named a game (or nothing = any game)
        if (a.length >= 2) {
            requested = m.arena(a[1]);
            if (requested == null) {
                type = GameType.parse(a[1]);
                if (type == null) {
                    Msg.send(p, t("join.unknown", "name", a[1]));
                    return;
                }
            }
        }
        attemptJoin(p, requested, type);
    }

    /** Opens the main menu for a player (used by the nether star). Does the same checks as /arcade gui. */
    public void openMain(Player p) {
        if (m.gameOf(p) != null) {
            Msg.send(p, t("join.already-in-game"));
            return;
        }
        if (m.spectatorGame(p) != null) {
            Msg.send(p, t("join.spectating"));
            return;
        }
        if (plugin.gameMenu().enabled()) plugin.gameMenu().open(p);
        else plugin.menu().open(p, null);
    }

    /** /arcade gui: the game menu (one item per game). /arcade gui <game>: the arena list of that game. */
    private void gui(CommandSender s, String[] a) {
        if (!(s instanceof Player p)) {
            Msg.send(s, t("join.only-players"));
            return;
        }
        if (a.length >= 2) {
            arenas(s, a);
            return;
        }
        openMain(p);
    }

    /** /arcade arenas [game]: the arena list menu (gui.yml). */
    private void arenas(CommandSender s, String[] a) {
        if (!(s instanceof Player p)) {
            Msg.send(s, t("join.only-players"));
            return;
        }
        if (m.gameOf(p) != null) {
            Msg.send(p, t("join.already-in-game"));
            return;
        }
        GameType filter = null;
        if (a.length >= 2) {
            filter = GameType.parse(a[1]);
            if (filter == null) {
                Msg.send(p, t("join.unknown", "name", a[1]));
                return;
            }
        }
        plugin.menu().open(p, filter);
    }

    /** /arcade spectate [arena|game]. */
    private void spectate(CommandSender s, String[] a) {
        if (!(s instanceof Player p)) {
            Msg.send(s, t("join.only-players"));
            return;
        }
        if (m.gameOf(p) != null) {
            Msg.send(p, t("join.already-in-game"));
            return;
        }
        if (a.length < 2) {
            plugin.spectateMenu().openGames(p);
            return;
        }
        Arena ar = m.arena(a[1]);
        if (ar != null) {
            startSpectating(p, ar);
            return;
        }
        GameType type = GameType.parse(a[1]);
        if (type == null) {
            Msg.send(p, t("join.unknown", "name", a[1]));
            return;
        }
        plugin.spectateMenu().openArenas(p, type);
    }

    /** Sends a player to watch an arena (survival mode, flying). Used by the spectate menu. */
    public void startSpectating(Player p, Arena ar) {
        if (m.gameOf(p) != null) {
            Msg.send(p, t("join.already-in-game"));
            return;
        }
        Game current = m.spectatorGame(p);
        if (current != null) {
            if (current.arena() == ar) return;
            current.stopSpectating(p); // switch arena
        }
        String problem = ar.validate();
        if (problem != null) {
            Msg.send(p, t("spectate.unavailable"));
            return;
        }
        String err = m.game(ar).spectate(p);
        if (err != null) {
            Msg.send(p, err.startsWith("&") ? err : "&c" + err);
            return;
        }
        Msg.send(p, t("spectate.started", "arena", ar.name, "game", ar.type.display));
    }

    /**
     * Joins a player: the exact arena {@code requested}, or any free arena of {@code type} (null = any game).
     * Used by /arcade join and by the arena menu.
     */
    public void attemptJoin(Player p, Arena requested, GameType type) {
        if (m.gameOf(p) != null) {
            Msg.send(p, t("join.already-in-game"));
            return;
        }
        if (m.spectatorGame(p) != null) {
            Msg.send(p, t("join.spectating"));
            return;
        }
        GameType wanted = requested != null ? requested.type : type;
        if (wanted != null && !plugin.settings().enabled(wanted)) {
            Msg.send(p, t("join.game-disabled", "game", wanted.display));
            return;
        }

        // why the arena that was asked for can't be joined (null = it can)
        String reason = null;
        if (requested != null) {
            String problem = requested.validate();
            reason = problem != null ? t("join.not-ready", "problem", problem) : m.game(requested).whyNotJoinable();
        }

        // Try every candidate, best first. If one fills up or refuses, the next one is used automatically.
        String lastError = reason;
        for (Arena target : m.attempts(requested, type)) {
            String err = m.game(target).join(p);
            if (err != null) {
                if (lastError == null) lastError = "&c" + err;
                continue;
            }
            if (requested != null && target != requested) {
                Msg.send(p, t("join.redirected", "requested", requested.name,
                        "reason", strip(reason == null ? "unavailable" : reason), "arena", target.name));
            } else if (requested == null && plugin.getConfig().getBoolean("matchmaking.join-message", true)) {
                Msg.send(p, t("join.joining", "arena", target.name, "game", target.type.display));
            }
            return;
        }

        // nothing could be joined
        if (lastError != null && requested != null) {
            Msg.send(p, lastError.startsWith("&") ? lastError : "&c" + lastError);
        } else if (type != null) {
            Msg.send(p, t("join.none-available-game", "game", type.display));
        } else {
            Msg.send(p, t("join.none-available"));
        }
    }

    /** Removes colour codes so a reason can be put inside another coloured message. */
    private static String strip(String s) {
        return s.replaceAll("&[0-9a-fk-orA-FK-OR]", "");
    }

    private void leave(CommandSender s) {
        if (!(s instanceof Player p)) return;
        Game sg = m.spectatorGame(p);
        if (sg != null) {
            sg.stopSpectating(p);
            Msg.send(p, t("spectate.stopped"));
            return;
        }
        Game g = m.gameOf(p);
        if (g == null) {
            Msg.send(p, t("leave.not-in-game"));
            return;
        }
        g.leave(p);
        Msg.send(p, t("common.you-left"));
    }

    private void list(CommandSender s) {
        if (m.arenas().isEmpty()) {
            Msg.send(s, "No arenas yet.");
            return;
        }
        Msg.plain(s, "&8&m      &r &bArenas &8&m      ");
        for (Arena ar : m.arenas()) {
            String problem = ar.validate();
            if (problem != null) {
                Msg.plain(s, "&e" + ar.name + " &8- &7" + ar.type.display + " &c(not ready)");
            } else {
                Game g = m.game(ar);
                Msg.plain(s, "&e" + ar.name + " &8- &7" + ar.type.display + " &8- &f" + g.state().name().toLowerCase()
                        + " &8(&b" + g.playerCount() + "&8/&b" + ar.max + "&8)");
            }
        }
    }

    // ------------------------------------------------------------ admin commands

    private Arena arena(CommandSender s, String[] a, int i) {
        if (a.length <= i) {
            Msg.send(s, "&cMissing arena name.");
            return null;
        }
        Arena ar = m.arena(a[i]);
        if (ar == null) Msg.send(s, "&cUnknown arena &e" + a[i]);
        return ar;
    }

    private void admin(CommandSender s, String[] a) {
        String sub = a[0].toLowerCase();
        if (!ADMIN_SUBS.contains(sub)) {
            help(s);
            return;
        }
        if (!s.hasPermission("arcade.admin")) {
            Msg.send(s, "&cYou don't have permission.");
            return;
        }
        Player p = s instanceof Player pl ? pl : null;
        switch (sub) {
            case "create" -> {
                if (a.length < 3) {
                    Msg.send(s, "&cUsage: /arcade create <name> <prophunt|blockparty|tntrun|partygames|miniwalls>");
                    return;
                }
                if (!a[1].matches("[A-Za-z0-9_-]+")) {
                    Msg.send(s, "&cArena names may only contain letters, numbers, - and _");
                    return;
                }
                GameType t = GameType.parse(a[2]);
                if (t == null) {
                    Msg.send(s, "&cUnknown game. Choose: prophunt, blockparty, tntrun, partygames, miniwalls");
                    return;
                }
                if (m.create(a[1], t) == null) {
                    Msg.send(s, "&cAn arena with that name already exists.");
                    return;
                }
                m.save();
                Msg.send(s, "&aCreated &e" + a[1] + " &a(" + t.display + "). " + setupHint(t));
            }
            case "reload" -> {
                plugin.reloadAll();
                Msg.send(s, t("reload.done"));
            }
            case "delete" -> {
                if (a.length < 2 || !m.delete(a[1])) {
                    Msg.send(s, "&cUnknown arena.");
                    return;
                }
                m.save();
                Msg.send(s, "&aArena deleted.");
            }
            case "setwaiting", "setspawn", "setwither", "pos1", "pos2", "setspectator" -> {
                if (p == null) {
                    Msg.send(s, "&cOnly players can do that.");
                    return;
                }
                Arena ar = arena(s, a, 1);
                if (ar == null) return;
                setLocation(p, ar, sub, a);
                m.save();
            }
            case "setplayers" -> {
                Arena ar = arena(s, a, 1);
                if (ar == null) return;
                try {
                    ar.min = Math.max(1, Integer.parseInt(a[2]));
                    ar.max = Math.max(ar.min, Integer.parseInt(a[3]));
                    m.save();
                    Msg.send(s, "&aPlayers set to &e" + ar.min + "-" + ar.max + "&a.");
                } catch (RuntimeException ex) {
                    Msg.send(s, "&cUsage: /arcade setplayers <name> <min> <max>");
                }
            }
            case "info" -> {
                Arena ar = arena(s, a, 1);
                if (ar == null) return;
                String problem = ar.validate();
                Msg.plain(s, "&e" + ar.name + " &7(" + ar.type.display + ") players &b" + ar.min + "-" + ar.max);
                Msg.plain(s, "&7spawns: &f" + ar.spawns.keySet() + " &7withers: &f" + ar.withers.keySet()
                        + " &7region: &f" + (ar.hasRegion() ? "set" : "not set")
                        + " &7spectator: &f" + (ar.spectator != null ? "set" : "not set (automatic)"));
                Msg.plain(s, problem == null ? "&aReady to play." : "&cNot ready: " + problem);
            }
            case "forcestart" -> {
                Arena ar = arena(s, a, 1);
                if (ar == null) return;
                if (ar.validate() != null) {
                    Msg.send(s, "&cArena isn't ready: " + ar.validate());
                    return;
                }
                Msg.send(s, m.game(ar).forceStart() ? "&aStarted." : "&cCan't start (no players or already running).");
            }
            case "forcestop" -> {
                Arena ar = arena(s, a, 1);
                if (ar == null) return;
                m.game(ar).shutdown();
                Msg.send(s, "&aGame stopped.");
            }
            case "checkpoint", "cheakpoint" -> {
                if (p == null || a.length < 4) { Msg.send(s, "&cUsage: /arcade checkpoint <add|remove> <arena> <number>"); return; }
                String action = a[1].toLowerCase(); Arena ar = m.arena(a[2]);
                if (ar == null) { Msg.send(s, "&cUnknown arena."); return; }
                int n; try { n = Integer.parseInt(a[3]); if (n < 1) throw new NumberFormatException(); } catch (NumberFormatException ex) { Msg.send(s, "&cCheckpoint number must be 1 or greater."); return; }
                if (!ar.type.key.equals("templerun")) { Msg.send(s, "&cCheckpoints are for Temple Run arenas."); return; }
                if (action.equals("remove")) {
                    Location old = ar.checkpoints.remove(n); if (old != null && old.getBlock().getType() == Material.LIGHT_WEIGHTED_PRESSURE_PLATE) old.getBlock().setType(Material.AIR);
                    m.save(); Msg.send(s, old == null ? "&cThat checkpoint did not exist." : "&aCheckpoint removed.");
                } else if (action.equals("add")) {
                    Location loc = p.getLocation().getBlock().getLocation(); loc.getBlock().setType(Material.LIGHT_WEIGHTED_PRESSURE_PLATE); ar.checkpoints.put(n, loc); m.save();
                    Msg.send(p, "&aCheckpoint #" + n + " added at your feet.");
                } else Msg.send(s, "&cUse add or remove.");
            }
            case "coin" -> {
                if (p == null || a.length < 3 || !a[1].equalsIgnoreCase("add")) { Msg.send(s, "&cUsage: /arcade coin add <arena>"); return; }
                Arena ar = m.arena(a[2]); if (ar == null) { Msg.send(s, "&cUnknown arena."); return; }
                if (ar.type != GameType.TEMPLE_RUN) { Msg.send(s, "&cCoins are for Temple Run arenas."); return; }
                ItemStack tool = new ItemStack(Material.GOLD_NUGGET); var meta = tool.getItemMeta(); meta.displayName(Msg.c("&6&lTemple Coin Placer")); meta.lore(List.of(Msg.c("&7Right-click a block to add a coin location"), Msg.c("&8Arena: " + ar.name))); tool.setItemMeta(meta);
                me.arcade.games.util.Items.tag(plugin, tool, "temple_coin_tool", ar.name); p.getInventory().addItem(tool); Msg.send(p, "&aRight-click blocks with this nugget to add coin spawn locations.");
            }
            case "end" -> {
                if (p == null || a.length < 2) { Msg.send(s, "&cUsage: /arcade end <arena>"); return; }
                Arena ar = m.arena(a[1]); if (ar == null) { Msg.send(s, "&cUnknown arena."); return; }
                if (ar.type != GameType.TEMPLE_RUN) { Msg.send(s, "&cFinish plates are for Temple Run arenas."); return; }
                ItemStack tool = new ItemStack(Material.HEAVY_WEIGHTED_PRESSURE_PLATE); var meta = tool.getItemMeta(); meta.displayName(Msg.c("&f&lTemple Run Finish Placer")); meta.lore(List.of(Msg.c("&7Right-click a block to place a finish"), Msg.c("&8Arena: " + ar.name))); tool.setItemMeta(meta);
                me.arcade.games.util.Items.tag(plugin, tool, "temple_end_tool", ar.name); p.getInventory().addItem(tool); Msg.send(p, "&aRight-click a block to register a finish pressure plate.");
            }
            case "wall" -> {
                if (p == null || a.length < 3) { Msg.send(s, "&cUsage: /arcade wall <arena> <team 1-4> (run twice, targeting each wall corner)"); return; }
                Arena ar = m.arena(a[1]); if (ar == null || ar.type != GameType.MINI_WALLS) { Msg.send(s, "&cUnknown Mini Walls arena."); return; }
                int team; try { team = Integer.parseInt(a[2]); if (team < 1 || team > 4) throw new NumberFormatException(); } catch (NumberFormatException ex) { Msg.send(s, "&cTeam must be 1-4."); return; }
                Block target = p.getTargetBlockExact(8); if (target == null) { Msg.send(s, "&cLook at a wall block within 8 blocks."); return; }
                WallPending pending = wallPending.remove(p.getUniqueId());
                if (pending == null || !pending.arena().equalsIgnoreCase(ar.name) || pending.team() != team) {
                    wallPending.put(p.getUniqueId(), new WallPending(ar.name, team, target.getLocation())); Msg.send(p, "&aFirst corner saved for team " + team + ". Look at the opposite corner and run the same command again.");
                } else {
                    ar.walls.computeIfAbsent(team, k -> new ArrayList<>()).add(new Arena.WallRegion(pending.first(), target.getLocation())); m.save();
                    Msg.send(p, "&aWall region added for team " + team + ". Total team wall regions: " + ar.walls.get(team).size());
                }
            }
            default -> help(s);
        }
    }

    private void setLocation(Player p, Arena ar, String sub, String[] a) {
        Location here = p.getLocation();
        switch (sub) {
            case "setwaiting" -> {
                ar.waiting = here;
                Msg.send(p, "&aWaiting lobby set.");
            }
            case "setspawn" -> {
                int n = ar.spawns.size() + 1;
                if (a.length >= 3) {
                    try {
                        n = Integer.parseInt(a[2]);
                    } catch (NumberFormatException ex) {
                        Msg.send(p, "&cSpawn number must be a number.");
                        return;
                    }
                }
                ar.spawns.put(n, here);
                Msg.send(p, "&aSpawn &e#" + n + " &aset." + (ar.type == GameType.MINI_WALLS || ar.type == GameType.BOMBLOBBERS ? " &7(= team " + n + ")" : ""));
            }
            case "setspectator" -> {
                ar.spectator = here;
                Msg.send(p, "&aSpectator spawn set. &7(Spectators and eliminated players appear here.)");
            }
            case "setwither" -> {
                if (a.length < 3) {
                    Msg.send(p, "&cUsage: /arcade setwither <arena> <team 1-4>");
                    return;
                }
                try {
                    int n = Integer.parseInt(a[2]);
                    ar.withers.put(n, here);
                    Msg.send(p, "&aWither for team &e" + n + " &aset.");
                } catch (NumberFormatException ex) {
                    Msg.send(p, "&cTeam must be a number.");
                }
            }
            case "pos1", "pos2" -> {
                // the block the player is standing ON
                Location b = here.clone().subtract(0, 1, 0).getBlock().getLocation();
                if (sub.equals("pos1")) ar.pos1 = b;
                else ar.pos2 = b;
                Msg.send(p, "&a" + sub + " set to &e" + b.getBlockX() + ", " + b.getBlockY() + ", " + b.getBlockZ());
            }
            default -> {}
        }
    }

    private String setupHint(GameType t) {
        return switch (t) {
            case TNT_RUN -> "&7Next: setwaiting, setspawn, pos1/pos2 (bottom + top corner of the whole floor stack), setspectator.";
            case BLOCK_PARTY -> "&7Next: setwaiting, setspawn, pos1/pos2 (two corners of the flat dance floor), setspectator.";
            case PARTY_GAMES -> "&7Next: setwaiting, setspawn, pos1/pos2 (two corners of one flat platform with void/drop around it), setspectator.";
            case PROP_HUNT -> "&7Next: setwaiting, setspawn 1 (hiders) and setspawn 2 (seekers), setspectator.";
            case MINI_WALLS -> "&7Next: setwaiting, then setspawn 1-4 and setwither 1-4 (one per team), setspectator.";
            case BOMBLOBBERS -> "&7Next: setwaiting, setspawn 1 (Red team) and setspawn 2 (Blue team), pos1/pos2 (arena bounds), setspectator.";
            case TNT_TAG, TEMPLE_RUN, SPLEGG, QUAKE -> "&7Next: setwaiting, setspawn, pos1/pos2 (arena bounds if needed), setspectator.";
        };
    }

    // ------------------------------------------------------------ tab completion

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String label, String[] a) {
        List<String> out = new ArrayList<>();
        if (a.length == 1) {
            out.addAll(PLAYER_SUBS);
            if (s.hasPermission("arcade.admin")) out.addAll(ADMIN_SUBS);
        } else if (a.length == 2) {
            String sub = a[0].toLowerCase();
            if (sub.equals("join")) {
                out.addAll(GAME_NAMES);
                for (Arena ar : m.arenas()) out.add(ar.name);
            } else if (sub.equals("gui") || sub.equals("menu") || sub.equals("arenas")) {
                out.addAll(GAME_NAMES);
            } else if (sub.equals("spectate") || sub.equals("spec") || sub.equals("watch")) {
                out.addAll(GAME_NAMES);
                for (Arena ar : m.arenas()) out.add(ar.name);
            } else if (sub.equals("create")) {
                return List.of();
            } else if (sub.equals("party")) {
                out.addAll(List.of("create", "invite", "accept", "leave", "disband", "list", "games", "menu"));
            } else if (sub.equals("checkpoint") || sub.equals("cheakpoint")) {
                out.addAll(List.of("add", "remove"));
            } else if (sub.equals("coin")) {
                out.add("add");
            } else if (ADMIN_SUBS.contains(sub)) {
                for (Arena ar : m.arenas()) out.add(ar.name);
            }
        } else if (a.length == 3 && a[0].equalsIgnoreCase("create")) {
            out.addAll(GAME_NAMES);
        } else if (a.length == 3 && (a[0].equalsIgnoreCase("checkpoint") || a[0].equalsIgnoreCase("cheakpoint"))) {
            for (Arena ar : m.arenas()) if (ar.type == GameType.TEMPLE_RUN) out.add(ar.name);
        } else if (a.length == 3 && a[0].equalsIgnoreCase("coin") || a.length == 2 && a[0].equalsIgnoreCase("end") || a.length == 2 && a[0].equalsIgnoreCase("wall")) {
            for (Arena ar : m.arenas()) if ((a[0].equalsIgnoreCase("coin") || a[0].equalsIgnoreCase("end")) ? ar.type == GameType.TEMPLE_RUN : ar.type == GameType.MINI_WALLS) out.add(ar.name);
        } else if (a.length == 4 && (a[0].equalsIgnoreCase("checkpoint") || a[0].equalsIgnoreCase("cheakpoint"))) {
            for (int i = 1; i <= 10; i++) out.add(String.valueOf(i));
        } else if (a.length == 3 && a[0].equalsIgnoreCase("wall")) {
            out.addAll(List.of("1", "2", "3", "4"));
        }
        String prefix = a[a.length - 1].toLowerCase();
        out.removeIf(x -> !x.toLowerCase().startsWith(prefix));
        return out;
    }
}
