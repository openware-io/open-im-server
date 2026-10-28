-- S6 旧 iam_user_role 冻结：迁移到归档表，生产代码不再读写旧表。
RENAME TABLE `iam_user_role` TO `iam_user_role_archive`;
