// skein-xtov.23.18 (UT-4): the fixture corpus of docs/ux/UX_TEST_PLAN.md §6.2.
//
// Plain, deterministic domain data (`:core:model` types and strings) that
// previews, screenshot tests, the Mac UX lab and JVM tests share, so "a long
// title" or "a 200-message chat" means the same bytes everywhere. Fixed ids,
// fixed timestamps (offsets from FIXTURE_NOW, UTC), seeded randomness, no
// locale-dependent formatting. UI state built from these lives in each
// feature's `src/debug` (it needs the feature's internal types); this file
// holds only what `:core:model` can express.
//
// Each member's KDoc starts with its `F-*` id from §6.2. F-VAULT-SMALL /
// -MEDIUM / -HUGE are `SyntheticVault.Preset.SMALL` / `MEDIUM` / `LARGE`
// (`app.skein.testing.fixtures`), not repeated here.

package app.skein.testing.corpus

import app.skein.core.model.Capability
import app.skein.core.model.Citation
import app.skein.core.model.CitationRecord
import app.skein.core.model.CitationSourceKind
import app.skein.core.model.DocId
import app.skein.core.model.Document
import app.skein.core.model.DocumentKind
import app.skein.core.model.Edge
import app.skein.core.model.EdgeKind
import app.skein.core.model.FrontmatterKeys
import app.skein.core.model.InferenceException
import app.skein.core.model.Locator
import app.skein.core.model.Message
import app.skein.core.model.Model
import app.skein.core.model.ModelFormat
import app.skein.core.model.Persona
import app.skein.core.model.RecallSource
import app.skein.core.model.Retrieved
import app.skein.core.model.Role
import app.skein.testing.InMemoryVaultRepository
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.math.pow
import kotlin.random.Random

/** The §6.2 fixture corpus. Every value is a pure function of the constants below. */
public object Corpus {
    /** 2026-09-26T10:00:00Z. Every fixture timestamp is an offset back from it. */
    public const val FIXTURE_NOW: Long = 1_790_416_800_000L

    /** The seed every generated fixture uses unless the caller passes another. */
    public const val SEED: Long = 42L

    private const val MINUTE: Long = 60_000L
    private const val HOUR: Long = 60 * MINUTE
    private const val DAY: Long = 24 * HOUR

    // Declared first: object properties initialise in declaration order.
    private val sourceTitles =
        listOf(
            "2026-08 grow journal (final) (copy 3).pdf",
            "Substrate notes from the spring workshop",
            "Oyster mushroom log",
        )

    private val lexicon =
        (
            "oyster straw spawn colonise hardwood flush humidity pinning substrate harvest mycelium shed " +
                "tent block soak drain mist caps fresh air temperature log batch notes grow journal week"
        ).split(" ")

    // ------------------------------------------------------------------
    // Titles
    // ------------------------------------------------------------------

    /**
     * F-TITLE-LONG: a 100-character English title; a 60-character unbreakable
     * word; a German compound; a CJK title; an emoji-leading title; an Arabic
     * (RTL) title.
     */
    public val titlesLong: List<String> =
        listOf(
            "Scaling oyster mushroom production from a spare-room grow tent to a small shed without contamination",
            "PneumonoultramicroscopicsilicovolcanoconiosisMyceliumnetwork",
            "Pilzzuchtsubstratfeuchtigkeitsmessprotokollvorlage",
            "平菇栽培记录：从菌种到第一批采收的完整过程与温湿度管理笔记",
            "🍄 Harvest log: second flush",
            "ملاحظات حول زراعة الفطر المحاري في المنزل",
        )

    /** F-TITLE-DUPES: five chats whose provisional titles collide (the "no anonymous Chat" rule, AC-24). */
    public val titleDupes: List<Document> =
        List(5) { i ->
            chat("f-title-dupe-${i + 1}", "What should I plant in October?", FIXTURE_NOW - (i + 1) * HOUR)
        }

    // ------------------------------------------------------------------
    // Models and personas
    // ------------------------------------------------------------------

    /** F-MODEL-LONG: the owner's model id. */
    public const val OWNER_MODEL_ID: String = "qwen2.5-3b-instruct-abliterated-q3-k-m-2c5f9a121ae6"

    /** F-MODEL-LONG: the owner's model file name. */
    public const val OWNER_MODEL_FILE: String = "qwen2.5-3b-instruct-abliterated-q3_k_m.gguf"

    /**
     * F-MODEL-LONG: the owner's id and file name, a 70B file name, a
     * 40-character display name (AC-03) and a 90-character custom import name.
     */
    public val modelLongNames: List<String> =
        listOf(
            OWNER_MODEL_ID,
            OWNER_MODEL_FILE,
            "Meta-Llama-3.1-70B-Instruct-IQ2_XXS.gguf",
            "Qwen2.5 3B Instruct Abliterated (Q3_K_M)",
            "Everyday writing and research assistant model, imported from the USB stick on 26 September",
        )

    /** F-MODELS: the default model (the owner's). */
    public val modelDefault: Model =
        model(OWNER_MODEL_ID, "Qwen 2.5 3B", OWNER_MODEL_FILE, 1_600_000_000L, 1)

    /** F-MODELS: a second model, the one loaded in the engine. */
    public val modelLoaded: Model =
        model(
            "llama-3.2-1b-instruct-q4-k-m-7d1e0b",
            "Llama 3.2 1B",
            "Llama-3.2-1B-Instruct-Q4_K_M.gguf",
            808_000_000L,
            2,
        )

    /** F-MODELS: a model still importing. */
    public val modelImporting: Model =
        model("gemma-2-2b-it-q4-k-m-4be1a9", "Gemma 2 2B", "gemma-2-2b-it-Q4_K_M.gguf", 1_710_000_000L, 3)

    /** F-MODELS: a model whose import or load failed. */
    public val modelFailed: Model =
        model(
            "meta-llama-3.1-70b-instruct-iq2-xxs-0f3c2d",
            "Meta-Llama-3.1-70B-Instruct-IQ2_XXS",
            "Meta-Llama-3.1-70B-Instruct-IQ2_XXS.gguf",
            19_100_000_000L,
            4,
        )

    /** F-MODELS: default, loaded, importing, failed. The states are UI state; the feature's builder applies them. */
    public val models: List<Model> = listOf(modelDefault, modelLoaded, modelImporting, modelFailed)

    /** F-PERSONAS: Default plus three personas, one with a long name. */
    public val personas: List<Persona> =
        listOf(
            Persona("f-persona-default", "Default", null, null, FIXTURE_NOW - 30 * DAY),
            Persona(
                "f-persona-grower",
                "Grow coach",
                "You help with mushroom cultivation.",
                null,
                FIXTURE_NOW - 20 * DAY,
            ),
            Persona("f-persona-editor", "Editor", "You tighten prose.", OWNER_MODEL_ID, FIXTURE_NOW - 10 * DAY),
            Persona(
                "f-persona-long",
                "Mycology research assistant for the community garden cooperative",
                null,
                null,
                FIXTURE_NOW - 5 * DAY,
            ),
        )

    // ------------------------------------------------------------------
    // Markdown
    // ------------------------------------------------------------------

    /** F-MD-TABLE: an 8-column table with long cells and a numeric column. */
    public val mdTable: String =
        listOf(
            row("Batch", "Species", "Substrate", "Spawn", "Incubation notes", "Room", "First flush (g)", "Outcome"),
            row("---", "---", "---", "---", "---", "---", "---:", "---"),
            row(
                "B-01",
                "Pleurotus ostreatus",
                "Pasteurised straw, chopped to five centimetres and soaked overnight",
                "10 %",
                "22 °C in the dark; fully colonised by day 14 with no visible contamination",
                "Shed, north wall",
                "412",
                "Good",
            ),
            row(
                "B-02",
                "Pleurotus djamor",
                "Hardwood pellets",
                "15 %",
                "Pinned early at 26 °C",
                "Tent",
                "1,208",
                "Best",
            ),
            row(
                "B-03",
                "Hericium erinaceus",
                "Supplemented sawdust",
                "8 %",
                "Slow; green mould on day 9",
                "Tent",
                "0",
                "Lost",
            ),
        ).joinToString("\n")

    /** F-MD-CODE / F-KNOWLEDGE-LONG: the 200-character code line. */
    public val longCodeLine: String =
        ("val readings = listOf(" + (1..80).joinToString(", ") { "$it.5" }).take(199) + ")"

    /** F-MD-CODE: a 40-line Kotlin block, a 200-character line, a block with no language, inline code. */
    public val mdCode: String =
        buildString {
            appendLine("Run `measure()` once per batch:")
            appendLine()
            appendLine("```kotlin")
            appendLine("fun measure(substrate: Substrate): Reading {")
            (1..38).forEach { appendLine("    val step$it = substrate.sample($it) // reading $it of 38") }
            appendLine("}")
            appendLine("```")
            appendLine()
            appendLine("```")
            appendLine(longCodeLine)
            appendLine("```")
        }.trimEnd()

    /** F-MD-LIST: a 6-level nested list, mixed ordered/unordered, a task list, a list ending in a citation. */
    public val mdList: String =
        """
        1. Prepare the substrate
           - Chop the straw
             1. Soak it overnight
                - Drain for an hour
                  1. Do the squeeze test
                     - A few drops only
        2. Inoculate

        - [ ] Order spawn
        - [x] Clean the shed

        - Keep the humidity high
        - Mist twice a day [1]
        """.trimIndent()

    /**
     * F-MD-CITE: markers `[1]`–`[12]`, `[10]` and `[1, 2]`, one inside bold,
     * one adjacent to a list end. The marker split across stream pieces is
     * [mdCitePieces].
     */
    public val mdCite: String =
        listOf(
            "Straw colonises fastest [1]. Hardwood is slower [2] but fruits for longer [3].",
            "The other logs agree [4] [5] [6] [7] [8] [9] [10] [11] [12], and the tenth batch [10] was the outlier.",
            "Both species logs agree [1, 2]. **Keep the humidity above 85 % [4]** while pinning.",
            "",
            "- Soak the straw",
            "- Drain it well[5]",
        ).joinToString("\n")

    /** F-MD-CITE: stream pieces with a marker split as `"["`, `"1"`, `"]"` (the `CitationParser` buffering case). */
    public val mdCitePieces: List<String> =
        listOf("Straw colonises fastest ", "[", "1", "]", " and hardwood lasts longer ", "[2", "]", ".")

    /** F-MD-MIXED: the 200-character URL. */
    public val longUrl: String =
        ("https://example.org/grow/" + (1..40).joinToString("/") { "flush-$it" }).take(200)

    /** F-MD-MIXED: headings, a block quote, links, a 200-character URL and emoji. */
    public val mdMixed: String =
        listOf(
            "# Second flush 🍄",
            "",
            "## What changed",
            "",
            "> Fresh air matters more than humidity once the pins form.",
            "",
            "See [the grow journal](https://example.org/grow-journal) and <$longUrl>.",
            "",
            "### Next",
            "",
            "Harvest when the caps flatten 🌱, then soak the block for 12 hours.",
        ).joinToString("\n")

    // ------------------------------------------------------------------
    // Chats
    // ------------------------------------------------------------------

    /** F-CHAT-SHORT: four messages; the first answer cites [sources]' first passage. */
    public val chatShort: List<Message> =
        listOf(
            message("f-chat-short", 0, Role.USER, "How long does oyster spawn take to colonise straw?", 4),
            Message(
                id = "f-chat-short-m1",
                chatDocId = "f-chat-short",
                role = Role.ASSISTANT,
                contentMd = "About two weeks at 22 °C: your log shows full colonisation on day 14 [1].",
                modelId = OWNER_MODEL_ID,
                retrievedChunks = emptyList(),
                createdAt = FIXTURE_NOW - 3 * MINUTE,
                citations = citationTo(sources().first()),
            ),
            message("f-chat-short", 2, Role.USER, "And hardwood?", 2),
            message("f-chat-short", 3, Role.ASSISTANT, "Slower, closer to three weeks, but it fruits for longer.", 1),
        )

    /**
     * F-CHAT-200 / F-CHAT-500 (`conversation(200)`, `conversation(500)`):
     * alternating user/assistant messages with power-law lengths, a code
     * block in every 10th, citation markers (text only, no record) in every
     * 7th, one minute apart, the last one at [FIXTURE_NOW].
     */
    public fun conversation(
        messages: Int,
        seed: Long = SEED,
        chatId: DocId = "f-chat-$messages",
    ): List<Message> {
        val rnd = Random(seed)
        return List(messages) { i ->
            val wordCount = (4 / (1 - rnd.nextDouble()).pow(0.8)).toInt().coerceAtMost(400)
            val text =
                buildString {
                    append(words(wordCount, rnd))
                    if (i % 7 == 6) append(" [1] [2]")
                    if (i % 10 == 9) append("\n\n```kotlin\nval flush = harvest(batch = $i)\n```")
                }
            message(chatId, i, if (i % 2 == 0) Role.USER else Role.ASSISTANT, text, messages - 1 - i)
        }
    }

    /** F-HUGE-MESSAGE: a 5,000-character user message. */
    public val hugeUserMessage: String = prose(5_000)

    /** F-HUGE-MESSAGE: a 20,000-character answer. */
    public val hugeAnswer: String = prose(20_000)

    /** F-DRAFT-LONG: a 12-line draft with one 300-character line. */
    public val draftLong: String =
        List(12) { i -> if (i == 5) prose(300) else "Line ${i + 1} of the draft about the next flush." }
            .joinToString("\n")

    /** F-CHATS-HISTORY: the chat that is answering. */
    public const val HISTORY_ANSWERING_ID: DocId = "f-history-01"

    /** F-CHATS-HISTORY: the chat with a finished (unread) answer. */
    public const val HISTORY_FINISHED_ID: DocId = "f-history-02"

    /**
     * F-CHATS-HISTORY: 30 chats, newest first: 4 today, 5 yesterday, 7 in the
     * previous 7 days, 8 in the previous 30 days, then 3 in August and 3 in
     * July. The six [titlesLong] are among them; [HISTORY_ANSWERING_ID] and
     * [HISTORY_FINISHED_ID] mark the answering and finished rows.
     */
    public val chatsHistory: List<Document> =
        run {
            val ages =
                listOf(10 * MINUTE, 40 * MINUTE, 2 * HOUR, 3 * HOUR) +
                    (0 until 5).map { DAY + it * 2 * HOUR } +
                    (2..7).map { it * DAY } + (7 * DAY + 2 * HOUR) +
                    (9..30 step 3).map { it * DAY } +
                    listOf(37, 45, 52, 67, 75, 82).map { it * DAY }
            ages.mapIndexed { i, age ->
                val title = titlesLong.getOrNull(i - 3) ?: "Chat ${i + 1}: ${words(4 + i % 5, Random(SEED + i))}"
                chat("f-history-" + (i + 1).toString().padStart(2, '0'), title, FIXTURE_NOW - age)
            }
        }

    // ------------------------------------------------------------------
    // Knowledge: vaults, notes, files, sources, graphs
    // ------------------------------------------------------------------

    /** F-VAULT-EMPTY: a vault with no documents (every empty state). */
    public fun emptyVault(): InMemoryVaultRepository = InMemoryVaultRepository(clock = { FIXTURE_NOW })

    /** F-KNOWLEDGE-LONG: the 80-character title. */
    public const val KNOWLEDGE_LONG_TITLE: String =
        "Q3 2025 quarterly report for the community garden cooperative, final numbers, v3"

    /** F-KNOWLEDGE-LONG: the attachment file name. */
    public const val KNOWLEDGE_LONG_FILE: String = "quarterly-report-2025-final-final-v3.pdf"

    /** F-KNOWLEDGE-LONG: 6-deep lists, a 6-column table and the 200-character code line. */
    public val knowledgeLong: Document =
        note(
            "f-knowledge-long",
            KNOWLEDGE_LONG_TITLE,
            listOf(
                mdList,
                "",
                row("Quarter", "Beds", "Volunteers", "Harvest (kg)", "Spend", "Notes"),
                row("---", "---:", "---:", "---:", "---:", "---"),
                row("Q3 2025", "42", "17", "1,284.5", "3,120", "Irrigation line replaced on the north beds"),
                "",
                "```",
                longCodeLine,
                "```",
            ).joinToString("\n"),
            FIXTURE_NOW - 2 * DAY,
        )

    /** F-NOTE-*: a note with properties. */
    public val noteWithProperties: Document =
        note(
            "f-note-properties",
            "Substrate notes",
            "Straw beats hardwood for speed.",
            FIXTURE_NOW - DAY,
            buildJsonObject {
                put(FrontmatterKeys.TAGS, JsonArray(listOf(JsonPrimitive("grow"), JsonPrimitive("substrate"))))
                put("status", JsonPrimitive("draft"))
                put("due", JsonPrimitive("2026-10-01"))
            },
        )

    /** F-NOTE-*: a new, empty note. */
    public val noteNew: Document = note("f-note-new", "", "", FIXTURE_NOW)

    /** F-NOTE-*: a note with a link to a note that does not exist. */
    public val noteWithMissingLink: Document =
        note("f-note-missing-link", "Pinning checklist", "Follow [[Fruiting chamber build]] first.", FIXTURE_NOW - HOUR)

    /** F-NOTE-*: 40 notes that link to [noteWithProperties], and their wikilink edges (its 40 backlinks). */
    public val backlinks: GraphFixture =
        run {
            val notes =
                List(40) { i ->
                    note(
                        "f-backlink-${i + 1}",
                        "Grow log day ${i + 1}",
                        "Checked [[Substrate notes]].",
                        FIXTURE_NOW - i * DAY,
                    )
                }
            GraphFixture(
                notes,
                notes.map { Edge(it.id, noteWithProperties.id, EdgeKind.WIKILINK, createdAt = it.updatedAt) },
            )
        }

    /** F-FILE-PDF: the extracted text of each page. */
    public val filePdfPages: List<String> =
        List(3) { i -> "Page ${i + 1}. ${prose(400 + i * 200, Random(SEED + i))}" }

    /** F-FILE-PDF: the attachment. */
    public val filePdf: Document =
        Document(
            id = "f-file-pdf",
            kind = DocumentKind.ATTACHMENT,
            title = KNOWLEDGE_LONG_FILE,
            bodyMd = null,
            createdAt = FIXTURE_NOW - 3 * DAY,
            updatedAt = FIXTURE_NOW - 3 * DAY,
            personaId = null,
            frontmatter = buildJsonObject { put("mime", JsonPrimitive("application/pdf")) },
            contentHash = null,
        )

    /** F-FILE-PDF: the note holding its extracted text (`source` points at [filePdf]). */
    public val filePdfText: Document =
        note(
            "f-file-pdf-text",
            KNOWLEDGE_LONG_FILE,
            filePdfPages.mapIndexed { i, page -> "## Page ${i + 1}\n\n$page" }.joinToString("\n\n"),
            FIXTURE_NOW - 3 * DAY,
            buildJsonObject { put(FrontmatterKeys.SOURCE, JsonPrimitive(filePdf.id)) },
        )

    /**
     * F-SOURCES (`sources()`): [passages] retrieved passages spread over
     * [documents] documents with long source names, highest score first.
     * `ScenarioInferenceEngine.retrievalService()` serves these.
     */
    public fun sources(
        passages: Int = 8,
        documents: Int = 3,
    ): List<Retrieved> {
        require(documents in 1..passages) { "need 1..$passages documents, was $documents" }
        return List(passages) { i ->
            val d = i % documents
            val title = sourceTitles[d % sourceTitles.size] + if (d >= sourceTitles.size) " ($d)" else ""
            Retrieved(
                chunkId = i + 1L,
                docId = "f-source-${d + 1}",
                docTitle = title,
                text = "Passage ${i + 1}: ${words(24, Random(SEED + i))}",
                score = 0.95 - i * 0.05,
                sourceKind = if (title.endsWith(".pdf")) DocumentKind.ATTACHMENT else DocumentKind.NOTE,
                recalledBy = setOf(RecallSource.VECTOR),
            )
        }
    }

    /** F-ATTACHED-2: two notes attached to a chat (the context chip states). */
    public val attached: List<Document> =
        listOf(
            note("f-attached-1", "Oyster mushroom log", "Day 14: fully colonised.", FIXTURE_NOW - DAY),
            noteWithProperties,
        )

    /** F-GRAPH-8: an 8-node neighbourhood around `f-graph-1`. */
    public val graph8: GraphFixture = graph(8)

    /** F-GRAPH-80: 80 nodes. */
    public val graph80: GraphFixture = graph(80)

    /** F-GRAPH-ISOLATED: a note with no links. */
    public val graphIsolated: GraphFixture =
        GraphFixture(listOf(note("f-graph-isolated", "Loose idea", "No links yet.", FIXTURE_NOW)), emptyList())

    /**
     * F-GRAPH-*: [nodes] notes; node 1 is the centre and nodes 2–8 link to it,
     * every later node links to a seeded earlier one, and every third node
     * adds a cross link. Bodies carry the matching `[[wikilinks]]`.
     */
    public fun graph(
        nodes: Int,
        seed: Long = SEED,
    ): GraphFixture {
        val rnd = Random(seed)
        val targets =
            List(nodes) { i ->
                when {
                    i == 0 -> emptyList()
                    i < 8 -> listOfNotNull(0, if (i % 3 == 0) rnd.nextInt(1, i) else null)
                    else -> listOfNotNull(rnd.nextInt(i), if (i % 3 == 0) rnd.nextInt(i) else null)
                }.distinct()
            }
        val title = { i: Int -> "Graph note ${i + 1}" }
        val notes =
            List(nodes) { i ->
                note(
                    "f-graph-${i + 1}",
                    title(i),
                    targets[i].joinToString(" ") { "[[${title(it)}]]" },
                    FIXTURE_NOW - i * HOUR,
                )
            }
        val edges =
            targets.flatMapIndexed { i, ts ->
                ts.map { Edge(notes[i].id, notes[it].id, EdgeKind.WIKILINK, createdAt = notes[i].updatedAt) }
            }
        return GraphFixture(notes, edges)
    }

    // ------------------------------------------------------------------
    // Errors
    // ------------------------------------------------------------------

    /** F-ERRORS: one of each `InferenceException` subtype (AC-22: a sentence and an action, never a class name). */
    public val errors: List<InferenceException> =
        listOf(
            InferenceException.ModelNotLoaded(),
            InferenceException.HashMismatch(expected = "a".repeat(64), actual = "b".repeat(64)),
            InferenceException.InvalidModel("not a GGUF file"),
            InferenceException.ServiceDied(),
            InferenceException.OutOfMemory(),
            InferenceException.Busy(),
            InferenceException.PostMmapHashMismatch(),
            InferenceException.CompanionHashMismatch("tokenizer"),
            InferenceException.TransactionTooLarge(),
            InferenceException.ModelInUse(),
            InferenceException.SessionLocked(),
            InferenceException.Internal(),
        )

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** [count] seeded lexicon words joined by spaces. */
    private fun words(
        count: Int,
        rnd: Random,
    ): String = List(count) { lexicon[rnd.nextInt(lexicon.size)] }.joinToString(" ")

    /** Exactly [length] characters of seeded prose (sentences of lexicon words). */
    private fun prose(
        length: Int,
        rnd: Random = Random(SEED),
    ): String =
        buildString {
            while (this.length < length) append(words(12, rnd).replaceFirstChar(Char::uppercaseChar)).append(". ")
        }.take(length)

    private fun row(vararg cells: String): String = cells.joinToString(" | ", "| ", " |")

    private fun model(
        id: String,
        name: String,
        file: String,
        sizeBytes: Long,
        ageDays: Int,
    ): Model =
        Model(
            id = id,
            name = name,
            path = "/data/user/0/app.skein/files/models/$file",
            sha256 =
                id
                    .hashCode()
                    .toUInt()
                    .toString(16)
                    .padStart(64, '0'),
            format = ModelFormat.GGUF,
            capabilities = setOf(Capability.TEXT),
            sizeBytes = sizeBytes,
            importedAt = FIXTURE_NOW - ageDays * DAY,
        )

    private fun note(
        id: DocId,
        title: String,
        body: String,
        updatedAt: Long,
        frontmatter: JsonObject = JsonObject(emptyMap()),
    ): Document = Document(id, DocumentKind.NOTE, title, body, updatedAt, updatedAt, null, frontmatter, null)

    private fun chat(
        id: DocId,
        title: String,
        updatedAt: Long,
    ): Document = Document(id, DocumentKind.CHAT, title, "", updatedAt, updatedAt, null, JsonObject(emptyMap()), null)

    private fun message(
        chatId: DocId,
        index: Int,
        role: Role,
        text: String,
        minutesAgo: Int,
    ): Message =
        Message(
            id = "$chatId-m$index",
            chatDocId = chatId,
            role = role,
            contentMd = text,
            modelId = if (role == Role.ASSISTANT) OWNER_MODEL_ID else null,
            retrievedChunks = emptyList(),
            createdAt = FIXTURE_NOW - minutesAgo * MINUTE,
        )

    private fun citationTo(source: Retrieved): CitationRecord =
        CitationRecord(
            retrieved =
                listOf(
                    Citation(
                        marker = 1,
                        documentId = source.docId,
                        revisionHash = "0".repeat(64),
                        locator = Locator(0, source.text.length),
                        excerpt = source.text,
                        sourceKind = CitationSourceKind.VECTOR,
                    ),
                ),
            cited = listOf(1),
        )
}

/** Notes plus the edges between them (F-GRAPH-*, F-NOTE-* backlinks). */
public data class GraphFixture(
    val notes: List<Document>,
    val edges: List<Edge>,
)
