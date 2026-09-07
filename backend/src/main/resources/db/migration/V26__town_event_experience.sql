-- Optional structured invitation link; historical letters stay unlinked.
ALTER TABLE town_letter ADD COLUMN event_public_id CHAR(26) NULL;

ALTER TABLE town_event
    ADD COLUMN player_response VARCHAR(12) NOT NULL DEFAULT 'UNDECIDED',
    ADD COLUMN attended_at DATETIME(3) NULL,
    ADD COLUMN cancelled_at DATETIME(3) NULL,
    ADD CONSTRAINT chk_town_event_player_response CHECK (player_response IN ('UNDECIDED', 'GOING', 'SKIPPED'));
