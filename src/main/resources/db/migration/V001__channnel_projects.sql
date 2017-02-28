CREATE TABLE channel_projects (
  id           BIGINT(20)   NOT NULL AUTO_INCREMENT PRIMARY KEY,
  channel_name VARCHAR(255) NOT NULL,
  project_id   BIGINT(20)   NOT NULL
);
