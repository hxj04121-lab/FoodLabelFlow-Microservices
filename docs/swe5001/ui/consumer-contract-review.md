# G0-M3：消费者契约核对

更新：2026-10-11。对应 [STCN-31](https://hxj04121.atlassian.net/browse/STCN-31)，负责人 M3 Xu Feiyang，指定评审 M4 Zhu Wenyu。本次完成 G0 消费者审查与缺口登记；运行实现和人工验收另行记录。

## 审查依据

基准为已合并 main `f08fd23a3f7dd21a5e435c872c3c0d71a53b64da`（[集成契约 PR #21](https://github.com/hxj04121-lab/FoodLabelFlow-Microservices/pull/21)），覆盖 `contracts/openapi/` 中 Specification、Formulation、Compliance、Label Workflow 四份 OpenAPI，以及发布事件、canonical envelope 和 `docs/swe5001/security/keycloak-realm-design.md`。旧 PR #8/#3 的候选缺口以本次合并版本为准；M4 契约现已提供。

[M1 的 G0 收尾决定](https://github.com/hxj04121-lab/FoodLabelFlow-Microservices/pull/15#issuecomment-6106565508)明确：C-02 稳定 ID 和 C-08 跨组织状态码已解决；公开读取接口、LW adapter 和角色映射移交 G1/STCN-51，不再作为 G0 文档交付的未完成实现项。该决定不等同于 M4 对本 PR 的人工验收。

## 页面与合并契约的字段映射

| 页面 / 演示步骤 | 已提供的公开接口与字段 | 消费者约束 / 后续缺口 |
|---|---|---|
| 登录 | realm 设计指定 spectrace-web public client、PKCE S256，顶层 sub/org_id/org_type/roles | 这是设计，非部署证明。SUPPLIER/MANUFACTURER 是组织类型；业务角色、issuer/audience、实际回调需部署对齐 |
| 规格创建 / 发布（1） | POST /api/specifications/versions，输入 materialId/effectiveDate/provenanceId/components；GET /versions/{specificationVersionId}；POST /versions/{specificationVersionId}/release | 服务分配版本号；已发布版本只读。经 Material → Supplier 取名称；不得用 sequenceNo 替代 specComponentId |
| 事件传播（2） | SpecificationPublished 及 canonical envelope | 浏览器不连 RabbitMQ；日志/看板展示事件与关联 ID；等待投影不假定即时成功 |
| Impact inbox（3、5） | GET /api/compliance/impact-findings、/{findingId}；完整 items payload 含 kind/outcome、版本引用、required/declared/missing/unresolvedAllergens | opaque cursor；limit 1..100，默认50；无 finding 时间列，不从 event occurredAt 或 projection receivedAt 推算 |
| 配方 adoption / 发布（4） | released-specifications、formula-versions、products 和 trace；创建输入 productId/provenanceId/items；release 输入 expectedCurrentFormulaVersionId | 所有 item 完整提交，quantity/unit 同时有值或同时 null；从 Product.currentFormulaVersion.id 读取预期指针，409 CURRENT_FORMULA_CHANGED 后重新确认，不自动覆盖 |
| ReviewTask（5） | CONFIRMED + REVIEW_REQUIRED 由 LW 消费事件建任务 | finding → ReviewTask 查询契约仍缺；只展示等待设计，不伪造公开查询端点或由浏览器创建任务 |
| 标签草稿（6） | POST /api/labels/drafts、GET /api/labels/{labelVersionId}、GET .../declarations；LabelDraft 含 labelVersionId/productId/formulaVersionId/ruleSetVersionId/jurisdictionCode/versionNumber/lifecycleStatus | public DTO 未暴露 creatorSubject/draftRevision/allowedActions；无声明编辑端点。线框编辑和按钮状态是设计要求，不表示接口已具备 |
| 标签校验 / submit（6） | POST .../validations 返回 validationRunId、labelVersionId、formulaVersionId、ruleSetVersionId、jurisdictionCode、status、findings；POST .../submit | LW 保存 exact snapshot，private draftRevision 不等于 versionNumber；public jurisdictionCode → internal jurisdiction 的 adapter 已有说明，仍缺存储校验记录公开读取；浏览器不调用 internal validations |
| 审批 / 发布（6） | GET/POST .../decisions；decisionId/labelVersionId/decision/decidedAt/comments；POST .../publish 返回 publicationRecordId/labelVersionId/lifecycleStatus/publishedAt | 不从这些 DTO 推断 creator/actor 或允许动作。maker-checker 和 exact validation 由服务执行；APPROVED 后才发布 |
| 发布回流（7） | LabelPublished 事件；LW 是当前标签权威 | current label/history 的公开读取待补充；不把发布响应当完整历史，不改写旧 finding，投影后单独重新评估 |
| 通用错误 | code/message/traceId/evidenceId、X-Correlation-ID；跨组织403，不存在404 | 都不泄露资源内容；422 缺投影不能解释为空声明；429/503 不伪装业务成功 |

## 缺口处理结果

| 编号 | 本次结论 | 所属阶段 |
|---|---|---|
| C-01 | 采用 M2 建议：完整 finding payload + opaque cursor，G0 无时间列；未来时间字段需独立决定 | G0 展示决定已落地 |
| C-02 | formulaItemId/specComponentId 已在 M1 public DTO、事件与 trace 对齐；名称可经 owner API hydration。allergen 字典和 provenance 详情不能凭空推断 | 稳定 ID 已解决；剩余公开读面随 G1 对齐 |
| C-03 | derived allergens / 字典 / 存储校验记录的浏览器读面尚未完整提供；严禁经 Gateway 暴露内部校验 | M1 明确移交 G1/STCN-51 |
| C-04 | M4 public validation 使用 jurisdictionCode/findings；内部为 jurisdiction/findings。服务拥有 private draftRevision 和 snapshot；不得复用基线 results/createdByUserId 或把 versionNumber 当 revision | 契约已审；adapter/read surface 联调移交 G1 |
| C-05 | 草稿创建、读取、校验、submit、三种 decision、publish 已提供；ReviewTask lookup、声明编辑、current/history、creator/allowedActions 尚缺 | 已登记供后续 UI 接入规划；G0 不实现或自造端点 |
| C-06 | Specification/Formulation 的 GET、create/release、并发指针检查已合并 | G0 消费者审查完成 |
| C-07 | M4 realm 设计已提供；SPEC_AUTHOR/SPEC_RELEASER/FORMULA_AUTHOR/FORMULA_RELEASER 与实际 realm 的对应待决定；部署域名/回调待 M4/M5 | role mapping 按 M1 决定移交 G1 |
| C-08 | main 已统一跨组织403、资源不存在404；Gateway 不重写所有权错误 | 已解决 |

## 评审证据与验收边界

既有提供方讨论：[M2 的 PR #3 回复](https://github.com/hxj04121-lab/FoodLabelFlow-Microservices/pull/3#issuecomment-6034527151)、[PR #8 的 ID/403 交接](https://github.com/hxj04121-lab/FoodLabelFlow-Microservices/pull/8#issuecomment-6034521572)。[M3 本次消费者评审](https://github.com/hxj04121-lab/FoodLabelFlow-Microservices/pull/21#issuecomment-6106872148)已发表到实际集成契约 PR #21，记录四份 API 核对结果、已解决项及剩余字段缺口。原 G0 PR #15 已由 M1 合并，本次为文档收尾补充。

七步线框与验证记录见 [README](README.md)，路由/CORS 与 G1 交接见 [gateway-routes.md](../gateway-routes.md)。本交付仅 G0 的 STCN-30/31/32；M4 人工验收仍需独立记录，不把本地验证或 M1 排期决定写成 Jira Done。
