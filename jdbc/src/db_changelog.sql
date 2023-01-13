--changeset db_changelog
create table db_changelog(
  id text not null primary key,
  filePath text not null,
  context text,
  checksum bigint,
  statements text[],
  rowsAffected integer not null default 0,
  createdAt timestamptz not null default current_timestamp
);

--changeset try-migrate-from-liquibase onFail:SKIP
insert into db_changelog (id, filePath, context, createdAt)
  select author || ':' || id, filename, contexts, dateexecuted from databasechangelog;
