# G0-M3：Gateway 路由与 CORS 草案

更新：2026-10-11。对应 [STCN-32](https://hxj04121.atlassian.net/browse/STCN-32)，M3 起草、M4 评审。依据 [架构 v3 §6.1、§6.2、§11](architecture/SpecTrace-CN_Architecture_Source_of_Truth_v3_AWS.md)，对照 main `f08fd23a3f7dd21a5e435c872c3c0d71a53b64da` 的四份已合并 OpenAPI。

这是 G0 可评审设计，不是 Spring Cloud Gateway 的运行配置；G1 再落地并测试。实际 service DNS、端口、OIDC realm、域名和角色码待 M4/M5 确认。

## 北南路由

| 路由 ID | 外部路径 | 目标 | 认证与角色 | 路径转换 / 边界 |
|---|---|---|---|---|
| specification | `/api/specifications/**` | Specification | JWT；供应商写自己的规格，制造商按公开可见性读发布版本 | 原样转发；已合并契约提供 GET/POST，见下表 |
| formulation | `/api/formulations/**` | Formulation | JWT；制造商本组织读写 | 原样转发；不接受浏览器指定组织绕过 token |
| compliance | `/api/compliance/**` | Compliance | JWT；制造商 impact 读/preview，具体角色待 realm 契约 | 原样转发；不能暴露内部 validations |
| label-workflow | `/api/labels/**` | Label Workflow | JWT；按草稿、校验、审核、发布角色分开授权 | 原样转发；服务重查 BR-05/06/08/11 |
| notification | `/api/notifications/**` | Notification | JWT；本组织订阅，Should | 默认不配置；服务及契约就绪后启用 |
| keycloak | `/auth/**` | Keycloak | OIDC 自身协议控制；用户登录、token、JWKS 等不是要求预先业务 JWT 的路由 | M4 将 Keycloak 配为 `/auth` 相对路径时原样转发；否则显式约定 rewrite，并验证公开 issuer 与 discovery 一致 |

规格和配方端点不能沿用单体 `/api/catalog/**`；Compliance 也不能把基线 `/api/v1/**` 自动当目标路径。未配置路由返回明确 404，不设置转发到任意服务的 catch-all。

### 已核对的 M1 端点

| 路由 | 方法与 suffix（相对于上表 prefix） | 备注 |
|---|---|---|
| specification | GET `caller-claims` | G1-M4 PR47 `0882375`：`/api/specifications/caller-claims` 返回 `{subject,org_id,org_type,roles}`；现有路由原样转发，无 rewrite；前端核对服务实际解析的身份。空白 sub 与未知 org_type 均拒绝为401，[最新真实 Specification 联调证据](evidence/G1-M3-readiness-20261013.md)已通过；合并与部署验收仍待 owner |
| specification | GET `suppliers`、`suppliers/{supplierId}`、`materials`、`materials/{materialId}`、`ingredients`、`versions`、`versions/{specificationVersionId}`；POST `materials`、`versions`、`versions/{specificationVersionId}/release` | 候选写角色 SPEC_AUTHOR / SPEC_RELEASER；尚需 M4 realm 对齐 |
| formulation | GET `products`、`products/{productId}`、`products/{productId}/formula-versions`、`released-specifications`、`formula-versions/{formulaVersionId}`、`formula-versions/{formulaVersionId}/trace`；POST `formula-versions`、`formula-versions/{formulaVersionId}/release` | 候选写角色 FORMULA_AUTHOR / FORMULA_RELEASER；release body 用 expectedCurrentFormulaVersionId，无 If-Match 要求 |

四份已合并公开契约仅 GET/POST；不因草案通配路径就允许其他 mutation。跨组织资源返回403，不存在返回404；C-08 已解决，Gateway 不重写服务响应。Label Workflow 提供 drafts、版本/declarations 读取、validations、submit、decisions 和 publish；缺失的公开读面见 [消费者核对](ui/consumer-contract-review.md)。

`/auth/**` 不能因为允许 OIDC 登录而对外开放 Keycloak 管理控制台/管理 API。管理路径应由 ALB/Gateway 独立 deny 或私有入口限制；JWKS/discovery 只公开协议所需路径。禁止通配业务 JWT 过滤器阻断正常 PKCE 登录。

## 明确不经公网路由的内容

- `/internal/**`、`/internal/validations`：仅 Label Workflow → Compliance，NetworkPolicy 显式允许；不能通过 `/api/compliance/internal/**` rewrite 间接到达。
- RabbitMQ、数据库、Keycloak admin、metrics 和服务管理端点：无浏览器业务路由。健康/观测走平台内部探针和采集入口，是否公开额外健康状态需单独决定。
- 不信任浏览器发来的 `X-Auth-Provider`、`X-External-Subject` 或自造组织上下文头。legacy seam 仅 local/test。

## Gateway 策略

| 策略 | G1 落地约束 | 所需验收 |
|---|---|---|
| JWT | 验签，并校验公开 issuer、过期/生效时间与约定 audience；从 M4 确认的 claim 提取角色和组织 | 缺失/伪造/过期/错误 issuer 或 audience 返回 401；缺角色返回 403；服务仍重查 token |
| 组织隔离 | token 是组织身份来源；浏览器 body 中的 organisationId 若存在须由服务与 claim 比较 | 跨制造商/供应商读写拒绝，UI 不泄露目标内容 |
| 限流 | Bucket4j，内存中每 Gateway 实例的保护性限制；数值由容量计划确定 | 429；文档明确扩成多副本后不构成全局精确配额 |
| payload 上限 | 显式限制 JSON body；数值在 G1 容量计划中记录 | 超限 413，避免默认无限制 |
| correlation | 缺失时产生 ID；限制客户端 ID 长度和字符（具体格式与 canonical envelope 对齐）并转发 `X-Correlation-ID` | 响应、服务日志、审计和事件可关联；不记录 token |
| 转发头 | 仅信任 ALB 的 Forwarded / X-Forwarded-*；维护真实外部 HTTPS host/issuer | OIDC redirect/discovery 不能返回内部 ClusterIP 或错误 HTTP URL |
| 重试 | Gateway 不自动重放 mutation；内部校验的 2s + 至多一次幂等重试由 LW 控制 | 冲突或失败不出现重复发布、重复审批 |

## CORS origin 矩阵

Origin 是 scheme + host + port，不含路径；GitHub Pages 项目路径属于 base URL / redirect URI。

| 环境 | allow origin | 使用方式 |
|---|---|---|
| GitHub Pages | 候选 `https://hxj04121-lab.github.io`，待 Pages 设置确认 | 项目 base URL 候选 `https://hxj04121-lab.github.io/FoodLabelFlow-Microservices/`；Gateway 精确允许 origin，Keycloak 精确登记带路径的回调 |
| 本地开发 | `http://localhost:5173` | 显式允许 Vite 来源；本地服务使用独立配置 |
| 本地备用 | `http://127.0.0.1:5173` | 仅实际使用时加入；与 localhost 是不同 origin |
| 其他来源 | 不允许 | 不使用 `*`、`null` origin 或任意 github.io 正则；不自动接受临时预览域名 |

稳定 Pages origin 与固定网关 HTTPS 域名由 M3/M5 对齐。staging 拆建不能改变前端 origin；网关不在线时前端解释窗口关闭，不假装业务成功。端到端 PKCE 回调、issuer 和 public hostname 的组合需 M4/M5 一起验证。

### 请求与响应头

- `allowedMethods`：目前已合并 M1/M2/M4 公开端点只需 GET/POST。OPTIONS preflight 单独在 CORS 层处理；PUT/PATCH/DELETE 仅在后续接受的公开契约需要时加入，实际方法仍须认证授权。
- `allowedHeaders`：`Authorization`、`Content-Type`、`X-Correlation-ID`；`Idempotency-Key`、`If-Match` 仅在公开 LW/M1 契约规定后加入。浏览器不因为内部校验需要幂等键就调用 internal 路由。
- `exposedHeaders`：`X-Correlation-ID`；需要使用且真实返回时公开 `Retry-After`、`ETag`、`Location`。
- `allowCredentials=false`：业务 API 采用 Bearer token，不依赖跨站 cookie；浏览器 API 请求不启用 `credentials: include`。Keycloak 登录重定向的自身会话 cookie 属于 OIDC 流程，不是业务 API CORS 的凭据策略。
- 若公开 token endpoint 的跨来源 fetch 需要 CORS，Keycloak webOrigins 与 Gateway 来源一致，并验证 Authorization Code + PKCE 流程；不盲目覆盖 IdP 的协议头。
- preflight cache 可先采用 600 秒的设计值，G1 验证；CORS 响应确保 `Vary: Origin`，且错误响应同样带允许来源的 CORS/关联头，避免浏览器把 401/403 误报为不可诊断网络错误。
- Pages base path 下刷新页面不能依赖服务器 SPA rewrite；G1 采用 hash 路由或经验证的 Pages fallback，回调 URL 必须匹配选定策略。

## G1 验收用例清单（尚未执行）

| 用例 | 预期 |
|---|---|
| 四个公开 API 前缀 | 各到自己的服务；路径保持；服务负向授权仍有效 |
| 未登录业务请求 | 401；允许来源能读取错误 body 和关联头 |
| 已登录但缺角色 / 跨组织 | 403；无目标内容泄露 |
| OPTIONS，允许来源 + Authorization + POST | preflight 成功，无业务 token 前置要求；POST 本身仍要求 JWT |
| 非允许 origin 或 null | 无对应 allow-origin 授权；服务也不把 CORS 当身份校验 |
| `/internal/validations` 与 rewrite 变体 | 无公网转发；LW 私网调用独立成功 |
| `/auth` 登录/JWKS 与 admin | PKCE/discovery 可用，issuer 正确；管理路径不可公网访问 |
| 错 issuer、过期 token、伪造组织头 | 被拒绝；不能走 legacy seam |
| 超限 payload / rate | 413 / 429，并保留可诊断错误；限流只按单实例描述 |
| staging 重建与 Pages 深链接 | 前端来源保持；回调匹配；刷新可用；窗口关闭有明确提示 |

## 待确认项

根据 [M1 10月11日决定](https://github.com/hxj04121-lab/FoodLabelFlow-Microservices/pull/15#issuecomment-6106565508)，以下内容移交 G1/STCN-51，此次只保留交接：

- M3/M4：SPEC_AUTHOR / SPEC_RELEASER / FORMULA_AUTHOR / FORMULA_RELEASER 与 deploy/keycloak 实际角色的对应；不可把 SUPPLIER/MANUFACTURER 组织类型当角色。
- M3/M4/M2：C-03 derived allergens / 存储 validation records 的浏览器公开读取入口；内部 validations 永不公开。
- M3/M4：C-04 public jurisdictionCode/findings 与内部 jurisdiction/findings 的适配、private draftRevision/exact snapshot 语义；不从 versionNumber 或旧前端 DTO 推断。
- M2：限流/请求体容量参数；M4：realm、issuer/audience、Keycloak /auth 和 admin 边界；M5：稳定域名、ALB、service DNS/端口。

M4 realm 设计现已提供 spectrace-web public PKCE S256 及顶层 org_id/org_type/roles；设计文件不代表 realm 已部署。G1 确认结果再记录于本文件；若改变契约，遵循 M2 + ADR 流程。G0 的本次提交不包含 Gateway 运行代码或 G1 测试结论。

## G1 本地实现进度（2026-10-11）

### 对接确认（2026-10-12）

- 登录 `redirect_uri` 与退出 `post_logout_redirect_uri` 均为精确根路径
  `https://hxj04121-lab.github.io/FoodLabelFlow-Microservices/`。前端用 origin 和
  Pages base path 构造同一个回调；hash 业务路由不进入回调，不使用 `/*`。
  Gateway CORS origin 为 `https://hxj04121-lab.github.io`，不含项目路径。
- 身份统一使用 `/auth/realms/spectrace`。Gateway 本地联调 issuer 为
  `http://localhost:8080/auth/realms/spectrace`，容器 JWKS 为
  `http://keycloak:8080/auth/realms/spectrace/protocol/openid-connect/certs`。
  直连本地 Keycloak 时入口为8180，issuer 对应
  `http://localhost:8180/auth/realms/spectrace`；同一次联调不能混用两个入口。
  四个服务的 Boot 与 starter 都显式绑定同一外部 issuer/私有 JWKS/audience。
  staging 将入口替换为 M5 提供的实际 HTTPS origin；切换后重新登录取得新 token。
- G1 只使用真实 JWT。`X-Auth-Provider` / `X-External-Subject` 的旧单体 seam
  依赖数据库 identity service 查询组织和角色，保留在 baseline backend。
  starter 的 `X-Auth-Subject` / `X-Auth-Org-*` / `X-Auth-Roles` 只是默认关闭的
  local/test fixture，不宣称兼容旧 seam；不通过改名或客户端头补组织/角色实现兼容。
  将来若要迁移，应由 M4/M1提供可信身份查询适配器，再由 M3审阅。
- PR47 的 NetworkPolicy 是部署候选：gateway→各服务及 label-workflow→compliance。
  L3/L4 策略不能限定 `/internal/**`，服务权限检查和 Gateway 的内部路径拒绝同时保留。
  ALB→Gateway、namespace/Keycloak CR 绑定及实际 egress 由 M4/M5 在 staging 验证。
- #34/#36 已合入 main；XFY 已同步。Pages 部署、AWS LB 登录和 M4最终验收仍待完成。

### 历史本地记录

G0 文档已合并后，XFY 继续添加 [本地 Gateway](../../platform/gateway/README.md)、[登录与 Pages 构建](../../frontend/README-G1.md)，测试范围及联调依赖见 [G1 本地验证记录](evidence/G1-M3-local-progress.md)。当前实现不新增公开契约；C-03/C-04/C-07 的团队决定仍待 M4/M2 对齐。实际部署的来源、issuer/audience、角色和 service 地址必须显式配置，不能把本地 fixture 当团队认可值。
