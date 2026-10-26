ALTER TABLE htl_hotel
  ADD COLUMN cover_image_url varchar(512) NULL COMMENT '酒店封面图 URL' AFTER address;
