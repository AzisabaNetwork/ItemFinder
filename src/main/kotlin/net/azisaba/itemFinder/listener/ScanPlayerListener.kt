package net.azisaba.itemFinder.listener

import net.azisaba.itemFinder.ItemFinder
import net.azisaba.itemFinder.util.Util.check
import net.azisaba.itemFinder.util.Util.or
import net.azisaba.itemFinder.util.Util.toHoverEvent
import net.md_5.bungee.api.chat.ClickEvent
import net.md_5.bungee.api.chat.TextComponent
import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import xyz.acrylicstyle.storageBox.utils.StorageBox

object ScanPlayerListener: Listener {
    var enabled = false

    @EventHandler
    fun onPlayerJoin(e: PlayerJoinEvent) {
        if (!enabled) return
        checkPlayer(e.player)
    }

    fun checkPlayer(player: Player) {
        check("${player.name}のインベントリ", player.name, player.inventory)
        check("${player.name}のエンダーチェスト", player.name, player.enderChest)
    }

    private fun check(what: String, player: String, inventory: Inventory) {
        val map = inventory.check()
        ItemFinder.itemsToFind.forEach { itemStack ->
            val amount: Long =
                map.firstNotNullOfOrNull { (item) ->
                    StorageBox.getStorageBox(item)?.let { box ->
                        if (box.isComponentItemStackSimilar(itemStack)) {
                            box.amount * item.amount.toLong()
                        } else {
                            null
                        }
                    }
                }
                    ?: map.firstNotNullOfOrNull { if (it.key.isSimilar(itemStack)) it.value.toLong() else null }
                    ?: 0
            if (amount >= itemStack.amount) {
                val text = TextComponent("${ChatColor.GOLD}[${ChatColor.WHITE}${itemStack.itemMeta?.displayName or itemStack.type.name}${ChatColor.GOLD}]${ChatColor.YELLOW}x${amount} ${ChatColor.GOLD}が${what}から見つかりました ${ChatColor.GRAY}(クリックでテレポート)")
                text.hoverEvent = itemStack.toHoverEvent()
                text.clickEvent = ClickEvent(ClickEvent.Action.RUN_COMMAND, "/tp $player")
                Bukkit.getOnlinePlayers().filter { it.hasPermission("itemfinder.notify") }.forEach { p ->
                    p.spigot().sendMessage(text)
                }
                Bukkit.getConsoleSender().spigot().sendMessage(text)
            }
        }
        /*
        val amount = map.filter { it.key.itemMeta?.attributeModifiers?.get(Attribute.GENERIC_LUCK)?.any { mod -> mod.amount > 100.0 } == true }.entries.firstOrNull()?.value ?: 0
        if (amount >= 1) {
            val text = TextComponent("${ChatColor.GOLD}[${ChatColor.WHITE}Luck >= 100のやつ${ChatColor.GOLD}]${ChatColor.YELLOW}x${amount} ${ChatColor.GOLD}が${what}から見つかりました ${ChatColor.GRAY}(クリックでテレポート)")
            text.clickEvent = ClickEvent(ClickEvent.Action.RUN_COMMAND, "/tp $player")
            Bukkit.getOnlinePlayers().filter { it.hasPermission("itemfinder.notify") }.forEach { p ->
                p.spigot().sendMessage(text)
            }
            Bukkit.getConsoleSender().spigot().sendMessage(text)
        }
        */
    }
}
