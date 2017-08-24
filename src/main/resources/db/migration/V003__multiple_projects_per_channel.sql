-- Add back the id column
ALTER TABLE channel_projects
  ADD COLUMN id BIGINT(20) NOT NULL AUTO_INCREMENT,
  DROP PRIMARY KEY,
  ADD PRIMARY KEY (id);

-- Add a unique constraint combined of channel name and project id
ALTER TABLE channel_projects ADD CONSTRAINT unique_registration UNIQUE (channel_name, project_id);
