# G0-M3：前端流程与线框图

更新：2026-10-11。负责人：M3 Xu Feiyang；评审：M4 Zhu Wenyu。
对应 [STCN-30](https://hxj04121.atlassian.net/browse/STCN-30)。G0 设计成果，待团队评审；不代表运行系统或 Jira 人工验收完成。

## 查看成果

打开 [wireframes.html](wireframes.html)，或在仓库根目录运行：

```powershell
python -m http.server 8765 --bind 127.0.0.1 --directory docs/swe5001
```

浏览器访问 `http://127.0.0.1:8765/ui/wireframes.html`。页面无外部依赖、无网络业务请求；链接只跳转到另一个线框。顶部导航和原生折叠区支持键盘，窄屏采用单列布局，可打印全部画面。示例 ID、人员和过敏原是设计样例，不是正式种子数据或实测结果。

相关交付：[消费者契约核对](consumer-contract-review.md)、[Gateway 路由与 CORS 草案](../gateway-routes.md)。保留现有 React Shell 的目录、配方、标签、影响和审核导航概念；业务界面沿用英文。

视觉继承现有 `Segoe UI` / `Microsoft YaHei` 字体、浅色中性表面和紫色主要操作，并保留可见键盘焦点。简化侧栏和 700px 以下单列是本线框稿的展示约定；生产 Shell 未改动。现有 Shell 的 `TEAM 16 · SWE5006` 字样属于基线遗留，后续 G1 整合时再处理。

## 来源与版本

| 来源 | 使用内容 |
|---|---|
| [AWS proposal](../proposal/01_SpecTrace-CN_SWE5001_Project_Proposal_team16_AWS.docx) §6.7 | 七步演示的原始顺序 |
| [架构 v3](../architecture/SpecTrace-CN_Architecture_Source_of_Truth_v3_AWS.md) §5、§6、§11 | BR-01..11、公开路由、内部校验、OIDC |
| [G0-M3 工单](../../../.project-control/work-orders/G0/M3-ui-flows-gateway-routes.yaml) | 流程、消费者评审、路由草案的范围和验收 |
| `frontend/src/app/Shell.tsx`、`frontend/src/api/labels.ts` | 现有导航与基线 DTO；不当作目标契约 |

本次依据已合并 main `f08fd23a3f7dd21a5e435c872c3c0d71a53b64da`（PR #21）复核四份 OpenAPI、发布事件和 M4 realm 设计。角色映射与剩余公开读面按 [M1 决定](https://github.com/hxj04121-lab/FoodLabelFlow-Microservices/pull/15#issuecomment-6106565508) 移交 G1。

## 登录前置流程

未登录 → 前端发起 Keycloak authorization code + PKCE → 校验 state/nonce、换取 token → 读取 `sub`、`org_id`、`org_type` 和角色 → 展示当前组织 → 经 Gateway 调用公开 API。

- token、code、PKCE verifier 不写入日志或 URL 持久书签；G1 优先在内存保留 token。会话到期重新登录。
- 401 提示重新登录；403 保留禁止访问的解释，不通过切换本地身份绕过。
- 切换演示用户需要真实登出/重新登录；线框中的角色切换链接只是展示不同画面。
- 角色控制按钮是用户体验；服务仍验证 JWT、组织所有权、版本和状态。

## 七步演示映射

接口列对照 main 已合并契约；尚缺的读取入口显式标注，线框不代表运行实现。

| 步骤 | proposal §6.7 内容 | 线框锚点 / 操作者 | 屏幕或 API / 系统观察点 | 下一步与约束 |
|---|---|---|---|---|
| 1 | 供应商发布 soy-lecithin Specification V2 | `#step-1` / 供应商 | `POST /api/specifications/versions` 创建；`POST /api/specifications/versions/{specificationVersionId}/release` 发布（已合并） | V1 只读，另建 V2；服务分配版本号，仅本组织可发布 |
| 2 | SpecificationPublished 传播 | `#step-2` / 系统，由演示人员观察 | 展示事件证据与关联 ID；从日志/看板核对 outbox → RabbitMQ → 投影 | 浏览器不直连 broker，不引入中心审计 API；投影延迟显示等待 |
| 3 | 匹配使用 V1 的配方，显示 POTENTIAL | `#step-3` / 制造商 | `GET /api/compliance/impact-findings` 和 `GET /api/compliance/impact-findings/{findingId}` | 显示 required/declared/missing/unresolved；不自动改配方，不创建 ReviewTask |
| 4 | 制造商发布采用 V2 的配方 | `#step-4` / 制造商 | `POST /api/formulations/formula-versions` 创建；`POST /api/formulations/formula-versions/{formulaVersionId}/release` 发布（已合并） | 完整保留其他 item；发布 body 带读取到的 expectedCurrentFormulaVersionId；冲突后重新确认 |
| 5 | CONFIRMED + REVIEW_REQUIRED 打开审核任务 | `#step-5` / 制造商 | Compliance finding 详情 + `/api/labels/**` 下的 ReviewTask 查询，待 M4 契约 | 由 Label Workflow 消费事件建任务；到达前显示等待，不在前端伪造任务 |
| 6 | 草稿、校验、提交、maker-checker 审批、原子发布 | `#step-6`、`#reviewer`、`#publication` / 标签编辑者、不同审批人、发布者 | `POST /api/labels/drafts`；`POST /{labelVersionId}/validations`、`/submit`、`/decisions`、`/publish`（suffix 均在 /api/labels 下）；LW 内部调用 validations | 精确版本元组 PASSED 才提交；创建者不能批准自己；APPROVED 才发布 |
| 7 | LabelPublished 更新 Compliance 投影 | `#step-7` / 系统，由演示人员观察 | 当前标签由 LW 读取；事件与关联 ID 在日志/看板取证，随后刷新 finding | 历史 finding 不改写；投影就绪后重新评估，不能声称原 finding 被即时清除 |

## 屏幕状态与业务保护

| 屏幕 | 必须包含的状态 | 可恢复方式 |
|---|---|---|
| 登录 | 登录前、回调失败、缺失组织 claim、会话到期 | 重新登录；缺 claim 由 M4 修复，不默认为任意组织 |
| 规格 / 配方 | 列表、版本详情、编辑、发布确认、已发布只读、权限不足、并发冲突 | 保留输入；409 刷新服务状态后重新核对；不覆盖已发布版本 |
| Impact inbox | 加载、空列表、POTENTIAL、CONFIRMED、NO_ACTION、REVIEW_REQUIRED、422 投影未就绪、403 | 空列表不冒充 NO_ACTION；手动刷新；分页保留筛选并使用 nextCursor |
| 草稿 / 校验 | 未校验、PASSED、FAILED、过敏原未解析、草稿修改后校验失效、503 | 修改后重新校验；无精确 PASSED 记录始终不能提交 |
| 审核 | 待审核、自己的草稿不能批准、其他审批者、要求修改、拒绝、状态冲突 | 展示理由和历史；退回后按 M4 状态机重新编辑/校验；拒绝版本不可发布 |
| 发布 | APPROVED、确认发布、发布成功、已被替代、拒绝/未批准 | 读取当前标签及历史；仅服务端发布成功后显示成功 |
| 事件观察 | 等待、已消费、处理失败 / DLQ | 保持旧的有效版本，展示延迟；团队从日志/看板排查，不把缺数据当空集合 |

错误展示保留 `code`、`message`、`traceId`、`evidenceId`（后两项可为空）；响应关联 ID 单独显示用于支持排查。429 按服务返回的 Retry-After（若有）等待，避免自动重放写操作。

main 已统一跨组织403、不存在404；两者都不展示目标内容。配方每项 quantity/unit 必须同时有值或同时为 null。公开契约未提供草稿 PUT/PATCH、声明编辑、ReviewTask lookup、current/history；这些画面是后续设计要求，不表示现有接口可直接实现。详细字段边界见消费者核对表。

## 线框走查脚本

这是设计验收脚本；运行记录在本地检查后填写，不表示真实环境 E2E。

1. 从登录线框进入供应商画面，确认组织上下文可见且发布 V1 没有编辑入口。
2. 发布 V2 的线框链接进入事件画面：只观察传播，不出现“配方已自动更新”。
3. 打开 POTENTIAL inbox，核对完整过敏原证据；确认只提供显式 adoption 导航，没有提前创建审核任务。
4. 查看配方 adoption，确认两项物料都保留版本引用，并显式发布新版本。
5. 查看 CONFIRMED finding 和审核任务等待/就绪状态；仅 REVIEW_REQUIRED 进入审核。
6. 检查 exact validation tuple、修改后必须重校验、创建者批准禁用、不同审批人的三个决策，以及 APPROVED 后发布。
7. 查看当前标签与事件回流画面：保留历史 finding、等待投影；重新评估结果单独展示。
8. 展开空列表、UNRESOLVED、401/403、422/503 和冲突说明，确认无失败伪成功。
9. 在窄屏、键盘导航和打印预览检查信息顺序、表格滚动及标题定位。

## 本次本地验证记录

2026-10-03，XFY 分支：

| 检查 | 结果 / 范围 |
|---|---|
| 来源核对 | 已从 AWS proposal DOCX 读取 §6.7；七步顺序与架构 v3 对齐 |
| 静态结构 | 22 个 HTML ID 无重复；38 个本地链接/锚点存在；七个演示 section 顺序正确；无 script 或外部资源 |
| 路由表 | 六组公开 prefix 与架构 §6.1 一致；internal validations 未列为公网路由 |
| 浏览器走查 | 从登录到七步演示、审批与发布的九个链接均跳转到预期锚点 |
| 保护状态 | 创建者批准按钮 disabled；校验失败、要求修改、拒绝的原生折叠说明可以展开 |
| 响应式 | 宽屏与约 390 CSS px 窄屏检查；窄屏栏位改为单列，无页面级水平溢出 |
| 视觉检查 | 宽/窄屏入口画面已截图检查；其余画面以 DOM、链接走查和源代码核对为证。检测器无机械问题；独立设计审查结论为适合本地 G0 评审 |
| 尚未验证 | 真实 Keycloak/API/事件、staging、人工验收、打印输出、完整键盘操作，以及所有后续画面的逐屏截图验收 |

这些历史检查验证设计稿的完整性与可浏览性，不替代运行测试。

## 2026-10-11 G0 收尾

| 子任务 | 本次交付 | 验收边界 |
|---|---|---|
| STCN-30 | 七步线框、屏幕状态和 API 映射；更新 M4 已合并端点与403说明 | 待 M4 人工走查验收 |
| STCN-31 | 四份合并 OpenAPI 的页面字段核对、C-01..08 结论和契约 PR 反馈 | 不代表后续公开读面或角色联调完成 |
| STCN-32 | 六组架构 prefix、CORS origin/headers 与内部路径边界草案 | G0 是设计交付，网关实现不在本次提交 |

本次 Node 24 下 `npm ci --ignore-scripts`、`npm run check:integration` 通过（M1/M2/M4、canonical envelope、UNRESOLVED source、HTTP compatibility）。安装报告现有依赖有9项审计告警（3 moderate、6 high）；本次文档提交未改依赖，不宣称已修复这些告警。本次静态检查通过：22个唯一 ID、31个 HTML 链接、七步顺序正确且无 script。浏览器九个演示链接均跳转正确；创建者审批按钮保持禁用。六组路由与架构一致，git diff --check 通过。本次未重做窄屏/打印或真实服务测试。

M1 已把公开读取接口、adapter、角色映射移交 G1/STCN-51；此处仅记录交接，不进行 G1 实现。M4/M5 的部署域名、issuer/audience、回调与正式人工验收仍需各负责人记录。未修改 Jira 状态。
