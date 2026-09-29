/* Host contract for the exact vendored SQLite tokenizer bridge. Android
 * instrumentation separately verifies SQLCipher, JNI, triggers and row probes. */
#include <assert.h>
#include <stdio.h>
#include <stdlib.h>
#include "androidx-jni/skein_fts_terms.h"
#include "androidx-jni/skein_utf16.h"

static void expect_terms(sqlite3 *db, const char *text, const char **expected, int count) {
    char output[SKEIN_FTS_OUTPUT_BYTES];
    SkeinFtsTerms terms = {output, 0, 0};
    assert(skein_fts_terms(db, text, (int)strlen(text), &terms) == SQLITE_OK);
    assert(terms.count == count);
    int offset = 0;
    for (int i = 0; i < count; i++) {
        assert(strcmp(output + offset, expected[i]) == 0);
        offset += (int)strlen(output + offset) + 1;
    }
    assert(offset == terms.size);
}

int main(void) {
    uint16_t decoded[16];
    const unsigned char standard[] = {0xf0, 0x90, 0x90, 0x80, 0, 'a'};
    const unsigned char legacy[] = {0xed, 0xa0, 0x81, 0xed, 0xb0, 0x80, 0xc0, 0x80, 'a'};
    assert(skein_utf16(standard, sizeof(standard), decoded) == 4);
    assert(decoded[0] == 0xd801 && decoded[1] == 0xdc00 && decoded[2] == 0 && decoded[3] == 'a');
    assert(skein_utf16(legacy, sizeof(legacy), decoded) == 4);
    assert(decoded[0] == 0xd801 && decoded[1] == 0xdc00 && decoded[2] == 0 && decoded[3] == 'a');
    const unsigned char invalid[] = {0xff, 0xf4, 0x90, 0x80, 0x80, 0xe0, 0x80};
    assert(skein_utf16(invalid, sizeof(invalid), decoded) == sizeof(invalid));
    for (int i = 0; i < (int)sizeof(invalid); i++) assert(decoded[i] == 0xfffd);

    sqlite3 *db = NULL;
    assert(sqlite3_open(":memory:", &db) == SQLITE_OK);
    sqlite3_stmt *binding = NULL;
    assert(sqlite3_prepare_v2(db, "SELECT hex(?)", -1, &binding, NULL) == SQLITE_OK);
    const unsigned char bom_utf8[] = {0xef, 0xbb, 0xbf, 0xf0, 0x90, 0x90, 0x80, 0, 'a'};
    assert(sqlite3_bind_text(binding, 1, (const char *)bom_utf8, sizeof(bom_utf8), SQLITE_STATIC) == SQLITE_OK);
    assert(sqlite3_step(binding) == SQLITE_ROW);
    assert(strcmp((const char *)sqlite3_column_text(binding, 0), "EFBBBFF09090800061") == 0);
    assert(sqlite3_reset(binding) == SQLITE_OK);
    /* Negative control: SQLite treats a leading UTF-16 BOM as an encoding
     * marker, so the superseded bind_text16 path would mutate this source. */
    const uint16_t bom_utf16[] = {0xfeff, 'a'};
    assert(sqlite3_bind_text16(binding, 1, bom_utf16, sizeof(bom_utf16), SQLITE_STATIC) == SQLITE_OK);
    assert(sqlite3_step(binding) == SQLITE_ROW);
    assert(strcmp((const char *)sqlite3_column_text(binding, 0), "61") == 0);
    assert(sqlite3_finalize(binding) == SQLITE_OK);
    const char *text = "中文English café cafe\xcc\x81 résumé naïve Русский العربية १२३ \xee\x80\x80secret 𐐀𐐁";
    const char *expected[] = {
        "中文english", "cafe", "cafe", "resume", "naive", "русский", "العربية", "१२३", "\xee\x80\x80secret", "𐐨𐐩"
    };
    expect_terms(db, text, expected, 10);
    const char *complex_accents[] = {"ộ", "o"};
    expect_terms(db, "ộ o\xcc\xa3\xcc\x82", complex_accents, 2);
    expect_terms(db, "🚀 \xcc\x81 !!!", NULL, 0);

    /* Confirm the returned terms against real unicode61 postings, not a
     * duplicate implementation or a regex expected-output assertion alone. */
    assert(sqlite3_exec(db, "CREATE VIRTUAL TABLE ft USING fts5(text)", NULL, NULL, NULL) == SQLITE_OK);
    sqlite3_stmt *stmt = NULL;
    assert(sqlite3_prepare_v2(db, "INSERT INTO ft(text) VALUES (?)", -1, &stmt, NULL) == SQLITE_OK);
    assert(sqlite3_bind_text(stmt, 1, text, -1, SQLITE_STATIC) == SQLITE_OK);
    assert(sqlite3_step(stmt) == SQLITE_DONE);
    assert(sqlite3_finalize(stmt) == SQLITE_OK);
    for (int i = 0; i < 10; i++) {
        assert(sqlite3_prepare_v2(db, "SELECT rowid FROM ft WHERE rowid = 1 AND ft MATCH ?", -1, &stmt, NULL) == SQLITE_OK);
        assert(sqlite3_bind_text(stmt, 1, expected[i], -1, SQLITE_STATIC) == SQLITE_OK);
        assert(sqlite3_step(stmt) == SQLITE_ROW);
        assert(sqlite3_finalize(stmt) == SQLITE_OK);
    }
    assert(sqlite3_prepare_v2(db, "SELECT rowid FROM ft WHERE ft MATCH 'English OR secret'", -1, &stmt, NULL) == SQLITE_OK);
    assert(sqlite3_step(stmt) == SQLITE_DONE);
    assert(sqlite3_finalize(stmt) == SQLITE_OK);

    char oversized[SKEIN_FTS_MAX_TERM_BYTES + 16];
    memset(oversized, 'x', SKEIN_FTS_MAX_TERM_BYTES + 1);
    strcpy(oversized + SKEIN_FTS_MAX_TERM_BYTES + 1, " safe");
    const char *safe[] = {"safe"};
    expect_terms(db, oversized, safe, 1);
    oversized[SKEIN_FTS_MAX_TERM_BYTES] = '\0';
    const char *max_term[] = {oversized};
    expect_terms(db, oversized, max_term, 1);

    char many_terms[SKEIN_FTS_MAX_TERMS * 4 + 1];
    for (int i = 0; i < SKEIN_FTS_MAX_TERMS * 2; i++) memcpy(many_terms + 2 * i, "x ", 2);
    many_terms[sizeof(many_terms) - 1] = '\0';
    char output[SKEIN_FTS_OUTPUT_BYTES];
    SkeinFtsTerms terms = {output, 0, 0};
    assert(skein_fts_terms(db, many_terms, (int)strlen(many_terms), &terms) == SQLITE_OK);
    assert(terms.count == SKEIN_FTS_MAX_TERMS);
    assert(terms.size == SKEIN_FTS_MAX_TERMS * 2);

    char *too_long = malloc(SKEIN_FTS_MAX_TEXT_BYTES + 1);
    assert(too_long != NULL);
    memset(too_long, 'x', SKEIN_FTS_MAX_TEXT_BYTES + 1);
    terms.size = 0;
    terms.count = 0;
    assert(skein_fts_terms(db, too_long, SKEIN_FTS_MAX_TEXT_BYTES + 1, &terms) == SQLITE_OK);
    assert(terms.size == 0 && terms.count == 0);
    free(too_long);
    assert(sqlite3_close(db) == SQLITE_OK);
    puts("SQLite unicode61 tokenizer and posting contracts passed");
    return 0;
}
