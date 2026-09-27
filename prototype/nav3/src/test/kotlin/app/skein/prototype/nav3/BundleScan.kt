// skein-xtov.24.4 (AL-05): the SECURITY_REVIEW_D7.md M3(a) Bundle scan, deny
// by default: every string key and leaf of the saved-state Bundle must be on
// the allowlist; the raw parcel must not contain any sentinel.
package app.skein.prototype.nav3

import android.os.Bundle
import android.os.Parcel
import android.os.Parcelable
import android.util.SparseArray
import androidx.compose.runtime.State
import java.io.Serializable

object BundleScan {
    private val uuid = SkeinId.CANONICAL
    private val skeinEnums = (Destination.entries + KnowledgeFilter.entries).map { it.name }.toSet()

    /** `@SerialName`s of the keys, their field names, and savedstate's polymorphic discriminator. */
    private val keySchema =
        ProtoKey.serializer().descriptor.let { root ->
            val names = mutableSetOf("type", "value")
            for (i in 0 until root.elementsCount) {
                val sub = root.getElementDescriptor(i)
                for (j in 0 until sub.elementsCount) {
                    val cls = sub.getElementDescriptor(j)
                    names += cls.serialName
                    for (k in 0 until cls.elementsCount) names += cls.getElementName(k)
                }
            }
            names
        }

    /** contentKey = serial tag / id-or-dash / … (see Keys.kt). */
    private fun isContentKey(s: String): Boolean {
        val parts = s.split('/')
        return parts.first() in keySchema && parts.drop(1).all { it == "-" || uuid.matches(it) || it in skeinEnums }
    }

    /**
     * Framework keys and the constant strings the framework itself writes. Every entry here was
     * read off a real captured Bundle and justified; nothing is a pattern that text could match.
     */
    private val framework =
        setOf(
            "android:viewHierarchyState",
            "android:views",
            "android:focusedViewId",
            "android:hasCurrentPermissionsRequest",
            "androidx.lifecycle.BundlableSavedStateRegistry.key",
            "androidx.savedstate.Restarter",
            "android:support:activity-result",
            "KEY_COMPONENT_ACTIVITY_REGISTERED_RCS",
            "KEY_COMPONENT_ACTIVITY_REGISTERED_KEYS",
            "KEY_COMPONENT_ACTIVITY_LAUNCHED_KEYS",
            "KEY_COMPONENT_ACTIVITY_PENDING_RESULTS",
            "KEY_COMPONENT_ACTIVITY_PENDING_RESULT",
            "KEY_COMPONENT_ACTIVITY_RANDOM_OBJECT",
            "classes_to_restore",
            "androidx.lifecycle.internal.SavedStateHandlesProvider",
            "values",
            "keys",
            "top",
            "CHAT",
            "KNOWLEDGE",
            "GRAPH",
        )

    /**
     * Compose's SaveableStateRegistry keys are `currentCompositeKeyHash.toString(36)`, and the
     * registry itself is saved under `SaveableStateRegistry:<view id>`; only ever map keys.
     */
    private val composeKey = Regex("^-?[0-9a-z]{1,14}$")
    private val registryKey = Regex("^SaveableStateRegistry:-?[0-9]+$")

    /** Window/fragment records the platform writes for every Activity; no Skein data can reach them. */
    private val frameworkParcelables =
        setOf(
            "android.widget.Toolbar\$SavedState",
            "com.android.internal.policy.PhoneWindow\$PanelFeatureState\$SavedState",
            "android.app.FragmentManagerState",
            "android.view.AbsSavedState\$1", // AbsSavedState.EMPTY_STATE
        )

    private fun allowedLeaf(s: String): Boolean =
        uuid.matches(s) || s in skeinEnums || s in keySchema || isContentKey(s) || s in framework

    private fun allowedKey(s: String): Boolean =
        allowedLeaf(s) ||
            composeKey.matches(s) ||
            registryKey.matches(s) ||
            s.startsWith("android:") ||
            s.startsWith("androidx.")

    data class Finding(
        val path: String,
        val value: String,
    )

    /** Every string that is not on the allowlist, with where it sits. Empty means the Bundle is clean. */
    fun violations(bundle: Bundle): List<Finding> = mutableListOf<Finding>().also { walk(bundle, "$", it) }

    @Suppress("DEPRECATION")
    private fun walk(
        value: Any?,
        path: String,
        out: MutableList<Finding>,
    ) {
        when (value) {
            null, is Number, is Boolean, is Char -> Unit
            is CharSequence -> if (!allowedLeaf(value.toString())) out += Finding(path, value.toString())
            is Bundle ->
                for (k in value.keySet()) {
                    if (!allowedKey(k)) out += Finding("$path{key}", k)
                    walk(value.get(k), "$path.$k", out)
                }
            is Map<*, *> ->
                for ((k, v) in value) {
                    if (k is CharSequence && !allowedKey(k.toString())) {
                        out += Finding("$path{key}", k.toString())
                    } else if (k !is CharSequence) {
                        walk(k, "$path{key}", out)
                    }
                    walk(v, "$path[$k]", out)
                }
            is Iterable<*> -> value.forEachIndexed { i, v -> walk(v, "$path[$i]", out) }
            is Array<*> -> value.forEachIndexed { i, v -> walk(v, "$path[$i]", out) }
            is IntArray, is LongArray, is FloatArray, is DoubleArray -> Unit
            is BooleanArray, is ByteArray, is CharArray, is ShortArray -> Unit
            is SparseArray<*> -> for (i in 0 until value.size()) walk(value.valueAt(i), "$path[${value.keyAt(i)}]", out)
            // `rememberSaveable { mutableStateOf(x) }` saves a ParcelableSnapshotMutable*State: scan x.
            is State<*> -> walk(value.value, "$path.value", out)
            is Parcelable -> walkParcelable(value, path, out)
            is Serializable -> out += Finding(path, "opaque Serializable ${value.javaClass.name}")
            else -> out += Finding(path, "opaque ${value.javaClass.name}")
        }
    }

    /** A Parcelable the scan cannot see into: its parcel bytes must hold no string outside the allowlist. */
    private fun walkParcelable(
        value: Parcelable,
        path: String,
        out: MutableList<Finding>,
    ) {
        if (value.javaClass.name in frameworkParcelables) return
        out += Finding(path, "opaque Parcelable ${value.javaClass.name}")
    }

    /** Round-trips through a Parcel with the app class loader (M3a), as system_server would hand it back. */
    fun roundTrip(bundle: Bundle): Bundle {
        val p = Parcel.obtain()
        try {
            bundle.writeToParcel(p, 0)
            p.setDataPosition(0)
            return p.readBundle(BundleScan::class.java.classLoader)!!.also { it.size() }
        } finally {
            p.recycle()
        }
    }

    /** The raw bytes, for a structure-blind sentinel search (UTF-16 as Parcel writes it, UTF-8 for Serializables). */
    fun bytes(bundle: Bundle): ByteArray {
        val p = Parcel.obtain()
        try {
            bundle.writeToParcel(p, 0)
            return p.marshall()
        } finally {
            p.recycle()
        }
    }

    fun contains(
        bytes: ByteArray,
        text: String,
    ): Boolean =
        listOf(Charsets.UTF_16LE, Charsets.UTF_8).any { cs ->
            val needle = text.toByteArray(cs)
            (0..bytes.size - needle.size).any { i -> needle.indices.all { bytes[i + it] == needle[it] } }
        }
}
