// skein-xtov.24.4 (AL-05, throwaway): the §8.2 keys the spike needs, typed
// per SECURITY_REVIEW_D7.md M1 — every field is a validated SkeinId or an enum.
package app.skein.prototype.nav3

import android.os.Bundle
import androidx.navigation3.runtime.NavKey
import androidx.savedstate.serialization.decodeFromSavedState
import androidx.savedstate.serialization.encodeToSavedState
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A lowercase canonical RFC 9562 UUID, validated at construction and on decode (M1, M2). */
@Serializable
@JvmInline
value class SkeinId(
    val value: String,
) {
    init {
        // M13: the message never carries the rejected value.
        require(CANONICAL.matches(value)) { "not a canonical id" }
    }

    companion object {
        val CANONICAL = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")

        /** M2(a): a foreign id yields null, so no saveable key can be built from it. */
        fun parse(raw: String): SkeinId? = if (CANONICAL.matches(raw)) SkeinId(raw) else null
    }
}

enum class Destination { CHAT, KNOWLEDGE, GRAPH }

enum class KnowledgeFilter { ALL, NOTES, FILES }

/**
 * [contentKey] is what Nav3's decorators key per-entry state by, and it is
 * saved into the Bundle as a map key by the hoisted `SaveableStateHolder`.
 * Nav3's default (`"$key:${key::class}"`, the data class `toString()`) would
 * leak any field a key ever grows, so every key spells its own: a serial tag
 * plus ids.
 */
@Serializable
sealed interface ProtoKey : NavKey {
    val destination: Destination
    val contentKey: String
}

@Serializable
@SerialName("chat.home")
data object ChatHomeKey : ProtoKey {
    override val destination get() = Destination.CHAT
    override val contentKey get() = "chat.home"
}

@Serializable
@SerialName("chat")
data class ChatKey(
    val chatId: SkeinId,
) : ProtoKey {
    override val destination get() = Destination.CHAT
    override val contentKey get() = "chat/${chatId.value}"
}

@Serializable
@SerialName("chat.context")
data class ChatContextKey(
    val chatId: SkeinId,
) : ProtoKey {
    override val destination get() = Destination.CHAT
    override val contentKey get() = "chat.context/${chatId.value}"
}

@Serializable
@SerialName("chat.source")
data class ChatSourceKey(
    val chatId: SkeinId,
    val docId: SkeinId,
) : ProtoKey {
    override val destination get() = Destination.CHAT
    override val contentKey get() = "chat.source/${chatId.value}/${docId.value}"
}

@Serializable
@SerialName("knowledge.home")
data class KnowledgeHomeKey(
    val filter: KnowledgeFilter = KnowledgeFilter.ALL,
) : ProtoKey {
    override val destination get() = Destination.KNOWLEDGE
    override val contentKey get() = "knowledge.home/${filter.name}"
}

@Serializable
@SerialName("note")
data class NoteKey(
    val docId: SkeinId,
) : ProtoKey {
    override val destination get() = Destination.KNOWLEDGE
    override val contentKey get() = "note/${docId.value}"
}

@Serializable
@SerialName("connections")
data class ConnectionsKey(
    val docId: SkeinId,
) : ProtoKey {
    override val destination get() = Destination.KNOWLEDGE
    override val contentKey get() = "connections/${docId.value}"
}

@Serializable
@SerialName("graph")
data class GraphKey(
    val focusDocId: SkeinId?,
) : ProtoKey {
    override val destination get() = Destination.GRAPH
    override val contentKey get() = "graph/${focusDocId?.value ?: "-"}"
}

@Serializable
@SerialName("graph.node")
data class GraphNodeKey(
    val focusDocId: SkeinId?,
    val nodeId: SkeinId,
) : ProtoKey {
    override val destination get() = Destination.GRAPH
    override val contentKey get() = "graph.node/${focusDocId?.value ?: "-"}/${nodeId.value}"
}

/**
 * Keys go into the Bundle one small `Bundle` each (serial tag + fields), and
 * decoding is total (M4c): an unknown tag, a malformed payload or an invalid
 * id drops that one entry; nothing throws. Skein owns this codec — it does
 * not use Nav3's `rememberNavBackStack`/`NavKeySerializer`, which stores the
 * Java class name and throws on an unknown one.
 */
object KeyCodec {
    fun encode(stack: List<ProtoKey>): ArrayList<Bundle> =
        stack.mapTo(ArrayList()) { encodeToSavedState(ProtoKey.serializer(), it) }

    fun decode(saved: List<Bundle>?): List<ProtoKey> =
        saved.orEmpty().mapNotNull { runCatching { decodeFromSavedState(ProtoKey.serializer(), it) }.getOrNull() }
}
