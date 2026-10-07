# G0-M3：消费者契约核对与待反馈事项

更新：2026-10-07。对应 [STCN-31](https://hxj04121.atlassian.net/browse/STCN-31)。已补审 M1 PR #8，并对照 M2 PR #3 最新版本。M4 契约仍缺；下列意见尚未发表到提供方的契约 PR，因此 STCN-31 的“在契约 PR 提出缺口”验收项仍未满足。本文件随 M3 设计成果送评审，不代表契约冻结或人工验收完成。

## 审查范围与版本

- 已检查主分支：`56931b460029abe04ec3d61a35de106e36b6008e`；相比初稿依据 `6c757a6` 仅新增 SonarCloud 配置，AWS v3 架构与 G0-M3 工单未变。
- [M1 PR #8](https://github.com/hxj04121-lab/FoodLabelFlow-Microservices/pull/8) head：`a788fa5fc815c34cb23cf219d5194d92a51937bb`。审查 Specification/Formulation 两份 OpenAPI 的端点、请求、响应、错误和角色说明。
- [M2 PR #3](https://github.com/hxj04121-lab/FoodLabelFlow-Microservices/pull/3) head：`e89233a35da419977a98e9d8e3cb963f0ce3074e`。与 10 月 3 日已审 head `caa825f` 比较，`compliance.v1.yaml`、`compliance-types.v1.schema.json`、`impact-finding-payload.v1.schema.json` 三文件无差异，因此原字段核对仍适用。
- PR #3 和 PR #8 均依赖 [PR #2](https://github.com/hxj04121-lab/FoodLabelFlow-Microservices/pull/2)。当前均未合并；读取候选代码不等于集成，XFY 没有引入提供方契约或代码。
- M2 最新 PR 说明已将 AWS v3 作为当前架构依据，FINAL 保留为历史来源。M1/M2 的角色与 wire 决定仍需 M2/M4 对齐。
- 主分支尚无目标 `contracts/`；M1 目标契约现在已在 PR #8 提供，M4 Label Workflow OpenAPI、LabelPublished 与 realm/claims 仍待提供。现有 `frontend/src/api/labels.ts` 是单体基线，不是目标微服务 API。

## 页面需要的字段

下面“候选已覆盖”只代表该 PR 的结构/语义足以支持这一显示需求，不代表 merged、runtime pass 或 canonical envelope 已通过。

| 页面 | 必需显示 / 操作输入 | 现有材料与判断 |
|---|---|---|
| 登录与组织上下文 | `sub`、`org_id`、`org_type`、角色、公开 issuer/audience、组织显示名 | 架构指定前三者与角色；M4 需确定 claim 路径、literal role 和组织名获取方式 |
| 规格版本 / 发布 | 材料和供应商 ID/名称、版本 ID/号/状态、全部 component、ingredient、provenance、组织所有权 | PR #8 候选已覆盖版本详情与发布；`SpecificationVersion.materialId` → Material → Supplier 可取得名称；provenance 只有 ID，详情未提供 |
| 配方 adoption / 发布 | 产品 ID/名称、当前/候选 formula ID/号、所有 formula item 与 specificationVersion、来源/组织、并发版本 | PR #8 提供 `FormulaVersion.productId/items`、Product 的 `currentFormulaVersion` 和发布输入 `expectedCurrentFormulaVersionId`；显式创建/发布新版本，不自动改旧版本 |
| Impact inbox | `findingId`、`organisationId`、`kind`、`outcome`、`requiredAllergens`、`declaredAllergens`、`missingAllergens`、`unresolvedAllergens` | M2 payload required 完整覆盖；UNRESOLVED 为 component 证据，不编造未知 allergen ID |
| Impact 版本链接 | `specificationChange.previous/candidate`、`formulaVersion`、`labelVersion`、`ruleSetVersion` 中 `id/versionNumber`，`jurisdiction` | 候选已覆盖版本引用；详情链接必须使用各所有者的公开 API，而非 DB join |
| Impact 分页 / 筛选 | `kind`、`outcome`、`limit`、`cursor`、`items`、`nextCursor`、`organisationId` | M2 已覆盖；limit 1..100、默认50、nextCursor可空。排序时间字段未暴露，见 C-01 |
| Impact 人类可读名称 | 产品/材料/组织名，allergen code/displayName，版本详情和证据来源 | PR #8 可补齐产品、材料、供应商与 ingredient 名称；allergen 名称、provenance 详情和 unresolved component 对应关系仍待 C-02，不要求随意扩充 schema |
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

## PR #8：演示步骤 1 / 4 的具体调用

以下方法和字段来自候选 OpenAPI，尚未通过最终契约冻结。公开前缀与架构 §6.1 一致；内部部署地址仍未确定。

| 操作 | 候选调用与输入 | 消费者处理 |
|---|---|---|
| 创建规格 V2 | `POST /api/specifications/versions`：`materialId/effectiveDate/provenanceId/components`；每个 component 为 `ingredientId/rawPhrase/matchRule` | 版本号由服务分配；ingredient 必须来自词汇表；不传自造 versionNumber |
| 查看 / 发布规格 | `GET /api/specifications/versions/{specificationVersionId}`；`POST .../{specificationVersionId}/release`（未声明 request body） | 使用返回的 version ID 和状态；409 VERSION_IMMUTABLE 不显示重复成功 |
| 确认可采用规格 | `GET /api/formulations/released-specifications` | `receivedAt` 是投影接收时间，不是 inbox finding 时间；固定选择的规格 ID，不自动替换为最新版 |
| 创建新配方 | `POST /api/formulations/formula-versions`：`productId/provenanceId/items`；每项为 `materialId/specificationVersionId/quantity/unit` | 完整提交所有 item；quantity 和 unit 都必须存在，且同时有值或同时为 null；保留未变化 item 的版本 |
| 发布新配方 | 先 `GET /api/formulations/products/{productId}` 读取 `currentFormulaVersion.id`，再 `POST /api/formulations/formula-versions/{formulaVersionId}/release`，body 为 `expectedCurrentFormulaVersionId` | 无当前已发布配方时才传 null；409 CURRENT_FORMULA_CHANGED 后重新读取并确认，不能用新 pointer 自动重试覆盖用户未看过的版本 |
| 版本与产品详情 | `GET /api/formulations/formula-versions/{formulaVersionId}` → `productId` → `GET /api/formulations/products/{productId}`；追溯用 `GET .../{formulaVersionId}/trace` | 产品显示名使用 `productDescription`；trace 暴露各项 specificationVersion/materialId/supplierId，按可见权限获取对应名称 |

共同约束：required `X-Correlation-ID` 由 Gateway 产生/转发；分页 `limit` 为1..100、默认50，使用 opaque cursor；错误 envelope 复用 `code/message/traceId/evidenceId`。PR #8 的 `SPEC_AUTHOR/SPEC_RELEASER/FORMULA_AUTHOR/FORMULA_RELEASER` 是候选角色，须与 M4 realm 对齐。当前只有创建与发布操作，没有草稿更新端点；不能让 UI 的“保存编辑”暗示未提供的 PUT/PATCH。

## 可交给对应 PR 的意见（未发送）

| 编号 | 对象 / 精确位置 | 发现与需要的决定 | 对前端的影响 |
|---|---|---|---|
| C-01，采纳 M2 的 G0 展示建议 | M2 PR #3：`ImpactFindingPage` / `ImpactFinding` 与 listImpactFindings 描述 | 保持 opaque-cursor 分页及完整 finding payload，G0 界面展示现有证据/版本引用而不显示时间列。`items` 不是 ID 数组，显示 ID 只是一种界面呈现；不从 cursor、event occurredAt 或 projection receivedAt 推算 finding 时间。以后若需要时间列，须单独决定契约字段。 | 当前线框不显示时间，wire shape 保持不变 |
| C-02，部分解决 | M2 PR #3 ImpactFinding；M1 PR #8 FormulaVersion、Material、Supplier、Component | 已可经 formulaVersion → productId → Product 获取产品说明，经 specificationVersion → materialId → Material → Supplier 获取名称。仍缺 allergen 字典、provenance 详情入口；M2 unresolved 要求 formulaItemId 和 specComponentId，但 M1 public DTO 及发布事件的 item/component 只暴露 sequenceNo 等字段，没有这两个 ID。两种身份/lookup 映射均需 owner 明确约定；不能默认为 sequenceNo 就是任一 ID。界面可展示响应中的版本 ID，并按可见条目去重取详情，避免无界 fan-out。 | 产品/材料上下文可设计；allergen 名称与 formula item/component 的完整证据定位仍未关闭 |
| C-03 | M2 PR #3：`/api/compliance/**` paths；架构 §6.1 derived allergens | 候选 paths 只有 preview/findings 和 internal validations；架构还列了 derived allergens。请与 M4 明确 UI 通过 LW validation record 获取还是需要公开只读 derivation/rule-set/allergen 字典端点，并给出契约。 | 基线 `/api/v1/...` 不可直接照搬为 target；声明选择与派生证据尚缺确定入口 |
| C-04 | M4 待提供的 public label validation DTO；M2 ValidationRun | 基线前端使用 `jurisdictionCode`、`results`、createdByUserId；M2 internal 用 `jurisdiction`、`findings`、draftRevision，maker-checker 用 creator subject。请规定 LW public wire 和明确 adapter，包含完整 exact tuple。 | 不能直接复用基线类型，否则字段丢失、旧校验误显示有效或错误 maker-checker |
| C-05 | M4 Label Workflow 契约 | 请覆盖 ReviewTask 查询、草稿变更/校验/submit、三种 decision、publication/current label/history、creator subject、状态/权限与409语义。并说明因异步 finding 到达而任务尚未就绪时的读取结果。 | G2 实际写入与 G3 inbox → task 跳转的阻塞项 |
| C-06，候选已覆盖 | M1 PR #8 Specification/Formulation 契约 | 已提供版本详情、显式 create/release、每项规格引用和 CURRENT_FORMULA_CHANGED。上表记录 exact request 与 hydration 路径。仍待合并/角色对齐，跨组织状态码见 C-08。 | demo steps1/4 的接口设计可继续，不再列为“未提供 M1 契约” |
| C-07 | M4 realm/claims + M5 public hostname | 请提供 `spectrace-web`、PKCE 回调/Pages base path、公开 issuer/audience、role claim 路径和角色码、组织显示名来源。 | Gateway/React 登录可以先写测试骨架，完整端到端验收仍依赖这些值 |
| C-08，新发现 | M1 PR #8 Formulation 所有权描述 / ResourceNotFound；架构 §15 | Formulation 对其他组织的资源返回404以隐藏存在性；架构 tenancy 验收写跨组织403，M2 finding详情也写403。请 M1/M2 确认是统一403，还是用 ADR/架构更新记录有意404策略；M3 不在 Gateway 擅自重写。 | 负向 E2E 的断言和错误文案需要明确；404显示“资源不存在或不可访问”，403显示权限不足，二者都不展示资源内容 |

### 建议给 M2 的评审文本

> M3 consumer review against PR #3 at e89233a and M1 PR #8 at a788fa5: the three previously reviewed M2 HTTP/payload/types files are unchanged from caa825f. Core inbox evidence and exact validation snapshots remain covered. M1 supplies version and product/material/supplier lookups, covering that part of C-02/C-06. G0 follows M2's opaque-cursor recommendation: keep full finding payloads, display current evidence/version references and omit the timestamp column; UI ID display does not change items to an ID array. A later finding-time field remains a separate contract decision, never inferred from cursor internals, event occurredAt or projection receivedAt. Remaining decisions are allergen/provenance hydration, both formulaItemId and specComponentId mappings, the derived-allergen read surface or LW public validation record, and M1's cross-organisation 404 versus architecture/M2 403. The browser will not call /internal/validations. M4 contracts and claims remain pending; this is partial consumer review, not v1 freeze.

### 建议给 M4/M1 的交接文本

> The seven-step UI walkthrough is ready for review. PR #8 covers the Specification/Formulation version reads, create/release path and expected-current-pointer check needed by steps 1/4. M1/M2 should clarify the cross-organisation 404/403 policy and the public component evidence mapping. M4 still needs to provide Label Workflow public validation/review/decision/publication responses and realm claims. Baseline jurisdictionCode/results/createdByUserId must not be silently treated as target jurisdiction/findings/creator subject. Candidate role names remain subject to M4 alignment.

## 关闭 STCN-31 之前

M2 已在 [PR #15 的技术评审](https://github.com/hxj04121-lab/FoodLabelFlow-Microservices/pull/15#issuecomment-6034570932)、[PR #3 的提供方回复](https://github.com/hxj04121-lab/FoodLabelFlow-Microservices/pull/3#issuecomment-6034527151) 和 [PR #8 的交接](https://github.com/hxj04121-lab/FoodLabelFlow-Microservices/pull/8#issuecomment-6034521572) 记录上述展示建议和未决身份映射。本文件已据此修正 C-01/C-02；这些是 M2 技术结论，不能替代 M4 人工验收，M3 尚未在提供方 PR 直接发表自己的消费者评审。

1. 等待并评审 M4 的目标契约；跟进 M1/M2 候选合并与 C-02/C-08 决定，更新页面字段映射。
2. 用户授权外部协作后，把相关意见发表到实际 contract PR，保存 permalink；当前文件不是已发表的评论。
3. 由 owner 与 M2 对字段/端点决定达成一致；有契约变更则 M2 批准 + ADR。
4. M4 人工验收并记录结果；不因本地核对完成就声称契约冻结或 Jira Done。
