-- standards/10: the release version is a business string, not an optimistic lock.
ALTER TABLE adm_client_release RENAME COLUMN version TO release_version;
