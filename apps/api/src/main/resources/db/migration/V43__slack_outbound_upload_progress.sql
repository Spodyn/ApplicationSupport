CREATE TABLE slack_outbound_uploads (
    attachment_id uuid PRIMARY KEY REFERENCES attachments(id) ON DELETE CASCADE,
    slack_file_id varchar(255) NOT NULL
);
