/*
 * skein_jni.c — minimal JNI shim mapping the `SkeinSQLiteNative` Kotlin
 * `external fun`s onto `sqlite3_*` calls in libskein_sqlite.so.
 *
 * Pattern is a direct descendant of the AOSP androidx.sqlite bundled JNI
 * driver at
 *   https://cs.android.com/androidx/platform/frameworks/support/+/androidx-main:sqlite/sqlite-bundled/src/androidMain/jni/
 * (Apache-2.0; see repo-root NOTICE for attribution). We intentionally
 * ship this as a *separate* .so — libskein_sqlite_jni.so — that links
 * against libskein_sqlite.so's exported SQLCipher/vec API (skein-6rwv).
 * That keeps the 6-7 MB payload in one place and this shim tiny.
 *
 * Handles: opaque pointers passed as Java `long`.
 * Errors:  any non-OK sqlite3 return throws `SkeinSQLiteException` with
 *          the errmsg + result code. The shim never keeps a jstring/jbyte
 *          reference across a return.
 * Security: passphrase bytes are held via GetByteArrayElements(JNI_ABORT)
 *          so JNI never copies them back; the caller zeroes them.
 */

#include <jni.h>
#include <stdlib.h>
#include <string.h>

/* SQLCipher's `sqlite3_key_v2` declaration in the vendored amalgamation
 * header is guarded by `#ifdef SQLITE_HAS_CODEC`. Turn that on for this
 * TU so the prototype is visible; the symbol itself is provided by
 * libskein_sqlite.so's exported set (skein-6rwv). */
#define SQLITE_HAS_CODEC 1

#include "sqlite3.h"

/* ------------------------------------------------------------------ */
/* Helpers                                                            */
/* ------------------------------------------------------------------ */

static void throw_sqlite_exception(JNIEnv *env, sqlite3 *db, int rc, const char *fallback) {
    jclass cls = (*env)->FindClass(env, "app/skein/core/vault/db/SkeinSQLiteException");
    if (cls == NULL) {
        /* Class-not-found already pending — nothing else to do. */
        return;
    }
    const char *msg = (db != NULL) ? sqlite3_errmsg(db) : NULL;
    if (msg == NULL || msg[0] == '\0') {
        msg = (fallback != NULL) ? fallback : "unknown sqlite error";
    }
    jmethodID ctor = (*env)->GetMethodID(env, cls, "<init>", "(ILjava/lang/String;)V");
    if (ctor == NULL) {
        return;
    }
    jstring jmsg = (*env)->NewStringUTF(env, msg);
    if (jmsg == NULL) {
        return;
    }
    jthrowable ex = (jthrowable)(*env)->NewObject(env, cls, ctor, (jint)rc, jmsg);
    if (ex != NULL) {
        (*env)->Throw(env, ex);
    }
}

static sqlite3 *db_ptr(jlong handle) {
    return (sqlite3 *)(intptr_t)handle;
}

static sqlite3_stmt *stmt_ptr(jlong handle) {
    return (sqlite3_stmt *)(intptr_t)handle;
}

/* ------------------------------------------------------------------ */
/* Connection lifecycle                                               */
/* ------------------------------------------------------------------ */

JNIEXPORT jlong JNICALL
Java_app_skein_core_vault_db_SkeinSQLiteNative_nativeOpen(
        JNIEnv *env, jclass klass, jstring fileName) {
    (void)klass;
    const char *path = (*env)->GetStringUTFChars(env, fileName, NULL);
    if (path == NULL) {
        return 0;
    }
    sqlite3 *db = NULL;
    int flags = SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE | SQLITE_OPEN_URI;
    int rc = sqlite3_open_v2(path, &db, flags, NULL);
    (*env)->ReleaseStringUTFChars(env, fileName, path);
    if (rc != SQLITE_OK) {
        throw_sqlite_exception(env, db, rc, "sqlite3_open_v2 failed");
        if (db != NULL) {
            sqlite3_close_v2(db);
        }
        return 0;
    }
    return (jlong)(intptr_t)db;
}

JNIEXPORT void JNICALL
Java_app_skein_core_vault_db_SkeinSQLiteNative_nativeClose(
        JNIEnv *env, jclass klass, jlong handle) {
    (void)env;
    (void)klass;
    sqlite3 *db = db_ptr(handle);
    if (db != NULL) {
        sqlite3_close_v2(db);
    }
}

JNIEXPORT void JNICALL
Java_app_skein_core_vault_db_SkeinSQLiteNative_nativeKey(
        JNIEnv *env, jclass klass, jlong handle, jbyteArray passphrase, jint length) {
    (void)klass;
    sqlite3 *db = db_ptr(handle);
    if (db == NULL) {
        throw_sqlite_exception(env, NULL, SQLITE_MISUSE, "nativeKey: null db handle");
        return;
    }
    if (passphrase == NULL || length <= 0) {
        throw_sqlite_exception(env, NULL, SQLITE_MISUSE, "nativeKey: empty passphrase");
        return;
    }
    jbyte *bytes = (*env)->GetByteArrayElements(env, passphrase, NULL);
    if (bytes == NULL) {
        return;
    }
    int rc = sqlite3_key_v2(db, NULL, bytes, (int)length);
    /* JNI_ABORT: do NOT copy back to the Java array; the caller zeroes it. */
    (*env)->ReleaseByteArrayElements(env, passphrase, bytes, JNI_ABORT);
    if (rc != SQLITE_OK) {
        throw_sqlite_exception(env, db, rc, "sqlite3_key_v2 failed");
    }
}

JNIEXPORT void JNICALL
Java_app_skein_core_vault_db_SkeinSQLiteNative_nativeExec(
        JNIEnv *env, jclass klass, jlong handle, jstring sql) {
    (void)klass;
    sqlite3 *db = db_ptr(handle);
    if (db == NULL) {
        throw_sqlite_exception(env, NULL, SQLITE_MISUSE, "nativeExec: null db handle");
        return;
    }
    const char *sql_utf = (*env)->GetStringUTFChars(env, sql, NULL);
    if (sql_utf == NULL) {
        return;
    }
    char *errmsg = NULL;
    int rc = sqlite3_exec(db, sql_utf, NULL, NULL, &errmsg);
    (*env)->ReleaseStringUTFChars(env, sql, sql_utf);
    if (rc != SQLITE_OK) {
        throw_sqlite_exception(env, db, rc, errmsg);
    }
    if (errmsg != NULL) {
        sqlite3_free(errmsg);
    }
}

JNIEXPORT jlong JNICALL
Java_app_skein_core_vault_db_SkeinSQLiteNative_nativeChanges(
        JNIEnv *env, jclass klass, jlong handle) {
    (void)env;
    (void)klass;
    sqlite3 *db = db_ptr(handle);
    return (db != NULL) ? (jlong)sqlite3_changes64(db) : 0;
}

JNIEXPORT jlong JNICALL
Java_app_skein_core_vault_db_SkeinSQLiteNative_nativeLastInsertRowId(
        JNIEnv *env, jclass klass, jlong handle) {
    (void)env;
    (void)klass;
    sqlite3 *db = db_ptr(handle);
    return (db != NULL) ? (jlong)sqlite3_last_insert_rowid(db) : 0;
}

/* ------------------------------------------------------------------ */
/* Statement lifecycle                                                */
/* ------------------------------------------------------------------ */

JNIEXPORT jlong JNICALL
Java_app_skein_core_vault_db_SkeinSQLiteNative_nativePrepare(
        JNIEnv *env, jclass klass, jlong handle, jstring sql) {
    (void)klass;
    sqlite3 *db = db_ptr(handle);
    if (db == NULL) {
        throw_sqlite_exception(env, NULL, SQLITE_MISUSE, "nativePrepare: null db handle");
        return 0;
    }
    const char *sql_utf = (*env)->GetStringUTFChars(env, sql, NULL);
    if (sql_utf == NULL) {
        return 0;
    }
    sqlite3_stmt *stmt = NULL;
    int rc = sqlite3_prepare_v2(db, sql_utf, -1, &stmt, NULL);
    (*env)->ReleaseStringUTFChars(env, sql, sql_utf);
    if (rc != SQLITE_OK) {
        throw_sqlite_exception(env, db, rc, "sqlite3_prepare_v2 failed");
        if (stmt != NULL) {
            sqlite3_finalize(stmt);
        }
        return 0;
    }
    return (jlong)(intptr_t)stmt;
}

JNIEXPORT void JNICALL
Java_app_skein_core_vault_db_SkeinSQLiteNative_nativeFinalize(
        JNIEnv *env, jclass klass, jlong stmtHandle) {
    (void)env;
    (void)klass;
    sqlite3_stmt *stmt = stmt_ptr(stmtHandle);
    if (stmt != NULL) {
        sqlite3_finalize(stmt);
    }
}

JNIEXPORT void JNICALL
Java_app_skein_core_vault_db_SkeinSQLiteNative_nativeReset(
        JNIEnv *env, jclass klass, jlong stmtHandle) {
    (void)klass;
    sqlite3_stmt *stmt = stmt_ptr(stmtHandle);
    if (stmt == NULL) {
        return;
    }
    int rc = sqlite3_reset(stmt);
    if (rc != SQLITE_OK) {
        throw_sqlite_exception(env, sqlite3_db_handle(stmt), rc, "sqlite3_reset failed");
    }
}

JNIEXPORT void JNICALL
Java_app_skein_core_vault_db_SkeinSQLiteNative_nativeClearBindings(
        JNIEnv *env, jclass klass, jlong stmtHandle) {
    (void)klass;
    sqlite3_stmt *stmt = stmt_ptr(stmtHandle);
    if (stmt == NULL) {
        return;
    }
    int rc = sqlite3_clear_bindings(stmt);
    if (rc != SQLITE_OK) {
        throw_sqlite_exception(env, sqlite3_db_handle(stmt), rc, "sqlite3_clear_bindings failed");
    }
}

JNIEXPORT jboolean JNICALL
Java_app_skein_core_vault_db_SkeinSQLiteNative_nativeStep(
        JNIEnv *env, jclass klass, jlong stmtHandle) {
    (void)klass;
    sqlite3_stmt *stmt = stmt_ptr(stmtHandle);
    if (stmt == NULL) {
        throw_sqlite_exception(env, NULL, SQLITE_MISUSE, "nativeStep: null stmt");
        return JNI_FALSE;
    }
    int rc = sqlite3_step(stmt);
    if (rc == SQLITE_ROW) {
        return JNI_TRUE;
    }
    if (rc == SQLITE_DONE) {
        return JNI_FALSE;
    }
    throw_sqlite_exception(env, sqlite3_db_handle(stmt), rc, "sqlite3_step failed");
    return JNI_FALSE;
}

/* ------------------------------------------------------------------ */
/* Column accessors                                                    */
/* ------------------------------------------------------------------ */

JNIEXPORT jint JNICALL
Java_app_skein_core_vault_db_SkeinSQLiteNative_nativeColumnCount(
        JNIEnv *env, jclass klass, jlong stmtHandle) {
    (void)env;
    (void)klass;
    sqlite3_stmt *stmt = stmt_ptr(stmtHandle);
    return (stmt != NULL) ? (jint)sqlite3_column_count(stmt) : 0;
}

JNIEXPORT jstring JNICALL
Java_app_skein_core_vault_db_SkeinSQLiteNative_nativeColumnName(
        JNIEnv *env, jclass klass, jlong stmtHandle, jint index) {
    (void)klass;
    sqlite3_stmt *stmt = stmt_ptr(stmtHandle);
    if (stmt == NULL) {
        return NULL;
    }
    const char *name = sqlite3_column_name(stmt, (int)index);
    return (name != NULL) ? (*env)->NewStringUTF(env, name) : NULL;
}

JNIEXPORT jint JNICALL
Java_app_skein_core_vault_db_SkeinSQLiteNative_nativeColumnType(
        JNIEnv *env, jclass klass, jlong stmtHandle, jint index) {
    (void)env;
    (void)klass;
    sqlite3_stmt *stmt = stmt_ptr(stmtHandle);
    return (stmt != NULL) ? (jint)sqlite3_column_type(stmt, (int)index) : SQLITE_NULL;
}

JNIEXPORT jstring JNICALL
Java_app_skein_core_vault_db_SkeinSQLiteNative_nativeColumnText(
        JNIEnv *env, jclass klass, jlong stmtHandle, jint index) {
    (void)klass;
    sqlite3_stmt *stmt = stmt_ptr(stmtHandle);
    if (stmt == NULL) {
        return NULL;
    }
    const unsigned char *txt = sqlite3_column_text(stmt, (int)index);
    return (txt != NULL) ? (*env)->NewStringUTF(env, (const char *)txt) : NULL;
}

JNIEXPORT jlong JNICALL
Java_app_skein_core_vault_db_SkeinSQLiteNative_nativeColumnLong(
        JNIEnv *env, jclass klass, jlong stmtHandle, jint index) {
    (void)env;
    (void)klass;
    sqlite3_stmt *stmt = stmt_ptr(stmtHandle);
    return (stmt != NULL) ? (jlong)sqlite3_column_int64(stmt, (int)index) : 0;
}

JNIEXPORT jdouble JNICALL
Java_app_skein_core_vault_db_SkeinSQLiteNative_nativeColumnDouble(
        JNIEnv *env, jclass klass, jlong stmtHandle, jint index) {
    (void)env;
    (void)klass;
    sqlite3_stmt *stmt = stmt_ptr(stmtHandle);
    return (stmt != NULL) ? (jdouble)sqlite3_column_double(stmt, (int)index) : 0.0;
}

JNIEXPORT jbyteArray JNICALL
Java_app_skein_core_vault_db_SkeinSQLiteNative_nativeColumnBlob(
        JNIEnv *env, jclass klass, jlong stmtHandle, jint index) {
    (void)klass;
    sqlite3_stmt *stmt = stmt_ptr(stmtHandle);
    if (stmt == NULL) {
        return NULL;
    }
    const void *blob = sqlite3_column_blob(stmt, (int)index);
    int size = sqlite3_column_bytes(stmt, (int)index);
    if (blob == NULL || size <= 0) {
        return (*env)->NewByteArray(env, 0);
    }
    jbyteArray out = (*env)->NewByteArray(env, size);
    if (out == NULL) {
        return NULL;
    }
    (*env)->SetByteArrayRegion(env, out, 0, size, (const jbyte *)blob);
    return out;
}

/* ------------------------------------------------------------------ */
/* Bindings                                                            */
/* ------------------------------------------------------------------ */

JNIEXPORT void JNICALL
Java_app_skein_core_vault_db_SkeinSQLiteNative_nativeBindNull(
        JNIEnv *env, jclass klass, jlong stmtHandle, jint index) {
    (void)klass;
    sqlite3_stmt *stmt = stmt_ptr(stmtHandle);
    if (stmt == NULL) {
        return;
    }
    int rc = sqlite3_bind_null(stmt, (int)index);
    if (rc != SQLITE_OK) {
        throw_sqlite_exception(env, sqlite3_db_handle(stmt), rc, "sqlite3_bind_null failed");
    }
}

JNIEXPORT void JNICALL
Java_app_skein_core_vault_db_SkeinSQLiteNative_nativeBindLong(
        JNIEnv *env, jclass klass, jlong stmtHandle, jint index, jlong value) {
    (void)klass;
    sqlite3_stmt *stmt = stmt_ptr(stmtHandle);
    if (stmt == NULL) {
        return;
    }
    int rc = sqlite3_bind_int64(stmt, (int)index, (sqlite3_int64)value);
    if (rc != SQLITE_OK) {
        throw_sqlite_exception(env, sqlite3_db_handle(stmt), rc, "sqlite3_bind_int64 failed");
    }
}

JNIEXPORT void JNICALL
Java_app_skein_core_vault_db_SkeinSQLiteNative_nativeBindDouble(
        JNIEnv *env, jclass klass, jlong stmtHandle, jint index, jdouble value) {
    (void)klass;
    sqlite3_stmt *stmt = stmt_ptr(stmtHandle);
    if (stmt == NULL) {
        return;
    }
    int rc = sqlite3_bind_double(stmt, (int)index, (double)value);
    if (rc != SQLITE_OK) {
        throw_sqlite_exception(env, sqlite3_db_handle(stmt), rc, "sqlite3_bind_double failed");
    }
}

JNIEXPORT void JNICALL
Java_app_skein_core_vault_db_SkeinSQLiteNative_nativeBindText(
        JNIEnv *env, jclass klass, jlong stmtHandle, jint index, jstring value) {
    (void)klass;
    sqlite3_stmt *stmt = stmt_ptr(stmtHandle);
    if (stmt == NULL) {
        return;
    }
    const char *txt = (*env)->GetStringUTFChars(env, value, NULL);
    if (txt == NULL) {
        return;
    }
    /* SQLITE_TRANSIENT: sqlite3 copies the buffer immediately. Safe to release. */
    int rc = sqlite3_bind_text(stmt, (int)index, txt, -1, SQLITE_TRANSIENT);
    (*env)->ReleaseStringUTFChars(env, value, txt);
    if (rc != SQLITE_OK) {
        throw_sqlite_exception(env, sqlite3_db_handle(stmt), rc, "sqlite3_bind_text failed");
    }
}

JNIEXPORT void JNICALL
Java_app_skein_core_vault_db_SkeinSQLiteNative_nativeBindBlob(
        JNIEnv *env, jclass klass, jlong stmtHandle, jint index, jbyteArray value) {
    (void)klass;
    sqlite3_stmt *stmt = stmt_ptr(stmtHandle);
    if (stmt == NULL) {
        return;
    }
    jsize len = (*env)->GetArrayLength(env, value);
    jbyte *bytes = (*env)->GetByteArrayElements(env, value, NULL);
    if (bytes == NULL) {
        return;
    }
    int rc = sqlite3_bind_blob(stmt, (int)index, bytes, (int)len, SQLITE_TRANSIENT);
    (*env)->ReleaseByteArrayElements(env, value, bytes, JNI_ABORT);
    if (rc != SQLITE_OK) {
        throw_sqlite_exception(env, sqlite3_db_handle(stmt), rc, "sqlite3_bind_blob failed");
    }
}
