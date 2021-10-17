package net.azisaba.itemFinder

import net.azisaba.itemFinder.listener.ScanChunkListener
import net.azisaba.itemFinder.listener.ScanPlayerListener
import net.azisaba.itemFinder.util.Util
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.java.JavaPlugin
import java.io.File

class ItemFinder: JavaPlugin() {
    companion object {
        lateinit var instance: ItemFinder
        val itemsToFind = mutableListOf<ItemStack>()
        val seen = mutableMapOf<String, MutableList<Pair<Int, Int>>>()
    }

    init {
        instance = this
    }

    override fun onEnable() {
        Class.forName("net.azisaba.itemFinder.libs.kotlin.collections.MapsKt")
        Class.forName("net.azisaba.itemFinder.libs.kotlin.collections.ArraysKt")
        logger.info("Is 1.17: ${Util.is1_17}")
        server.getPluginCommand("itemfinder")!!.setExecutor(ItemFinderCommand)
        server.getPluginCommand("itemfinder")!!.tabCompleter = ItemFinderCommand
        server.pluginManager.registerEvents(ScanChunkListener, this)
        server.pluginManager.registerEvents(ScanPlayerListener, this)
        config.getList("itemsToFind")?.forEach { any ->
            when (any) {
                is ItemStack -> itemsToFind.add(any)
                is ConfigurationSection -> itemsToFind.add(ItemStack.deserialize(any.getValues(true)))
                is Map<*, *> -> {
                    @Suppress("UNCHECKED_CAST")
                    itemsToFind.add(ItemStack.deserialize(any as MutableMap<String, Any>))
                }
                else -> {
                    if (any == null) {
                        logger.warning("[Config] Don't know how to deserialize null @ itemsToFind")
                    } else {
                        logger.warning("[Config] Don't know how to deserialize $any (${any::class.java.canonicalName}) @ itemsToFind")
                    }
                }
            }
        }
        config.getList("seen")?.forEach { any ->
            try {
                val addToSeen = { map: Map<*, *> ->
                    map.forEach e@ { (k, v) ->
                        if (v !is List<*>) {
                            logger.warning("[Config] Don't know how to deserialize $v @ seen>addToSeen")
                            return@e
                        }
                        try {
                            seen[k.toString()] = v.map {
                                val sp = v.toString().split(',')
                                Pair(sp[0].toInt(), sp[1].toInt())
                            }.toMutableList()
                        } catch (e: RuntimeException) {
                            logger.warning("[Config] Don't know how to deserialize $v @ seen>addToSeen>map")
                        }
                    }
                }
                when (any) {
                    is ConfigurationSection -> addToSeen(any.getValues(true))
                    is Map<*, *> -> addToSeen(any)
                    else -> error(":(")
                }
            } catch (e: RuntimeException) {
                logger.warning("[Config] Don't know how to deserialize $any @ seen")
            }
        }
    }

    override fun onDisable() {
        logger.info("Shutting down chunk scanner executor")
        ScanChunkListener.chunkScannerExecutor.shutdownNow()
        logger.info("Saving config")
        File("./plugins/ItemFinder").mkdirs()
        config.set("itemsToFind", itemsToFind)
        config.set("seen", seen.mapValues { (_, v) -> v.map { (i, j) -> "${i},${j}" } })
        config.save(File("./plugins/ItemFinder/config.yml"))
    }
}
