# M3 今日交付与待验收清单 — 2026-10-15

负责人：Xu Feiyang（M3）；报告审核：Zhu Wenyu（M4）；整合：Huang Xiangjia（M1）。
报告草稿截止 **10 月 15 日 23:59（Asia/Singapore）**，团队整合日为 10 月 16 日。
本文中的“已完成”指独立实现或证据整理完成，不代替 Jira 状态、M4 人工验收或线上验收。

## 今天可交付的材料

| 材料 | 用途与状态 |
| --- | --- |
| [八节英文 M3 报告](M3-technical-sections.md) | 草稿已整理，可供 M4 review、M1 整合；覆盖模板 §3.1.2、§4.4、§6，附测试和 CI 证据 |
| [证据索引](M3-evidence-index-20261015.json) | 精确 commit、run/job、测试数量、镜像 digest、文件 SHA256 和验收边界 |
| [真实 G2 CI 业务结果](../evidence/G2-M3-real-workflow-5beb98a.json) | 四服务 + Keycloak/Gateway/MySQL/RabbitMQ；当前 PUBLISHED、发布 201、跨组织 403 |
| [真实 G2 发布截图](../evidence/G2-M3-real-published-5beb98a.png) | 原 CI 产物直接复制，无登录凭证表单；属于临时 CI 环境 |
| [G1 输入清单](../G1-M3-M5-inputs.md)、[G1 验收步骤](../G1-M3-staging-acceptance.md)、[G3 演示步骤](../G3-M3-demo-acceptance.md) | 已准备；实际线上运行仍待输入与服务就绪 |
| [设计规则](../../../DESIGN.md) | 全前端统一的实际设计约定，后续页面可复用 |

上述业务结果来自实现版本 `5beb98a5b2fe56f1b30d7ada54d145bac9d5ad02`，执行于
10 月 14 日，本清单于 10 月 15 日复核整理；不能写成 10 月 15 日已完成 staging 演示。
两个真实 maker/checker 录像保留在
[该 CI run](https://github.com/hxj04121-lab/FoodLabelFlow-Microservices/actions/runs/38532352262)
的 `m3-real-keycloak-evidence` artifact 中；下载有效期以 GitHub 的实际状态为准。

## 自主部分核对

| 工作项 | 已完成的自主部分 | 尚未完成的验收 |
| --- | --- | --- |
| G0 消费接口与前端准备 | [消费契约与原型记录](../ui/README.md)已有历史交付 | 不在本轮扩充范围 |
| STCN-51 Gateway | 公共路由、JWT/JWKS/audience、CORS、限流/体积/私有路径 guard、精确 declarations PUT；41 项测试 | 真实 HTTPS 入口与部署配置验收 |
| STCN-52 登录 | PKCE、内存 token、caller 对照、组织/角色、401/403、退出；真实 CI 身份验证 | Pages/Keycloak/Gateway 线上登录链路 |
| STCN-53 静态站点 | Pages workflow、稳定 callback/base、实际 build 元数据与 7 项配置检查 | Pages 实际发布及线上源配置一致性 |
| STCN-74–77 标签页面 | 创建/编辑/验证/提交、独立审核/退回/拒绝、显式发布、current 查询、权限/不确定写恢复；真实 G2 CI 通过 | 部署环境上的业务闭环与 M4 接受 |
| STCN-101 Inbox | 两阶段证据、筛选、opaque 分页、版本链接、异常/权限处理；18 项针对用例 | staging finding 数据与事件闭环核对 |
| STCN-102 全流程演示 | 独立 deployed profile、缺输入拒绝、显式写入开关、脱敏成功结果、复现步骤 | **NOT RUN**：需完整应用、基线标签、实际版本与窗口 |
| STCN-118 报告 | 八节英文草稿、源/CI/产物映射、局限与任务清单已整理 | M4 报告 review、M1 整合与最终提交 |
| 全页面统一 | Dashboard/Catalog/材料/供应商/标签/状态/参考页及认证页面统一；桌面/平板/手机截图复核 | 不将原型未连接状态算成新业务集成 |

[PR #37](https://github.com/hxj04121-lab/FoodLabelFlow-Microservices/pull/37) 与
[PR #54](https://github.com/hxj04121-lab/FoodLabelFlow-Microservices/pull/54)已由团队采用进入 main。
STCN-103/104 已 DESCOPED，不补造 partner/Notification onboarding、耗时或测量数据。
本轮不依据 CI 自动关闭 Jira，也不把其他组员实现计入 M3。

后续推进：#58 已由团队合入 main；M3 已核对配置修复、7 项运行参数/目标测试和 32 项
Helm 检查。工作流进入 main 只是代码前置条件解除；实际 issuer、Pages 参数、Runner
Online 确认与发布/验收记录仍待 owner 提供。最新缺口和主线 M3 CI 的录像步骤重试见
[post58 核对记录](../G1-M3-post58-readiness.md)，不沿用旧版 #58 缺陷判断当前代码。

## CI 结果应怎样写

- 实现版本 `5beb98a`：M3 push/PR 与 full CI 三份 run 均成功；实际测试数量和链接见报告。
  Pages 发布、PR 镜像发布按条件跳过，不能写成“Pages 已上线”。
- 报告准备基线 `afc90e0`：main 的容器 job 遇到 Maven Central HTTP 502 依赖下载失败。
  这是已读取日志的失败记录，不宣称主线全绿、重试已成功或业务代码已修复。
- 本轮重新运行 deployed/demo 配置检查，3 组通过；只是缺参、角色分离、地址/callback
  和显式写入 guard 的离线检查，没有拿 staging 账号运行浏览器。
- 报告文档 PR 的 CI 与上述实现证据分开记录；文档提交不生成新的实现验收结果。

## 线上待完成与配合人

| 配合人 | 需要交付 | 我们收到后继续做什么 |
| --- | --- | --- |
| M5，M1/仓库管理员配合 | Runner Online、受保护发布流程、实际服务/依赖就绪、Gateway HTTPS 和 issuer、private JWKS/Service 绑定、Pages build/config、source/digest、访问窗口 | 核对已发布构建与 discovery 后运行只读 G1 smoke；不能用服务器 Running 替代应用就绪 |
| M4 | 在 staging 发布 north 产品 `prod_usda_1118468` 的 US current PUBLISHED 基线，绑定真实 current formula；提供 labelVersionId 和业务 versionNumber；独立 maker/checker 身份经安全渠道交付 | 先核对基线绑定和权限，再运行 G2/G3 受控演示 |
| M1 | 核对线上产品、Chocolate Base 前序规格、配方、ingredient/provenance 与上述基线标签的准确版本 | 填写实际输入，保留当前指针与版本证据；不把 seed 期望值当线上事实 |
| M4/M5 | 同一 finding/org/formula/label/event correlation 的唯一 ReviewTask 与 LabelPublished.v2 消费记录 | 补齐 OWNER_EVIDENCE_REQUIRED 项，再提交 M4 人工接受 |

M3 随后执行：G1 只读部署检查 → G2/G3 受控浏览器业务闭环 → 关联 owner 观察记录 →
补报告实测及正式验收。代码 review 可与部署准备并行。没有实际执行记录的项继续保留
**NOT RUN / PENDING / OWNER_EVIDENCE_REQUIRED**。

## 给 M1 的交付说明（可直接转发，尚未代发）

M3 今天的八节英文报告和证据清单已整理，报告涵盖 Gateway/PKCE/标签审核发布、Impact
inbox、全前端统一及已通过的本地/真实组件 CI；附精确 commit、测试 run、真实 G2 结果和
镜像 digest。G1/G2/G3 的线上验收、唯一 ReviewTask 和 LabelPublished.v2 消费证据仍明确
标为待完成，请 M4 review 后由 M1 整合。待 M5 应用/HTTPS 与 M4 基线标签、M1 线上版本核对
齐全后，M3 再补真实全流程实测，不以 CI 或基础设施进度替代线上结果。
