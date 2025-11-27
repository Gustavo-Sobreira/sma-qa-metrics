-- schema.sql
CREATE TABLE IF NOT EXISTS qa_results (
  id serial PRIMARY KEY,
  repo varchar(512),
  commit_hash varchar(128),
  tool varchar(64),
  report jsonb,
  created_at timestamp default now()
);

CREATE TABLE IF NOT EXISTS commit_metrics (
  id serial PRIMARY KEY,
  repo varchar(512),
  author varchar(255),
  commits_count int,
  analyzed_at timestamp default now()
);
