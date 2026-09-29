package app.skein.core.vault.db

import androidx.sqlite.SQLiteConnection

/** Generic repository handles must still use the driver's actual SQLite tokenizer. */
internal fun SQLiteConnection.lexicalTerms(text: String): List<String> =
    checkNotNull(this as? SkeinSQLiteConnection) { "Lexical retrieval requires SkeinSQLiteConnection" }
        .lexicalTerms(text)
