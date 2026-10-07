> 2026-10-07范围变更：21day仅连接时屿；Android已移除知识库密钥、检索、阅读、收藏批注及关联代码。独立21day同步/来源接口和时屿只读摘要保留。下文知识接口和三端实施内容为此前历史，不再是手机现行功能，不代表其他项目知识服务被停用。

# 21day 来源接口契约

2026-10-05，已修改待测试。本文件描述已部署的服务契约；生产合成账号验证通过，尚不代表用户人工验收。

## 身份与隔离

HTTPS `https://zhuisu.leadjet.com.cn`，Bearer 为现有邮箱身份访问令牌。服务检查 `aud=21day`、`scope=21day:sync`、sub、会话撤销。用户由令牌推导，不接受请求里的 owner/userId。知识仍单独授权。原 `/sync/push|pull|snapshot` 保留。

## 展示习惯

`GET /21day-api/v1/habits/cards?timeZoneId=Asia%2FShanghai`

返回 `{sourceProject:"21day",cursor,cards:[{sourceProject,sourceId,sourceRevision,day,name,unit,input,scheduled,target,entry,timer,data,actions}]}`。

`entry=null` 为未记录，`value=0` 是真实0。`data` 是该轮可缓存的来源快照：

```json
{"plan":{"id":"UUID","name":"阅读","start":"2026-10-05","mode":"AT_LEAST","unit":"分钟","input":"TIMER","rules":[{"from":"2026-10-05","days":[1,2,3,4,5,6,7],"target":20,"reminder":1260}],"archivedOn":null,"smoking":false,"visual":"READING"},"entries":{},"timer":null}
```

entries 按 yyyy-MM-dd 索引，值 `{habitId,date,value,note,recordedAt,remainderSeconds,sessions:[{start,end}]}`；毫秒Unix时间，时长总量分钟，余秒0–59。timer 为 null 或 `{at,day,id,device}`，id/device 为UUID，day归开始日期。

## 来源命令与稳定重试

`POST /21day-api/v1/habits/commands`

```json
{"operation":{"operationId":"每次用户动作新UUID，重试保持原值与全文","entityType":"habits","entityId":"该轮UUID","baseRevision":1,"schemaVersion":1,"data":{},"deleted":false,"baseline":{},"kind":"replace"},"action":"count","day":"2026-10-05","timeZoneId":"Asia/Shanghai","deviceId":"设备UUID"}
```

baseline 为操作前卡片完整 data；data 为克隆后按下表修改的完整对象。客户端先持久化该请求再发送，丢响应必须重放原请求，不能重新生成 operationId/时间/data。返回 `{operationId,status,revision,current}`；status=applied/conflict/deleted/invalid/unsupported。applied 的 current 是最新来源 data，应刷新卡片；conflict 保留请求和两版，禁止自动覆盖。

| action | kind | 允许变更 |
| --- | --- | --- |
| count | count | COUNT当日entry.value +1，recordedAt=当前毫秒；不存在则默认note="",sessions=[],remainderSeconds=0 |
| confirm | replace | DAILY当日value=1，戒烟每日零支则0；其他字段同上 |
| set_total | replace | 替换指定已到执行日value/note/recordedAt；总量变化时sessions=[]、remainderSeconds=0；不能累加 |
| timer_start | replace | TIMER设置timer={at,day,id:newUUID,device:deviceId} |
| timer_finish | timer_finish | 仅负责设备；timer=null，按开始day追加session，旧分钟×60+余秒+floor((end-start)/1000)，拆回分钟和余秒；保留原备注 |
| timer_cancel | replace | 仅负责设备，timer=null，不增加记录 |
| timer_takeover | timer_takeover | 保持活动timer的id/at/day，仅device替换为本设备；收到来源applied后才可结束/取消 |
| archive | replace | 无运行计时，plan.archivedOn=服务端所选时区今天，历史不删 |

归档、非执行日、未来日期不能打卡；21天周期和历史目标保留。不同字段三方合并；两次独立count累加；相同operationId只执行一次；同日总量更正冲突保留；两个设备同期开计时冲突保留。该接口不操作睡前验证、扫码/NFC或手机锁屏。

## 实施分工

本聊天负责 `E:/21day` 手机、21day服务、上述契约和APK。另一聊天「规划三端数据互通」负责时屿同步/展示/来源回写、island反向事项接口；本聊天负责knowledge v2和后端统一部署；用户已授权发消息协调。公共协议0.1.4的可选Baseline/Kind和ISyncPolicy向后兼容0.1.1，旧客户端无policy仍按旧revision规则。

请在总规划所在项目的 `docs/interop-client-contract.md` 记录island与knowledge供Android接入的确切路径/字段。不要改写本文件里的21day契约；变更先协调。

## 保护配置通道与部署

`GET /21day-api/v1/vault/status` 返回ready。GET/PUT `/vault/deepseek` 与 `/vault/verification-points`：PUT `{baseRevision,value}`，返回revision；冲突409。value分别为 `{key}`、`{qrToken,nfcId}`。只读挂载32字节主密钥文件，由 `Vault:MasterKeyFile` 指定，账户数据密钥采用AES-GCM信封加密；这不是端到端加密。普通同步和旧JSON备份不包含这些值。

公共包0.1.4新增已有回执的hash核对读取，来源命令先重放原回执，再验证新操作，因此原archive请求跨午夜可稳定重试。此前0.1.3已保持0.1.1七字段operation的旧hash兼容。

## 知识v2实际契约（2026-10-05）

独立工程 `D:/project/smes-knowledge-api`，独立knowledge_meta_db/knowledge_app与只读知识快照挂载，原 `/knowledge/v1/` 保留。用户改为设置中输入只读密钥；不实施管理员按邮箱授予首批权限。新Bearer增加knowledge audience与knowledge:sync服务准入，所有v2正文/检索/附件/批注额外检查 `X-Knowledge-Key`。只接受现有CanSync=false读密钥；写密钥不能在客户端使用。配置当前允许sMES7.0，检索先过滤版本/路径再读和排名。密钥轮换配置修改后重启v2服务，客户端不保存文档离线缓存。

- GET `/knowledge/v2/access` → `{grants:[{project,version,prefix}],snapshotId}`。
- POST `/search` `{project:"sMES",version:"7.0",question}` → 原KnowledgeSearchResult，含snapshotId、sources[{relativePath,heading,startLine,endLine,text,contentHash}]、noteCount/skippedCount/limited。
- GET `/read?project=sMES&version=7.0&snapshotId=...&path=...` → `{snapshotId,relativePath,text,contentHash,rawHash,current}`。路径均URL编码。
- POST `/verify` `{project,version,path,snapshotId,contentHash}` → `{unchanged}`。
- GET `/asset?project=...&version=...&snapshotId=...&documentPath=...&path=...`：先验证授权正文，附件必须被该正文Markdown相对链接引用；只允许图片/PDF，最大16MiB。
- GET `/notes` → `{notes:[{id,revision,data}]}`，仅当前账号，仍需当前知识密钥。
- PUT `/notes/{uuid}` `{baseRevision,project,version,path,snapshotId,contentHash,bookmark,note,sourceProject,sourceId}`。sourceProject可为21day/island/null，保存来源引用和个人批注，不修改知识原文。返回 `{id,revision}`；同样内容重试幂等，同版本不同修改409。撤销密钥后无法读取收藏、批注或原文。note最多5000字符。

身份/21day/knowledge-v2已部署；island来源摘要由协作聊天交付后统一部署为build0.1.1、共享包0.1.4。实际GET `/island-api/v1/items/cards`返回`{sourceProject:"island",cards:[{sourceId,sourceRevision,title,status,dueAt}]}`，只读投影当前账号schema1/island-desktop-v1的非删除事项；手机不向该接口写入。四服务生产合成探针和客户端验证结果见Debug，不能替代用户人工验收。
