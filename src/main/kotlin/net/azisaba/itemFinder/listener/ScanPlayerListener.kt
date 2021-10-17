package net.azisaba.itemFinder.listener

import net.azisaba.itemFinder.ItemFinder
import net.azisaba.itemFinder.util.Util.check
import net.azisaba.itemFinder.util.Util.or
import net.azisaba.itemFinder.util.Util.toHoverEvent
import net.md_5.bungee.api.chat.ClickEvent
import net.md_5.bungee.api.chat.TextComponent
import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.inventory.Inventory

object ScanPlayerListener: Listener {
    var enabled = false

    @EventHandler
    fun onPlayerJoin(e: PlayerJoinEvent) {
        if (!enabled) return
        check("${e.player.name}のインベントリ", e.player.name, e.player.inventory)
        check("${e.player.name}のエンダーチェスト", e.player.name, e.player.enderChest)
    }

    private fun check(what: String, player: String, inventory: Inventory) {
        val map = inventory.check()
        ItemFinder.itemsToFind.forEach { itemStack ->
            val amount = map.filter { it.key.isSimilar(itemStack) }.entries.firstOrNull()?.value ?: 0
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
    }
}
