package me.arcade.games.game.impl;

import me.arcade.games.ArcadePlugin;
import me.arcade.games.arena.Arena;
import me.arcade.games.game.Game;
import me.arcade.games.util.Msg;
import me.arcade.games.util.Settings;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.block.Block;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import io.papermc.paper.entity.TeleportFlag;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.*;

public class PropHuntGame extends Game {
    private static final List<Material> DEFAULT_PROPS = List.of(
            Material.CRAFTING_TABLE, Material.BOOKSHELF, Material.FURNACE, Material.BRICKS, Material.HAY_BLOCK,
            Material.PUMPKIN, Material.MELON, Material.TNT, Material.JUKEBOX, Material.BARREL, Material.OAK_LOG,
            Material.COBBLESTONE, Material.STONE_BRICKS, Material.OAK_PLANKS, Material.SANDSTONE);
    private int hideTicks, seekTicks;
    private Material changerMat = Material.NETHER_STAR;

    private final Set<UUID> hiders = new HashSet<>();
    private final Set<UUID> seekers = new HashSet<>();
    /** Falling blocks are removed by the server after 600 ticks, so the rider is respawned before that. */
    private static final int RESPAWN_TICKS = 300;

    /**
     * One visible copy of a disguise. For normal blocks {@code base} is an invisible marker armor stand that is
     * moved every tick and {@code fb} is a FallingBlock riding it (the server syncs falling blocks only about
     * once a second, so a rider on a fast-updating carrier looks far smoother). For block-entity blocks
     * {@code base} is a BlockDisplay and {@code fb} is null.
     */
    private static final class Variant {
        Entity base;
        FallingBlock fb;
        int lastBlockLight = -1, lastSkyLight = -1;

        void remove() {
            if (fb != null) fb.remove();
            if (base != null) base.remove();
        }
    }

    /**
     * One hider's disguise. Geyser puts the block at a different height for Bedrock than Java sees, so there
     * is a copy for each: {@code java} (shown to Java players) and {@code bedrock} (shown to Bedrock players).
     * {@code bedrock} is null for blocks Bedrock cannot show anyway.
     */
    private static final class Prop {
        Variant java;
        Variant bedrock;
        Material type;
        int age;

        void remove() {
            if (java != null) java.remove();
            if (bedrock != null) bedrock.remove();
        }
    }

    private final Map<UUID, Prop> disguises = new HashMap<>();
    private final Random rnd = new Random();
    private boolean seeking;
    private int timer;
    private Team hideTeam;
    private List<Material> props = DEFAULT_PROPS;

    public PropHuntGame(ArcadePlugin plugin, Arena arena) {
        super(plugin, arena);
    }


    /** Reads a list of block names from config.yml; unknown names are skipped with a warning. */
    private List<Material> configured(String path) {
        List<Material> out = new ArrayList<>();
        for (String name : cfg().getStringList(path)) {
            Material m = Material.matchMaterial(name.trim());
            if (m != null && m.isBlock()) out.add(m);
            else plugin.getLogger().warning("Prop Hunt: unknown block '" + name + "' in " + path);
        }
        return out;
    }

    @Override
    protected void onStart() {
        detectorsLoaded = false; // re-check for Floodgate/Geyser each game
        List<Material> start = configured("start-blocks");
        props = start.isEmpty() ? DEFAULT_PROPS : start;
        hideTicks = Math.max(1, Settings.ticks(cfg().getDouble("hide-seconds")));
        seekTicks = Math.max(1, Settings.ticks(cfg().getDouble("seek-seconds")));
        changerMat = Settings.material(cfg().getString("hider.changer-item"), Material.NETHER_STAR);
        Scoreboard sb = Bukkit.getScoreboardManager().getMainScoreboard();
        hideTeam = sb.getTeam("ah_hide");
        if (hideTeam == null) hideTeam = sb.registerNewTeam("ah_hide");
        hideTeam.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.NEVER);

        List<Player> list = allPlayers();
        for (Player q : list) {
            plugin.getLogger().info("Prop Hunt: " + q.getName() + " detected as " + (isBedrock(q) ? "BEDROCK" : "JAVA"));
        }
        Collections.shuffle(list);
        int perSeeker = Math.max(1, cfg().getInt("players-per-seeker"));
        int seekerCount = Math.max(1, list.size() / perSeeker);
        List<Location> sp = arena.spawnList();
        Location hiderSpawn = sp.get(0);
        Location seekerSpawn = sp.size() > 1 ? sp.get(1) : sp.get(0);
        seeking = false;
        timer = hideTicks;

        for (int i = 0; i < list.size(); i++) {
            Player p = list.get(i);
            if (i < seekerCount) {
                seekers.add(p.getUniqueId());
                p.teleport(seekerSpawn);
                if (cfg().getBoolean("seeker.blind-while-waiting")) {
                    p.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, hideTicks + 40, 0, false, false, false));
                }
                if (cfg().getBoolean("seeker.freeze-while-waiting")) {
                    p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, hideTicks, 6, false, false, false));
                }
                giveSeekerKit(p);
                Msg.title(p, t("prophunt.seeker-title"),
                        t("prophunt.seeker-subtitle", "seconds", Settings.num(cfg().getDouble("hide-seconds"))));
            } else {
                hiders.add(p.getUniqueId());
                p.teleport(hiderSpawn);
                p.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, PotionEffect.INFINITE_DURATION, 0, false, false, false));
                hideTeam.addEntry(p.getName());
                disguise(p, props.get(rnd.nextInt(props.size())));
                p.getInventory().addItem(Msg.item(changerMat, 1, cfg().getString("hider.changer-name"),
                        cfg().getStringList("hider.changer-lore").toArray(new String[0])));
                Msg.title(p, t("prophunt.hider-title"), t("prophunt.hider-subtitle"));
            }
        }
        broadcast(t("prophunt.chosen", "count", seekerCount, "seconds", Settings.num(cfg().getDouble("hide-seconds"))));
    }

    private void giveSeekerKit(Player p) {
        p.getInventory().clear();
        p.getInventory().addItem(Msg.item(Settings.material(cfg().getString("seeker.item"), Material.IRON_SWORD), 1,
                cfg().getString("seeker.item-name")));
    }

    /**
     * Blocks drawn by a block-entity renderer (chests, beds, signs, ...). A falling block can't show these on
     * any client, so they use a BlockDisplay (Java players only - Bedrock cannot show display entities).
     */
    private static boolean needsDisplay(Material m) {
        String n = m.name();
        return n.endsWith("CHEST") || n.endsWith("_BED") || n.endsWith("SHULKER_BOX") || n.endsWith("_BANNER")
                || n.contains("SIGN") || n.endsWith("_HEAD") || n.endsWith("_SKULL") || n.equals("BELL")
                || n.equals("CONDUIT") || n.equals("ENCHANTING_TABLE") || n.equals("DECORATED_POT");
    }

    private static Location flat(Location l) {
        Location c = l.clone();
        c.setYaw(0f);
        c.setPitch(0f);
        return c;
    }

    private double javaOffset() {
        return cfg().getDouble("block-y-offset");
    }

    private double bedrockOffset() {
        return cfg().getDouble("bedrock-block-y-offset");
    }

    // ---- Bedrock detection: Floodgate / Geyser through reflection, so neither is a hard dependency ----
    private java.lang.reflect.Method fgIsPlayer, gsIsPlayer;
    private Object fgApi, gsApi;
    private boolean detectorsLoaded;

    private void loadDetectors() {
        detectorsLoaded = true;
        fgApi = null;
        fgIsPlayer = null;
        gsApi = null;
        gsIsPlayer = null;
        try {
            Class<?> c = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
            fgApi = c.getMethod("getInstance").invoke(null);
            fgIsPlayer = c.getMethod("isFloodgatePlayer", UUID.class);
        } catch (Throwable ignored) {
            fgApi = null;
            fgIsPlayer = null;
        }
        try {
            Class<?> c = Class.forName("org.geysermc.geyser.api.GeyserApi");
            gsApi = c.getMethod("api").invoke(null);
            gsIsPlayer = c.getMethod("isBedrockPlayer", UUID.class);
        } catch (Throwable ignored) {
            gsApi = null;
            gsIsPlayer = null;
        }
    }

    private boolean isBedrock(Player p) {
        if (!detectorsLoaded) loadDetectors();
        UUID id = p.getUniqueId();
        try {
            if (fgIsPlayer != null && (Boolean) fgIsPlayer.invoke(fgApi, id)) return true;
        } catch (Throwable ignored) {
        }
        try {
            if (gsIsPlayer != null && (Boolean) gsIsPlayer.invoke(gsApi, id)) return true;
        } catch (Throwable ignored) {
        }
        if (id.getMostSignificantBits() == 0L) return true; // Floodgate's UUID format
        // Floodgate puts a prefix (default ".") in front of Bedrock names; Java names can't contain it
        String prefix = cfg().getString("bedrock-name-prefix");
        return prefix != null && !prefix.isEmpty() && p.getName().startsWith(prefix);
    }

    private void setHidden(Player viewer, Variant v, boolean hide) {
        if (v == null) return;
        for (Entity e : new Entity[]{v.base, v.fb}) {
            if (e == null) continue;
            if (hide) viewer.hideEntity(plugin, e);
            else viewer.showEntity(plugin, e);
        }
    }

    /** Bedrock players only see the Bedrock copy, Java players only the Java copy. */
    private void syncVisibility(Prop pr) {
        if (pr.bedrock == null) return;
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            boolean bedrock = isBedrock(viewer);
            setHidden(viewer, pr.java, bedrock);
            setHidden(viewer, pr.bedrock, !bedrock);
        }
    }

    /** Spawns a FallingBlock showing {@code m} and mounts it on {@code carrier}. */
    private FallingBlock spawnFalling(Location at, Material m, Entity carrier) {
        FallingBlock fb = at.getWorld().spawnFallingBlock(at, m.createBlockData());
        fb.setGravity(false);
        fb.setDropItem(false);
        fb.setHurtEntities(false);
        fb.setPersistent(false);
        fb.setInvulnerable(true);
        fb.addScoreboardTag("ag_fall"); // GameListener cancels any attempt of it turning into a real block
        carrier.addPassenger(fb);
        return fb;
    }

    private Variant spawnCarried(Location l, Material m, double yOff) {
        Location at = l.clone().add(0, yOff, 0);
        Variant v = new Variant();
        v.base = l.getWorld().spawn(at, ArmorStand.class, as -> {
            as.setInvisible(true);
            as.setMarker(true);
            as.setGravity(false);
            as.setSilent(true);
            as.setInvulnerable(true);
            as.setBasePlate(false);
            as.setPersistent(false);
            as.addScoreboardTag("ag_fall");
        });
        v.fb = spawnFalling(at, m, v.base);
        return v;
    }

    private double displayOffset() {
        return cfg().getDouble("display-y-offset");
    }

    private Variant spawnDisplay(Location l, Material m) {
        Variant v = new Variant();
        Location at = l.clone().add(0, displayOffset(), 0);
        v.base = l.getWorld().spawn(at, BlockDisplay.class, bd -> {
            bd.setBlock(m.createBlockData());
            bd.setTransformation(new Transformation(new Vector3f(-0.5f, 0f, -0.5f), new AxisAngle4f(),
                    new Vector3f(1f, 1f, 1f), new AxisAngle4f()));
            bd.setTeleportDuration(1);
            bd.setPersistent(false);
        });
        applyLight(v, l);
        return v;
    }

    /**
     * Entities are drawn without the soft corner shadows ("ambient occlusion") placed blocks have, so a
     * disguise looks slightly brighter than the real block next to it. A BlockDisplay lets us set its
     * brightness: real light at the hider's spot minus {@code light-reduction} (games/prophunt.yml).
     * A negative light-reduction switches this off (the client then lights it by itself).
     */
    private void applyLight(Variant v, Location l) {
        if (v == null || !(v.base instanceof BlockDisplay bd)) return;
        int red = cfg().getInt("light-reduction", 2);
        if (red < 0) return;
        Block b = l.getBlock();
        int sky = Math.max(0, Math.min(15, b.getLightFromSky() - red));
        int blk = Math.max(0, Math.min(15, b.getLightFromBlocks() - red));
        if (cfg().getBoolean("light-fixed", false)) {
            sky = Math.max(0, Math.min(15, cfg().getInt("light-fixed-sky", 12)));
            blk = Math.max(0, Math.min(15, cfg().getInt("light-fixed-block", 0)));
        }
        if (sky == v.lastSkyLight && blk == v.lastBlockLight) return;
        v.lastSkyLight = sky;
        v.lastBlockLight = blk;
        bd.setBrightness(new Display.Brightness(blk, sky));
    }

    /** True when Java players see the disguise as a BlockDisplay (default) instead of a falling block. */
    private boolean javaUsesDisplay() {
        return !"falling".equalsIgnoreCase(cfg().getString("java-render", "display").trim());
    }

    /**
     * Creates the disguise. Bedrock (through Geyser) cannot see display entities, so Bedrock players always get the
     * falling-block copy; Java players get the dimmed BlockDisplay (or the falling block when java-render: falling).
     */
    private Prop spawnProp(Location l, Material m) {
        Prop pr = new Prop();
        pr.type = m;
        if (needsDisplay(m)) {
            pr.java = spawnDisplay(l, m);
            return pr;
        }
        pr.java = javaUsesDisplay() ? spawnDisplay(l, m) : spawnCarried(l, m, javaOffset());
        pr.bedrock = spawnCarried(l, m, bedrockOffset());
        syncVisibility(pr);
        return pr;
    }

    private void moveVariant(Variant v, Location l, double yOff) {
        if (v.fb != null) {
            v.base.teleport(l.clone().add(0, yOff, 0), PlayerTeleportEvent.TeleportCause.PLUGIN,
                    TeleportFlag.EntityState.RETAIN_PASSENGERS);
        } else {
            v.base.teleport(l.clone().add(0, displayOffset(), 0));
        }
    }

    private static boolean broken(Variant v) {
        return v != null && (v.base.isDead() || !v.base.isValid());
    }

    /** Replaces the rider block when it is about to expire (or died). Returns true if it was replaced. */
    private boolean refreshRider(Variant v, Material m, boolean force) {
        if (v == null || v.fb == null) return false;
        if (!force && !v.fb.isDead() && v.fb.isValid()) return false;
        FallingBlock old = v.fb;
        v.fb = spawnFalling(v.base.getLocation(), m, v.base);
        old.remove();
        return true;
    }

    private void disguise(Player p, Material m) {
        disguises.put(p.getUniqueId(), spawnProp(flat(p.getLocation()), m));
    }

    private void undisguise(Player p) {
        Prop pr = disguises.remove(p.getUniqueId());
        if (pr != null) pr.remove();
        if (hideTeam != null) hideTeam.removeEntry(p.getName());
    }

    @Override
    protected void tick() {
        double jy = javaOffset();
        double by = bedrockOffset();
        for (Map.Entry<UUID, Prop> en : disguises.entrySet()) {
            Player p = Bukkit.getPlayer(en.getKey());
            if (p == null) continue;
            Prop pr = en.getValue();
            Location l = flat(p.getLocation());
            if (broken(pr.java) || broken(pr.bedrock)) {
                // a carrier is gone: rebuild everything (new one first, so there is no flicker)
                Prop fresh = spawnProp(l, pr.type);
                pr.remove();
                en.setValue(fresh);
                continue;
            }
            pr.age++;
            boolean expiring = pr.age >= RESPAWN_TICKS;
            boolean changed = refreshRider(pr.java, pr.type, expiring);
            changed |= refreshRider(pr.bedrock, pr.type, expiring);
            if (expiring) pr.age = 0;
            moveVariant(pr.java, l, jy);
            if (pr.bedrock != null) moveVariant(pr.bedrock, l, by);
            if (ticks % 5 == 0) applyLight(pr.java, l);
            if (changed || ticks % 20 == 0) syncVisibility(pr);
        }
        timer--;
        if (!seeking) {
            if (timer <= 0) {
                seeking = true;
                timer = seekTicks;
                for (UUID id : seekers) {
                    Player p = Bukkit.getPlayer(id);
                    if (p == null) continue;
                    p.removePotionEffect(PotionEffectType.BLINDNESS);
                    p.removePotionEffect(PotionEffectType.SLOWNESS);
                }
                broadcast(t("prophunt.released"));
                sound(Sound.ENTITY_ENDER_DRAGON_GROWL);
            } else if (ticks % 20 == 0) {
                if (plugin.getConfig().getBoolean("timer.enabled", true)) for (Player p : allPlayers()) Msg.bar(p, t("prophunt.release-countdown", "seconds", timer / 20 + 1));
            }
        } else {
            if (timer <= 0) {
                List<String> names = new ArrayList<>();
                for (UUID id : hiders) names.add(nameOf(id));
                end(t("prophunt.hiders-win-time", "names", String.join(", ", names)));
                return;
            }
            if (ticks % 20 == 0) {
                int secs = timer / 20;
                for (Player p : allPlayers()) {
                    Msg.bar(p, t("prophunt.status",
                            "role", t(hiders.contains(p.getUniqueId()) ? "prophunt.role-hider" : "prophunt.role-seeker"),
                            "hiders", hiders.size(),
                            "time", (secs / 60) + ":" + String.format("%02d", secs % 60)));
                }
            }
        }
    }

    /** Time left in the CURRENT phase (hiding countdown, then the seek timer). */
    @Override
    public String remainingTimeText() {
        if (state != State.ACTIVE) return "--:--";
        int secs = Math.max(0, (timer + 19) / 20);
        return String.format(Locale.ROOT, "%d:%02d", secs / 60, secs % 60);
    }

    @Override
    public String scoreboardValue(Player player, String key) {
        return switch (key.toLowerCase(Locale.ROOT)) {
            case "phase" -> seeking ? "Seeking" : "Hiding";
            case "role" -> hiders.contains(player.getUniqueId()) ? "Hider" : seekers.contains(player.getUniqueId()) ? "Seeker" : "-";
            case "hiders" -> String.valueOf(hiders.size());
            case "seekers" -> String.valueOf(seekers.size());
            default -> super.scoreboardValue(player, key);
        };
    }

    @Override
    public void onDamage(Player victim, EntityDamageEvent e) {
        e.setCancelled(true);
        if (!seeking) return;
        Player d = attacker(e);
        if (d != null && seekers.contains(d.getUniqueId()) && hiders.contains(victim.getUniqueId())) {
            caught(victim, d);
        }
    }

    private void caught(Player victim, Player seeker) {
        UUID id = victim.getUniqueId();
        hiders.remove(id);
        seekers.add(id);
        undisguise(victim);
        victim.removePotionEffect(PotionEffectType.INVISIBILITY);
        giveSeekerKit(victim);
        broadcast(t("prophunt.caught", "victim", victim.getName(), "seeker", seeker.getName(), "hiders", hiders.size()));
        victim.playSound(victim.getLocation(), Sound.ENTITY_PLAYER_HURT, 1f, 1f);
        Msg.title(victim, t("prophunt.found-title"), t("prophunt.found-subtitle"));
        checkEnd();
    }

    @Override
    public void onInteract(Player p, PlayerInteractEvent e) {
        if (!hiders.contains(p.getUniqueId()) || e.getItem() == null || e.getItem().getType() != changerMat) return;
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getClickedBlock() == null) return;
        e.setCancelled(true);
        Material m = e.getClickedBlock().getType();
        List<Material> allowed = configured("allowed-blocks");
        boolean ok = allowed.isEmpty() ? (m.isSolid() && m.isOccluding()) : allowed.contains(m);
        if (!ok) {
            Msg.bar(p, t("prophunt.cant-disguise"));
            return;
        }
        Prop old = disguises.get(p.getUniqueId());
        if (old != null) {
            disguises.put(p.getUniqueId(), spawnProp(flat(p.getLocation()), m));
            old.remove();
        }
        Msg.bar(p, t("prophunt.disguised", "block", m.name().toLowerCase().replace('_', ' ')));
    }

    @Override
    protected void onLeave(Player p, boolean wasAlive) {
        hiders.remove(p.getUniqueId());
        seekers.remove(p.getUniqueId());
        undisguise(p);
    }

    @Override
    protected void checkEnd() {
        if (hiders.isEmpty()) end(t("prophunt.seekers-win"));
        else if (seekers.isEmpty()) end(t("prophunt.hiders-win-left"));
    }

    @Override
    protected void onEnd() {
        for (Prop pr : disguises.values()) pr.remove();
        disguises.clear();
        if (hideTeam != null) {
            for (String entry : new ArrayList<>(hideTeam.getEntries())) hideTeam.removeEntry(entry);
        }
        hiders.clear();
        seekers.clear();
    }
}
