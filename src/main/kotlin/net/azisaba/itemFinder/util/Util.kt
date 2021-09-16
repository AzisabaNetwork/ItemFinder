package net.azisaba.itemFinder.util

import net.azisaba.itemFinder.ItemFinder
import net.md_5.bungee.api.chat.HoverEvent
import net.md_5.bungee.api.chat.TextComponent
import org.bukkit.Bukkit
import org.bukkit.ChunkSnapshot
import org.bukkit.block.BlockState
import org.bukkit.inventory.ItemStack
import util.promise.rewrite.Promise
import util.reflect.Reflect

object Util {
    private val serverVersion =
        Bukkit.getServer().javaClass.getPackage().name.replace(".", ",").split(",")[3]

    val is1_17 = try {
        Class.forName("net.minecraft.server.$serverVersion.Packet")
        false
    } catch (ex: ClassNotFoundException) {
        true
    }

    private fun ItemStack.toNMS(): Any =
        Class.forName("org.bukkit.craftbukkit.$serverVersion.inventory.CraftItemStack")
            .getMethod("asNMSCopy", ItemStack::class.java)
            .invoke(null, this)

    private fun n(pre_1_17: String, after_1_17: String) = if (is1_17) after_1_17 else pre_1_17

    private fun getNMSClass(clazz: NMSClass): Class<*> = Class.forName(when (clazz) {
        NMSClass.NBTTagCompound -> n("net.minecraft.server.$serverVersion.NBTTagCompound", "net.minecraft.nbt.NBTTagCompound")
    })

    enum class NMSClass {
        NBTTagCompound,
    }

    private fun Any.reflect() = Reflect.on(this)

    fun ItemStack.toHoverEvent() =
        HoverEvent(
            HoverEvent.Action.SHOW_ITEM, arrayOf(
                TextComponent(
                    this.clone()
                        .apply { amount = 1 }
                        .toNMS()
                        .reflect()
                        .call<Any>("save", getNMSClass(NMSClass.NBTTagCompound).newInstance())
                        .get()
                        .toString()
                )
            )
        )

    fun <R> (() -> R).runOnMain(): Promise<R> {
        if (Bukkit.isPrimaryThread()) return Promise.resolve(this())
        return Promise.create { context ->
            Bukkit.getScheduler().runTask(ItemFinder.instance, Runnable {
                context.resolve(this())
            })
        }
    }

    fun ChunkSnapshot.getBlockState(x: Int, y: Int, z: Int): Promise<BlockState?> {
        val world = Bukkit.getWorld(this.worldName) ?: return Promise.resolve(null)
        return { world.getBlockAt(this.x * 16 + x, y, this.z * 16 + z).state }.runOnMain()
    }

    infix fun String?.or(another: String) = if (this.isNullOrBlank()) another else this
}
