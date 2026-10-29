-- In-app read state. NULL means unread; existing rows therefore start unread.
ALTER TABLE notifications ADD COLUMN read_at DATETIME(6) NULL;

-- Serves the per-user unread count and the "mark all read" update.
CREATE INDEX ix_notifications_recipient_read ON notifications (recipient_id, read_at);
