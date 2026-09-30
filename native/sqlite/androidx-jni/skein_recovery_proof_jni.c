/* Unwired recovery proof. Never opens the live vault, creates a database, or returns source text. */
#include <jni.h>
#include <sqlite3.h>
#include <string.h>
#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>

/* Keep these finite codes synchronized with ExistingVaultKeyProof. */
#define PROOF_OK 0
#define PROOF_KEY_OR_CORRUPT 1
#define PROOF_UNSUPPORTED 2
#define PROOF_UNAVAILABLE 3

static void wipe(void *buffer, size_t size) {
    volatile unsigned char *p = (volatile unsigned char *)buffer;
    while (size--) *p++ = 0;
}

static int unavailable(int rc) {
    int primary = rc & 255;
    return primary == SQLITE_IOERR || primary == SQLITE_CANTOPEN || primary == SQLITE_BUSY ||
        primary == SQLITE_LOCKED || primary == SQLITE_NOMEM || primary == SQLITE_FULL;
}

static int scalar_int(sqlite3 *db, const char *sql, int *value) {
    sqlite3_stmt *stmt = NULL;
    int rc = sqlite3_prepare_v2(db, sql, -1, &stmt, NULL);
    if (rc == SQLITE_OK) {
        rc = sqlite3_step(stmt);
        if (rc == SQLITE_ROW && sqlite3_column_type(stmt, 0) == SQLITE_INTEGER) {
            *value = sqlite3_column_int(stmt, 0);
            rc = sqlite3_step(stmt);
            if (rc == SQLITE_DONE) rc = SQLITE_OK;
        } else if (rc == SQLITE_ROW || rc == SQLITE_DONE) {
            rc = SQLITE_ERROR;
        }
    }
    sqlite3_finalize(stmt);
    return rc;
}

JNIEXPORT jint JNICALL
Java_app_skein_core_vault_db_RecoveryProofNative_nativeVerify(
        JNIEnv *env, jobject receiver, jstring fileName, jbyteArray rawKey) {
    (void)receiver;
    if (fileName == NULL || rawKey == NULL || (*env)->GetArrayLength(env, rawKey) != 32) {
        return PROOF_UNAVAILABLE;
    }
    jbyte key[32];
    (*env)->GetByteArrayRegion(env, rawKey, 0, 32, key);
    if ((*env)->ExceptionCheck(env)) { wipe(key, sizeof(key)); return PROOF_UNAVAILABLE; }
    const char *path = (*env)->GetStringUTFChars(env, fileName, NULL);
    if (path == NULL) { wipe(key, sizeof(key)); return PROOF_UNAVAILABLE; }
    /* Explicitly reject missing, empty, nonregular and plaintext files before SQLite sees them. */
    int descriptor = open(path, O_RDONLY | O_NOFOLLOW);
    struct stat info;
    unsigned char header[16];
    int acceptable = descriptor >= 0 && fstat(descriptor, &info) == 0 && S_ISREG(info.st_mode) &&
        info.st_size >= 16 && read(descriptor, header, sizeof(header)) == sizeof(header) &&
        memcmp(header, "SQLite format 3", 15) != 0;
    if (descriptor >= 0) close(descriptor);
    if (!acceptable) {
        (*env)->ReleaseStringUTFChars(env, fileName, path);
        wipe(key, sizeof(key));
        return PROOF_UNAVAILABLE;
    }
    sqlite3 *db = NULL;
    /* No URI or immutable mode: committed WAL pages remain part of the read snapshot. */
    int rc = sqlite3_open_v2(path, &db, SQLITE_OPEN_READONLY | SQLITE_OPEN_NOFOLLOW, NULL);
    (*env)->ReleaseStringUTFChars(env, fileName, path);
    int result = PROOF_UNAVAILABLE;
    sqlite3_stmt *stmt = NULL;
    char key_sql[96] = "PRAGMA key = \"x'";
    size_t prefix = strlen(key_sql);
    static const char hex[] = "0123456789abcdef";
    for (int i = 0; i < 32; ++i) {
        unsigned char byte = (unsigned char)key[i];
        key_sql[prefix + i * 2] = hex[byte >> 4];
        key_sql[prefix + i * 2 + 1] = hex[byte & 15];
    }
    memcpy(key_sql + prefix + 64, "'\";", 4);
    wipe(key, sizeof(key));
    if (rc != SQLITE_OK || sqlite3_db_readonly(db, "main") != 1) goto done;
    /* Same RAW-key semantics as SkeinSQLiteDriver, NOT sqlite3_key_v2(raw32) passphrase mode. */
    rc = sqlite3_exec(db, key_sql, NULL, NULL, NULL);
    wipe(key_sql, sizeof(key_sql));
    if (rc != SQLITE_OK) goto failed;
    rc = sqlite3_exec(db, "PRAGMA cipher_memory_security = ON; PRAGMA query_only = ON;", NULL, NULL, NULL);
    if (rc != SQLITE_OK) goto failed;
    rc = sqlite3_prepare_v2(db, "PRAGMA cipher_version", -1, &stmt, NULL);
    if (rc != SQLITE_OK) goto failed;
    rc = sqlite3_step(stmt);
    if (rc != SQLITE_ROW || sqlite3_column_bytes(stmt, 0) == 0) { result = PROOF_UNSUPPORTED; goto done; }
    sqlite3_finalize(stmt); stmt = NULL;
    int count = 0;
    /* Forces a real encrypted page read; merely opening or applying a key proves nothing. */
    rc = scalar_int(db, "SELECT count(*) FROM sqlite_master", &count);
    if (rc != SQLITE_OK) goto failed;
    if (count == 0) { result = PROOF_UNSUPPORTED; goto done; }
    int version = 0;
    rc = scalar_int(db, "PRAGMA user_version", &version);
    if (rc != SQLITE_OK) goto failed;
    if (!(version == 1 || version == 3 || version == 5 || version == 7 || version == 8 ||
          version == 9 || version == 10 || version == 11)) { result = PROOF_UNSUPPORTED; goto done; }
    rc = scalar_int(db, "SELECT count(*) FROM sqlite_master WHERE type='table' AND "
        "name IN ('documents','messages','personas','attachment_keys')", &count);
    if (rc != SQLITE_OK) goto failed;
    if (count != 4) { result = PROOF_UNSUPPORTED; goto done; }
    rc = sqlite3_prepare_v2(db, "PRAGMA quick_check", -1, &stmt, NULL);
    if (rc != SQLITE_OK) goto failed;
    rc = sqlite3_step(stmt);
    if (rc != SQLITE_ROW) goto failed;
    const unsigned char *check = sqlite3_column_text(stmt, 0);
    if (check == NULL || strcmp((const char *)check, "ok") != 0) { result = PROOF_KEY_OR_CORRUPT; goto done; }
    rc = sqlite3_step(stmt);
    if (rc != SQLITE_DONE) goto failed;
    sqlite3_finalize(stmt); stmt = NULL;
    rc = sqlite3_prepare_v2(db, "PRAGMA cipher_integrity_check", -1, &stmt, NULL);
    if (rc != SQLITE_OK) goto failed;
    rc = sqlite3_step(stmt);
    if (rc == SQLITE_ROW) { result = PROOF_KEY_OR_CORRUPT; goto done; }
    if (rc != SQLITE_DONE) goto failed;
    result = PROOF_OK;
    goto done;
failed:
    result = unavailable(rc) ? PROOF_UNAVAILABLE : PROOF_KEY_OR_CORRUPT;
done:
    wipe(key_sql, sizeof(key_sql));
    sqlite3_finalize(stmt);
    if (db != NULL) sqlite3_close_v2(db);
    return result;
}
