package net.azisaba.itemFinder.util

class CsvBuilder(private val header: List<String>) {
    constructor(vararg header: String) : this(header.toList())

    private val body = mutableListOf<String>()

    fun bodySize() = body.size

    fun add(vararg values: String) {
        if (values.size != header.size) {
            error("Incorrect values size: values: ${values.size} != header: ${header.size}")
        }
        body.add(values.joinToString(",") { "\"${it.replace("\"", "\\\"")}\"" })
    }

    fun build(): String {
        val headerPart = header.joinToString(",") { "\"${it.replace("\"", "\\\"")}\"" }
        val bodyPart = body.joinToString("\n")
        return "$headerPart\n$bodyPart\n"
    }
}
