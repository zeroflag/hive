set hive.limit.optimize.enable=true;
set hive.fetch.task.conversion=none;

CREATE EXTERNAL TABLE hht (key string, value int)
STORED BY 'org.apache.hadoop.hive.hbase.HBaseStorageHandler'
WITH SERDEPROPERTIES ("hbase.columns.mapping" = ":key,cf1:val")
TBLPROPERTIES ("hbase.table.name" = "hht", "hbase.mapred.output.outputtable" = "hht");

insert into hht values ('a', 1);
insert into hht values ('b', 1);
insert into hht values ('c', 1);

select * from hht limit 10;
