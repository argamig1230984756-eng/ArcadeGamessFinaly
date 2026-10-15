# ArcadeGames (Paper 1.21.10, Java 21)

Five minigames: **Prop Hunt, BlockParty, TNTRun, Party Games, Mini Walls**.

## Build
```
mvn clean package
```
Jar ends up in `target/ArcadeGames.jar` -> drop into `plugins/`.

## Player commands
- `/arcade join [arena|game]` (alias `/ag`) - e.g. `/ag join tntrun`
  - `/ag join` picks a random game that has a free arena, then a random free arena of it
  - `/ag join tntrun` picks a random free TNT Run arena
  - `/ag join arena1` joins that arena; if it is full / running / not ready you are sent to another arena of the same game
- `/arcade` or `/arcade gui` - the **game menu**: ONE item per game, click it = you are sent to a random arena of that game
- `/arcade arenas [game]` - the old arena list (every arena as its own item)
- `/arcade spectate [arena|game]` - watch a game (survival mode, flying, invisible, can't touch anything)
- `/arcade leave`, `/arcade list`
- Right-click the red bed in the waiting lobby to leave.

## Everything is editable (folder `plugins/ArcadeGames/`)
The folder is created the first time the plugin loads. Edit a file, then run `/arcade reload` - no restart needed.

| File | What is in it |
|---|---|
| `config.yml` | lobby countdown, end delay, leave item, **matchmaking** (random / fullest / emptiest / ordered, fallback when full), where players go after a match |
| `game-menu.yml` | **the main menu**: one item per game (slot, material, name, description, lore per state), buttons, background |
| `lobby-items.yml` | the hotbar items: **nether star (slot 5)**, info book, clock - slot, material, name, lore, action, give rules, worlds |
| `info-book.yml` | title, author and every page of the server info book |
| `spectate.yml` | spectating (game mode, flight, invisibility, distance limit ...) and **both spectate menus** |
| `gui.yml` | **the arena list menu**: title, size, which slots hold arenas, background, empty slots, the item / name / lore for every arena state (players, min, max, free places ...), buttons, paging, sorting |
| `messages.yml` | every message, title and action bar players see (colour codes with `&`, `{placeholders}` are filled in) |
| `games/prophunt.yml` | hide / seek time, seekers per player, items, allowed blocks, Bedrock offsets, default player limits |
| `games/blockparty.yml` | rounds, round speed, tile size, pause between rounds |
| `games/tntrun.yml` | double jumps and their power, start delay, how fast blocks vanish |
| `games/partygames.yml` | which mini-games run, their length / titles / difficulty, points |
| `games/miniwalls.yml` | Wither health, time limit, the starting kit |
| `arenas.yml` | arena locations (written by the setup commands below) |

Every game file also has `enabled: true/false` (a disabled game is skipped by matchmaking) and
`default-min-players` / `default-max-players` for newly created arenas.
A key you delete from a file simply falls back to the built-in default.

### Matchmaking (`config.yml`)
```yaml
matchmaking:
  mode: random                      # random | fullest | emptiest | ordered
  prefer-lobbies-with-players: true # with "random": new players go where somebody already waits, so games start
  fallback-if-full: true            # full/running arena -> send to another arena of the same game
  join-message: true
```
This works the same for all five games.

## Admin setup (permission `arcade.admin`)
Stand where you want the point to be, then:

| Command | What it does |
|---|---|
| `/arcade create <name> <game>` | new arena (`prophunt` `blockparty` `tntrun` `partygames` `miniwalls`) |
| `/arcade setwaiting <name>` | waiting lobby |
| `/arcade setspawn <name> [n]` | add/replace spawn n |
| `/arcade setwither <name> <1-4>` | Mini Walls wither position for team n |
| `/arcade pos1 / pos2 <name>` | region corners (stand ON the corner block) |
| `/arcade setplayers <name> <min> <max>` | player limits |
| `/arcade info / forcestart / forcestop / delete <name>` | management |
| `/arcade reload` | re-read config.yml, messages.yml and games/*.yml |

### Per game
- **TNTRun** - waiting, spawns, pos1/pos2 = bottom corner and top corner of the whole floor stack (sand/gravel over TNT works). Falling below the region eliminates you.
- **BlockParty** - waiting, spawns above the floor, pos1/pos2 = two corners of the flat floor. The floor is regenerated every round and restored afterwards.
- **Party Games** - waiting, spawns standing on the platform, pos1/pos2 = the platform's top layer. Needs a void/drop around the platform. Plays Anvil Rain, Bombardment, Sumo, Spleef in random order.
- **Prop Hunt** - waiting, spawn 1 = hiders, spawn 2 = seekers (optional). Hiders are invisible with a block-display disguise; right-click blocks with the nether star to copy them.
- **Mini Walls** - waiting, `setspawn 1..4` + `setwither 1..4` (2-4 teams). Wither dead = no more respawns for that team; last team alive wins.

Arenas are saved in `plugins/ArcadeGames/arenas.yml`.

## Upgrading from 1.0
The Prop Hunt block lists / offsets moved from `config.yml` to `games/prophunt.yml`
(same key names, without the `prophunt:` prefix). Copy your values over once.

## New in 1.2
- **Always back to spawn**: leaving, a finished or stopped match, `/arcade forcestop`, disconnecting - players always end up at the spawn from `config.yml`, never back where they joined from. (`return-location: previous` in `config.yml` restores the old behaviour.)
- **Arena menu** (`/arcade gui`, `/arcade gui <game>`, or plain `/arcade`), fully editable in `gui.yml`.

## New in 1.3
- **Fill first**: a new player is always sent to the arena that already has players (fullest first) until it is full.
  Only when no arena has players is an empty one picked at random. `matchmaking.fill-first` in `config.yml`.
- **Game menu** (`game-menu.yml`): one item per game. Left click = join a random arena of that game
  (`right-click:` can open the arena list or the spectate list instead).
- **Hotbar items** (`lobby-items.yml`), given on join / after a game / after respawn:
  - nether star, slot 5 -> game menu
  - book, slot 4 -> server info book (`info-book.yml`)
  - clock, slot 6 -> spectate menu: all games -> click a game -> all its arenas -> click an arena to watch
  Change slots, items, names, actions, or add more items in the file.
- **Spectating**: `/arcade setspectator <arena>` sets where spectators appear (also used for eliminated
  players; without it the old automatic spot above the arena is used). Spectators are in survival mode, fly,
  are invisible and cannot touch anything. They leave with the red bed / `/arcade leave` and are sent
  to the spawn when the game ends. All switches are in `spectate.yml`.
- **Prop Hunt lighting**: Java players now see the disguise as a BlockDisplay whose brightness is set to the real
  light minus `light-reduction` (`games/prophunt.yml`, default 2). Raise it if the block still looks too bright.
  `java-render: falling` brings the old disguise back.
- New messages in `messages.yml` (`spectate:` section, `join.spectating`). Missing keys in your old files
  fall back to the built-in defaults, so nothing breaks when you update. Nothing was removed.

## Additional gameplay changes

This source revision includes configurable TNT Tag fuse time and a TNT helmet for the tagged player, empty-hand tagging, action-bar timers for ArcadeMode games and BlockParty, Temple Run checkpoint respawning on void falls, periodic gold-nugget coins and top-three coin results, configurable Mini Walls wall removal, spectator hand-slot clearing, and configurable clickable links appended to the server info book. Party Games is hidden from the main game menu and has a separate hotbar item that opens the Party Games arena list.

The party item currently opens the Party Games arena selector; party creation/invitations and team-vs-team party matchmaking are not implemented in this revision. Set the real URLs in `info-book.yml` before using the clickable links. Build and test on your target Paper version before deploying; Bedrock compatibility depends on the server's Geyser/Floodgate setup.


## Added course and party tools

- `/arcade party menu` opens the party GUI. Create a party, invite by typing a player's name, inspect members, leave/disband, and browse Party Games arenas. `/arcade party invite <player>` and `/arcade party accept` are also available.
- `/arcade checkpoint add <arena> <number>` places a gold pressure plate at your feet; `/arcade checkpoint remove <arena> <number>` removes it. The alias `/arcade cheakpoint` is supported too.
- `/arcade coin add <arena>` gives a gold-nugget placement tool. Right-click a block to save a Temple Run coin spawn. Coins are shown as enlarged gold-nugget displays and are collected by running close to them.
- `/arcade end <arena>` gives an iron-pressure-plate placement tool. Right-click a block to place and register a finish plate; the first three runners to finish are announced.
- `/arcade wall <arena> <team 1-4>`: look at one corner of a Mini Walls wall and run the command, then look at the opposite corner and run it again. Repeat to register multiple wall cuboids for each team. The regions are saved in `arenas.yml` and removed after the configured delay.
- `config.yml` contains action-bar timer and party settings. Each `games/<game>.yml` has `elimination.mode: SPECTATOR` or `LOBBY`; Temple Run course settings and Mini Walls wall-removal settings are in their respective files.
- `info-book.yml` supports clickable web links under `links:`; set the actual Discord invite and store URL there. The server IP is configured with `server-ip` in `config.yml`.


## PlaceholderAPI placeholders

Install PlaceholderAPI to use these in TAB or other compatible plugins:
- `%arcadegames_players_tnttag%`, `%arcadegames_players_templerun%`, `%arcadegames_players_blockparty%`, `%arcadegames_players_tntrun%`, `%arcadegames_players_miniwalls%`, `%arcadegames_players_bomblobbers%`, `%arcadegames_players_quake%`, `%arcadegames_players_splegg%`
- `%arcadegames_game%` returns the id of the minigame the player is in (`prophunt`, `blockparty`, `tntrun`, `partygames`, `miniwalls`, `tnttag`, `templerun`, `splegg`, `bomblobbers`, `quake`) - waiting lobby, playing, eliminated or spectating - and an empty string otherwise. Use it in TAB, e.g. `display-condition: "%arcadegames_game%=prophunt"`.
- `%arcadegames_wins%` and `%arcadegames_losses%` for the viewing player. In-game: `%arcadegames_time%`, `%arcadegames_alive%`, `%arcadegames_total%`, `%arcadegames_coins%` (Temple Run), `%arcadegames_it%` (TNT Tag). Stats are saved in `player-stats.yml`.

Loadouts for Quake, Splegg, Mini Walls, and BombLobbers can be edited under `plugins/ArcadeGames/inventories/`.
