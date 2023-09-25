package net.azisaba.itemFinder.util

import org.bukkit.ChatColor
import org.bukkit.Material
import org.bukkit.inventory.ItemStack

data class ItemData(
    val amount: Long,
    val type: Material,
    val displayName: String?,
) {
    constructor(stack: ItemStack) : this(stack, stack.amount.toLong())

    constructor(stack: ItemStack, amount: Long) : this(
        amount,
        stack.type,
        if (stack.itemMeta?.hasDisplayName() == true) stack.itemMeta?.displayName else null
    )

    val displayNameWithoutColor = displayName?.let { ChatColor.stripColor(it) }

    fun isSimilar(another: ItemData) = type == another.type && another.displayName == displayName

    fun grow(amount: Long) = copy(amount = this.amount + amount)

    /**
     * Converts the ItemData to array of amount, type, name (without color), name (with color)
     */
    fun toStringArray() = arrayOf(amount.toString(), type.name, displayNameWithoutColor.toString(), displayName.toString())
}

fun MutableList<ItemData>.merge(itemData: ItemData) {
    find { it.isSimilar(itemData) }.also {
        if (it != null) {
            remove(it)
            add(it.grow(itemData.amount))
        } else {
            add(itemData)
        }
    }
}

fun List<ItemData>.toCsv(): CsvBuilder {
    val builder = CsvBuilder("Amount", "Type", "Item name", "Item name with color")
    sortedByDescending { it.amount }.forEach { data ->
        builder.add(
            data.amount.toString(),
            data.type.name,
            data.displayNameWithoutColor.toString(),
            data.displayName.toString(),
        )
    }
    return builder
}
