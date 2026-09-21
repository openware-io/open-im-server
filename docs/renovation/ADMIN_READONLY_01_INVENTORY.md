# im-admin 批次 0 跨域直连只读清单

本文冻结批次 0 开始时 admin 对其他领域权威数据的直接访问。清单中的代码仅允许为保持既有 HTTP 行为进行缺陷修复；不得新增 Mapper、实体、SQL、写路径或 Redis 在线状态写入。迁移目标以 `ADMIN_DOMAIN_01_DDD.md` 的数据归属矩阵为准。

| 权威领域 | 历史表 | 历史实体 | 直接 Mapper | 当前管理入口 | 迁移目标 |
| --- | --- | --- | --- | --- | --- |
| user | `users` | `User` | `UserRepository` | `/admin/users/**` | user 内部 Query/管理命令与投影 |
| user | `friends` | `Friend` | `FriendRepository` | `/admin/friends` | user 查询或管理投影 |
| user | `device_tokens` | `DeviceToken` | `DeviceTokenRepository` | `/admin/device-tokens/{userId}` | user 查询或管理投影 |
| user | `user_stickers` | `UserSticker` | `UserStickerRepository` | `/admin/user-stickers` | user 管理命令与投影 |
| message | `messages` | `Message` | `MessageRepository` | `/admin/messages` | message 查询、管理命令与投影 |
| conversation | `groups` | `Group` | `GroupRepository` | 无直接控制器 | conversation 查询、管理命令与投影 |
| access-ws | Redis 在线集合 | 无 | 无 | `/admin/monitor/online` | 只读在线查询或事件投影 |

`system_configs`、`app_releases`、举报、违规、敏感词和小程序配置是后续批次要迁移为 admin 权威的历史共享数据，不属于本清单中的跨域新增许可。每次跨域替换必须同步更新本文、HTTP 兼容矩阵和架构门禁，并保留旧接口回归证据。
