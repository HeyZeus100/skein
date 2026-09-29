package app.skein.core.vault.db

import androidx.sqlite.SQLiteStatement

/** Exact UTF-8 for derived chunks and FTS queries; generic repository keys keep legacy binding. */
internal fun SQLiteStatement.bindLexicalText(
    index: Int,
    value: String,
) {
    checkNotNull(this as? SkeinSQLiteStatement) { "Lexical retrieval requires SkeinSQLiteStatement" }
        .bindUtf8Text(index, value)
}
