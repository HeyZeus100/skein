// skein-st1r (POST_REVIEW_RESOLUTIONS §2.3) + skein-3v9 (E0.I15, plan §4.8):
// `ModelManifest` — the manifest shape that makes "every file the loader opens
// is hash-covered" a schema property rather than a convention, and that also
// carries the catalog fields the `models` table needs.
//
// ONE SHAPE, VERSION 2. Coordinator decision `skein-cqiu` (2026-09-21) settled
// a contradiction between plan §4.8 (which described a v1 document) and this
// file as skein-st1r first landed it (which described a v2 document): the two
// were not a superset relation — they disagreed about `license` (object vs
// bare string), about whether a root `file` key existed, and about the version
// constant itself. There is now exactly one shape, it is version 2, and no v1
// document has ever existed in this repository, so nothing accepts v1. The
// normative description lives at
// `core/model/src/main/resources/schema/model-manifest.schema.json`; this file
// is the enforcement point, and `tools/ci/validate-manifests.py` checks that
// the two agree against the fixtures in `src/test/resources/manifests/`.
//
// Why a hand-written parser and not `@Serializable`. The schema's constraints
// — `additionalProperties: false`, hex digest patterns, the `uniqueItems`
// capability set, "no `main` inside `companions`", plain-file-name rules — are
// most of the value here, and a generated decoder checks almost none of them.
// `core/model`'s `CitationRecordJson` reached the same conclusion for the same
// reason, and this module deliberately avoids adding the serialization
// compiler plugin. kotlinx's tree API gives strict JSON parsing; every rule
// above it is explicit below.
//
// `blake3` — recorded, and why. §2.2 requires the post-mmap pass to use a
// distinct algorithm, which means the expected BLAKE3-256 of the main file has
// to exist somewhere before the mapping is made. §2.3 resolves this two ways
// and this parser supports both:
//   * a shipped default-model manifest declares `blake3` alongside `sha256`,
//     so the post-mmap expectation is known without ever loading the file
//     (`tools/ci/validate-manifests.py` REQUIRES it for anything under
//     `app/src/main/assets/models/`);
//   * a user-imported model has no such declaration, so `ImmutableModelStore`
//     computes BLAKE3 during the same streaming pass that checks SHA-256 and
//     records it on the resulting `StoredFile`.
// When a manifest does declare `blake3`, import verifies it too, so a manifest
// whose two digests disagree about the same bytes is refused rather than
// silently preferring one.
//
// The parser does not read, fetch or trust an attestation. Origin trust is a
// separate gate — see `ManifestAttestation` and MODEL_STORE.md §4. `E3.I6`
// (skein-zo8) implements the verification; until then the object is carried
// and nothing acts on it.

package app.skein.core.inference.models

import app.skein.core.model.Capability
import app.skein.core.model.CompanionFile
import app.skein.core.model.CompanionRole
import app.skein.core.model.Model
import app.skein.core.model.ModelFormat
import app.skein.core.verify.ModelFileRole
import app.skein.core.verify.ModelVerification
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * One manifest-covered file.
 *
 * [sha256] is mandatory for every entry — that is the §2 rule. [blake3] is the
 * post-mmap expectation and is optional in the document; see this file's
 * header for when it is present and what happens when it is not.
 */
data class ManifestFile(
    val role: ModelFileRole,
    val file: String,
    val sha256: String,
    val sizeBytes: Long,
    val required: Boolean = true,
    val blake3: String? = null,
)

/**
 * The license an artifact ships under.
 *
 * An object rather than a bare SPDX string (skein-cqiu point 3): [spdx] is
 * required so the license audit always has a machine-readable identifier,
 * while [url] and [notes] carry the provenance a human reviewer needs. Several
 * model licenses — the Qwen Research License among the v1 defaults — are
 * bespoke and not fully captured by an SPDX id alone.
 */
data class ManifestLicense(
    val spdx: String,
    val url: String? = null,
    val notes: String? = null,
)

/** Where the artifact came from. Provenance only — nothing in the app fetches it (no INTERNET permission). */
data class ManifestSource(
    val url: String? = null,
    val revision: String? = null,
)

/**
 * The sigstore attestation a manifest points at.
 *
 * Carried, never acted on: MODEL_STORE.md §4 keeps origin trust structurally
 * separate from the digest gate, so nothing here can weaken or excuse a
 * digest check. `E3.I6` (skein-zo8) implements offline verification against a
 * pinned trust root.
 */
data class ManifestAttestationRef(
    val bundleFile: String,
    val certificateIdentity: String,
    val certificateOidcIssuer: String,
    val url: String? = null,
    val covers: Set<AttestationCoverage> = setOf(AttestationCoverage.MAIN),
)

/**
 * A parsed, schema-valid model manifest.
 *
 * Schema validity here means exactly the rule set in
 * `model-manifest.schema.json`: manifest_version 2, one `main` entry, every
 * companion carrying a known role and a well-formed sha256, no duplicate roles
 * or file names, no unknown properties anywhere. It says nothing about whether
 * the bytes on disk match, and nothing about whether the manifest came from
 * anyone trustworthy.
 */
data class ModelManifest(
    val id: String,
    val manifestVersion: Int,
    val name: String,
    val format: ModelFormat,
    val capabilities: Set<Capability>,
    val license: ManifestLicense,
    val main: ManifestFile,
    val companions: List<ManifestFile>,
    val contextLength: Int = DEFAULT_CONTEXT_LENGTH,
    val source: ManifestSource? = null,
    val attestation: ManifestAttestationRef? = null,
) {
    /** Main file first, then companions in declaration order. */
    val files: List<ManifestFile> get() =
        buildList(companions.size + 1) {
            add(main)
            addAll(companions)
        }

    /** Convenience read-through for callers that want only the attestation URL. */
    val attestationUrl: String? get() = attestation?.url

    fun byRole(role: ModelFileRole): ManifestFile? = files.firstOrNull { it.role == role }

    fun byFileName(name: String): ManifestFile? = files.firstOrNull { it.file == name }

    /**
     * Projects this manifest onto the locked `:core:model` [Model] contract —
     * plan §4.8's "mapping to the `models` table".
     *
     * [path] is the absolute, app-private path of the main file after import.
     * [companionPaths] supplies the same for each companion the caller resolved
     * in the immutable store.
     *
     * A companion path for a role this manifest does not cover is rejected
     * rather than ignored: POST_REVIEW §2.2 rule 3 says every file the loader
     * opens must be manifest-covered, and silently dropping the argument would
     * let a caller believe it had bound a file that nothing will ever verify.
     * A covered companion with no supplied path is simply absent from the
     * result — that is the `required: false` case, and [ManifestBinding] is
     * where required-ness is enforced against what the store actually holds.
     */
    fun toModel(
        path: String,
        companionPaths: Map<CompanionRole, String>,
    ): Model {
        val covered = companions.mapNotNull { it.role.companionRole }.toSet()
        val uncovered = companionPaths.keys - covered
        require(uncovered.isEmpty()) {
            "companion path supplied for ${uncovered.map { it.db }} which this manifest does not cover"
        }
        val resolved =
            companions.mapNotNull { entry ->
                val role = entry.role.companionRole ?: return@mapNotNull null
                val companionPath = companionPaths[role] ?: return@mapNotNull null
                role to CompanionFile(path = companionPath, sha256 = entry.sha256)
            }
        return Model(
            id = id,
            name = name,
            path = path,
            sha256 = main.sha256,
            format = format,
            capabilities = capabilities,
            sizeBytes = main.sizeBytes,
            contextLength = contextLength,
            attestationUrl = attestationUrl,
            companions = resolved.toMap(),
        )
    }

    companion object {
        /** The only manifest version that has ever existed. skein-cqiu point 1: there is no v1 to accept. */
        const val SUPPORTED_VERSION: Int = 2

        /** `Model.contextLength`'s default, used when the document omits `context_length`. */
        const val DEFAULT_CONTEXT_LENGTH: Int = 16_384

        /** Schema floor for `context_length`. */
        const val MIN_CONTEXT_LENGTH: Int = 512

        private const val HEX_DIGEST_LENGTH = 64
        private const val MAX_NAME_LENGTH = 120

        private val ID_REGEX = Regex("^[a-z0-9][a-z0-9.-]{2,63}$")

        private val ROOT_KEYS =
            setOf(
                "manifest_version",
                "id",
                "name",
                "format",
                "file",
                "sha256",
                "blake3",
                "size_bytes",
                "capabilities",
                "context_length",
                "license",
                "source",
                "attestation",
                "companions",
            )
        private val COMPANION_KEYS = setOf("role", "file", "sha256", "blake3", "size_bytes", "required")
        private val LICENSE_KEYS = setOf("spdx", "url", "notes")
        private val SOURCE_KEYS = setOf("url", "revision")
        private val ATTESTATION_KEYS =
            setOf("bundle_file", "url", "certificate_identity", "certificate_oidc_issuer", "covers")

        /**
         * Parses a `*.skein.json` manifest document.
         *
         * Returns [ManifestParse.Parsed] or a typed [ModelVerification.Refusal]
         * — no exception escapes, because a malformed manifest is a refusal
         * like any other, not a crash.
         */
        fun parse(document: String): ManifestParse =
            try {
                parseOrThrow(document)
            } catch (e: IllegalArgumentException) {
                // kotlinx.serialization signals every malformed-JSON and
                // wrong-JsonElement-type condition as IllegalArgumentException
                // (SerializationException extends it), as do the `require`
                // calls below.
                ManifestParse.Refused(ModelVerification.MalformedManifest(e.message ?: "unparseable"))
            }

        @Suppress("ReturnCount", "LongMethod")
        private fun parseOrThrow(document: String): ManifestParse {
            val root = SkeinJson.decode(document).asObject("manifest")
            root.rejectUnknownKeys(ROOT_KEYS, "manifest")?.let { return ManifestParse.Refused(it) }

            val version = root.requireInt("manifest_version")
            if (version != SUPPORTED_VERSION) {
                return ManifestParse.Refused(
                    ModelVerification.MalformedManifest("manifest_version $version is not supported"),
                )
            }

            val id = root.requireString("id")
            if (!ID_REGEX.matches(id)) {
                return ManifestParse.Refused(ModelVerification.MalformedManifest("id is not a well-formed model id"))
            }

            val name = root.requireString("name")
            if (name.isEmpty() || name.length > MAX_NAME_LENGTH) {
                return ManifestParse.Refused(ModelVerification.MalformedManifest("name is empty or over 120 chars"))
            }

            val formatWire = root.requireString("format")
            val format =
                ModelFormat.entries.firstOrNull { it.db == formatWire }
                    ?: return ManifestParse.Refused(
                        ModelVerification.MalformedManifest("unknown format '$formatWire'"),
                    )

            val capabilities = capabilities(root) { return ManifestParse.Refused(it) }

            val contextLength = root.optionalInt("context_length") ?: DEFAULT_CONTEXT_LENGTH
            if (contextLength < MIN_CONTEXT_LENGTH) {
                return ManifestParse.Refused(
                    ModelVerification.MalformedManifest("context_length is below the $MIN_CONTEXT_LENGTH floor"),
                )
            }

            val license = license(root) { return ManifestParse.Refused(it) }
            val source = source(root) { return ManifestParse.Refused(it) }
            val attestation = attestation(root) { return ManifestParse.Refused(it) }

            val main =
                fileEntry(root, ModelFileRole.MAIN)
                    ?: return ManifestParse.Refused(
                        ModelVerification.CompanionMissing(ModelFileRole.MAIN, "main"),
                    )

            val companions = companions(root) { return ManifestParse.Refused(it) }

            duplicateComplaint(main, companions)?.let { return ManifestParse.Refused(it) }

            return ManifestParse.Parsed(
                ModelManifest(
                    id = id,
                    manifestVersion = version,
                    name = name,
                    format = format,
                    capabilities = capabilities,
                    license = license,
                    main = main,
                    companions = companions,
                    contextLength = contextLength,
                    source = source,
                    attestation = attestation,
                ),
            )
        }

        // ------------------------------------------------------------------
        // Field groups. Each takes a `refuse` continuation so a refusal
        // returns straight out of `parseOrThrow` rather than being wrapped in
        // yet another result type at every level.
        // ------------------------------------------------------------------

        private inline fun capabilities(
            root: JsonObject,
            refuse: (ModelVerification.Refusal) -> Nothing,
        ): Set<Capability> {
            val declared = root["capabilities"]?.jsonArray ?: refuse(missing("capabilities"))
            if (declared.isEmpty()) {
                refuse(ModelVerification.MalformedManifest("capabilities must list at least one entry"))
            }
            val result = linkedSetOf<Capability>()
            for (element in declared) {
                val wire =
                    (element as? JsonPrimitive)?.takeIf { it.isString }?.content
                        ?: refuse(ModelVerification.MalformedManifest("capabilities entries must be strings"))
                val capability =
                    Capability.entries.firstOrNull { it.db == wire }
                        ?: refuse(ModelVerification.MalformedManifest("unknown capability '$wire'"))
                if (!result.add(capability)) {
                    refuse(ModelVerification.MalformedManifest("duplicate capability '$wire'"))
                }
            }
            return result
        }

        private inline fun license(
            root: JsonObject,
            refuse: (ModelVerification.Refusal) -> Nothing,
        ): ManifestLicense {
            val obj = (root["license"] as? JsonObject) ?: refuse(missing("license"))
            obj.rejectUnknownKeys(LICENSE_KEYS, "license")?.let { refuse(it) }
            val spdx = obj.optionalString("spdx") ?: refuse(missing("spdx"))
            if (spdx.isEmpty()) refuse(ModelVerification.MalformedManifest("license spdx is empty"))
            return ManifestLicense(spdx = spdx, url = obj.optionalString("url"), notes = obj.optionalString("notes"))
        }

        private inline fun source(
            root: JsonObject,
            refuse: (ModelVerification.Refusal) -> Nothing,
        ): ManifestSource? {
            val obj =
                (root["source"] ?: return null) as? JsonObject
                    ?: refuse(ModelVerification.MalformedManifest("source is not a JSON object"))
            obj.rejectUnknownKeys(SOURCE_KEYS, "source")?.let { refuse(it) }
            return ManifestSource(url = obj.optionalString("url"), revision = obj.optionalString("revision"))
        }

        private inline fun attestation(
            root: JsonObject,
            refuse: (ModelVerification.Refusal) -> Nothing,
        ): ManifestAttestationRef? {
            val obj =
                (root["attestation"] ?: return null) as? JsonObject
                    ?: refuse(ModelVerification.MalformedManifest("attestation is not a JSON object"))
            obj.rejectUnknownKeys(ATTESTATION_KEYS, "attestation")?.let { refuse(it) }
            val bundleFile = obj.optionalString("bundle_file") ?: refuse(missing("bundle_file"))
            if (!isSafeFileName(bundleFile)) refuse(unsafeName())
            val identity = obj.optionalString("certificate_identity") ?: refuse(missing("certificate_identity"))
            val issuer = obj.optionalString("certificate_oidc_issuer") ?: refuse(missing("certificate_oidc_issuer"))
            val covers = linkedSetOf<AttestationCoverage>()
            for (element in obj["covers"]?.jsonArray.orEmpty()) {
                val wire =
                    (element as? JsonPrimitive)?.takeIf { it.isString }?.content
                        ?: refuse(ModelVerification.MalformedManifest("attestation covers entries must be strings"))
                val coverage =
                    AttestationCoverage.fromWire(wire)
                        ?: refuse(ModelVerification.MalformedManifest("unknown attestation coverage '$wire'"))
                if (!covers.add(coverage)) {
                    refuse(ModelVerification.MalformedManifest("duplicate attestation coverage '$wire'"))
                }
            }
            return ManifestAttestationRef(
                bundleFile = bundleFile,
                certificateIdentity = identity,
                certificateOidcIssuer = issuer,
                url = obj.optionalString("url"),
                covers = covers.ifEmpty { setOf(AttestationCoverage.MAIN) },
            )
        }

        private inline fun companions(
            root: JsonObject,
            refuse: (ModelVerification.Refusal) -> Nothing,
        ): List<ManifestFile> {
            val result = mutableListOf<ManifestFile>()
            for (element in root["companions"]?.jsonArray.orEmpty()) {
                val entry = element.asObject("companion")
                entry.rejectUnknownKeys(COMPANION_KEYS, "companion")?.let { refuse(it) }

                val roleWire = entry.requireString("role")
                val role =
                    ModelFileRole.fromWire(roleWire)
                        ?: refuse(ModelVerification.MalformedManifest("unknown companion role '$roleWire'"))
                if (role == ModelFileRole.MAIN) {
                    refuse(ModelVerification.MalformedManifest("'main' may not appear in companions"))
                }

                val file = entry.requireString("file")
                val sha256 = entry.optionalString("sha256")
                if (sha256 == null || !isHexDigest(sha256)) {
                    // §2: a companion without a usable sha256 is the exact
                    // "trust the adjacent file" hole. Refuse at parse time.
                    refuse(ModelVerification.CompanionMissing(role, file))
                }
                val blake3 = entry.optionalString("blake3")
                if (blake3 != null && !isHexDigest(blake3)) {
                    refuse(ModelVerification.MalformedManifest("companion blake3 is not 64 hex characters"))
                }
                val sizeBytes = entry.requireLong("size_bytes")
                if (sizeBytes < 1) refuse(ModelVerification.MalformedManifest("size_bytes must be at least 1"))

                result +=
                    ManifestFile(
                        role = role,
                        file = file,
                        sha256 = sha256.lowercase(),
                        sizeBytes = sizeBytes,
                        required = entry["required"]?.jsonPrimitive?.booleanOrNull ?: true,
                        blake3 = blake3?.lowercase(),
                    )
            }
            return result
        }

        private fun fileEntry(
            root: JsonObject,
            role: ModelFileRole,
        ): ManifestFile? {
            val file = root.optionalString("file") ?: return null
            val sha256 = root.optionalString("sha256")?.lowercase() ?: return null
            require(isHexDigest(sha256)) { "sha256 is not 64 hex characters" }
            val blake3 = root.optionalString("blake3")?.lowercase()
            require(blake3 == null || isHexDigest(blake3)) { "blake3 is not 64 hex characters" }
            val sizeBytes = root.requireLong("size_bytes")
            require(sizeBytes >= 1) { "size_bytes must be at least 1" }
            return ManifestFile(
                role = role,
                file = file,
                sha256 = sha256,
                sizeBytes = sizeBytes,
                required = true,
                blake3 = blake3,
            )
        }

        private fun duplicateComplaint(
            main: ManifestFile,
            companions: List<ManifestFile>,
        ): ModelVerification.Refusal? {
            val all =
                buildList {
                    add(main)
                    addAll(companions)
                }
            val seenRoles = mutableSetOf<ModelFileRole>()
            val seenFiles = mutableSetOf<String>()
            for (entry in all) {
                if (!seenRoles.add(entry.role)) {
                    return ModelVerification.MalformedManifest("duplicate role '${entry.role.wire}'")
                }
                if (!seenFiles.add(entry.file)) {
                    return ModelVerification.MalformedManifest("duplicate file name")
                }
                if (!isSafeFileName(entry.file)) return unsafeName()
            }
            return null
        }

        private fun missing(key: String): ModelVerification.Refusal =
            ModelVerification.MalformedManifest("missing or non-string '$key'")

        private fun unsafeName(): ModelVerification.Refusal =
            ModelVerification.MalformedManifest("file name must be a plain name, not a path")

        private fun isHexDigest(value: String): Boolean =
            value.length == HEX_DIGEST_LENGTH && value.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

        /**
         * The store writes each manifest entry into `models/<id>/<file>`, so a
         * name carrying a separator or `..` would escape the immutable store.
         */
        private fun isSafeFileName(name: String): Boolean =
            name.isNotEmpty() &&
                name != "." &&
                name != ".." &&
                !name.contains('/') &&
                !name.contains('\\') &&
                !name.contains('\u0000')
    }
}

/** Result of [ModelManifest.parse]. */
sealed interface ManifestParse {
    data class Parsed(
        val manifest: ModelManifest,
    ) : ManifestParse

    data class Refused(
        val refusal: ModelVerification.Refusal,
    ) : ManifestParse
}

// ------------------------------------------------------------------ JSON glue

/**
 * `kotlinx.serialization`'s tree API, used without the compiler plugin: the
 * manifest is small and hand-shaped, and `parseToJsonElement` gives strict
 * JSON parsing without adding `@Serializable` codegen to this module.
 */
private object SkeinJson {
    private val json = kotlinx.serialization.json.Json

    fun decode(document: String): JsonElement = json.parseToJsonElement(document)
}

private fun JsonElement.asObject(what: String): JsonObject =
    this as? JsonObject ?: throw IllegalArgumentException("$what is not a JSON object")

/**
 * The schema's `additionalProperties: false`, enforced in code.
 *
 * Not pedantry: an unknown key is either a typo in a field that therefore
 * silently took its default, or a field from a newer manifest shape this build
 * does not understand. Both are cases where continuing would mean acting on a
 * document we have only partly read.
 */
private fun JsonObject.rejectUnknownKeys(
    known: Set<String>,
    what: String,
): ModelVerification.Refusal? =
    keys.firstOrNull { it !in known }?.let {
        ModelVerification.MalformedManifest("unknown property '$it' in $what")
    }

private fun JsonObject.optionalString(key: String): String? =
    (this[key] as? JsonPrimitive)
        ?.takeIf {
            it.isString
        }?.content

private fun JsonObject.requireString(key: String): String =
    optionalString(key) ?: throw IllegalArgumentException("missing or non-string '$key'")

private fun JsonObject.requireLong(key: String): Long =
    (this[key] as? JsonPrimitive)?.longOrNull ?: throw IllegalArgumentException("missing or non-integer '$key'")

private fun JsonObject.optionalInt(key: String): Int? = (this[key] as? JsonPrimitive)?.longOrNull?.toInt()

private fun JsonObject.requireInt(key: String): Int = requireLong(key).toInt()

private fun kotlinx.serialization.json.JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
