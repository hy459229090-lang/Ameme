# ADR-006：MVP 采用 LAN 点对点同步

- 状态：accepted
- 日期：2026-07-14
- 决策人：产品负责人 + 架构基线
- 影响 Gate：Gate 3–6
- 替代/被替代：替代 ADR-003 在 MVP 中的云协调实现

## 决议

MVP 的跨设备同步由设备端 `Peer Sync Node` 完成：

1. iOS 使用 Bonjour/Network.framework 的服务发现与连接能力；Android 使用 NSD/DNS-SD 与平台网络 API。
2. 发现消息只暴露短期服务实例和协议版本，不广播账户、空间或内容标识。
3. 连接后执行同账户/设备身份认证和 TLS 1.3 等价的加密会话，再交换 capability、cursor 和 envelope。
4. 同步协议继续使用 append-only revision、sequence、cursor、idempotency、tombstone、ack/proof；transport 与云无关。
5. Structured 默认按 space 策略同步；Raw 逐对象选择并遵守来源索引、TTL、配额和删除规则。
6. 每个设备保存 peer cursor、known replica、last seen、schema major 和 deletion ack；离线设备未确认时不得宣称全副本删除完成。

## 平台边界

- iOS 需要 `NSLocalNetworkUsageDescription` 和声明的 Bonjour service type；后台连接可能中断，回前台后重建会话。
- Android 使用 `NsdManager`；target Android 17/API 37 时需适配 `ACCESS_LOCAL_NETWORK` 或合适的系统中介能力。
- LAN 被视为不可信网络，不能因为同 Wi-Fi 就跳过身份、加密或用户授权。

## 否决与降级

- LAN 权限被拒绝：保持本机记录/搜索/Agent 可用，显示“仅本机”。
- 跨平台发现不稳定：提供一次性二维码/短码交换连接信息，但数据仍点对点传输。
- 后台窗口不足：改为打开 App 时同步，不使用保活或持续扫描规避系统限制。
- 只有产品负责人新决策后才能增加云 relay/backup；协议适配不得自动上传内容。

## 验证

执行 `LAN-SYNC-01`：双端发现/权限、同账户认证、恶意 LAN、断网/重复/乱序、前后台、热点/隔离 Wi-Fi、Raw 断点续传、删除收敛、资源与用户理解。

## 官方依据

- Apple Local Network Privacy / Bonjour：<https://developer.apple.com/documentation/technotes/tn3179-understanding-local-network-privacy>
- Android Network Service Discovery：<https://developer.android.com/develop/connectivity/wifi/use-nsd>
- Android Local Network Permission：<https://developer.android.com/privacy-and-security/local-network-permission>
