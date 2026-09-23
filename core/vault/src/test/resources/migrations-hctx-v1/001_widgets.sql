-- Test fixture (skein-hctx): deliberately trivial DDL, simple enough for
-- FakeSkeinSQLiteNative's DDL tracking (see that class's schemaObjectType
-- doc) to model exactly without reimplementing a real SQL engine. Not
-- shipped in the production manifest.

CREATE TABLE widgets (
  id INTEGER PRIMARY KEY,
  name TEXT NOT NULL
);--;

CREATE INDEX idx_widgets_name ON widgets(name);--;
