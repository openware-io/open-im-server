ALTER TABLE ord_ktv_session
    ADD COLUMN pause_started_at DATETIME(3) NULL COMMENT '当前暂停开始时间';
