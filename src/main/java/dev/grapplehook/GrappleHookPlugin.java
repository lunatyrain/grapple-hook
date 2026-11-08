package dev.grapplehook;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class GrappleHookPlugin extends JavaPlugin implements Listener, TabExecutor {

    private NamespacedKey itemKey;
    private final Map<UUID, GrappleSession> sessions = new HashMap<>();
    private final Map<UUID, Long> noFallUntil = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        itemKey = new NamespacedKey(this, "grapple_hook");
        getServer().getPluginManager().registerEvents(this, this);
        var cmd = getCommand("grapplehook");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }
    }

    @Override
    public void onDisable() {
        for (GrappleSession s : new ArrayList<>(sessions.values())) s.end(false);
        sessions.clear();
    }

    // ------------------------------------------------------------------ items

    /** Item that only carries a model (used for the hook head and the rope displays). */
    static ItemStack modelItem(String model) {
        ItemStack it = new ItemStack(Material.STICK);
        ItemMeta m = it.getItemMeta();
        m.setItemModel(new NamespacedKey("grapplehook", model));
        it.setItemMeta(m);
        return it;
    }

    public ItemStack createItem() {
        ItemStack item = new ItemStack(Material.CARROT_ON_A_STICK); // no bobber, so right click is free for us
        ItemMeta m = item.getItemMeta();
        m.setItemModel(new NamespacedKey("grapplehook", "grapple_hook"));
        m.displayName(Component.text("Grapple Hook", NamedTextColor.AQUA, TextDecoration.BOLD)
                .decoration(TextDecoration.ITALIC, false));
        m.lore(List.of(
                Component.text("Right click to fire the hook.", NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false),
                Component.text("Sneak or right click again to let go.", NamedTextColor.DARK_GRAY)
                        .decoration(TextDecoration.ITALIC, false)));
        m.getPersistentDataContainer().set(itemKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(m);
        return item;
    }

    private boolean isGrapple(ItemStack item) {
        if (item == null || item.getType() != Material.CARROT_ON_A_STICK || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(itemKey, PersistentDataType.BYTE);
    }

    // ----------------------------------------------------------------- events

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        Action a = e.getAction();
        if (a != Action.RIGHT_CLICK_AIR && a != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack item = e.getItem();
        if (!isGrapple(item)) return;
        e.setCancelled(true);

        Player p = e.getPlayer();
        if (e.getHand() != EquipmentSlot.HAND && e.getHand() != EquipmentSlot.OFF_HAND) return;
        if (!p.hasPermission("grapplehook.use")) return;

        GrappleSession active = sessions.get(p.getUniqueId());
        if (active != null) {
            if (getConfig().getBoolean("sneak-to-release", true)) active.end(true);
            return;
        }
        if (p.hasCooldown(item)) return;
        fire(p, item);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        GrappleSession s = sessions.get(e.getPlayer().getUniqueId());
        if (s != null) s.end(false);
        noFallUntil.remove(e.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onFall(EntityDamageEvent e) {
        if (e.getCause() != EntityDamageEvent.DamageCause.FALL) return;
        if (!(e.getEntity() instanceof Player p)) return;
        Long until = noFallUntil.get(p.getUniqueId());
        if (until == null) return;
        if (System.currentTimeMillis() <= until) e.setCancelled(true);
        else noFallUntil.remove(p.getUniqueId());
    }

    // ------------------------------------------------------------------- fire

    private void fire(Player p, ItemStack item) {
        double range = getConfig().getDouble("range", 45.0);
        Location eye = p.getEyeLocation();
        Vector look = eye.getDirection();
        RayTraceResult r = p.getWorld().rayTraceBlocks(eye, look, range, FluidCollisionMode.NEVER, true);

        boolean hit = r != null && r.getHitPosition() != null && r.getHitBlock() != null;
        Vector anchor = hit ? r.getHitPosition() : eye.toVector().add(look.clone().multiply(range));

        int cd = (int) Math.round(20 * getConfig().getDouble(hit ? "cooldown-seconds" : "miss-cooldown-seconds", 8.0));
        if (cd > 0) p.setCooldown(item, cd);

        GrappleSession s = new GrappleSession(this, p, anchor, hit, hit ? r.getHitBlock() : null);
        sessions.put(p.getUniqueId(), s);
        s.start();
    }

    void sessionEnded(UUID id) {
        sessions.remove(id);
    }

    void grantNoFall(Player p) {
        long ms = (long) (getConfig().getDouble("no-fall-seconds", 3.0) * 1000);
        noFallUntil.put(p.getUniqueId(), System.currentTimeMillis() + ms);
    }

    // ---------------------------------------------------------------- command

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(Component.text("/" + label + " <give [player]|reload>", NamedTextColor.YELLOW));
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "reload" -> {
                reloadConfig();
                sender.sendMessage(Component.text("GrappleHook config reloaded.", NamedTextColor.GREEN));
            }
            case "give" -> {
                Player target;
                if (args.length >= 2) target = getServer().getPlayerExact(args[1]);
                else if (sender instanceof Player self) target = self;
                else target = null;
                if (target == null) {
                    sender.sendMessage(Component.text("Player not found.", NamedTextColor.RED));
                    return true;
                }
                target.getInventory().addItem(createItem()).values()
                        .forEach(left -> target.getWorld().dropItemNaturally(target.getLocation(), left));
                sender.sendMessage(Component.text("Gave a Grapple Hook to " + target.getName() + ".", NamedTextColor.GREEN));
            }
            default -> sender.sendMessage(Component.text("Unknown subcommand.", NamedTextColor.RED));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (args.length == 1) return List.of("give", "reload");
        if (args.length == 2 && args[0].equalsIgnoreCase("give"))
            return getServer().getOnlinePlayers().stream().map(Player::getName).toList();
        return List.of();
    }
}
