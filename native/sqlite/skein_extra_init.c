/*
 * skein_extra_init.c — SQLite EXTRA_INIT hook that registers sqlite-vec as an
 * auto-loading extension. Compiled into the amalgamation via
 *   -DSQLITE_EXTRA_INIT=skein_extra_init
 *
 * Because we build with -DSQLITE_CORE, sqlite-vec.c is statically linked and
 * exports sqlite3_vec_init(sqlite3*, char**, const sqlite3_api_routines*).
 * sqlite3_auto_extension expects a function pointer of shape
 *   int(*)(sqlite3*, char**, const sqlite3_api_routines*)
 * so we cast to the exact signature.
 */
#include "sqlite3.h"

extern int sqlite3_vec_init(sqlite3 *db, char **pzErrMsg,
                            const sqlite3_api_routines *pApi);
extern int sqlcipher_extra_init(const char *arg);
extern void sqlcipher_extra_shutdown(void);

/*
 * `skein_extra_init` is named in libskein_sqlite.so's linker version script
 * (see native/sqlite/CMakeLists.txt §5a, skein-6rwv), so a JNI shim can
 * dlsym it. The version script's `local: *;` clause hides everything not
 * whitelisted, but a symbol emitted with STV_HIDDEN under -fvisibility=hidden
 * cannot be un-hidden by the version script — so tag the definition with
 * explicit default visibility. `skein_extra_shutdown` is not on the JNI shim's
 * critical path and stays hidden.
 */
__attribute__((visibility("default")))
int skein_extra_init(const char *arg) {
    int rc = sqlcipher_extra_init(arg);
    if (rc != SQLITE_OK) return rc;
    return sqlite3_auto_extension((void (*)(void))sqlite3_vec_init);
}

void skein_extra_shutdown(void) {
    sqlcipher_extra_shutdown();
    sqlite3_cancel_auto_extension((void (*)(void))sqlite3_vec_init);
}
