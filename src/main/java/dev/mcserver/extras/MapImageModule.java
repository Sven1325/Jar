package dev.mcserver.extras;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.MapInitializeEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;
import org.bukkit.plugin.java.JavaPlugin;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * /mapimage <URL> [SpaltenxZeilen] [fit|fill|stretch]
 * Wandelt leere Maps aus dem Inventar in Karten mit dem Bild um. Die Bilder werden im Plugin-Ordner gespeichert
 * (images/&lt;MapId&gt;.png) und beim Serverstart wieder auf die Karten gelegt.
 */
public final class MapImageModule implements Listener, TabExecutor {
    private final JavaPlugin plugin;

    MapImageModule(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    private org.bukkit.configuration.file.FileConfiguration getConfig() { return plugin.getConfig(); }
    private java.util.logging.Logger getLogger() { return plugin.getLogger(); }
    private java.io.File getDataFolder() { return plugin.getDataFolder(); }
    private org.bukkit.Server getServer() { return plugin.getServer(); }
    private void saveDefaultConfig() { plugin.saveDefaultConfig(); }


    private static final Pattern GRID = Pattern.compile("(\\d{1,2})[xX*](\\d{1,2})");

    private File imageDir;
    private ImageLoader loader;
    private final Map<Integer, BufferedImage> cache = new ConcurrentHashMap<>();
    private final Set<UUID> busy = ConcurrentHashMap.newKeySet();

    public void enable() {
        saveDefaultConfig();
        ImageIO.setUseCache(false); // keine temporaeren Dateien beim Dekodieren

        imageDir = new File(getDataFolder(), "images");
        if (!imageDir.isDirectory() && !imageDir.mkdirs()) {
            getLogger().warning("Ordner konnte nicht angelegt werden: " + imageDir);
        }
        loader = new ImageLoader(
                getConfig().getLong("max-download-mb", 8) * 1024 * 1024,
                getConfig().getLong("max-image-pixels", 25_000_000L),
                getConfig().getInt("timeout-seconds", 15),
                getConfig().getStringList("allowed-hosts"),
                getConfig().getBoolean("allow-private-addresses", false));

        getServer().getPluginManager().registerEvents(this, plugin);
        var cmd = plugin.getCommand("mapimage");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }
        restoreAll();
    }

    // ------------------------------------------------------------ Bilder auf Karten legen / wiederherstellen

    private void attach(MapView view, BufferedImage img) {
        for (MapRenderer r : new ArrayList<>(view.getRenderers())) {
            view.removeRenderer(r);
        }
        view.addRenderer(new ImageRenderer(img));
        view.setLocked(true);
        view.setTrackingPosition(false);
        view.setUnlimitedTracking(false);
    }

    private File fileFor(int id) {
        return new File(imageDir, id + ".png");
    }

    private BufferedImage load(int id) {
        BufferedImage hit = cache.get(id);
        if (hit != null) {
            return hit;
        }
        File f = fileFor(id);
        if (!f.isFile()) {
            return null;
        }
        try {
            BufferedImage img = ImageIO.read(f);
            if (img != null) {
                cache.put(id, img);
            }
            return img;
        } catch (IOException e) {
            getLogger().warning("Bild für Karte " + id + " nicht lesbar: " + e.getMessage());
            return null;
        }
    }

    /** Beim Start: alle gespeicherten Bilder auf ihre Karten legen. */
    private void restoreAll() {
        File[] files = imageDir.listFiles((d, n) -> n.matches("\\d+\\.png"));
        if (files == null) {
            return;
        }
        int ok = 0;
        for (File f : files) {
            int id = Integer.parseInt(f.getName().replace(".png", ""));
            @SuppressWarnings("deprecation")
            MapView view = Bukkit.getMap(id);
            BufferedImage img = view == null ? null : load(id);
            if (view != null && img != null) {
                attach(view, img);
                ok++;
            }
        }
        getLogger().info(ok + " Kartenbild(er) wiederhergestellt.");
    }

    /** Karten, die erst spaeter geladen werden, bekommen ihr Bild hier. */
    @EventHandler
    public void onMapInit(MapInitializeEvent event) {
        MapView view = event.getMap();
        @SuppressWarnings("deprecation")
        int id = view.getId();
        BufferedImage img = load(id);
        if (img != null) {
            attach(view, img);
        }
    }

    // ------------------------------------------------------------ Befehl

    private static void msg(CommandSender to, String text, NamedTextColor color) {
        to.sendMessage(Component.text(text, color));
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            msg(sender, "Nur Spieler können diesen Befehl benutzen.", NamedTextColor.RED);
            return true;
        }
        if (args.length == 0) {
            msg(player, "Benutzung: /" + label + " <Bild-URL> [Spalten x Zeilen, z. B. 2x2] [fit|fill|stretch]",
                    NamedTextColor.YELLOW);
            msg(player, "Du brauchst so viele leere Maps im Inventar, wie das Raster Felder hat.", NamedTextColor.GRAY);
            return true;
        }

        int maxGrid = Math.max(1, Math.min(10, getConfig().getInt("max-grid", 5)));
        int cols = 1, rows = 1;
        ImageLoader.Mode mode = parseMode(getConfig().getString("default-mode", "fit"));
        for (int i = 1; i < args.length; i++) {
            Matcher m = GRID.matcher(args[i]);
            if (m.matches()) {
                cols = Integer.parseInt(m.group(1));
                rows = Integer.parseInt(m.group(2));
            } else if (parseModeOrNull(args[i]) != null) {
                mode = parseModeOrNull(args[i]);
            } else {
                msg(player, "Unbekannte Angabe: " + args[i], NamedTextColor.RED);
                return true;
            }
        }
        if (cols < 1 || rows < 1 || cols > maxGrid || rows > maxGrid) {
            msg(player, "Das Raster muss zwischen 1x1 und " + maxGrid + "x" + maxGrid + " liegen.", NamedTextColor.RED);
            return true;
        }
        final int fc = cols, fr = rows, needed = cols * rows;
        final ImageLoader.Mode fmode = mode;
        final String url = args[0];

        if (countEmptyMaps(player) < needed) {
            msg(player, "Du brauchst " + needed + " leere Map(s) im Inventar.", NamedTextColor.RED);
            return true;
        }
        if (!busy.add(player.getUniqueId())) {
            msg(player, "Es läuft bereits ein Bild-Import. Bitte warten.", NamedTextColor.RED);
            return true;
        }
        msg(player, "Lade Bild herunter ...", NamedTextColor.GRAY);

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            List<BufferedImage> tiles;
            try {
                tiles = ImageLoader.toTiles(loader.download(url), fc, fr, fmode);
            } catch (IOException e) {
                finish(player, () -> msg(player, "Fehler: " + e.getMessage(), NamedTextColor.RED));
                return;
            } catch (Throwable t) { // z. B. OutOfMemoryError bei riesigen Bildern
                getLogger().warning("Bildverarbeitung fehlgeschlagen: " + t);
                finish(player, () -> msg(player, "Das Bild konnte nicht verarbeitet werden.", NamedTextColor.RED));
                return;
            }
            final List<BufferedImage> result = tiles;
            finish(player, () -> deliver(player, result, fc, fr));
        });
        return true;
    }

    /** Zurueck auf den Hauptthread, Sperre freigeben, Aktion ausfuehren. */
    private void finish(Player player, Runnable action) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            busy.remove(player.getUniqueId());
            if (player.isOnline()) {
                action.run();
            }
        });
    }

    private void deliver(Player player, List<BufferedImage> tiles, int cols, int rows) {
        if (countEmptyMaps(player) < tiles.size()) {
            msg(player, "Du hast nicht mehr genug leere Maps im Inventar.", NamedTextColor.RED);
            return;
        }
        takeEmptyMaps(player, tiles.size());

        World world = Bukkit.getWorlds().get(0);
        Map<Integer, BufferedImage> toSave = new HashMap<>();
        PlayerInventory inv = player.getInventory();
        for (int i = 0; i < tiles.size(); i++) {
            MapView view = Bukkit.createMap(world);
            @SuppressWarnings("deprecation")
            int id = view.getId();
            BufferedImage tile = tiles.get(i);
            cache.put(id, tile);
            attach(view, tile);
            toSave.put(id, tile);

            ItemStack item = new ItemStack(Material.FILLED_MAP);
            MapMeta meta = (MapMeta) item.getItemMeta();
            meta.setMapView(view);
            if (tiles.size() > 1) {
                meta.lore(List.of(Component.text("Teil " + (i / cols + 1) + "/" + rows + " · "
                        + (i % cols + 1) + "/" + cols + " (Zeile/Spalte)", NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false)));
            }
            item.setItemMeta(meta);
            inv.addItem(item).values().forEach(left -> player.getWorld().dropItemNaturally(player.getLocation(), left));
        }
        saveAsync(toSave);

        msg(player, tiles.size() == 1 ? "Fertig! Die Map zeigt jetzt das Bild."
                : "Fertig! " + tiles.size() + " Maps erstellt. Hänge sie nebeneinander in Item-Rahmen: "
                + "von links nach rechts, Zeile für Zeile von oben.", NamedTextColor.GREEN);
    }

    private void saveAsync(Map<Integer, BufferedImage> images) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            for (var e : images.entrySet()) {
                try {
                    ImageIO.write(e.getValue(), "png", fileFor(e.getKey()));
                } catch (IOException ex) {
                    getLogger().warning("Bild für Karte " + e.getKey() + " konnte nicht gespeichert werden: "
                            + ex.getMessage());
                }
            }
        });
    }

    // ------------------------------------------------------------ Leere Maps im Inventar

    private static int countEmptyMaps(Player p) {
        int n = 0;
        for (ItemStack s : p.getInventory().getStorageContents()) {
            if (s != null && s.getType() == Material.MAP) {
                n += s.getAmount();
            }
        }
        ItemStack off = p.getInventory().getItemInOffHand();
        return off.getType() == Material.MAP ? n + off.getAmount() : n;
    }

    private static void takeEmptyMaps(Player p, int amount) {
        PlayerInventory inv = p.getInventory();
        ItemStack[] contents = inv.getStorageContents();
        for (int i = 0; i < contents.length && amount > 0; i++) {
            ItemStack s = contents[i];
            if (s == null || s.getType() != Material.MAP) {
                continue;
            }
            int take = Math.min(amount, s.getAmount());
            amount -= take;
            if (take >= s.getAmount()) {
                contents[i] = null;
            } else {
                s.setAmount(s.getAmount() - take);
            }
        }
        inv.setStorageContents(contents);
        ItemStack off = inv.getItemInOffHand();
        if (amount > 0 && off.getType() == Material.MAP) {
            int take = Math.min(amount, off.getAmount());
            if (take >= off.getAmount()) {
                inv.setItemInOffHand(null);
            } else {
                off.setAmount(off.getAmount() - take);
                inv.setItemInOffHand(off);
            }
        }
    }

    // ------------------------------------------------------------ Hilfen

    private static ImageLoader.Mode parseModeOrNull(String s) {
        return switch (s.toLowerCase(Locale.ROOT)) {
            case "fit" -> ImageLoader.Mode.FIT;
            case "fill" -> ImageLoader.Mode.FILL;
            case "stretch" -> ImageLoader.Mode.STRETCH;
            default -> null;
        };
    }

    private static ImageLoader.Mode parseMode(String s) {
        ImageLoader.Mode m = s == null ? null : parseModeOrNull(s);
        return m == null ? ImageLoader.Mode.FIT : m;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return List.of("https://");
        }
        List<String> out = new ArrayList<>();
        String cur = args[args.length - 1].toLowerCase(Locale.ROOT);
        for (String s : List.of("1x1", "2x1", "2x2", "3x2", "3x3", "fit", "fill", "stretch")) {
            if (s.startsWith(cur)) {
                out.add(s);
            }
        }
        return out;
    }
}
