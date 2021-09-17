package net.azisaba.itemFinder.util

import net.azisaba.itemFinder.ItemFinder
import net.azisaba.itemFinder.util.Util.getMinecraftId
import net.azisaba.itemFinder.util.Util.getTagAsString
import net.md_5.bungee.api.chat.ClickEvent
import net.md_5.bungee.api.chat.HoverEvent
import net.md_5.bungee.api.chat.TextComponent
import org.bukkit.Bukkit
import org.bukkit.ChunkSnapshot
import org.bukkit.block.BlockState
import org.bukkit.inventory.ItemStack
import util.ReflectionHelper
import util.promise.rewrite.Promise
import util.reflect.Reflect
import java.util.Base64
import kotlin.math.roundToInt

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
        NMSClass.IRegistry -> n("net.minecraft.server.$serverVersion.IRegistry", "net.minecraft.core.IRegistry")
        NMSClass.RegistryBlocks -> n("net.minecraft.server.$serverVersion.RegistryBlocks", "net.minecraft.core.RegistryBlocks")
    })

    enum class NMSClass {
        NBTTagCompound,
        IRegistry,
        RegistryBlocks,
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

    fun ItemStack.toClickEvent(name: String = "@s") =
        ClickEvent(
            ClickEvent.Action.RUN_COMMAND,
            "/itemfinder give ${"$name ${getMinecraftId()}${getTagAsString()} 1".encodeBase64()}",
        )

    fun ItemStack.toGiveCommand(name: String = "@s") = "/minecraft:give $name ${getMinecraftId()}${getTagAsString()} 1"

    fun ItemStack.getMinecraftId(): String {
        val itemField = getNMSClass(NMSClass.IRegistry).getField("ITEM").get(null)
        return getNMSClass(NMSClass.RegistryBlocks)
            .getMethod("getKey", Object::class.java)
            .invoke(itemField, this.toNMS().reflect().call<Any>("getItem").get())
            .toString()
    }

    fun ItemStack.getTagAsString(): String =
        this.toNMS().reflect().call<Any>("getTag").get().let { it?.toString() ?: "" }

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

    fun Double.wellRound() = (this * 100.0).roundToInt() / 100.0

    fun String.encodeBase64() = Base64.getEncoder().encodeToString(this.toByteArray())
    fun String.decodeBase64() = String(Base64.getDecoder().decode(this))
}
