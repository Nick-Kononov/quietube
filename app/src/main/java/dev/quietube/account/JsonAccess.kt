package dev.quietube.account

import kotlinx.serialization.json.*

/** Shared by the web, TV and action responses: their shapes change often, so every read is a tolerant lookup. */
internal val accountJson = Json { ignoreUnknownKeys = true; isLenient = false }
internal fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
internal fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
internal fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
internal fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray

/** Every object in the tree, parents first. Kept iterative: the watch page is large and is searched for several renderers. */
internal fun JsonElement.objects(): Sequence<JsonObject> = sequence {
    val open = ArrayDeque<Iterator<JsonElement>>()
    open.addLast(listOf(this@objects).iterator())
    while (open.isNotEmpty()) {
        val level = open.last()
        if (!level.hasNext()) { open.removeLast(); continue }
        val element = level.next()
        if (element is JsonObject) yield(element)
        val children = when (element) { is JsonObject -> element.values.iterator(); is JsonArray -> element.iterator(); else -> null }
        if (children != null && open.size <= MAX_DEPTH) open.addLast(children)
    }
}
private const val MAX_DEPTH = 80
