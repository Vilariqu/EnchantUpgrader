package com.example.enchantupgrader;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public class EnchantUpgrader extends JavaPlugin implements Listener, CommandExecutor {

    private final Map<Enchantment, Integer> maxLevels = new HashMap<>();
    private boolean enableCostScaling = true;
    private boolean debugMode = true;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadConfig();
        
        Bukkit.getPluginManager().registerEvents(this, this);
        getCommand("enupgrade-reload").setExecutor(this);
        
        getLogger().info("EnchantUpgrader включен! Режим отладки: " + debugMode);
    }

    private void loadConfig() {
        maxLevels.clear();
        FileConfiguration config = getConfig();
        
        enableCostScaling = config.getBoolean("cost-scaling", true);
        debugMode = config.getBoolean("debug-mode", true);
        
        if (config.contains("enchantments")) {
            Set<String> enchantKeys = config.getConfigurationSection("enchantments").getKeys(false);
            for (String enchKey : enchantKeys) {
                int maxLevel = config.getInt("enchantments." + enchKey);
                Enchantment ench = Enchantment.getByKey(org.bukkit.NamespacedKey.minecraft(enchKey.toLowerCase()));
                
                if (ench != null) {
                    maxLevels.put(ench, maxLevel);
                    if (debugMode) {
                        getLogger().info("Зарегистрировано зачарование: " + ench.getKey() + " с макс. уровнем " + maxLevel);
                    }
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onAnvilPrepare(PrepareAnvilEvent event) {
        try {
            ItemStack left = event.getInventory().getItem(0);
            ItemStack right = event.getInventory().getItem(1);
            
            if (left == null || right == null) {
                if (debugMode) getLogger().info("Один из предметов отсутствует");
                return;
            }
            
            if (!left.getType().equals(right.getType())) {
                if (debugMode) getLogger().info("Типы предметов не совпадают: " + left.getType() + " и " + right.getType());
                return;
            }
            
            ItemStack newResult = left.clone();
            ItemMeta meta = newResult.getItemMeta();
            if (meta == null) return;
            
            for (Enchantment ench : newResult.getEnchantments().keySet()) {
                meta.removeEnchant(ench);
            }
            newResult.setItemMeta(meta);
            
            Map<Enchantment, Integer> leftEnchants = getAllEnchants(left);
            Map<Enchantment, Integer> rightEnchants = getAllEnchants(right);
            
            int extraCost = 0;
            boolean modified = false;
            
            for (Map.Entry<Enchantment, Integer> entry : leftEnchants.entrySet()) {
                Enchantment ench = entry.getKey();
                int leftLevel = entry.getValue();
                int rightLevel = rightEnchants.getOrDefault(ench, 0);
                
                int maxAllowed = maxLevels.getOrDefault(ench, ench.getMaxLevel());
                int newLevel = leftLevel;
                
                if (rightLevel > 0) {
                    if (leftLevel == rightLevel) {
                        newLevel = Math.min(leftLevel + 1, maxAllowed);
                    } else {
                        newLevel = Math.min(Math.max(leftLevel, rightLevel), maxAllowed);
                    }
                }
                
                applyEnchant(newResult, ench, newLevel);
                if (newLevel > leftLevel) {
                    extraCost += (newLevel * 2);
                    modified = true;
                }
            }
            
            for (Map.Entry<Enchantment, Integer> entry : rightEnchants.entrySet()) {
                Enchantment ench = entry.getKey();
                if (leftEnchants.containsKey(ench)) continue;
                
                int rightLevel = entry.getValue();
                int maxAllowed = maxLevels.getOrDefault(ench, ench.getMaxLevel());
                int newLevel = Math.min(rightLevel, maxAllowed);
                
                applyEnchant(newResult, ench, newLevel);
                extraCost += (newLevel * 2);
                modified = true;
            }
            
            if (modified) {
                if (debugMode) getLogger().info("Успешно модифицирован результат");
                event.setResult(newResult);
                
                if (enableCostScaling) {
                    int newRepairCost = Math.min(event.getInventory().getRepairCost() + extraCost, 39);
                    event.getInventory().setRepairCost(newRepairCost);
                }
                
                if (!event.getViewers().isEmpty() && event.getViewers().get(0) instanceof Player) {
                    Player player = (Player) event.getViewers().get(0);
                    Bukkit.getScheduler().runTaskLater(this, player::updateInventory, 1L);
                }
            }
        } catch (Exception e) {
            if (debugMode) {
                getLogger().severe("Ошибка в onAnvilPrepare: " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    private Map<Enchantment, Integer> getAllEnchants(ItemStack item) {
        Map<Enchantment, Integer> enchants = new HashMap<>();
        if (item == null) return enchants;
        
        if (item.getItemMeta() instanceof EnchantmentStorageMeta meta) {
            enchants.putAll(meta.getStoredEnchants());
        } else {
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                enchants.putAll(meta.getEnchants());
            }
        }
        return enchants;
    }

    private void applyEnchant(ItemStack item, Enchantment ench, int level) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        
        if (meta instanceof EnchantmentStorageMeta storageMeta) {
            if (storageMeta.hasStoredEnchant(ench)) {
                storageMeta.removeStoredEnchant(ench);
            }
            storageMeta.addStoredEnchant(ench, level, true);
        } else {
            if (meta.hasEnchant(ench)) {
                meta.removeEnchant(ench);
            }
            meta.addEnchant(ench, level, true);
        }
        item.setItemMeta(meta);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (cmd.getName().equalsIgnoreCase("enupgrade-reload")) {
            reloadConfig();
            loadConfig();
            sender.sendMessage("§aКонфигурация перезагружена! Режим отладки: " + debugMode);
            return true;
        }
        return false;
    }
}