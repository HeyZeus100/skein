-- Test fixture (skein-hctx): stands in for a later migration (e.g.
-- skein-cyq's 009) landing after this fix -- adds a new table, proving
-- VaultLifecycle's catalogue-derivation code needs zero changes to pick it
-- up: only this file and INDEX.txt are new.

CREATE TABLE gadgets (
  id INTEGER PRIMARY KEY
);--;
