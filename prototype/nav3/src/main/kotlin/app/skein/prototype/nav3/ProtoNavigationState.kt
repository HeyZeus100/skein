// skein-xtov.24.4 (AL-05, throwaway): the §8.1 shape — a top-level
// destination plus one Skein-owned stack per destination — with just enough
// of the §8.3 rules for the gate tests. AL-06 owns the real navigator.
package app.skein.prototype.nav3

import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList

@Stable
class ProtoNavigationState(
    top: Destination = Destination.CHAT,
    initial: Map<Destination, List<ProtoKey>> = emptyMap(),
) {
    var top: Destination by mutableStateOf(top)
        private set

    val stacks: Map<Destination, SnapshotStateList<ProtoKey>> =
        Destination.entries.associateWith { d ->
            val keys = initial[d].orEmpty().filter { it.destination == d }
            (if (keys.firstOrNull()?.isRoot == true) keys else listOf(rootOf(d)) + keys).toMutableStateList()
        }

    val current: SnapshotStateList<ProtoKey> get() = stacks.getValue(top)

    fun switchTo(destination: Destination) {
        top = destination
    }

    /** Go to / follow (§8.3 rules 1, 2) with de-duplication (rule 4): an existing key is popped back to. */
    fun push(key: ProtoKey) {
        top = key.destination
        val stack = stacks.getValue(key.destination)
        val at = stack.indexOf(key)
        if (at >= 0) stack.removeRange(at + 1, stack.size) else stack.add(key)
    }

    /** Selecting from a visible list replaces the detail (rule 3). */
    fun select(detail: ProtoKey) {
        top = detail.destination
        val stack = stacks.getValue(detail.destination)
        stack.removeRange(1, stack.size)
        stack.add(detail)
    }

    /** §3.6 steps 3–5. False means "not consumed" (step 6: the Chat root). */
    fun back(): Boolean =
        when {
            current.size > 1 -> {
                current.removeAt(current.lastIndex)
                true
            }
            top != Destination.CHAT -> {
                top = Destination.CHAT
                true
            }
            else -> false
        }

    /** Esc follows §3.6 steps 1–4 only; it never switches destination or leaves. */
    fun escape(): Boolean = current.size > 1 && back()

    /**
     * §8.3 rule 7 / M4d: drop, silently, every key whose ids no longer resolve
     * (B8 would batch this through `kindsOf`); a root that does not resolve
     * falls back to the destination's plain root.
     */
    suspend fun sanitise(exists: suspend (SkeinId) -> Boolean) {
        for ((d, stack) in stacks) {
            val kept = ArrayList<ProtoKey>(stack.size)
            for ((i, k) in stack.withIndex()) {
                val resolves = k.ids().all { exists(it) }
                if (resolves) {
                    kept += k
                } else if (i == 0) {
                    kept += rootOf(d)
                }
            }
            if (kept != stack.toList()) {
                stack.clear()
                stack.addAll(kept)
            }
        }
    }

    companion object {
        fun rootOf(destination: Destination): ProtoKey =
            when (destination) {
                Destination.CHAT -> ChatHomeKey
                Destination.KNOWLEDGE -> KnowledgeHomeKey()
                Destination.GRAPH -> GraphKey(focusDocId = null)
            }

        private const val TOP = "top"

        /** T1 into the Bundle: enum names and [KeyCodec] entries only; restore is total (M4c). */
        val Saver: Saver<ProtoNavigationState, Bundle> =
            Saver(
                save = { nav ->
                    Bundle().apply {
                        putString(TOP, nav.top.name)
                        for ((d, stack) in nav.stacks) putParcelableArrayList(d.name, KeyCodec.encode(stack))
                    }
                },
                restore = { saved ->
                    val top = Destination.entries.firstOrNull { it.name == saved.getString(TOP) } ?: Destination.CHAT
                    ProtoNavigationState(
                        top,
                        Destination.entries.associateWith { d ->
                            @Suppress("DEPRECATION")
                            KeyCodec.decode(runCatching { saved.getParcelableArrayList<Bundle>(d.name) }.getOrNull())
                        },
                    )
                },
            )
    }
}

/** A destination root: the Graph root carries its focus. */
internal val ProtoKey.isRoot: Boolean get() = this == ChatHomeKey || this is KnowledgeHomeKey || this is GraphKey

internal fun ProtoKey.ids(): List<SkeinId> =
    when (this) {
        ChatHomeKey, is KnowledgeHomeKey -> emptyList()
        is ChatKey -> listOf(chatId)
        is ChatContextKey -> listOf(chatId)
        is ChatSourceKey -> listOf(chatId, docId)
        is NoteKey -> listOf(docId)
        is ConnectionsKey -> listOf(docId)
        is GraphKey -> listOfNotNull(focusDocId)
        is GraphNodeKey -> listOfNotNull(focusDocId, nodeId)
    }

@Composable
fun rememberProtoNavigationState(init: () -> ProtoNavigationState = { ProtoNavigationState() }): ProtoNavigationState =
    rememberSaveable(saver = ProtoNavigationState.Saver, init = init)
