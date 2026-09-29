/* The SAME default unicode61 tokenizer as chunks_fts (migration 001).
 * No text is persisted, logged, or interpolated into SQL. Keep these bounds
 * aligned with core/model/LexicalQueryLimits.kt. A token is skipped whole,
 * never cut into a prefix that may not name its indexed posting. */
#ifndef SKEIN_FTS_TERMS_H
#define SKEIN_FTS_TERMS_H

#include "sqlite3.h"
#include <string.h>

#define SKEIN_FTS_MAX_TERMS 128
#define SKEIN_FTS_MAX_TERM_BYTES 512
#define SKEIN_FTS_MAX_TEXT_BYTES 65536
#define SKEIN_FTS_OUTPUT_BYTES (SKEIN_FTS_MAX_TERMS * (SKEIN_FTS_MAX_TERM_BYTES + 1))

typedef struct SkeinFtsTerms {
    char *output;
    int size;
    int count;
} SkeinFtsTerms;

static int skein_collect_fts_term(void *context, int flags, const char *token,
                                  int length, int start, int end) {
    (void)flags;
    (void)start;
    (void)end;
    SkeinFtsTerms *terms = (SkeinFtsTerms *)context;
    if (length <= 0 || length > SKEIN_FTS_MAX_TERM_BYTES) return SQLITE_OK;
    memcpy(terms->output + terms->size, token, (size_t)length);
    terms->size += length;
    terms->output[terms->size++] = '\0';
    terms->count++;
    return terms->count == SKEIN_FTS_MAX_TERMS ? SQLITE_DONE : SQLITE_OK;
}

static int skein_fts_terms(sqlite3 *db, const char *text, int length, SkeinFtsTerms *terms) {
    if (length <= 0 || length > SKEIN_FTS_MAX_TEXT_BYTES) return SQLITE_OK;
    fts5_api *api = NULL;
    sqlite3_stmt *statement = NULL;
    int rc = sqlite3_prepare_v2(db, "SELECT fts5(?)", -1, &statement, NULL);
    if (rc == SQLITE_OK) {
        rc = sqlite3_bind_pointer(statement, 1, &api, "fts5_api_ptr", NULL);
        if (rc == SQLITE_OK) {
            rc = sqlite3_step(statement);
            if (rc == SQLITE_ROW) rc = SQLITE_OK;
        }
    }
    int finalize_rc = sqlite3_finalize(statement);
    if (rc == SQLITE_OK) rc = finalize_rc;
    if (rc != SQLITE_OK) return rc;
    if (api == NULL) return SQLITE_ERROR;

    fts5_tokenizer tokenizer;
    void *context = NULL;
    Fts5Tokenizer *instance = NULL;
    rc = api->xFindTokenizer(api, "unicode61", &context, &tokenizer);
    if (rc != SQLITE_OK) return rc;
    rc = tokenizer.xCreate(context, NULL, 0, &instance);
    if (rc != SQLITE_OK) return rc;
    rc = tokenizer.xTokenize(instance, terms, FTS5_TOKENIZE_QUERY, text, length, skein_collect_fts_term);
    tokenizer.xDelete(instance);
    return rc == SQLITE_DONE ? SQLITE_OK : rc;
}

#endif
