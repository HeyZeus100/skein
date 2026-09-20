-- Deliberately-broken migration for MigratorInstrumentedTest's ROLLBACK
-- test (E2.I2 / skein-5my). The first statement is valid; the second is
-- not. Migrator must apply both inside one `BEGIN IMMEDIATE` transaction,
-- so the syntax error in the second statement must roll back the first
-- one too — `valid_but_isolated` must NOT exist afterward, and
-- `PRAGMA user_version` must be unchanged.
--
-- The trailing comma before the closing paren is genuinely invalid SQL
-- ("near ')': syntax error") — verified against both CPython's sqlite3
-- module and this project's driver. An earlier draft used
-- `x INT INVALID_SQL_HERE` as the "obviously wrong" column, but SQLite's
-- `type-name` grammar accepts an arbitrary run of bare names as a type
-- (e.g. `DOUBLE PRECISION`), so that statement silently parses as a
-- column with a weird-but-legal type name instead of failing — it does
-- NOT exercise the ROLLBACK path this test needs.

CREATE TABLE valid_but_isolated (x INT);--;
CREATE TABLE syntax_error (x INT,);--;
