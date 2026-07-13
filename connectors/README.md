# connectors

信息源适配器目录。

Connector 的责任是取得获准的 SourceObject 并附带来源、权限、空间、敏感度和 lineage 信息；不得绕过标准管线直写产品数据库。

每个 Connector 必须记录官方接口、授权方式、速率限制、数据政策、失败降级和删除行为。
