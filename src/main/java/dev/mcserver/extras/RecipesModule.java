package dev.mcserver.extras;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.type.Light;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.BlockDataMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;

/**
 * Zwei eigene Rezepte:
 *  - Unsichtbarer Item-Rahmen: Item-Rahmen in der Mitte, Glasscheiben drumherum
 *  - Lichtquellen-Block:       Fackel in der Mitte, Glasscheiben drumherum
 */
public final class RecipesModule implements Listener {
    private final JavaPlugin plugin;

    RecipesModule(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    private org.bukkit.configuration.file.FileConfiguration getConfig() { return plugin.getConfig(); }
    private java.util.logging.Logger getLogger() { return plugin.getLogger(); }
    private java.io.File getDataFolder() { return plugin.getDataFolder(); }
    private org.bukkit.Server getServer() { return plugin.getServer(); }
    private void saveDefaultConfig() { plugin.saveDefaultConfig(); }


    private NamespacedKey invisibleKey;
    private final List<NamespacedKey> recipeKeys = new ArrayList<>();

    public void enable() {
        saveDefaultConfig();
        invisibleKey = new NamespacedKey(plugin, "invisible_frame");

        registerRecipes();
        getServer().getPluginManager().registerEvents(this, plugin);
    }

    public void disable() {
        for (NamespacedKey key : recipeKeys) {
            Bukkit.removeRecipe(key);
        }
        recipeKeys.clear();
    }

    // ------------------------------------------------------------ Rezepte

    private void registerRecipes() {
        if (getConfig().getBoolean("invisible-item-frame.enabled", true)) {
            int amount = clamp(getConfig().getInt("invisible-item-frame.amount", 1), 1, 64);
            NamespacedKey key = new NamespacedKey(plugin, "invisible_item_frame");
            ShapedRecipe recipe = new ShapedRecipe(key, createInvisibleFrame(amount));
            recipe.shape("GGG", "GFG", "GGG");
            recipe.setIngredient('G', Material.GLASS_PANE);
            recipe.setIngredient('F', Material.ITEM_FRAME);
            addRecipe(recipe);
        }

        if (getConfig().getBoolean("light-block.enabled", true)) {
            int amount = clamp(getConfig().getInt("light-block.amount", 1), 1, 64);
            NamespacedKey key = new NamespacedKey(plugin, "light_block");
            ShapedRecipe recipe = new ShapedRecipe(key, createLightBlock(amount, lightLevel()));
            recipe.shape("GGG", "GTG", "GGG");
            recipe.setIngredient('G', Material.GLASS_PANE);
            recipe.setIngredient('T', Material.TORCH);
            addRecipe(recipe);
        }
    }

    private void addRecipe(ShapedRecipe recipe) {
        Bukkit.removeRecipe(recipe.getKey()); // falls nach /reload schon vorhanden
        if (Bukkit.addRecipe(recipe)) {
            recipeKeys.add(recipe.getKey());
        } else {
            getLogger().warning("Rezept konnte nicht registriert werden: " + recipe.getKey());
        }
    }

    private int lightLevel() {
        return clamp(getConfig().getInt("light-block.level", 15), 0, 15);
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    // ------------------------------------------------------------ Items

    private static Component name(String text) {
        return Component.text(text, NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false);
    }

    private ItemStack createInvisibleFrame(int amount) {
        ItemStack item = new ItemStack(Material.ITEM_FRAME, amount);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(name("Unsichtbarer Item-Rahmen"));
        meta.getPersistentDataContainer().set(invisibleKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createLightBlock(int amount, int level) {
        ItemStack item = new ItemStack(Material.LIGHT, amount);
        ItemMeta meta = item.getItemMeta();
        if (meta instanceof BlockDataMeta blockMeta) {
            Light data = (Light) Bukkit.createBlockData(Material.LIGHT);
            data.setLevel(level);
            blockMeta.setBlockData(data);
        }
        meta.displayName(name("Lichtquelle"));
        item.setItemMeta(meta);
        return item;
    }

    private boolean isInvisibleFrameItem(ItemStack item) {
        if (item == null || item.getType() != Material.ITEM_FRAME || !item.hasItemMeta()) {
            return false;
        }
        return item.getItemMeta().getPersistentDataContainer().has(invisibleKey, PersistentDataType.BYTE);
    }

    private boolean isInvisibleFrame(ItemFrame frame) {
        return frame.getPersistentDataContainer().has(invisibleKey, PersistentDataType.BYTE);
    }

    // ------------------------------------------------------------ Events

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (!getConfig().getBoolean("discover-on-join", true) || recipeKeys.isEmpty()) {
            return;
        }
        event.getPlayer().discoverRecipes(recipeKeys);
    }

    /** Wird ein unsichtbarer Rahmen aufgestellt, wird die Entity unsichtbar gemacht und markiert. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(HangingPlaceEvent event) {
        if (!(event.getEntity() instanceof ItemFrame frame)) {
            return;
        }
        ItemStack used = event.getItemStack();
        Player player = event.getPlayer();
        if (used == null && player != null) {
            EquipmentSlot hand = event.getHand();
            used = hand == EquipmentSlot.OFF_HAND
                    ? player.getInventory().getItemInOffHand()
                    : player.getInventory().getItemInMainHand();
        }
        if (!isInvisibleFrameItem(used)) {
            return;
        }
        frame.setVisible(false);
        frame.getPersistentDataContainer().set(invisibleKey, PersistentDataType.BYTE, (byte) 1);
    }

    /** Beim Abbau wird wieder der unsichtbare Rahmen (statt eines normalen) gedroppt. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(HangingBreakEvent event) {
        if (!(event.getEntity() instanceof ItemFrame frame) || !isInvisibleFrame(frame)) {
            return;
        }
        Player remover = event instanceof HangingBreakByEntityEvent byEntity
                && byEntity.getRemover() instanceof Player p ? p : null;

        event.setCancelled(true);
        Location loc = frame.getLocation();
        World world = frame.getWorld();
        ItemStack content = frame.getItem();
        frame.remove();

        if (remover != null && remover.getGameMode() == GameMode.CREATIVE) {
            return;
        }
        world.dropItemNaturally(loc, createInvisibleFrame(1));
        if (content != null && !content.getType().isAir()) {
            world.dropItemNaturally(loc, content);
        }
    }

    /** Lichtquellen-Bloecke geben beim Abbau im Survival das Item zurueck (mit gleicher Helligkeit). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (block.getType() != Material.LIGHT || !getConfig().getBoolean("light-block.drop-on-break", true)) {
            return;
        }
        if (event.getPlayer().getGameMode() == GameMode.CREATIVE) {
            return;
        }
        int level = block.getBlockData() instanceof Light light ? light.getLevel() : lightLevel();
        event.setDropItems(false);
        block.getWorld().dropItemNaturally(block.getLocation().add(0.5, 0.5, 0.5), createLightBlock(1, level));
    }
}
