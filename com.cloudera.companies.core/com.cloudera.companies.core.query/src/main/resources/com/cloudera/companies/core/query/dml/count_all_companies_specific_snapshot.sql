--
-- Count companies accross specific snapshot
--

SELECT count(1) AS company_count
FROM company
WHERE snapshot_year=2012 and snapshot_month=05;


