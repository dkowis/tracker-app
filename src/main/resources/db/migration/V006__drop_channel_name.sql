-- Get rid of the channel name field
ALTER TABLE channel_projects DROP COLUMN channel_name;

-- ALso drop rows that don't have a channel ID
DELETE FROM channel_projects WHERE channel_id is null;

-- Also add a unique constraint between channel_id and project_id
