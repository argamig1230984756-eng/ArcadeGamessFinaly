package me.arcade.games.party;

import me.arcade.games.ArcadePlugin;
import me.arcade.games.arena.Arena;
import me.arcade.games.arena.GameType;
import me.arcade.games.util.Items;
import me.arcade.games.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import java.util.*;

public final class PartyManager implements Listener {
 private final ArcadePlugin plugin; private final Map<UUID,LinkedHashSet<UUID>> parties=new HashMap<>(); private final Map<UUID,UUID> leaderOf=new HashMap<>(), invites=new HashMap<>(); private final String title="§5§lParty Games"; private final Set<UUID> awaitingInvite=new HashSet<>(); private final Map<UUID,Long> inviteAt=new HashMap<>();
 public PartyManager(ArcadePlugin p){plugin=p;}
 private UUID lead(Player p){return leaderOf.getOrDefault(p.getUniqueId(),p.getUniqueId());} private LinkedHashSet<UUID> members(Player p){return parties.get(lead(p));}
 public void command(Player p,String[] a){if(!plugin.getConfig().getBoolean("party.enabled",true)){Msg.send(p,"&cParties are currently disabled.");return;}if(a.length<2){help(p);return;} switch(a[1].toLowerCase(Locale.ROOT)){case "create"->create(p);case "invite"->invite(p,a);case "accept"->accept(p);case "leave"->leave(p);case "disband"->disband(p);case "list"->list(p);case "games"->openGames(p);case "menu"->openPartyMenu(p);default->help(p);}}
 private void help(Player p){Msg.send(p,"&d/arcade party &fcreate, invite <player>, accept, leave, disband, list, games");}
 private void create(Player p){if(leaderOf.containsKey(p.getUniqueId())){Msg.send(p,"&cYou're already in a party.");return;} LinkedHashSet<UUID> g=new LinkedHashSet<>();g.add(p.getUniqueId());parties.put(p.getUniqueId(),g);leaderOf.put(p.getUniqueId(),p.getUniqueId());Msg.send(p,"&aParty created. Invite friends with /arcade party invite <player>.");}
 private void invite(Player p,String[] a){if(a.length<3){Msg.send(p,"&cUsage: /arcade party invite <player>");return;}if(!leaderOf.containsKey(p.getUniqueId()))create(p);UUID l=lead(p);if(!l.equals(p.getUniqueId())){Msg.send(p,"&cOnly the leader can invite.");return;}Player t=Bukkit.getPlayerExact(a[2]);if(t==null){Msg.send(p,"&cPlayer isn't online.");return;}if(leaderOf.containsKey(t.getUniqueId())){Msg.send(p,"&cPlayer already has a party.");return;}int max=Math.max(1,plugin.getConfig().getInt("party.max-size",16));if(parties.get(l).size()>=max){Msg.send(p,"&cYour party is full.");return;}invites.put(t.getUniqueId(),l);inviteAt.put(t.getUniqueId(),System.currentTimeMillis());Msg.send(p,"&aInvite sent.");Msg.send(t,"&dParty invite from "+p.getName()+". Use /arcade party accept.");}
 private void accept(Player p){UUID id=p.getUniqueId();UUID l=invites.remove(id);Long at=inviteAt.remove(id);if(l==null||!parties.containsKey(l)){Msg.send(p,"&cNo pending invite.");return;}long timeout=Math.max(1,plugin.getConfig().getLong("party.invite-timeout-seconds",60))*1000L;if(at==null||System.currentTimeMillis()-at>timeout){Msg.send(p,"&cThat party invite expired.");return;}if(parties.get(l).size()>=Math.max(1,plugin.getConfig().getInt("party.max-size",16))){Msg.send(p,"&cThat party is full.");return;}if(leaderOf.containsKey(p.getUniqueId())){Msg.send(p,"&cLeave your current party first.");return;}parties.get(l).add(p.getUniqueId());leaderOf.put(p.getUniqueId(),l);announce(l,"&d"+p.getName()+" joined the party.");}
 private void leave(Player p){UUID l=leaderOf.get(p.getUniqueId());if(l==null){Msg.send(p,"&cYou're not in a party.");return;}if(l.equals(p.getUniqueId())){disband(p);return;}parties.get(l).remove(p.getUniqueId());leaderOf.remove(p.getUniqueId());Msg.send(p,"&aYou left the party.");announce(l,"&e"+p.getName()+" left the party.");}
 private void disband(Player p){UUID l=lead(p);LinkedHashSet<UUID> g=parties.get(l);if(g==null){Msg.send(p,"&cYou're not in a party.");return;}if(!l.equals(p.getUniqueId())){Msg.send(p,"&cOnly the leader can disband.");return;}for(UUID id:g){leaderOf.remove(id);Player q=Bukkit.getPlayer(id);if(q!=null)Msg.send(q,"&cParty disbanded.");}parties.remove(l);}
 private void list(Player p){LinkedHashSet<UUID> g=members(p);if(g==null){Msg.send(p,"&cCreate a party first.");return;}Msg.send(p,"&dParty members:");for(UUID id:g){Player q=Bukkit.getPlayer(id);Msg.send(p,"&7- "+(q==null?Bukkit.getOfflinePlayer(id).getName():q.getName())+(id.equals(lead(p))?" &6(Leader)":""));}}
 private void announce(UUID l,String m){Set<UUID> g=parties.get(l);if(g!=null)for(UUID id:g){Player p=Bukkit.getPlayer(id);if(p!=null)Msg.send(p,m);}}
 public void openPartyMenu(Player p){if(!plugin.getConfig().getBoolean("party.enabled",true)){Msg.send(p,"&cParties are currently disabled.");return;}Inventory inv=Bukkit.createInventory(null,27,mainTitle());
  put(inv,10,Material.FIREWORK_ROCKET,"&a&lCreate Party","party_create","&7Create a party and become leader.");
  put(inv,12,Material.PLAYER_HEAD,"&d&lInvite Player","party_invite","&7Click, then type a player's exact name in chat.");
  put(inv,14,Material.BOOK,"&e&lParty Members","party_list","&7View everyone in your party.");
  put(inv,16,Material.BARRIER,"&c&lLeave / Disband","party_leave","&7Leave your party, or disband it if leader.");
  put(inv,22,Material.NETHER_STAR,"&b&lBrowse Party Games","party_browse","&7Choose an arena and queue together.");p.openInventory(inv);}
 private String mainTitle(){return plugin.getConfig().getString("party.menu-title","&5&lParty Menu").replace('&','§');}
 private void put(Inventory inv,int slot,Material material,String name,String tag,String lore){ItemStack it=new ItemStack(material);ItemMeta m=it.getItemMeta();m.displayName(Msg.c(name));m.lore(List.of(Msg.c(lore)));it.setItemMeta(m);Items.tag(plugin,it,"party_action",tag);inv.setItem(slot,it);}
 @EventHandler public void chatInvite(AsyncPlayerChatEvent e){Player p=e.getPlayer();if(!awaitingInvite.remove(p.getUniqueId()))return;e.setCancelled(true);String targetName=e.getMessage().trim();Bukkit.getScheduler().runTask(plugin,()->inviteByName(p,targetName));}
 private void inviteByName(Player p,String name){if(name.equalsIgnoreCase("cancel")){Msg.send(p,"&7Invite cancelled.");return;}invite(p,new String[]{"party","invite",name});}
 public void openGames(Player p){if(!plugin.getConfig().getBoolean("party.enabled",true)){Msg.send(p,"&cParties are currently disabled.");return;}if(!plugin.getConfig().getBoolean("party.allow-solo-queue",true)&&(members(p)==null||members(p).size()<2)){Msg.send(p,"&cCreate a party and invite at least one friend first.");return;}Inventory inv=Bukkit.createInventory(null,27,title);int slot=10;for(Arena ar:plugin.manager().arenas()){if(ar.type!=GameType.PARTY_GAMES||ar.validate()!=null)continue;var game=plugin.manager().game(ar);int size=members(p)==null?1:members(p).size();if(!game.joinable()||ar.max-game.playerCount()<size)continue;ItemStack it=new ItemStack(Material.FIREWORK_ROCKET);ItemMeta m=it.getItemMeta();m.displayName(Msg.c("&d&l"+ar.name));m.lore(List.of(Msg.c("&7Click to queue your party"),Msg.c("&7Players: "+game.playerCount()+"/"+ar.max)));it.setItemMeta(m);Items.tag(plugin,it,"party_arena",ar.name);inv.setItem(slot++,it);if(slot>=17)break;}if(slot==10){ItemStack it=new ItemStack(Material.BARRIER);ItemMeta m=it.getItemMeta();m.displayName(Msg.c("&cNo available Party Games arenas"));it.setItemMeta(m);inv.setItem(13,it);}p.openInventory(inv);}
 @EventHandler public void click(InventoryClickEvent e){if(!(e.getWhoClicked() instanceof Player p))return;
  if(mainTitle().equals(e.getView().getTitle())){e.setCancelled(true);String action=Items.tagOf(plugin,e.getCurrentItem(),"party_action");if(action==null)return;p.closeInventory();switch(action){case "party_create"->create(p);case "party_invite"->{if(!leaderOf.containsKey(p.getUniqueId()))create(p);if(!lead(p).equals(p.getUniqueId())){Msg.send(p,"&cOnly the leader can invite.");return;}awaitingInvite.add(p.getUniqueId());Msg.send(p,"&dType the exact online player name in chat (or type cancel).");}case "party_list"->list(p);case "party_leave"->leave(p);case "party_browse"->openGames(p);}return;}
  if(!title.equals(e.getView().getTitle()))return;e.setCancelled(true);String name=Items.tagOf(plugin,e.getCurrentItem(),"party_arena");if(name==null)return;if(leaderOf.containsKey(p.getUniqueId())&&!lead(p).equals(p.getUniqueId())){Msg.send(p,"&cOnly the leader can queue the party.");return;}LinkedHashSet<UUID> g=members(p);if(g==null){g=new LinkedHashSet<>();g.add(p.getUniqueId());}Arena ar=plugin.manager().arena(name);if(ar==null)return;var game=plugin.manager().game(ar);if(g.size()>ar.max-game.playerCount()){Msg.send(p,"&cNot enough room for the party.");return;}for(UUID id:g){Player q=Bukkit.getPlayer(id);if(q!=null&&(plugin.manager().gameOf(q)!=null||plugin.manager().spectatorGame(q)!=null)){Msg.send(p,"&cEveryone must be in the lobby.");return;}}p.closeInventory();for(UUID id:new ArrayList<>(g)){Player q=Bukkit.getPlayer(id);if(q!=null){String err=game.join(q);if(err!=null)Msg.send(q,"&c"+err);}}announce(lead(p),"&aParty queued for &d"+ar.name+"&a.");}
}
