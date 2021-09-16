package net.azisaba.itemFinder

import net.azisaba.itemFinder.listener.ScanChunkListener
import net.azisaba.itemFinder.util.Util.toHoverEvent
import net.md_5.bungee.api.chat.TextComponent
import org.bukkit.ChatColor
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.command.TabExecutor
import org.bukkit.entity.Player

object ItemFinderCommand: TabExecutor {
    private val listOf64 = (1..64).toList().map { it.toString() }

    override fun onCommand(sender: CommandSender, command: Command, s: String, args: Array<String>): Boolean {
        if (args.isEmpty()) {
            sender.sendMessage("${ChatColor.RED}/itemfinder <add|remove|removeall|clearlogs>")
            return true
        }
        when (args[0]) {
            "off" -> {
                ScanChunkListener.enabled = false
                sender.sendMessage("${ChatColor.GREEN}チャンクのスキャンをオフにしました。")
            }
            "on" -> {
                ScanChunkListener.enabled = true
                sender.sendMessage("${ChatColor.GREEN}チャンクのスキャンをオンにしました。")
            }
            "add" -> {
                if (sender !is Player) {
                    sender.sendMessage("${ChatColor.RED}このコマンドはコンソールからは実行できません。")
                    return true
                }
                val amount = args.getOrNull(1)?.toIntOrNull()
                if (amount == null) {
                    sender.sendMessage("${ChatColor.RED}/itemfinder add <最低アイテム数>")
                    return true
                }
                if (sender.inventory.itemInMainHand.type.isAir) {
                    sender.sendMessage("${ChatColor.RED}メインハンドにアイテムを持ってください。")
                    return true
                }
                ItemFinder.itemsToFind.removeIf { it.isSimilar(sender.inventory.itemInMainHand) }
                ItemFinder.itemsToFind.add(sender.inventory.itemInMainHand.clone().apply { this.amount = amount })
                val text = TextComponent("探す対象のアイテムを追加しました。")
                text.color = net.md_5.bungee.api.ChatColor.GREEN
                text.hoverEvent = sender.inventory.itemInMainHand.toHoverEvent()
                sender.spigot().sendMessage(text)
            }
            "remove" -> {
                if (sender !is Player) {
                    sender.sendMessage("${ChatColor.RED}このコマンドはコンソールからは実行できません。")
                    return true
                }
                if (sender.inventory.itemInMainHand.type.isAir) {
                    sender.sendMessage("${ChatColor.RED}メインハンドにアイテムを持ってください。")
                    return true
                }
                ItemFinder.itemsToFind.removeIf { it.isSimilar(sender.inventory.itemInMainHand) }
                val text = TextComponent("探す対象のアイテムを削除しました。")
                text.color = net.md_5.bungee.api.ChatColor.GREEN
                text.hoverEvent = sender.inventory.itemInMainHand.toHoverEvent()
                sender.spigot().sendMessage(text)
            }
            "removeall" -> {
                ItemFinder.itemsToFind.clear()
                sender.sendMessage("${ChatColor.GREEN}探す対象のアイテムリストをすべて削除しました。")
            }
            "clearlogs" -> {
                ItemFinder.seen.values.forEach { it.clear() }
                sender.sendMessage("${ChatColor.GREEN}スキャンされたチャンクリストを削除しました。")
            }
            "scanall" -> {
                if (sender !is Player) {
                    sender.sendMessage("${ChatColor.RED}このコマンドはコンソールからは実行できません。")
                    return true
                }
                sender.sendMessage("${ChatColor.GREEN}読み込まれているすべてのチャンクをスキャン中です。しばらく時間がかかります。")
                ScanChunkListener.chunkScannerExecutor.submit {
                    sender.world.loadedChunks.forEach {
                        ScanChunkListener.checkChunk(it.getChunkSnapshot(true, false, false))
                    }
                    sender.sendMessage("${ChatColor.GREEN}")
                }
            }
            "scanhere" -> {
                if (sender !is Player) {
                    sender.sendMessage("${ChatColor.RED}このコマンドはコンソールからは実行できません。")
                    return true
                }
                val c = sender.location.chunk
                ItemFinder.seen.getOrPut(sender.world.name) { mutableListOf() }.remove(c.x to c.z)
                sender.sendMessage("${ChatColor.GREEN}チャンクをスキャン中です。")
                ScanChunkListener.checkChunkAsync(c) {
                    sender.sendMessage("${ChatColor.GREEN}チャンクのスキャンが完了しました。")
                }
            }
        }
        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, s: String, args: Array<String>): List<String> {
        if (args.isEmpty()) return emptyList()
        if (args.size == 1) return listOf("on", "off", "add", "remove", "removeall", "clearlogs", "scanall", "scanhere").filter(args[0])
        if (args.size == 2) {
            if (args[0] == "add") return listOf64.filter(args[1])
        }
        return emptyList()
    }

    private fun List<String>.filter(s: String): List<String> = distinct().filter { s1 -> s1.lowercase().startsWith(s.lowercase()) }
}
