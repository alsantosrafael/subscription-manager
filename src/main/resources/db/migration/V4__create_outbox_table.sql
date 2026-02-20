CREATE TABLE IF NOT EXISTS event_publication (
    id UUID NOT NULL,
    listener_id VARCHAR(512) NOT NULL,
    event_type VARCHAR(512) NOT NULL,
    serialized_event TEXT NOT NULL,
    publication_date TIMESTAMP WITH TIME ZONE NOT NULL,
    completion_date TIMESTAMP WITH TIME ZONE,
    completion_attempts INTEGER,
    last_resubmission_date TIMESTAMP WITH TIME ZONE,
    status VARCHAR(255),
    PRIMARY KEY (id)
    );

CREATE INDEX IF NOT EXISTS idx_event_publication_incomplete
    ON event_publication (completion_date)
    WHERE completion_date IS NULL;