package org.mvk.blockLogger;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.block.DoubleChest;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.NotNull;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.jetbrains.annotations.Nullable;
import org.bukkit.command.TabCompleter;

import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;

import java.util.UUID;
import java.util.HashMap;
import java.util.Map;

import java.io.File;
import java.sql.*;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

public class BlockLogger extends JavaPlugin implements Listener, TabCompleter {

    // ===== CONFIG =====
    private FileConfiguration messages;

    // ===== DATABASE =====
    protected Connection connection;

    // ===== ITEM =====
    protected ItemStack historyBrush;

    private static final String PERM_COMMAND = "blocklogger.command";
    private static final String PERM_HISTORY = "blocklogger.history";
    private static final String PERM_RELOAD  = "blocklogger.reload";
    private static final String PERM_MAXLOG = "blocklogger.maxlog";
    private int chatMaxLogs = 10;
    private final Map<UUID, Block> openContainers = new HashMap<>();


    private final MiniMessage mm = MiniMessage.miniMessage();

    protected Component c(String path) {
        String raw = messages.getString(path, "<red>Missing: " + path);
        return mm.deserialize(raw);
    }

    protected Component c(String path, String... replacements) {
        String raw = messages.getString(path, path);

        for (int i = 0; i < replacements.length; i += 2) {
            raw = raw.replace(replacements[i], replacements[i + 1]);
        }

        return mm.deserialize(raw);
    }

    protected void sendOutput(Player player, ItemStack brush, Component message) {

        OutputType type = getOutputType();

        if (type == OutputType.CHAT || type == OutputType.COMBINED) {
            player.sendMessage(message);
        }

        if (type == OutputType.ACTIONBAR || type == OutputType.COMBINED) {
            player.sendActionBar(message);
        }

        if ((type == OutputType.ITEMDESCRIPTION || type == OutputType.COMBINED) && brush != null) {
            ItemMeta meta = brush.getItemMeta();
            List<Component> lore = meta.hasLore() ? meta.lore() : new ArrayList<>();
            lore.add(message);
            meta.lore(lore);
            brush.setItemMeta(meta);
        }
    }

    @EventHandler
    public void onInventoryOpen(InventoryOpenEvent e) {

        if (!(e.getPlayer() instanceof Player player)) return;

        Block block = getContainerBlock(e.getInventory());
        if (block == null) return;

        openContainers.put(player.getUniqueId(), block);
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent e) {

        if (!(e.getPlayer() instanceof Player player)) return;

        openContainers.remove(player.getUniqueId());
    }

    @Override
    public void onEnable() {
        loadMessages();
        setupDatabase();
        setupHistoryBrush();

        Bukkit.getPluginManager().registerEvents(this, this);

        getCommand("bl").setTabCompleter(this);

        getLogger().info(msg("plugin_enabled"));
    }

    @Override
    public List<String> onTabComplete(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String alias,
            @NotNull String[] args
    ) {

        if (!command.getName().equalsIgnoreCase("bl")) return List.of();

        if (args.length == 1) {
            return List.of("history", "reload", "maxlog");
        }

        if (args.length >= 2 && args[0].equalsIgnoreCase("history")) {
            return List.of("<x>", "<y>", "<z>");
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("maxlog")) {
            return List.of("5", "10", "20", "30", "50");
        }

        return List.of();
    }

    @Override
    public void onDisable() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException e) {
            getLogger().log(Level.SEVERE, "DB close error", e);
        }
    }

    protected String formatTimeAgo(String dbTime) {
        try {
            Instant eventTime = Timestamp.valueOf(dbTime).toInstant()
                    .plus(Duration.ofHours(2));

            Instant now = Instant.now();

            long seconds = Duration.between(eventTime, now).getSeconds();
            if (seconds < 60) return seconds + " s ago (UTS-2)";

            long minutes = seconds / 60;
            if (minutes < 60) return minutes + " m ago (UTS-2)";

            long hours = minutes / 60;
            if (hours < 24) return hours + " h ago (UTS-2)";

            long days = hours / 24;
            return days + " d ago (UTS-2)";

        } catch (Exception e) {
            return dbTime;
        }
    }

    private void loadMessages() {
        File file = new File(getDataFolder(), "messages.yml");
        if (!file.exists()) {
            saveResource("messages.yml", false);
        }
        messages = YamlConfiguration.loadConfiguration(file);
    }

    protected String msg(String path) {
        return messages.getString(path, "Missing message: " + path);
    }

    // ===== DATABASE SETUP =====
    private void setupDatabase() {
        try {
            if (!getDataFolder().exists()) getDataFolder().mkdirs();

            File dbFile = new File(getDataFolder(), "logs.db");
            connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());

            try (Statement st = connection.createStatement()) {

                st.execute("""
                    CREATE TABLE IF NOT EXISTS block_logs (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        world TEXT,
                        x INT,
                        y INT,
                        z INT,
                        action TEXT,
                        block TEXT,
                        player TEXT,
                        time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                    )
                """);

                st.execute("""
                    CREATE TABLE IF NOT EXISTS container_logs (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        world TEXT,
                        x INT,
                        y INT,
                        z INT,
                        action TEXT,
                        item TEXT,
                        amount INT,
                        player TEXT,
                        time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                    )
                """);
            }
        } catch (SQLException e) {
            getLogger().log(Level.SEVERE, "Database init error", e);
        }
    }

    private void setupHistoryBrush() {

        historyBrush = new ItemStack(Material.BRUSH);
        ItemMeta meta = historyBrush.getItemMeta();

        meta.displayName(Component.text("History Brush", NamedTextColor.GOLD));

        List<Component> lore = new ArrayList<>();
        lore.add(Component.text(msg("history_brush_lore"), NamedTextColor.GRAY));
        meta.lore(lore);

        historyBrush.setItemMeta(meta);

        NamespacedKey key = new NamespacedKey(this, "history_brush");
        ShapedRecipe recipe = new ShapedRecipe(key, historyBrush);
        recipe.shape("GGG", " B ", "   ");
        recipe.setIngredient('B', Material.BRUSH);
        recipe.setIngredient('G', Material.GLOW_INK_SAC);

        Bukkit.addRecipe(recipe);
    }

    protected boolean isHistoryBrush(ItemStack item) {
        if (item == null || item.getType() != Material.BRUSH) return false;
        if (!item.hasItemMeta()) return false;

        ItemMeta meta = item.getItemMeta();
        if (!meta.hasDisplayName()) return false;

        return Component.text("History Brush", NamedTextColor.GOLD)
                .equals(meta.displayName());
    }

    // ===== OUTPUT TYPE =====
    protected OutputType getOutputType() {
        String type = messages.getString("output.type", "chatmessage").toLowerCase();
        return switch (type) {
            case "actionbar" -> OutputType.ACTIONBAR;
            case "itemdescription" -> OutputType.ITEMDESCRIPTION;
            case "combined" -> OutputType.COMBINED;
            default -> OutputType.CHAT;
        };
    }

    // ===== HISTORY OUTPUT =====
    protected void outputHistory(Player player, ItemStack brush, List<Component> history) {

        OutputType type = getOutputType();
        if (history.isEmpty()) {
            sendOutput(player, brush, c("no_records"));
            return;
        }

        Component last = history.get(0);

        if (type == OutputType.CHAT || type == OutputType.COMBINED) {
            sendOutput(player, null, c("block_history_title"));

            int limit = Math.min(chatMaxLogs, history.size());
            for (int i = 0; i < limit; i++) {
                player.sendMessage(history.get(i));
            }
        }

        if (type == OutputType.ACTIONBAR || type == OutputType.COMBINED) {
            player.sendActionBar(last);
        }

        if ((type == OutputType.ITEMDESCRIPTION || type == OutputType.COMBINED) && brush != null) {
            ItemMeta meta = brush.getItemMeta();
            List<Component> lore = new ArrayList<>();

            lore.add(c("history_format.title"));
            lore.add(c("history_format.separator"));
            lore.add(last);

            meta.lore(lore);
            brush.setItemMeta(meta);

        }
    }

    protected void logBlock(Block block, String action, String player) {
        try (PreparedStatement ps = connection.prepareStatement("""
        INSERT INTO block_logs (world, x, y, z, action, block, player)
        VALUES (?, ?, ?, ?, ?, ?, ?)
    """)) {
            ps.setString(1, block.getWorld().getName());
            ps.setInt(2, block.getX());
            ps.setInt(3, block.getY());
            ps.setInt(4, block.getZ());
            ps.setString(5, action);
            ps.setString(6, block.getType().name());
            ps.setString(7, player);
            ps.executeUpdate();
        } catch (SQLException e) {
            getLogger().log(Level.SEVERE, "Block log error", e);
        }
    }
    protected List<Component> getBlockHistory(Block block) {

        List<Component> result = new ArrayList<>();

        try (PreparedStatement ps = connection.prepareStatement("""
        SELECT action, block, player, time
        FROM block_logs
        WHERE world=? AND x=? AND y=? AND z=?
        ORDER BY id DESC
        LIMIT 10
    """)) {

            ps.setString(1, block.getWorld().getName());
            ps.setInt(2, block.getX());
            ps.setInt(3, block.getY());
            ps.setInt(4, block.getZ());

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(
                            mm.deserialize(
                                    rs.getString("action") +
                                            " <white>" + rs.getString("block") + "</white>" +
                                            messages.getString("by_player").replace("%player%", rs.getString("player")) +
                                            messages.getString("time_ago").replace("%time%", formatTimeAgo(rs.getString("time")))
                            )
                    );
                }
            }
        } catch (SQLException e) {
            getLogger().log(Level.SEVERE, "History read error", e);
        }

        return result;
    }
    @EventHandler
    public void onBlockPlace(BlockPlaceEvent e) {
        Block block = e.getBlockPlaced();
        Player player = e.getPlayer();

        logBlock(
                block,
                msg("log_block_place"),
                player.getName()
        );
    }
    @EventHandler
    public void onBlockBreak(BlockBreakEvent e) {
        Block block = e.getBlock();
        Player player = e.getPlayer();

        logBlock(
                block,
                msg("log_block_break"),
                player.getName()
        );
    }
    @Nullable
    protected Block getContainerBlock(Inventory inventory) {

        if (inventory == null) return null;

        InventoryHolder holder = inventory.getHolder();

        if (holder instanceof Container container) {
            return container.getBlock();
        }

        if (holder instanceof DoubleChest doubleChest) {
            InventoryHolder left = doubleChest.getLeftSide();
            if (left instanceof Container container) {
                return container.getBlock();
            }
        }

        return null;
    }



    @EventHandler
    public void onExplode(EntityExplodeEvent e) {

        String source = e.getEntity() != null
                ? e.getEntity().getType().name()
                : "UNKNOWN";

        for (Block block : e.blockList()) {
            logBlock(
                    block,
                    msg("log_block_explode"),
                    source
            );
        }
    }
    protected void logContainer(Block block, String action, String item, int amount, String player) {
        try (PreparedStatement ps = connection.prepareStatement("""
        INSERT INTO container_logs (world, x, y, z, action, item, amount, player)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
    """)) {
            ps.setString(1, block.getWorld().getName());
            ps.setInt(2, block.getX());
            ps.setInt(3, block.getY());
            ps.setInt(4, block.getZ());
            ps.setString(5, action);
            ps.setString(6, item);
            ps.setInt(7, amount);
            ps.setString(8, player);
            ps.executeUpdate();
        } catch (SQLException e) {
            getLogger().severe("Container log error");
            e.printStackTrace();
        }
    }

    protected List<Component> getContainerHistory(Block block) {

        List<Component> result = new ArrayList<>();

        try (PreparedStatement ps = connection.prepareStatement("""
        SELECT action, item, amount, player, time
        FROM container_logs
        WHERE world=? AND x=? AND y=? AND z=?
        ORDER BY id DESC
        LIMIT 10
    """)) {

            ps.setString(1, block.getWorld().getName());
            ps.setInt(2, block.getX());
            ps.setInt(3, block.getY());
            ps.setInt(4, block.getZ());

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(
                            mm.deserialize(
                                    rs.getString("action") +
                                            " <white>x" + rs.getInt("amount") + " " + rs.getString("item") + "</white>" +
                                            " <green>" + rs.getString("player") + "</green>" +
                                            " <gray>⏰ " + formatTimeAgo(rs.getString("time")) + "</gray>"
                            )
                    );
                }
            }
        } catch (SQLException e) {
            getLogger().log(Level.SEVERE, "Container history read error", e);
        }

        return result;
    }

    @EventHandler
    public void onContainerClick(InventoryClickEvent e) {

        if (!(e.getWhoClicked() instanceof Player player)) return;
        if (e.getClickedInventory() == null) return;

        Inventory topInv = e.getView().getTopInventory();
        Block block = getContainerBlock(topInv);

        if (block == null) return;

        switch (e.getAction()) {

            case PICKUP_ALL, PICKUP_HALF, PICKUP_ONE, PICKUP_SOME -> {
                if (e.getClickedInventory().equals(topInv)) {
                    ItemStack item = e.getCurrentItem();
                    if (item != null && item.getType() != Material.AIR) {
                        logContainer(block, msg("log_container_take"), item.getType().name(), item.getAmount(), player.getName());
                    }
                }
            }

            case PLACE_ALL, PLACE_ONE, PLACE_SOME, SWAP_WITH_CURSOR -> {
                if (e.getClickedInventory().equals(topInv)) {
                    ItemStack cursor = e.getCursor();
                    if (cursor != null && cursor.getType() != Material.AIR) {
                        logContainer(block, msg("log_container_put"), cursor.getType().name(), cursor.getAmount(), player.getName());
                    }
                }
            }

            // 🔁 SHIFT + CLICK (ЛКМ та ПКМ)
            case MOVE_TO_OTHER_INVENTORY -> {
                ItemStack item = e.getCurrentItem();
                if (item != null && item.getType() != Material.AIR) {
                    if (e.getClickedInventory().equals(topInv)) {
                        logContainer(block, msg("log_container_take"), item.getType().name(), item.getAmount(), player.getName());
                    }
                    else {
                        logContainer(block, msg("log_container_put"), item.getType().name(), item.getAmount(), player.getName());
                    }
                }
            }

            case COLLECT_TO_CURSOR -> {
                ItemStack cursor = e.getCursor();
                if (cursor != null && cursor.getType() != Material.AIR) {
                    if (topInv.contains(cursor.getType())) {
                        logContainer(block, msg("log_container_take") + " (Stack)", cursor.getType().name(), cursor.getAmount(), player.getName());
                    }
                }
            }
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent e) {
        if (!(e.getWhoClicked() instanceof Player player)) return;

        Block block = getContainerBlock(e.getView().getTopInventory());
        if (block == null) return;

        int topInvSize = e.getView().getTopInventory().getSize();

        for (int slot : e.getRawSlots()) {
            if (slot < topInvSize) {
                ItemStack itemAdded = e.getNewItems().get(slot);
                if (itemAdded != null && itemAdded.getType() != Material.AIR) {
                    logContainer(
                            block,
                            msg("log_container_put") + " (Drag)",
                            itemAdded.getType().name(),
                            itemAdded.getAmount(),
                            player.getName()
                    );
                }
            }
        }
    }


    protected void highlightBlock(Block block, int durationTicks) {

        Bukkit.getScheduler().runTaskTimer(this, new Runnable() {

            int ticks = 0;

            @Override
            public void run() {
                if (ticks >= durationTicks) {
                    return;
                }

                block.getWorld().spawnParticle(
                        Particle.END_ROD,
                        block.getX() + 0.5,
                        block.getY() + 1.1,
                        block.getZ() + 0.5,
                        10,
                        0.3,
                        0.3,
                        0.3,
                        0.01
                );

                ticks += 10;
            }
        }, 0L, 10L);
    }

    @EventHandler
    public void onBrushUse(PlayerInteractEvent e) {

        if (e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (!isHistoryBrush(e.getItem())) return;
        if (e.getClickedBlock() == null) return;

        Player player = e.getPlayer();
        Block clicked = e.getClickedBlock();

        e.setCancelled(true);

        Block targetBlock = clicked;

        if (clicked.getState() instanceof Container) {
            targetBlock = clicked;
        }

        highlightBlock(targetBlock, 60);

        List<Component> history;

        if (targetBlock.getState() instanceof Container) {
            history = getContainerHistory(targetBlock);
        } else {
            history = getBlockHistory(targetBlock);
        }

        outputHistory(player, e.getItem(), history);
        player.getInventory().setItemInMainHand(e.getItem());
        player.updateInventory();
    }


    // ===== COMMAND PLACEHOLDER =====
    @Override
    public boolean onCommand(@NotNull CommandSender sender,
                             @NotNull Command cmd,
                             @NotNull String label,
                             @NotNull String[] args) {

        if (!(sender instanceof Player player)) {
            sender.sendMessage(msg("player_only_command"));
            return true;
        }

        if (!player.hasPermission(PERM_COMMAND)) {
            player.sendMessage(ChatColor.RED + "No permission.");
            return true;
        }

        if (args.length == 0) {
            player.sendMessage(ChatColor.YELLOW + "/bl history <x> <y> <z>");
            player.sendMessage(ChatColor.YELLOW + "/bl reload");
            return true;
        }

        // ===== /bl reload =====
        if (args[0].equalsIgnoreCase("reload")) {

            if (!player.hasPermission(PERM_RELOAD)) {
                player.sendMessage(ChatColor.RED + "No permission.");
                return true;
            }

            loadMessages();
            player.sendMessage(c("messages_reloaded"));
            return true;
        }
        // ===== /bl maxlog <x> =====
        if (args[0].equalsIgnoreCase("maxlog")) {

            if (!player.hasPermission(PERM_MAXLOG)) {
                sendOutput(player, null, c("no_permission"));
                return true;
            }

            if (args.length != 2) {
                sendOutput(player, null, c("maxlog_usage"));
                return true;
            }

            int value;
            try {
                value = Integer.parseInt(args[1]);
            } catch (NumberFormatException e) {
                sendOutput(player, null, c("maxlog_number"));
                return true;
            }

            if (value < 1 || value > 50) {
                sendOutput(player, null, c("maxlog_range"));
                return true;
            }

            chatMaxLogs = value;

            sendOutput(
                    player,
                    null,
                    c("maxlog_set", "%value%", String.valueOf(value))
            );
            return true;
        }

        if (args[0].equalsIgnoreCase("history")) {

            if (!player.hasPermission(PERM_HISTORY)) {
                player.sendMessage(ChatColor.RED + "No permission.");
                return true;
            }

            if (args.length != 4) {
                player.sendMessage(ChatColor.RED + "Usage: /bl history <x> <y> <z>");
                return true;
            }

            int x, y, z;

            try {
                x = Integer.parseInt(args[1]);
                y = Integer.parseInt(args[2]);
                z = Integer.parseInt(args[3]);
            } catch (NumberFormatException e) {
                player.sendMessage(ChatColor.RED + "Coordinates must be numbers.");
                return true;
            }

            Block block = player.getWorld().getBlockAt(x, y, z);

            List<Component> history;

            if (block.getState() instanceof Container) {
                player.sendMessage(
                        msg("container_history_command")
                                .replace("%x%", String.valueOf(x))
                                .replace("%y%", String.valueOf(y))
                                .replace("%z%", String.valueOf(z))
                );
                history = getContainerHistory(block);
            } else {
                player.sendMessage(
                        msg("block_history_command")
                                .replace("%x%", String.valueOf(x))
                                .replace("%y%", String.valueOf(y))
                                .replace("%z%", String.valueOf(z))
                );
                history = getBlockHistory(block);
            }
            if (history.isEmpty()) {
                sendOutput(player, player.getInventory().getItemInMainHand(), c("no_records"));
            } else {
                for (int i = 0; i < history.size(); i++) {
                    player.sendMessage((i + 1) + ". " + history.get(i));
                }
            }
            return true;
        }
        return true;
    }
}
