package me.arcade.games.game.impl;

import me.arcade.games.ArcadePlugin;
import me.arcade.games.arena.Arena;
import me.arcade.games.game.Game;
import me.arcade.games.util.Msg;
import me.arcade.games.util.Settings;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.*;

public class BlockPartyGame extends Game {
    private static final Material[] COLORS = {
            Material.WHITE_WOOL, Material.ORANGE_WOOL, Material.MAGENTA_WOOL, Material.LIGHT_BLUE_WOOL,
            Material.YELLOW_WOOL, Material.LIME_WOOL, Material.PINK_WOOL, Material.GRAY_WOOL,
            Material.LIGHT_GRAY_WOOL, Material.CYAN_WOOL, Material.PURPLE_WOOL, Material.BLUE_WOOL,
            Material.BROWN_WOOL, Material.GREEN_WOOL, Material.RED_WOOL, Material.BLACK_WOOL};
    private static final String[] NAMES = {
            "White", "Orange", "Magenta", "Light Blue", "Yellow", "Lime", "Pink", "Gray",
            "Light Gray", "Cyan", "Purple", "Blue", "Brown", "Green", "Red", "Black"};

    private final Map<Block, BlockData> original = new HashMap<>();
    private final Random rnd = new Random();
    private int round, timer, total, floorY;
    private boolean cleared;
    private Material target;
    private String targetName = "-";

    public BlockPartyGame(ArcadePlugin plugin, Arena arena) {
        super(plugin, arena);
    }

    @Override
    protected void onStart() {
        World w = arena.world();
        floorY = arena.minY();
        for (int x = arena.minX(); x <= arena.maxX(); x++) {
            for (int z = arena.minZ(); z <= arena.maxZ(); z++) {
                Block b = w.getBlockAt(x, floorY, z);
                original.put(b, b.getBlockData());
            }
        }
        int i = 0;
        for (Player p : allPlayers()) {
            p.teleport(spawnFor(i++));
            p.setGameMode(GameMode.ADVENTURE);
        }
        round = 0;
        startRound();
    }

    private void startRound() {
        if (round >= Math.max(1, cfg().getInt("max-rounds"))) {
            List<String> names = new ArrayList<>();
            for (UUID id : alive) names.add(nameOf(id));
            end(t("blockparty.over", "names", String.join(", ", names)));
            return;
        }
        round++;
        cleared = false;
        World w = arena.world();
        int size = round > cfg().getInt("big-tiles-until-round") ? 1 : Math.max(1, cfg().getInt("big-tile-size"));
        int cw = (arena.maxX() - arena.minX()) / size + 1;
        int cd = (arena.maxZ() - arena.minZ()) / size + 1;
        int[][] cells = new int[cw][cd];
        Set<Integer> used = new HashSet<>();
        for (int[] row : cells) {
            for (int k = 0; k < row.length; k++) {
                row[k] = rnd.nextInt(COLORS.length);
                used.add(row[k]);
            }
        }
        for (int x = arena.minX(); x <= arena.maxX(); x++) {
            for (int z = arena.minZ(); z <= arena.maxZ(); z++) {
                int c = cells[(x - arena.minX()) / size][(z - arena.minZ()) / size];
                w.getBlockAt(x, floorY, z).setType(COLORS[c], false);
            }
        }
        List<Integer> usedList = new ArrayList<>(used);
        int idx = usedList.get(rnd.nextInt(usedList.size()));
        target = COLORS[idx];
        String name = NAMES[idx];
        targetName = name;
        int startT = Settings.ticks(cfg().getDouble("round-time-start-seconds"));
        int stepT = Settings.ticks(cfg().getDouble("round-time-decrease-per-round-seconds"));
        int minT = Math.max(1, Settings.ticks(cfg().getDouble("round-time-min-seconds")));
        total = Math.max(minT, startT - round * stepT);
        timer = total;
        ItemStack it = Msg.item(target, 1, "&f" + name);
        for (Player p : alivePlayers()) {
            PlayerInventory inv = p.getInventory();
            inv.clear();
            for (int s = 0; s < 9; s++) inv.setItem(s, it.clone());
            inv.setHeldItemSlot(4);
            Msg.title(p, "&e&l" + name.toUpperCase(), t("blockparty.round-subtitle", "round", round));
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 1.5f);
        }
    }

    @Override
    protected void tick() {
        for (Player p : alivePlayers()) {
            if (state != State.ACTIVE) return;
            if (p.getLocation().getY() < floorY - 3) {
                broadcast(t("blockparty.fell", "player", p.getName(), "left", alive.size() - 1));
                eliminate(p);
            }
        }
        if (state != State.ACTIVE) return;
        timer--;
        if (!cleared) {
            float f = Math.max(0f, (float) timer / total);
            for (Player p : alivePlayers()) {
                p.setExp(0f);
                p.setLevel(0);
            }
            if (timer <= 0) {
                World w = arena.world();
                for (int x = arena.minX(); x <= arena.maxX(); x++) {
                    for (int z = arena.minZ(); z <= arena.maxZ(); z++) {
                        Block b = w.getBlockAt(x, floorY, z);
                        if (b.getType() != target) b.setType(Material.AIR, false);
                    }
                }
                cleared = true;
                timer = Math.max(1, Settings.ticks(cfg().getDouble("clear-pause-seconds")));
                for (Player p : alivePlayers()) {
                    p.setExp(0f);
                    p.setLevel(0);
                    p.playSound(p.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, 0.6f, 1.5f);
                }
            }
        } else if (timer <= 0) {
            startRound();
        }
    }

    /** Seconds until the wrong blocks disappear (one decimal), e.g. "4.3". Shown by %arcadegames_time_left%. */
    @Override
    public String remainingTimeText() {
        if (state != State.ACTIVE || cleared) return "0.0";
        return String.format(Locale.ROOT, "%.1f", Math.max(0, timer) / 20.0);
    }

    @Override
    public String scoreboardValue(Player player, String key) {
        return switch (key.toLowerCase(Locale.ROOT)) {
            case "round" -> String.valueOf(round);
            case "max_rounds" -> String.valueOf(Math.max(1, cfg().getInt("max-rounds")));
            case "color" -> targetName;
            default -> super.scoreboardValue(player, key);
        };
    }

    @Override
    protected void checkEnd() {
        if (alive.size() > 1) return;
        String winner = alive.isEmpty() ? t("common.nobody") : nameOf(alive.iterator().next());
        end(t("blockparty.win", "winner", winner));
    }

    @Override
    protected void onEnd() {
        for (Map.Entry<Block, BlockData> en : original.entrySet()) en.getKey().setBlockData(en.getValue(), false);
        original.clear();
    }
}
