/*
 * host_harness.c — proves the SQLCipher + sqlite-vec + FTS5 stack works in a
 * single connection on the host. Mirrors the assertions the Android instrumented
 * test would run:
 *   1. PRAGMA cipher_version returns a non-empty string (SQLCipher active)
 *   2. sqlite-vec vec0 vtable + KNN returns the nearer vector first
 *   3. fts5 vtable + MATCH returns the inserted row
 *   4. Reopening the file without the key fails
 *   5. Raw bytes of the DB file contain no inserted plaintext strings
 */
#include "sqlite3.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define PASS 0
#define FAIL 1

static int check(sqlite3 *db, int rc, const char *what) {
    if (rc != SQLITE_OK && rc != SQLITE_ROW && rc != SQLITE_DONE) {
        fprintf(stderr, "FAIL %s: %s\n", what, sqlite3_errmsg(db));
        return FAIL;
    }
    return PASS;
}

static const char *SECRET = "spike_e0_i7_secret_plaintext_marker";

int main(int argc, char **argv) {
    const char *path = argc > 1 ? argv[1] : "/tmp/skein-spike.db";
    remove(path);

    sqlite3 *db = NULL;
    int rc = sqlite3_open(path, &db);
    if (check(db, rc, "open")) return 1;

    /* 1. PRAGMA key + cipher_version */
    rc = sqlite3_exec(db, "PRAGMA key = 'correct horse battery staple';",
                      NULL, NULL, NULL);
    if (check(db, rc, "PRAGMA key")) return 1;

    sqlite3_stmt *stmt = NULL;
    rc = sqlite3_prepare_v2(db, "PRAGMA cipher_version;", -1, &stmt, NULL);
    if (check(db, rc, "prepare cipher_version")) return 1;
    rc = sqlite3_step(stmt);
    if (rc != SQLITE_ROW) {
        fprintf(stderr, "FAIL cipher_version: no row\n");
        return 1;
    }
    const unsigned char *ver = sqlite3_column_text(stmt, 0);
    if (!ver || !ver[0]) {
        fprintf(stderr, "FAIL cipher_version: empty\n");
        return 1;
    }
    printf("PASS cipher_version: %s\n", ver);
    sqlite3_finalize(stmt);

    /* 2. sqlite-vec: vec_version() + vec0 vtable + KNN */
    rc = sqlite3_prepare_v2(db, "SELECT vec_version();", -1, &stmt, NULL);
    if (check(db, rc, "prepare vec_version")) return 1;
    rc = sqlite3_step(stmt);
    if (rc != SQLITE_ROW) { fprintf(stderr, "FAIL vec_version\n"); return 1; }
    printf("PASS vec_version: %s\n", sqlite3_column_text(stmt, 0));
    sqlite3_finalize(stmt);

    rc = sqlite3_exec(db,
        "CREATE VIRTUAL TABLE v USING vec0(e float[4] distance_metric=cosine);",
        NULL, NULL, NULL);
    if (check(db, rc, "create vec0")) return 1;

    rc = sqlite3_exec(db,
        "INSERT INTO v(rowid, e) VALUES "
        "  (1, '[1.0, 0.0, 0.0, 0.0]'),"
        "  (2, '[0.0, 1.0, 0.0, 0.0]');",
        NULL, NULL, NULL);
    if (check(db, rc, "insert vec")) return 1;

    rc = sqlite3_prepare_v2(db,
        "SELECT rowid FROM v WHERE e MATCH '[0.9, 0.1, 0.0, 0.0]' "
        "AND k = 2 ORDER BY distance;",
        -1, &stmt, NULL);
    if (check(db, rc, "prepare KNN")) return 1;
    rc = sqlite3_step(stmt);
    if (rc != SQLITE_ROW) { fprintf(stderr, "FAIL KNN no row\n"); return 1; }
    int first = sqlite3_column_int(stmt, 0);
    if (first != 1) {
        fprintf(stderr, "FAIL KNN: expected rowid 1 first, got %d\n", first);
        return 1;
    }
    printf("PASS KNN nearest is rowid %d\n", first);
    sqlite3_finalize(stmt);

    /* 3. FTS5 vtable + MATCH */
    rc = sqlite3_exec(db, "CREATE VIRTUAL TABLE f USING fts5(x);",
                      NULL, NULL, NULL);
    if (check(db, rc, "create fts5")) return 1;
    char sql[512];
    snprintf(sql, sizeof(sql),
        "INSERT INTO f(x) VALUES ('the quick brown fox %s jumps over');",
        SECRET);
    rc = sqlite3_exec(db, sql, NULL, NULL, NULL);
    if (check(db, rc, "insert fts5")) return 1;

    rc = sqlite3_prepare_v2(db,
        "SELECT rowid FROM f WHERE f MATCH 'fox';", -1, &stmt, NULL);
    if (check(db, rc, "prepare fts5 match")) return 1;
    rc = sqlite3_step(stmt);
    if (rc != SQLITE_ROW) { fprintf(stderr, "FAIL fts5 MATCH\n"); return 1; }
    printf("PASS fts5 MATCH found rowid %d\n", sqlite3_column_int(stmt, 0));
    sqlite3_finalize(stmt);

    sqlite3_close(db);

    /* 4. Reopen without key -> should fail with "file is not a database" */
    db = NULL;
    rc = sqlite3_open(path, &db);
    if (rc != SQLITE_OK) { fprintf(stderr, "unexpected open error\n"); return 1; }
    rc = sqlite3_prepare_v2(db, "SELECT count(*) FROM sqlite_master;",
                            -1, &stmt, NULL);
    if (rc == SQLITE_OK) rc = sqlite3_step(stmt);
    if (rc == SQLITE_ROW) {
        fprintf(stderr, "FAIL: DB read without key succeeded\n");
        return 1;
    }
    printf("PASS reopen without key fails: %s\n", sqlite3_errmsg(db));
    if (stmt) sqlite3_finalize(stmt);
    sqlite3_close(db);

    /* 5. Raw file bytes must not contain SECRET plaintext */
    FILE *f = fopen(path, "rb");
    fseek(f, 0, SEEK_END);
    long sz = ftell(f);
    rewind(f);
    char *buf = malloc(sz);
    fread(buf, 1, sz, f);
    fclose(f);
    int found = 0;
    size_t slen = strlen(SECRET);
    for (long i = 0; i + (long)slen <= sz; i++) {
        if (memcmp(buf + i, SECRET, slen) == 0) { found = 1; break; }
    }
    free(buf);
    if (found) {
        fprintf(stderr, "FAIL: plaintext SECRET present in ciphertext DB\n");
        return 1;
    }
    printf("PASS raw file has no plaintext (size=%ld bytes)\n", sz);

    printf("\nALL PASS — SQLCipher + sqlite-vec + FTS5 in one connection.\n");
    return 0;
}
