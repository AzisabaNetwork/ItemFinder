package net.azisaba.itemFinder.listener

import net.azisaba.itemFinder.ItemFinder
import net.azisaba.itemFinder.util.Util.check
import net.azisaba.itemFinder.util.Util.getBlockState
import net.azisaba.itemFinder.util.Util.or
import net.azisaba.itemFinder.util.Util.runOnMain
import net.azisaba.itemFinder.util.Util.toHoverEvent
import net.md_5.bungee.api.chat.ClickEvent
import net.md_5.bungee.api.chat.HoverEvent
import net.md_5.bungee.api.chat.TextComponent
import net.minecraft.server.v1_15_R1.ChunkCoordIntPair
import net.minecraft.server.v1_15_R1.ContainerUtil
import net.minecraft.server.v1_15_R1.NBTTagCompound
import net.minecraft.server.v1_15_R1.NonNullList
import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.Chunk
import org.bukkit.ChunkSnapshot
import org.bukkit.Location
import org.bukkit.command.CommandSender
import org.bukkit.craftbukkit.v1_15_R1.inventory.CraftItemStack
import org.bukkit.entity.EntityType
import org.bukkit.entity.ItemFrame
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.world.ChunkLoadEvent
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import xyz.acrylicstyle.storageBox.utils.StorageBox
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min

object ScanChunkListener : Listener {
    var enabled = false
    val chunkScannerExecutor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors() * 4)!!

    @EventHandler
    fun onChunkLoad(e: ChunkLoadEvent) {
        if (ItemFinder.instance.config.getBoolean(
                "removeItem",
                false
            ) && !ItemFinder.seen.getOrPut(e.chunk.world.name) { mutableListOf() }.contains(e.chunk.x to e.chunk.z)
        ) {
            e.chunk.entities.forEach { entity ->
                if (entity.type == EntityType.DROPPED_ITEM) entity.remove()
            }
        }
        if (!enabled || e.isNewChunk) return
        checkChunkAsync(e.chunk) { item, amount, _ ->
            StorageBox.getStorageBox(item).let { box ->
                val vanillaItem = box?.type?.let { ItemStack(it) }
                ItemFinder.itemsToFind.any { itemStack ->
                    if (vanillaItem?.isSimilar(itemStack) == true && box.amount >= itemStack.amount) {
                        amount.set(box.amount)
                        true
                    } else {
                        item.isSimilar(itemStack) && amount.get() >= itemStack.amount
                    }
                }
            }
        }
    }

    fun checkChunkAsync(chunk: Chunk, sender: CommandSender? = null, andThen: () -> Unit = {}, predicate: (item: ItemStack, amount: AtomicLong, location: Location) -> Boolean): Future<*> {
        if (ItemFinder.seen.getOrPut(chunk.world.name) { mutableListOf() }
                .contains(chunk.x to chunk.z)) CompletableFuture.completedFuture(null)
        val wasLoaded = chunk.isLoaded

        val snapshot = {
            if (!wasLoaded) chunk.load()
            val check: (map: Map<ItemStack, Int>, loc: Location) -> Unit = { map, loc ->
                map.forEach { (item, origAmount) ->
                    val amount = AtomicLong(origAmount.toLong())
                    if (predicate(item, amount, loc)) {
                        val x = loc.blockX
                        val y = loc.blockY
                        val z = loc.blockZ
                        val text = TextComponent("${ChatColor.GOLD}[${ChatColor.WHITE}${item.itemMeta?.displayName or item.type.name}${ChatColor.GOLD}]${ChatColor.YELLOW}x${amount} ${ChatColor.GOLD}が以下の座標から見つかりました:")
                        text.hoverEvent = item.toHoverEvent()
                        val posText =
                            TextComponent("  ${ChatColor.GREEN}X: ${ChatColor.GOLD}$x, ${ChatColor.GREEN}Y: ${ChatColor.GOLD}$y, ${ChatColor.GREEN}Z: ${ChatColor.GOLD}$z")
                        posText.hoverEvent =
                            HoverEvent(HoverEvent.Action.SHOW_TEXT, TextComponent.fromLegacyText("クリックでテレポート"))
                        posText.clickEvent =
                            ClickEvent(ClickEvent.Action.RUN_COMMAND, "/tppos $x $y $z 0 0 ${chunk.world.name}")
                        text.addExtra(posText)
                        notify(sender, text)
                    }
                }
            }
            chunk.entities.filter { it is InventoryHolder && it !is Player }.forEach {
                check((it as InventoryHolder).check(), it.location)
            }
            chunk.entities.filterIsInstance<ItemFrame>().forEach { itemFrame ->
                check(itemFrame.item.check(), itemFrame.location)
            }
            val snapshot = chunk.getChunkSnapshot(true, false, false)
            if (!wasLoaded) chunk.unload()
            snapshot
        }.runOnMain().complete()
        return chunkScannerExecutor.submit {
            try {
                { checkChunk(snapshot, sender, predicate) }.runOnMain().complete()
                andThen()
            } catch (e: Exception) {
                ItemFinder.instance.logger.warning("Could not check chunk ${chunk.x to chunk.z}")
                e.printStackTrace()
            }
        }
    }

    fun checkChunk(snapshot: ChunkSnapshot, sender: CommandSender? = null, predicate: (item: ItemStack, amount: AtomicLong, location: Location) -> Boolean) {
        if (ItemFinder.seen.getOrPut(snapshot.worldName) { mutableListOf() }.contains(snapshot.x to snapshot.z)) return
        ItemFinder.seen[snapshot.worldName]!!.add(snapshot.x to snapshot.z)
        for (x in 0..15) {
            for (z in 0..15) {
                // cap at 255, for now
                val maxY = min(255, snapshot.getHighestBlockYAt(x, z))
                for (y in 0..maxY) {
                    val data = snapshot.getBlockData(x, y, z)
                    if (
                        !data::class.java.name.contains("Chest")
                        && !data::class.java.name.contains("ShulkerBox")
                        && !data::class.java.name.contains("Barrel")
                        && !data::class.java.name.contains("Furnace") // BlastFurnace
                        && !data::class.java.name.contains("Hopper")
                        && !data::class.java.name.contains("Dropper")
                        && !data::class.java.name.contains("Dispenser")
                        && !data::class.java.name.contains("Smoker")
                    ) continue
                    val state = snapshot.getBlockState(x, y, z).complete()
                    if (state !is InventoryHolder) continue
                    state.check().forEach { (item, origAmount) ->
                        val amount = AtomicLong(origAmount.toLong())
                        if (predicate(item, amount, state.location)) {
                            val absX = snapshot.x * 16 + x
                            val absZ = snapshot.z * 16 + z
                            val text =
                                TextComponent("${ChatColor.GOLD}[${ChatColor.WHITE}${item.itemMeta?.displayName or item.type.name}${ChatColor.GOLD}]${ChatColor.YELLOW}x${amount} ${ChatColor.GOLD}が以下の座標から見つかりました:")
                            text.hoverEvent = item.toHoverEvent()
                            val posText =
                                TextComponent("  ${ChatColor.GREEN}X: ${ChatColor.GOLD}$absX, ${ChatColor.GREEN}Y: ${ChatColor.GOLD}$y, ${ChatColor.GREEN}Z: ${ChatColor.GOLD}$absZ")
                            posText.hoverEvent =
                                HoverEvent(HoverEvent.Action.SHOW_TEXT, TextComponent.fromLegacyText("クリックでテレポート"))
                            posText.clickEvent = ClickEvent(
                                ClickEvent.Action.RUN_COMMAND,
                                "/tppos $absX $y $absZ 0 0 ${snapshot.worldName}"
                            )
                            text.addExtra(posText)
                            notify(sender, text)
                        }
                    }
                }
            }
        }
    }

    fun checkChunk(worldName: String, chunkData: NBTTagCompound, sender: CommandSender? = null, predicate: (item: ItemStack, amount: AtomicLong, location: Location) -> Boolean) {
        val root = chunkData.getCompound("Level")
        root.getList("TileEntities", 10)
            .filterIsInstance<NBTTagCompound>()
            .forEach { tileEntity ->
                val itemsListTag = tileEntity.getList("Items", 10)
                if (itemsListTag.isEmpty()) return@forEach
                val nmsItems = NonNullList.a(54, net.minecraft.server.v1_15_R1.ItemStack.a)
                ContainerUtil.b(tileEntity, nmsItems)
                val x = tileEntity.getInt("x")
                val y = tileEntity.getInt("y")
                val z = tileEntity.getInt("z")
                val bukkitItems = nmsItems.map { CraftItemStack.asBukkitCopy(it) }
                bukkitItems.check().forEach { (item, origAmount) ->
                    val amount = AtomicLong(origAmount.toLong())
                    if (predicate(item, amount, Location(Bukkit.getWorld(worldName), x.toDouble(), y.toDouble(), z.toDouble()))) {
                        val text =
                            TextComponent("${ChatColor.GOLD}[${ChatColor.WHITE}${item.itemMeta?.displayName or item.type.name}${ChatColor.GOLD}]${ChatColor.YELLOW}x${amount} ${ChatColor.GOLD}が以下の座標から見つかりました:")
                        text.hoverEvent = item.toHoverEvent()
                        val posText =
                            TextComponent("  ${ChatColor.GREEN}X: ${ChatColor.GOLD}$x, ${ChatColor.GREEN}Y: ${ChatColor.GOLD}$y, ${ChatColor.GREEN}Z: ${ChatColor.GOLD}$z")
                        posText.hoverEvent =
                            HoverEvent(HoverEvent.Action.SHOW_TEXT, TextComponent.fromLegacyText("クリックでテレポート"))
                        posText.clickEvent = ClickEvent(
                            ClickEvent.Action.RUN_COMMAND,
                            "/tppos $x $y $z 0 0 $worldName"
                        )
                        text.addExtra(posText)
                        notify(sender, text)
                    }
                }
            }
    }

    private fun notify(sender: CommandSender? = null, vararg components: TextComponent) {
        if (sender == null) {
            Bukkit.getOnlinePlayers()
                .filter { it.hasPermission("itemfinder.notify") }
                .forEach { p -> components.forEach { p.spigot().sendMessage(it) } }
            components.forEach { Bukkit.getConsoleSender().spigot().sendMessage(it) }
        } else {
            components.forEach {
                sender.spigot().sendMessage(it)
                Bukkit.getConsoleSender().spigot().sendMessage(it)
            }
        }
    }
}
