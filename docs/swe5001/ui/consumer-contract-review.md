# G0-M3：消费者契约核对与待反馈事项

日期：2026-10-03。对应 [STCN-31](https://hxj04121.atlassian.net/browse/STCN-31)。完成现有材料的本地消费者评审；目标契约不齐且本次仅本地工作，以下意见尚未发布到 PR，因此 STCN-31 的“在契约 PR 提出缺口”验收项仍未满足。

## 审查范围与版本

- 主分支来源：`6c757a62c04486ca9074714567930b028f9ee238`，AWS v3 架构与 G0-M3 工单。
- [M2 PR #3](https://github.com/hxj04121-lab/FoodLabelFlow-Microservices/pull/3) head：`caa825f06f6c4ba8f9c31b816250c2c4ef6ed854`（2026-10-03 读取）。审查 `contracts/openapi/compliance.v1.yaml`、共享 types、ImpactFinding payload schema 及 source manifest。
- PR #3 含 [PR #2](https://github.com/hxj04121-lab/FoodLabelFlow-Microservices/pull/2) 的 ImpactFinding schema 作为 stack prerequisite。未将 PR 分支合并到 XFY，也未复制/修改它们的契约。
- M2 manifest 使用前一版 FINAL 输入。当前架构改为 AWS v3，但相关业务规则与两阶段语义保持不变；本记录不把旧部署输入当当前环境。
- 主分支未提供目标 `contracts/`；M1 Specification/Formulation 和 M4 Label Workflow OpenAPI、realm/claims 契约待提供。现有 `frontend/src/api/labels.ts` 是单体基线，不是目标微服务 API。

## 页面需要的字段

下面“候选已覆盖”只代表该 PR 的结构/语义足以支持这一显示需求，不代表 merged、runtime pass 或 canonical envelope 已通过。

| 页面 | 必需显示 / 操作输入 | 现有材料与判断 |
|---|---|---|
| 登录与组织上下文 | `sub`、`org_id`、`org_type`、角色、公开 issuer/audience、组织显示名 | 架构指定前三者与角色；M4 需确定 claim 路径、literal role 和组织名获取方式 |
| 规格版本 / 发布 | 材料和供应商 ID/名称、版本 ID/号/状态、全部 component、ingredient、provenance、组织所有权 | M1 目标 DTO 待提供；不能假定单体 `/api/catalog` 可直接迁移 |
| 配方 adoption / 发布 | 产品 ID/名称、当前/候选 formula ID/号、所有 formula item 与 specificationVersion、来源/组织、并发版本 | M1 契约待提供；前端显式创建/发布新版本，不自动改旧版本 |
| Impact inbox | `findingId`、`organisationId`、`kind`、`outcome`、`requiredAllergens`、`declaredAllergens`、`missingAllergens`、`unresolvedAllergens` | M2 payload required 完整覆盖；UNRESOLVED 为 component 证据，不编造未知 allergen ID |
| Impact 版本链接 | `specificationChange.previous/candidate`、`formulaVersion`、`labelVersion`、`ruleSetVersion` 中 `id/versionNumber`，`jurisdiction` | 候选已覆盖版本引用；详情链接必须使用各所有者的公开 API，而非 DB join |
| Impact 分页 / 筛选 | `kind`、`outcome`、`limit`、`cursor`、`items`、`nextCursor`、`organisationId` | M2 已覆盖；limit 1..100、默认50、nextCursor可空。排序时间字段未暴露，见 C-01 |
| Impact 人类可读名称 | 产品/材料/组织名，allergen code/displayName，版本详情和证据来源 | payload 仅 IDs；需约定 owner API hydration 或读模型，见 C-02，不要求随意扩充 schema |
| 草稿 / 校验 | label ID/号、creator subject、formula/rule-set/jurisdiction、declarations、draftRevision、当前生命周期 | M4 public DTO 待提供；必须可判断 exact snapshot 与修改后的旧校验失效 |
| 校验结果 | `validationRunId`、`organisationId`、`correlationId`、完整 snapshot 元组、`status`、`ranAt`、`findings`（code/severity/passed/blocking/message） | M2 internal response 已覆盖；M4 需要公开本地 validation record 给 UI，不直连 internal |
| 审核 / decision | ReviewTask ID/状态、label version、creator subject、decision actor/time/reason、当前允许动作、并发修订 | M4 待提供；BR-06 比 JWT subject，不能用显示名/邮箱判定 |
| 发布 / 当前标签 | lifecycle APPROVED/PUBLISHED、publication ID/时间/actor、产品+jurisdiction、current flag、历史版本/替代状态 | M4 待提供；LW 是 current label 权威，不读 Formulation 的旧 product 指针 |
| 通用错误 | `code/message/traceId/evidenceId` 和响应 `X-Correlation-ID` | M2 ApiError 与基线兼容，后两项 nullable；M1/M4 尚需统一 |

## 已核对的候选行为

- POTENTIAL 与 CONFIRMED 显式区分；仅 CONFIRMED + REVIEW_REQUIRED 建 ReviewTask。
- NO_ACTION 强制 missing/unresolved 为空；REVIEW_REQUIRED 必须有缺失或未解析证据。
- `POST /internal/validations` 要求 exact draft snapshot 与 `Idempotency-Key=labelVersionId:draftRevision`；重复同 key/snapshot 返回存储结果，变更 snapshot 返回409；前端不调用该端点。
- ValidationRun 的 PASSED 无 blocking finding，FAILED 至少一个 blocking；完整版本元组可供 LW 保存并展示。
- `GET /api/compliance/impact-findings/{findingId}` 要求组织所有权；跨制造商403；分页从 JWT 推导 tenant。
- missing projections 返回422，不把未加载 label declarations 当空；429/503 和错误关联头可用于 UI 恢复。

## 可交给对应 PR 的意见（未发送）

| 编号 | 对象 / 精确位置 | 发现与需要的决定 | 对前端的影响 |
|---|---|---|---|
| C-01 | M2 PR #3：`ImpactFindingPage` / `ImpactFinding` 与 listImpactFindings 描述 | 描述按 creation time + findingId 排序，但 response 没有 createdAt。请确定是 opaque cursor 足够且 UI 不显示时间，还是加入明确时间字段；不用暴露 cursor 内部结构。 | 当前线框不显示伪造时间；若需“最新影响”时间列则待定 |
| C-02 | M2 PR #3：ImpactFinding 的版本/allergen ID 字段；M1 版本详情契约 | schema 无 productId、材料/产品名、allergen displayName 或 provenance 详情。请约定 ID → owner 公开详情的映射/列表读模型，避免每行无限请求及引用无法解析；也可明确先显示 ID。 | G3 展示可读证据和产品上下文之前需要契约支持；不擅自添加 schema 字段 |
| C-03 | M2 PR #3：`/api/compliance/**` paths；架构 §6.1 derived allergens | 候选 paths 只有 preview/findings 和 internal validations；架构还列了 derived allergens。请与 M4 明确 UI 通过 LW validation record 获取还是需要公开只读 derivation/rule-set/allergen 字典端点，并给出契约。 | 基线 `/api/v1/...` 不可直接照搬为 target；声明选择与派生证据尚缺确定入口 |
| C-04 | M4 待提供的 public label validation DTO；M2 ValidationRun | 基线前端使用 `jurisdictionCode`、`results`、createdByUserId；M2 internal 用 `jurisdiction`、`findings`、draftRevision，maker-checker 用 creator subject。请规定 LW public wire 和明确 adapter，包含完整 exact tuple。 | 不能直接复用基线类型，否则字段丢失、旧校验误显示有效或错误 maker-checker |
| C-05 | M4 Label Workflow 契约 | 请覆盖 ReviewTask 查询、草稿变更/校验/submit、三种 decision、publication/current label/history、creator subject、状态/权限与409语义。并说明因异步 finding 到达而任务尚未就绪时的读取结果。 | G2 实际写入与 G3 inbox → task 跳转的阻塞项 |
| C-06 | M1 Specification/Formulation 契约 | 请提供发布版本详情和显式 adoption/create/release 操作、所有 item 的版本引用、组织可见性与冲突语义；确认公共路径保留架构 prefixes。 | demo steps1/4 与 C-02 hydration 的阻塞项 |
| C-07 | M4 realm/claims + M5 public hostname | 请提供 `spectrace-web`、PKCE 回调/Pages base path、公开 issuer/audience、role claim 路径和角色码、组织显示名来源。 | Gateway/React 登录可以先写测试骨架，完整端到端验收仍依赖这些值 |

### 建议给 M2 的评审文本

> M3 consumer review against PR #3 at caa825f: kind/outcome, the four allergen evidence sets, exact version references, tenant-scoped paging, canonical errors and validation snapshot fields support the planned inbox/validation views. Three decisions remain before consumer closure: (1) the list promises creation-time ordering but exposes no timestamp; confirm an ID-only list or define a display timestamp; (2) specify public hydration for version/allergen IDs and product/material/provenance context; (3) reconcile the architecture's derived-allergen read surface with the paths supplied, or document the LW-owned public validation-record alternative. The browser will never call /internal/validations. M1/M4 public contracts and claims are still pending, so this is partial review, not v1 freeze or completed acceptance.

### 建议给 M4/M1 的交接文本

> The seven-step UI walkthrough is ready for review. Please publish the public Specification/Formulation and Label Workflow contracts, with version-detail reads, explicit adoption, exact validation tuple/draftRevision, creator JWT subject, decision/current-publication responses and permission/conflict semantics. Baseline jurisdictionCode/results/createdByUserId must not be silently treated as target jurisdiction/findings/created_by_subject. UI field requirements and open decisions C-02..C-07 are recorded here; no new endpoint or role is considered accepted by this draft.

## 关闭 STCN-31 之前

1. 等待并评审 M1/M4 的目标契约，补充上述页面字段映射。
2. 用户授权外部协作后，把相关意见发表到实际 contract PR，保存 permalink；当前文件不是已发表的评论。
3. 由 owner 与 M2 对字段/端点决定达成一致；有契约变更则 M2 批准 + ADR。
4. M4 人工验收并记录结果；不因本地核对完成就声称契约冻结或 Jira Done。
