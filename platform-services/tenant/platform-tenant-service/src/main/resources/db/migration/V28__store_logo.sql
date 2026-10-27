ALTER TABLE `tnt_store`
  ADD COLUMN `logo_url` varchar(512) NULL COMMENT '门店 Logo URL（图片由媒体服务上传）' AFTER `name`;
