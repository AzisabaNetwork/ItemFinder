package net.azisaba.itemFinder

import com.sk89q.worldedit.IncompleteRegionException
import com.sk89q.worldedit.WorldEdit
import com.sk89q.worldedit.bukkit.BukkitAdapter
import net.azisaba.itemFinder.listener.ScanChunkListener
import net.azisaba.itemFinder.listener.ScanPlayerListener
import net.azisaba.itemFinder.util.*
import net.azisaba.itemFinder.util.Util.or
import net.azisaba.itemFinder.util.Util.split
import net.azisaba.itemFinder.util.Util.toHoverEvent
import net.azisaba.itemFinder.util.Util.wellRound
import net.md_5.bungee.api.chat.ClickEvent
import net.md_5.bungee.api.chat.HoverEvent
import net.md_5.bungee.api.chat.TextComponent
import net.minecraft.server.v1_15_R1.ChunkCoordIntPair
import net.minecraft.server.v1_15_R1.ContainerUtil
import net.minecraft.server.v1_15_R1.NBTTagCompound
import net.minecraft.server.v1_15_R1.NonNullList
import net.minecraft.server.v1_15_R1.RegionFileCache
import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.command.TabExecutor
import org.bukkit.craftbukkit.v1_15_R1.inventory.CraftItemStack
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import xyz.acrylicstyle.storageBox.utils.StorageBox
import java.io.File
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.floor
import kotlin.math.max

object ItemFinderCommand: TabExecutor {
    private val commands = listOf(
        "on", "off", "onPlayer", "offPlayer", "add", "remove", "removeall", "clearlogs",
        "scanall", "scanhere", "scannearby", "scanarea", "scan-region", "scan-all-regions",
        "scan-around-players", "scan-player-inventory", "info", "reload", "list",
    )
    private val scanStatus = mutableMapOf<String, Pair<Int, AtomicInteger>>()

    // 1-64, 1C(1728), 1LC(3456), 1C(1728)*1C(27), 1C(1728)*1LC(64)
    private val listOf64 = (1..64)
        .toMutableList()
        .apply { addAll(listOf(1728, 3456, 46656, 93312)) }
        .map { it.toString() }

    override fun onCommand(sender: CommandSender, command: Command, s: String, args: Array<String>): Boolean {
        if (args.isEmpty()) {
            sender.sendMessage("${ChatColor.RED}/itemfinder (${commands.joinToString("|")})")
            return true
        }
        when (args[0].lowercase()) {
            "off" -> {
                ScanChunkListener.enabled = false
                sender.sendMessage("${ChatColor.GREEN}チャンクのスキャンをオフにしました。")
            }
            "on" -> {
                ScanChunkListener.enabled = true
                sender.sendMessage("${ChatColor.GREEN}チャンクのスキャンをオンにしました。")
            }
            "offplayer" -> {
                ScanPlayerListener.enabled = false
                sender.sendMessage("${ChatColor.GREEN}プレイヤーのスキャンをオフにしました。")
            }
            "onplayer" -> {
                ScanPlayerListener.enabled = true
                sender.sendMessage("${ChatColor.GREEN}プレイヤーのスキャンをオンにしました。")
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
                if (scanStatus.containsKey(sender.world.name)) {
                    sender.sendMessage("${ChatColor.GREEN}このワールドはすでにスキャン中です。")
                    return true
                }
                sender.sendMessage("${ChatColor.GREEN}${sender.world.name}ワールド内の読み込まれているすべてのチャンクのデータを取得中です。")
                val snapshots = sender.world.loadedChunks.map { it.chunkSnapshot }
                if (scanStatus.containsKey(sender.world.name)) {
                    sender.sendMessage("${ChatColor.GREEN}このワールドはすでにスキャン中です。")
                    return true
                }
                val time = Util.getCurrentDateTimeAsString()
                val count = AtomicInteger(0)
                val worldName = sender.world.name
                val allItems = mutableListOf<ItemData>()
                val matchedItems = mutableListOf<ItemData>()
                val locations = CsvBuilder("Location", "Amount", "Type", "Item name", "Item name with color")
                scanStatus[worldName] = Pair(snapshots.size, count)
                sender.sendMessage("${ChatColor.GREEN}${worldName}ワールド内の読み込まれているすべてのチャンクのスキャンを開始しました。しばらく時間がかかります。")
                ScanChunkListener.chunkScannerExecutor.submit {
                    val futures = snapshots.map {
                        CompletableFuture.runAsync({
                            try {
                                ScanChunkListener.checkChunk(it, sender) { item, amount, location ->
                                    var itemData = ItemData(item, amount.toLong())
                                    getCompareResult(item, amount) { itemData = it }.also { result ->
                                        if (result) {
                                            synchronized(matchedItems) { matchedItems.merge(itemData) }
                                            locations.add("${location.blockX}, ${location.blockY}, ${location.blockZ}", *itemData.toStringArray())
                                        }
                                        synchronized(allItems) { allItems.merge(itemData) }
                                    }
                                }
                            } catch (e: Exception) {
                                ItemFinder.instance.logger.warning("Failed to check chunk ${it.x to it.z}")
                                e.printStackTrace()
                            } finally {
                                count.incrementAndGet()
                                Thread.sleep(200)
                            }
                        }, ScanChunkListener.chunkScannerExecutor)
                    }
                    CompletableFuture.allOf(*futures.toTypedArray()).get()
                    scanStatus.remove(worldName)
                    sender.sendMessage("${ChatColor.GREEN}${worldName}ワールド内のスキャンが完了しました。")
                    showResults(sender, time, allItems.toCsv(), matchedItems.toCsv(), locations)
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
                ScanChunkListener.checkChunkAsync(c, sender, {
                    sender.sendMessage("${ChatColor.GREEN}チャンクのスキャンが完了しました。")
                }) { item, amount, _ ->
                    if (args.size == 1) {
                        getCompareResult(item, amount) {}
                    } else {
                        val joined = args.drop(1).joinToString(" ")
                        item.type.name == joined || (item.hasItemMeta() && item.itemMeta?.hasDisplayName() == true && ChatColor.stripColor(item.itemMeta?.displayName) == joined)
                    }
                }
            }
            "scannearby" -> {
                if (sender !is Player) {
                    sender.sendMessage("${ChatColor.RED}このコマンドはコンソールからは実行できません。")
                    return true
                }
                if (scanStatus.containsKey(sender.world.name)) {
                    sender.sendMessage("${ChatColor.GREEN}このワールドはすでにスキャン中です。")
                    return true
                }
                if (args.size == 1) {
                    sender.sendMessage("${ChatColor.RED}/itemfinder scannearby <radius>")
                    return true
                }
                val baseX = sender.location.chunk.x
                val baseZ = sender.location.chunk.z
                val chunkLocations = mutableListOf<Pair<Int, Int>>()
                val radius = args[1].toInt()
                for (dx in -radius..radius) {
                    for (dz in -radius..radius) {
                        val x = baseX + dx
                        val z = baseZ + dz
                        chunkLocations.add(x to z)
                        ItemFinder.seen.getOrPut(sender.world.name) { mutableListOf() }.remove(x to z)
                    }
                }
                val count = AtomicInteger(0)
                val worldName = sender.world.name
                scanStatus[worldName] = Pair(chunkLocations.size, count)
                val time = Util.getCurrentDateTimeAsString()
                sender.sendMessage("${ChatColor.GREEN}${chunkLocations.size}個のチャンクをスキャン中です。")
                val allItems = mutableListOf<ItemData>()
                val matchedItems = mutableListOf<ItemData>()
                val locations = CsvBuilder("Location", "Amount", "Type", "Item name", "Item name with color")
                ScanChunkListener.chunkScannerExecutor.submit {
                    try {
                        chunkLocations
                            .map { sender.world.getChunkAt(it.first, it.second) }
                            .map { c ->
                                ScanChunkListener.checkChunkAsync(c, sender) { item, amount, location ->
                                    var itemData = ItemData(item, amount.toLong())
                                    val result = if (args.size == 2) {
                                        getCompareResult(item, amount) { itemData = it }
                                    } else {
                                        val joined = args.drop(2).joinToString(" ")
                                        item.type.name == joined || (item.hasItemMeta() &&
                                                item.itemMeta?.hasDisplayName() == true &&
                                                ChatColor.stripColor(item.itemMeta?.displayName) == joined)
                                    }
                                    synchronized(allItems) { allItems.merge(itemData) }
                                    if (result) {
                                        synchronized(matchedItems) { matchedItems.merge(itemData) }
                                        locations.add("${location.blockX}, ${location.blockY}, ${location.blockZ}", *itemData.toStringArray())
                                    }
                                    return@checkChunkAsync result
                                }
                            }
                            .forEach { it.get() }
                    } finally {
                        scanStatus.remove(worldName)
                        sender.sendMessage("${ChatColor.GREEN}チャンクのスキャンが完了しました。")
                        showResults(sender, time, allItems.toCsv(), matchedItems.toCsv(), locations)
                    }
                }
            }
            "scanarea" -> {
                if (!Bukkit.getPluginManager().isPluginEnabled("WorldEdit")) {
                    sender.sendMessage("${ChatColor.RED}WorldEditが読み込まれていません。")
                    return true
                }
                if (sender !is Player) {
                    sender.sendMessage("${ChatColor.RED}このコマンドはコンソールからは実行できません。")
                    return true
                }
                if (scanStatus.containsKey(sender.world.name)) {
                    sender.sendMessage("${ChatColor.GREEN}このワールドはすでにスキャン中です。")
                    return true
                }
                val actor = BukkitAdapter.adapt(sender)
                val manager = WorldEdit.getInstance().sessionManager
                val localSession = manager.get(actor)
                val region = try {
                    val world = localSession.selectionWorld ?: throw IncompleteRegionException()
                    localSession.getSelection(world)
                } catch (e: IncompleteRegionException) {
                    actor.printError(com.sk89q.worldedit.util.formatting.text.TextComponent.of("Please make a region selection first."))
                    return true
                }
                val count = AtomicInteger(0)
                val worldName = sender.world.name
                scanStatus[worldName] = Pair(region.chunks.size, count)
                val time = Util.getCurrentDateTimeAsString()
                sender.sendMessage("${ChatColor.GREEN}${region.chunks.size}個のチャンクをスキャン中です。")
                val allItems = Collections.synchronizedList(mutableListOf<ItemData>())
                val matchedItems = Collections.synchronizedList(mutableListOf<ItemData>())
                val locations = CsvBuilder("Location", "Amount", "Type", "Item name", "Item name with color")
                ScanChunkListener.chunkScannerExecutor.submit {
                    try {
                        region.chunks
                            .map { sender.world.getChunkAt(it.x, it.z) }
                            .map { c ->
                                ItemFinder.seen.getOrPut(sender.world.name) { mutableListOf() }.remove(c.x to c.z)
                                ScanChunkListener.checkChunkAsync(c, sender) { item, amount, location ->
                                    if (!region.contains(BukkitAdapter.adapt(location).toVector().toBlockPoint())) {
                                        return@checkChunkAsync false
                                    }
                                    var itemData = ItemData(item, amount.toLong())
                                    val result = if (args.size == 1) {
                                        getCompareResult(item, amount) { itemData = it }
                                    } else {
                                        val joined = args.drop(1).joinToString(" ")
                                        item.type.name == joined || (item.hasItemMeta() &&
                                                item.itemMeta?.hasDisplayName() == true &&
                                                ChatColor.stripColor(item.itemMeta?.displayName) == joined)
                                    }
                                    synchronized(allItems) { allItems.merge(itemData) }
                                    if (result) {
                                        synchronized(matchedItems) { matchedItems.merge(itemData) }
                                        locations.add("${location.blockX}, ${location.blockY}, ${location.blockZ}", *itemData.toStringArray())
                                    }
                                    return@checkChunkAsync result
                                }
                            }
                            .forEach { it.get() }
                    } finally {
                        scanStatus.remove(worldName)
                        sender.sendMessage("${ChatColor.GREEN}チャンクのスキャンが完了しました。")
                        showResults(sender, time, allItems.toCsv(), matchedItems.toCsv(), locations)
                    }
                }
            }
            "scan-around-players" -> {
                if (sender !is Player) {
                    sender.sendMessage("${ChatColor.RED}このコマンドはコンソールからは実行できません。")
                    return true
                }
                val players = sender.world.players
                if (players.isEmpty()) {
                    sender.sendMessage("${ChatColor.RED}このワールドにはプレイヤーがいません。")
                    return true
                }
                val count = AtomicInteger(0)
                sender.sendMessage("${ChatColor.GREEN}${players.size}人のプレイヤーのチャンクをスキャン中です。しばらく時間がかかります。")
                players.forEach {
                    ScanChunkListener.checkChunkAsync(it.location.chunk, sender, {
                        if (count.incrementAndGet() == players.size) {
                            sender.sendMessage("${ChatColor.GREEN}プレイヤーのチャンクのスキャンが完了しました。")
                        }
                    }) { item, amount, _ ->
                        getCompareResult(item, amount) {}
                    }
                }
            }
            "scan-player-inventory" -> {
                if (sender !is Player) {
                    sender.sendMessage("${ChatColor.RED}このコマンドはコンソールからは実行できません。")
                    return true
                }
                sender.sendMessage("${ChatColor.GREEN}プレイヤーのインベントリをスキャン中です。")
                val count = AtomicInteger(0)
                Bukkit.getOnlinePlayers().forEach {
                    Bukkit.getScheduler().runTaskLater(ItemFinder.instance, Runnable {
                        ScanPlayerListener.checkPlayer(it)
                        if (count.incrementAndGet() == Bukkit.getOnlinePlayers().size) {
                            sender.sendMessage("${ChatColor.GREEN}プレイヤーのインベントリのスキャンが完了しました。")
                        }
                    }, count.get().toLong() * 4)
                }
            }
            "scan-region" -> {
                val player = sender as Player
                val worldName = player.world.name
                val chunkX = player.location.chunk.x
                val chunkZ = player.location.chunk.z
                Bukkit.getScheduler().runTaskAsynchronously(ItemFinder.instance, Runnable {
                    val regionContainer = File(Bukkit.getWorldContainer(), "${player.world.name}/region")
                    RegionFileCache::class.java.getDeclaredConstructor(File::class.java)
                        .apply { isAccessible = true }
                        .newInstance(regionContainer)
                        .use { cache ->
                            val nbt = cache.read(ChunkCoordIntPair(chunkX, chunkZ))
                                ?: return@Runnable player.sendMessage("${ChatColor.RED}Failed to load chunk")
                            ScanChunkListener.checkChunk(worldName, nbt, sender) { item, amount, _ ->
                                getCompareResult(item, amount) {}
                            }
                        }
                })
            }
            "scan-all-regions" -> {
                val player = sender as Player
                val worldName = player.world.name
                val regionContainer = File(Bukkit.getWorldContainer(), "${player.world.name}/region")
                val chunkLocations = mutableListOf<ChunkCoordIntPair>()
                val regex = Regex("^r.(-?\\d+).(-?\\d+).mca$")
                regionContainer.listFiles()!!.forEach { file ->
                    try {
                        val groups = regex.find(file.name)?.groupValues ?: return@forEach
                        val regionX = groups[1].toInt()
                        val regionZ = groups[2].toInt()
                        for (x in 0..31) {
                            for (z in 0..31) {
                                val chunkX = regionX * 32 + x
                                val chunkZ = regionZ * 32 + z
                                chunkLocations.add(ChunkCoordIntPair(chunkX, chunkZ))
                            }
                        }
                    } catch (_: Exception) {}
                }
                val count = AtomicInteger(0)
                val ignoredChunks = AtomicInteger()
                scanStatus[worldName] = Pair(chunkLocations.size, count)
                val time = Util.getCurrentDateTimeAsString()
                sender.sendMessage("${ChatColor.GREEN}${chunkLocations.size}個のチャンクをスキャン中です。")
                ItemFinder.instance.logger.info("Starting scan of $worldName (${chunkLocations.size} chunks) using up to ${Runtime.getRuntime().availableProcessors()} threads")
                val allItems = Collections.synchronizedList(mutableListOf<ItemData>())
                val matchedItems = Collections.synchronizedList(mutableListOf<ItemData>())
                val locations = CsvBuilder("Location", "Amount", "Type", "Item name", "Item name with color")
                Bukkit.getScheduler().runTaskAsynchronously(ItemFinder.instance, Runnable {
                    try {
                        chunkLocations.split(Runtime.getRuntime().availableProcessors()).map split@ { list ->
                            ScanChunkListener.chunkScannerExecutor.submit {
                                RegionFileCache::class.java.getDeclaredConstructor(File::class.java)
                                    .apply { isAccessible = true }
                                    .newInstance(regionContainer)
                                    .use { cache ->
                                        list.forEach { pair ->
                                            if (Thread.currentThread().isInterrupted) {
                                                return@forEach
                                            }
                                            try {
                                                val nbt = cache.read(ChunkCoordIntPair(pair.x, pair.z))
                                                    ?: return@forEach ignoredChunks.set(ignoredChunks.get() + 1)
                                                val root = nbt.getCompound("Level")
                                                if (root.getList("TileEntities", 10).isEmpty()) {
                                                    ignoredChunks.incrementAndGet()
                                                    return@forEach
                                                }
                                                ScanChunkListener.checkChunk(
                                                    worldName,
                                                    nbt,
                                                    sender
                                                ) { item, amount, location ->
                                                    var itemData = ItemData(item, amount.toLong())
                                                    val result = if (args.size == 1) {
                                                        getCompareResult(item, amount) { itemData = it }
                                                    } else {
                                                        val joined = args.drop(2).joinToString(" ")
                                                        item.type.name == joined || (item.hasItemMeta() &&
                                                                item.itemMeta?.hasDisplayName() == true &&
                                                                ChatColor.stripColor(item.itemMeta?.displayName) == joined)
                                                    }
                                                    synchronized(allItems) { allItems.merge(itemData) }
                                                    if (result) {
                                                        synchronized(matchedItems) { matchedItems.merge(itemData) }
                                                        locations.add(
                                                            "${location.blockX}, ${location.blockY}, ${location.blockZ}",
                                                            *itemData.toStringArray()
                                                        )
                                                    }
                                                    return@checkChunk result
                                                }
                                            } catch (e: Exception) {
                                                ItemFinder.instance.logger.severe("Failed to scan chunk ${pair.x}, ${pair.z}")
                                                e.printStackTrace()
                                            } finally {
                                                count.incrementAndGet()
                                            }
                                        }
                                    }
                            }
                        }.forEach { it.get() }
                    } catch (e: Throwable) {
                        e.printStackTrace()
                    } finally {
                        scanStatus.remove(worldName)
                        sender.sendMessage("${ChatColor.GREEN}チャンクのスキャンが完了しました。${ignoredChunks.get()}/${chunkLocations.size}個のチャンクが無視されました。")
                        showResults(sender, time, allItems.toCsv(), matchedItems.toCsv(), locations)
                        Bukkit.getConsoleSender().sendMessage("${ChatColor.GREEN}チャンクのスキャンが完了しました。${ignoredChunks.get()}/${chunkLocations.size}個のチャンクが無視されました。")
                        showResults(Bukkit.getConsoleSender(), time, allItems.toCsv(), matchedItems.toCsv(), locations)
                    }
                })
            }
            "info" -> {
                scanStatus.forEach { (world, pair) ->
                    val percentage = ((pair.second.get() / pair.first.toDouble()) * 100.0).wellRound()
                    sender.sendMessage("${ChatColor.GREEN}ワールド '${ChatColor.RED}${world}${ChatColor.GREEN}' のスキャン状況: ${ChatColor.RED}${pair.second.get()} ${ChatColor.GOLD}/ ${ChatColor.RED}${pair.first} ${ChatColor.GOLD}(${ChatColor.YELLOW}$percentage%${ChatColor.GOLD})")
                }
                if (sender is Player) {
                    sender.sendMessage("${ChatColor.GREEN}ワールド '${ChatColor.RED}${sender.world.name}${ChatColor.GREEN}' 内の読み込まれているチャンク数: ${ChatColor.RED}${sender.world.loadedChunks.size}")
                }
            }
            "list" -> {
                val page = max(args.getOrNull(1)?.toIntOrNull() ?: 1, 1)
                val minIndex = 15 * (page - 1)
                val maxIndex = 15 * page
                sender.sendMessage("${ChatColor.GOLD}スキャン対象のアイテム ($page):")
                ItemFinder.itemsToFind.forEachIndexed { index, itemStack ->
                    if (index in minIndex until maxIndex) {
                        val text = TextComponent("${ChatColor.GOLD}[${ChatColor.WHITE}${itemStack.itemMeta?.displayName or itemStack.type.name}${ChatColor.GOLD}]${ChatColor.YELLOW}x${itemStack.amount}")
                        text.hoverEvent = itemStack.toHoverEvent()
                        if (sender is Player) text.clickEvent = ClickEvent(ClickEvent.Action.RUN_COMMAND, "/itemfinder give $index")
                        sender.spigot().sendMessage(text)
                    }
                }
            }
            "reload" -> {
                ItemFinder.instance.reloadConfig()
                sender.sendMessage("${ChatColor.GREEN}設定を再読み込みしました。")
            }
            "give" -> {
                if (sender !is Player) return true
                val i = args.getOrNull(1)?.toIntOrNull() ?: return true
                ItemFinder.itemsToFind.getOrNull(i)?.let { sender.inventory.addItem(it.clone().apply { amount = 1 }) }
            }
        }
        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, s: String, args: Array<String>): List<String> {
        if (args.isEmpty()) return emptyList()
        if (args.size == 1) return commands.filter(args[0])
        if (args.size == 2) {
            if (args[0] == "add") return listOf64.filter(args[1])
        }
        return emptyList()
    }

    private fun List<String>.filter(s: String): List<String> = distinct().filter { s1 -> s1.lowercase().startsWith(s.lowercase()) }

    private fun showResults(sender: CommandSender, time: String, allCsv: CsvBuilder, matchedCsv: CsvBuilder, locations: CsvBuilder? = null) {
        val resultsDir = File("plugins/ItemFinder/results")
        resultsDir.mkdirs()
        File(resultsDir, "$time-all.csv").writeText(allCsv.build())
        File(resultsDir, "$time-matched.csv").writeText(matchedCsv.build())
        if (locations != null) File(resultsDir, "$time-locations.csv").writeText(locations.build())
        if (matchedCsv.bodySize() <= 100) {
            val text = TextComponent("CSV形式でコピー")
            text.color = ChatColor.AQUA.asBungee()
            text.isUnderlined = true
            text.clickEvent = ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, (locations ?: matchedCsv).build())
            text.hoverEvent = HoverEvent(
                HoverEvent.Action.SHOW_TEXT,
                TextComponent.fromLegacyText("クリックでコピー")
            )
            sender.spigot().sendMessage(text)
        }
        if (locations == null) {
            sender.sendMessage("${ChatColor.GREEN}$time-all.csv, $time-matched.csvに保存されました。")
        } else {
            sender.sendMessage("${ChatColor.GREEN}$time-all.csv, $time-matched.csv, $time-locations.csvに保存されました。")
        }
    }

    private fun getCompareResult(item: ItemStack, amount: AtomicLong, itemDataSetter: (ItemData) -> Unit) =
        ItemFinder.itemsToFind
            .any { itemToFind ->
                StorageBox.getStorageBox(item)?.let { box ->
                    val component = box.componentItemStack
                    if (component == null) {
                        null
                    } else {
                        if (component.isSimilar(itemToFind) && box.amount >= itemToFind.amount) {
                            itemDataSetter(ItemData(component, box.amount * amount.toLong()))
                            amount.set(box.amount)
                            true
                        } else {
                            null
                        }
                    }
                } ?: item.isSimilar(itemToFind) && amount.get() >= itemToFind.amount
            }
}
