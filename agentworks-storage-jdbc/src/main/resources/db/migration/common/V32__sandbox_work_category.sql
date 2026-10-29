-- Issue #40: a retained sandbox is durable resumable work owned by its exact runtime cohort. The PostgreSQL
-- authority in V32_1 fences new sandbox allocation and keeps cohort retirement blocked until cleanup is confirmed.
alter table cohort_work_item drop constraint cohort_work_item_category_ck;
alter table cohort_work_item add constraint cohort_work_item_category_ck check (category in (
    'CLAIM', 'OPERATION', 'CHECKPOINT', 'WAKEUP', 'INBOX', 'INTERACTION', 'SANDBOX', 'SCHEDULE', 'CHILD_WORK'));
