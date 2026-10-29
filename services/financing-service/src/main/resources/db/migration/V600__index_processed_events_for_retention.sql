-- The processed_events retention job selects rows by age; without this index each batch scans the whole table.
CREATE INDEX ix_processed_events_processed_at ON processed_events (processed_at);
