-- R2/R3: bind an analysis to every snapshot dependency and expose freshness.
ALTER TABLE analyses ADD COLUMN dependency_hash TEXT NOT NULL DEFAULT '';
CREATE INDEX idx_analyses_dependency
  ON analyses(owner_id, month_id, dependency_hash);
