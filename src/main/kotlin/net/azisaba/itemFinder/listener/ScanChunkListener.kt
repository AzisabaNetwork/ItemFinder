package net.azisaba.itemFinder.listener

import net.azisaba.itemFinder.ItemFinder
import net.azisaba.itemFinder.util.Util.getBlockState
import net.azisaba.itemFinder.util.Util.or
import net.azisaba.itemFinder.util.Util.runOnMain
import net.azisaba.itemFinder.util.Util.toHoverEvent
import net.md_5.bungee.api.chat.ClickEvent
import net.md_5.bungee.api.chat.HoverEvent
import net.md_5.bungee.api.chat.TextComponent
import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.Chunk
import org.bukkit.ChunkSnapshot
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.world.ChunkLoadEvent
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.BlockStateMeta
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.Future

object ScanChunkListener: Listener {
    var enabled = false
    val chunkScannerExecutor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors() * 2)!!

    @EventHandler
    fun onChunkLoad(e: ChunkLoadEvent) {
        if (!enabled || e.isNewChunk) return
        checkChunkAsync(e.chunk)
    }

    fun checkChunkAsync(chunk: Chunk, andThen: () -> Unit = {}): Future<*> {
        if (!chunk.isLoaded) return CompletableFuture.completedFuture(null)
        if (ItemFinder.seen.getOrPut(chunk.world.name) { mutableListOf() }.contains(chunk.x to chunk.z)) CompletableFuture.completedFuture(null)
        val snapshot = chunk.getChunkSnapshot(true, false, false)
        return chunkScannerExecutor.submit {
            try {
                checkChunk(snapshot)
                andThen()
            } catch (e: Exception) {
                ItemFinder.instance.logger.warning("Could not check chunk ${chunk.x to chunk.z}")
                e.printStackTrace()
            }
        }
    }

    fun checkChunk(snapshot: ChunkSnapshot) {
        if (ItemFinder.seen.getOrPut(snapshot.worldName) { mutableListOf() }.contains(snapshot.x to snapshot.z)) return
        ItemFinder.seen[snapshot.worldName]!!.add(snapshot.x to snapshot.z)
        for (x in 0..15) {
            for (z in 0..15) {
                val maxY = snapshot.getHighestBlockYAt(x, z)
                for (y in 0..maxY) {
                    val data = snapshot.getBlockData(x, y, z)
                    if (
                        !data::class.java.name.contains("Chest")
                        && !data::class.java.name.contains("ShulkerBox")
                        && !data::class.java.name.contains("Barrel")
                        && !data::class.java.name.contains("Furnace") // BlastFurnace
                        && !data::class.java.name.contains("Hopper")
                    ) continue
                    val state = snapshot.getBlockState(x, y, z).complete()
                    if (state !is InventoryHolder) continue
                    val map = state.check()
                    ItemFinder.itemsToFind.forEach { itemStack ->
                        val amount = map.filter { it.key.isSimilar(itemStack) }.entries.firstOrNull()?.value ?: 0
                        if (amount >= itemStack.amount) {
                            val absX = snapshot.x * 16 + x
                            val absZ = snapshot.z * 16 + z
                            val text = TextComponent("${ChatColor.GOLD}[${ChatColor.WHITE}${itemStack.itemMeta?.displayName or itemStack.type.name}${ChatColor.GOLD}]${ChatColor.YELLOW}x${amount} ${ChatColor.GOLD}が以下の座標から見つかりました:")
                            text.hoverEvent = itemStack.toHoverEvent()
                            val posText = TextComponent("  ${ChatColor.GREEN}X: ${ChatColor.GOLD}$absX, ${ChatColor.GREEN}Y: ${ChatColor.GOLD}$y, ${ChatColor.GREEN}Z: ${ChatColor.GOLD}$absZ")
                            posText.hoverEvent = HoverEvent(HoverEvent.Action.SHOW_TEXT, TextComponent.fromLegacyText("クリックでテレポート"))
                            posText.clickEvent = ClickEvent(ClickEvent.Action.RUN_COMMAND, "/tppos $absX $y $absZ 0 0 ${snapshot.worldName}")
                            Bukkit.getOnlinePlayers().filter { it.hasPermission("itemfinder.notify") }.forEach { p ->
                                p.spigot().sendMessage(text)
                                p.spigot().sendMessage(posText)
                            }
                            Bukkit.getConsoleSender().let { console ->
                                console.spigot().sendMessage(text)
                                console.spigot().sendMessage(posText)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun InventoryHolder.check(): Map<ItemStack, Int> {
        val map = mutableMapOf<ItemStack, Int>()
        val items = { this.inventory.contents }.runOnMain().complete()
        items.forEach { itemStack ->
            @Suppress("SENSELESS_COMPARISON") // it's actually nullable, wtf
            if (itemStack == null) return@forEach
            map.merge(itemStack.clone().apply { amount = 1 }, itemStack.amount, Integer::sum)
            itemStack.itemMeta?.let {
                if (it is BlockStateMeta && it.hasBlockState()) {
                    it.blockState.let { bs ->
                        if (bs is InventoryHolder) {
                            bs.check().forEach { (t, u) -> map.merge(t, u * itemStack.amount, Integer::sum) }
                        }
                    }
                }
            }
        }
        return map
    }
}
