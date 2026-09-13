-- A new token on each claim prevents an old relay from overwriting a newer owner.
-- Existing SENDING rows remain reclaimable after their current timeout.
ALTER TABLE outbox_event ADD COLUMN claim_token UUID;
