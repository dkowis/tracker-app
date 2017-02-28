-- Modify the primary key to be the channel name
ALTER TABLE channel_projects MODIFY `id` int(20),
  drop primary key,
  add primary key(channel_name);
-- Drop the column id, because it's not particularly valuable
alter table channel_projects drop column id;