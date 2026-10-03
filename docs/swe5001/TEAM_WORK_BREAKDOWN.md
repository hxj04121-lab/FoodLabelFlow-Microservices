# SpecTrace-CN（SWE5001）团队分工

> **状态**：草案，待团队评审。日期 2026-10-01；2026-10-03 按 AWS 和按需开启的 staging 更新（[ADR-08](adr/ADR-08-aws-instead-of-digitalocean.md)、[ADR-09](adr/ADR-09-on-demand-staging-windows.md)）。
> **依据**：
> - AWS 版 proposal §6.3（成员职责）和 §7（WBS、Gate）
> - 架构 source of truth v3 §8、§14
> - 课程 Briefing、报告模板、答辩指南
>
> **工单**：[`.project-control/work-orders/`](../../.project-control/work-orders/)，G0–G3 共 20 张，和 5006 同格式。scope 里的每一条就是一个 Jira 子任务。
> **Jira**：项目 [`STCN`](https://hxj04121.atlassian.net/jira/software/projects/STCN/boards/34)，每个 Gate 一个 Sprint。父任务是 STCN-1 到 STCN-20，子任务是 STCN-21 到 STCN-113，以及 2026-10-03 新增的 STCN-114、STCN-115。

## 本周必须完成

- [ ] **4 Oct 前**：在 Canvas 组队（5 人），提交 AWS 版 proposal（[`proposal/01_…_team16_AWS.docx`](proposal/01_SpecTrace-CN_SWE5001_Project_Proposal_team16_AWS.docx)）。负责人：Huang。
- [ ] **7 Oct 前**：达到 G0 的完成标准（契约 v1、测试计划、Terraform 建了再毁、基线重跑）。负责人：全员。
- [ ] **尽快**：Sun 在自己的 AWS 账号（即 staging 账号）里实测免费套餐能否创建 EKS、RDS 多可用区和 ALB，并确认 vCPU 配额 ≥ 16。
- [ ] **5006 Sprint 4 规划时**：下调每人的 SP，给 5001 的 G1–G3 留出时间。

## 0. 课程要求速览

- **交付物**：
  - 可运行系统
  - 代码库（GitHub）
  - 项目报告（模板 §1–§6）
  - DevSecOps 流水线脚本和测试脚本
- **必须证明**：
  - 平台和生态（Seed、Producer、Consumer）
  - 可扩展性
  - cloud native
  - 开发和部署自动化
  - 最低限度的安全控制
  - 至少 1 个应用
- **评分**（非公司赞助）：
  - 答辩 20%，报告 30%（含 5% 互评），笔试 50%。
  - 项目和笔试各自要 ≥ 40%。
  - 互评要给组员排出各不相同的名次，看的是贡献，不是小时数。所以每个人的贡献都要在 PR、Jira、报告和答辩里看得见。
- **进度报告**：双周一次，交到 Canvas。写完成的工作、每人工时、遇到的问题、下两周计划。
- **答辩**：40 分钟，含问答。各段时间是讲解 + 问答（分钟）：
  - Overview 2+1
  - Conduct 1.5+0.5
  - Logical 7+2
  - Physical 7+2
  - DevOps 6+1
  - Demo 8+2

## 1. 角色（M 号沿用 5006）

| M | 成员 | GitHub | 5001 主责 | 横向角色 |
|---|---|---|---|---|
| M1 | Huang Xiangjia | hxj04121-lab | Specification + Formulation；starter 基础能力；outbox、事件 envelope、幂等消费；组织模型 BR-11 | PM、集成负责人、进度报告和终稿报告主编 |
| M2 | Cai Runchen | rcncai | Compliance（核心域）：抽取、投影、两阶段影响分析、API；Helm chart 模板；压测与 failover | 契约与 ADR 管家 |
| M3 | Xu Feiyang | codingbychatgpt | React 参考消费端 + Spring Cloud Gateway；Should：partner client + Notification | Demo 和 PPT 负责人 |
| M4 | Zhu Wenyu | zhuwenyu04 | Label Workflow：抽取、改写 3 个存储过程、等价性测试、API；Keycloak；starter 安全模块 | 安全负责人 |
| M5 | Sun Huajian | SHJ-SHJ0128 | AWS 环境即代码（Terraform、EKS 插件、operators、Alloy），一键建拆 staging；CI/CD 和安全门禁；证据绑定 | 发布与环境负责人（额度和预算闸） |

proposal §6.3 的分工保持不变，本文只补上原来没有写负责人的公共件。每人在 5001 做的内容都挨着他在 5006 做的，上下文切换少。

## 2. 工作量（man-day）

各列合计等于 proposal 的 WBS（共 50 md：Must 47 + Should 3）。换成 AWS 后，第 7 项多 1 md（EKS 插件、一键建拆、种子回放），从 Should 里出：分布式追踪降为 Could。

| WBS | 合计 | H | C | X | Z | S |
|---|---|---|---|---|---|---|
| 1 基线/契约/spike | 3 | 1.0 | 0.5 | 0.5 | 0.5 | 0.5 |
| 2 starter/Gateway/Keycloak/组织 claims | 5 | 1.5 | – | 2.0 | 1.5 | – |
| 3 抽取/拆库/组织模型 | 8 | 4.0 | 2.0 | – | 2.0 | – |
| 4 存储过程改写/等价性/审批发布 API+页面 | 7 | – | – | 3.0 | 4.0 | – |
| 5 outbox/RabbitMQ/事件/投影/幂等消费 | 5 | 2.0 | 1.5 | – | 1.0 | 0.5 |
| 6 两阶段影响分析 | 4 | – | 3.0 | 1.0 | – | – |
| 7 AWS 环境即代码、按需建拆 + 可观测性 | 7 | 0.5 | 1.0 | – | 0.5 | 5.0 |
| 8 CI/CD + 安全门禁 | 3 | – | – | 0.5 | – | 2.5 |
| 9 压测 + failover | 3 | 0.5 | 1.5 | – | – | 1.0 |
| 10 Demo/答辩/报告 | 2 | 0.4 | 0.4 | 0.4 | 0.4 | 0.4 |
| 11 Should | 3 | – | – | 2.5 | – | 0.5 |
| **合计** | **50** | **9.9** | **9.9** | **9.9** | **9.9** | **10.4** |

每人每个 Gate 的量如下。这个数就是 Jira 父任务的 SP，1 SP = 1 md。

| | G0 | G1 | G2 | G3 | Report |
|---|---|---|---|---|---|
| Huang | 1.0 | 3.0 | 5.0 | 0.7 | 0.2 |
| Cai | 0.5 | 2.0 | 4.0 | 3.0 | 0.4 |
| Xu | 0.5 | 2.5 | 3.0 | 3.5 | 0.4 |
| Zhu | 0.5 | 2.5 | 5.0 | 1.5 | 0.4 |
| Sun | 2.5 | 4.0 | 2.0 | 1.5 | 0.4 |

**风险和对策：**

- **G2 时 Zhu 最重（5.0）**：Zhu 正在 5006 做审批和原子发布，直接移植过来（规则见第 6 节）。
- **G2/G3 时 Cai 是关键路径**：Huang 在 G3 只有 0.7，空出来的时间支援 Cai 做 Phase 2 联调。
- **G1 时 Sun（4.0）和 Huang 最重**：所以 Sun 在 G0 用 spike 先把 Terraform 写好（建了再毁），G1 主要做 EKS 插件和一键建拆。Helm 模板交给 Cai，proposal 本来就写了 Cai 和 Sun 结对配节点池。
- **只算 Must 时 Xu 是 7.4**：如果 Should 被砍，他那部分改做 demo 自动化回归、报告 §4.4/§6 和 PPT。

## 3. 按 Gate 的工单

详细的 scope、验收标准、测试、依赖和证据都写在 YAML 里。下面每张工单只用一句话概括。

### staging 窗口（ADR-09）

staging 在 AWS 新加坡区，**只在下面几个窗口开**。每次由 Sun 用 `staging-up` 创建，用完 `staging-down` 销毁；平时开发用本地 Docker Compose。

| 窗口 | 时间 | 时长 |
|---|---|---|
| G0 spike | 7 Oct 前 | 几小时 |
| G1 收尾 | 约 10–11 Oct | ~1 天 |
| G2 收尾 | 16 Oct | ~1 天 |
| G3 实验 | 约 18–20 Oct | 2–3 天，所有实验在同一个窗口跑完 |
| 答辩 | 彩排日 + 答辩当天 | ~2 天，结束后马上销毁 |

合计约 7 个运行日，约 110–140 美元。staging 建在 **Sun 的 AWS 账号**里：先用这个新账号的免费额度（最多 200 美元），超出部分由该账号先承担。G3 前要把这个账号升级成付费账号，避免额度用完时账号被暂停。

### G0 契约与探路（1–7 Oct，只做可逆工作）

**完成标准**：基线在 tag 上重跑全绿；contracts v1（4 份 OpenAPI，加 envelope 和 4 个事件 schema）合并；测试和容量计划合并；Terraform 建好再销毁，留证据。

| 工单 | 负责人 | md | 一句话 | 评审 | Jira |
|---|---|---|---|---|---|
| [G0-M1](../../.project-control/work-orders/G0/M1-baseline-layout-spec-form-contracts.yaml) | Huang | 1.0 | Canvas 组队并提交；基线重跑；定仓库布局、开分支保护；写 Spec/Form 契约和事件 envelope | Cai | [STCN-1](https://hxj04121.atlassian.net/browse/STCN-1) |
| [G0-M2](../../.project-control/work-orders/G0/M2-compliance-contracts-test-plan.yaml) | Cai | 0.5 | Compliance 契约和 ImpactFinding schema；测试与容量计划（目标提前声明）；统一审一遍契约 | Zhu | [STCN-2](https://hxj04121.atlassian.net/browse/STCN-2) |
| [G0-M3](../../.project-control/work-orders/G0/M3-ui-flows-gateway-routes.yaml) | Xu | 0.5 | 前端流程和线框图；从消费者角度审契约；Gateway 路由表和 CORS 草案 | Zhu | [STCN-3](https://hxj04121.atlassian.net/browse/STCN-3) |
| [G0-M4](../../.project-control/work-orders/G0/M4-label-workflow-contracts-realm-design.yaml) | Zhu | 0.5 | LW 契约和 LabelPublished schema；Keycloak realm 设计；存储过程等价性 oracle 清单 | Xu | [STCN-4](https://hxj04121.atlassian.net/browse/STCN-4) |
| [G0-M5](../../.project-control/work-orders/G0/M5-do-budget-terraform-spike.yaml) | Sun | 2.5 | AWS 账号准备（额度、Budgets 告警、vCPU 配额 ≥ 16、域名、OIDC）；实测免费套餐能建 EKS、RDS 多可用区、ALB；预算闸按窗口成本对比剩余额度；Terraform 模块在 spike 里验证，建了再毁 | Huang | [STCN-5](https://hxj04121.atlassian.net/browse/STCN-5) |

可以先在分支上做 starter 和 Helm 的 spike，等 7 Oct proposal review 之后再合并。

### G1 平台骨架（8–11 Oct，关键路径）

**完成标准**（在 G1 收尾的 staging 窗口内取证）：CI 把 4 个服务骨架部署到 EKS；浏览器经 LB → Gateway → Keycloak 登录后，能拿到某个服务返回的调用者 org claims。

| 工单 | 负责人 | md | 一句话 | 评审 | Jira |
|---|---|---|---|---|---|
| [G1-M1](../../.project-control/work-orders/G1/M1-starter-outbox-skeletons.yaml) | Huang | 3.0 | starter 基础；本地 compose；4 个服务骨架；负向测试工具；outbox 和幂等消费；Testcontainers | Cai | [STCN-6](https://hxj04121.atlassian.net/browse/STCN-6) |
| [G1-M2](../../.project-control/work-orders/G1/M2-node-pool-helm-compliance-skeleton.yaml) | Cai | 2.0 | compliance 用 c6i.large 计算型节点组（与 Sun 结对）；Helm chart 模板；Compliance 骨架；k6 脚本 | Zhu | [STCN-7](https://hxj04121.atlassian.net/browse/STCN-7) |
| [G1-M3](../../.project-control/work-orders/G1/M3-gateway-react-signin.yaml) | Xu | 2.5 | Spring Cloud Gateway；React 用 code + PKCE 登录；GitHub Pages 静态站点和前端流水线 | Zhu | [STCN-8](https://hxj04121.atlassian.net/browse/STCN-8) |
| [G1-M4](../../.project-control/work-orders/G1/M4-keycloak-security-module.yaml) | Zhu | 2.5 | Keycloak realm as code 和演示用户；starter 安全模块；realm 导入集群；NetworkPolicy 白名单规格；LW 骨架 | Xu | [STCN-9](https://hxj04121.atlassian.net/browse/STCN-9) |
| [G1-M5](../../.project-control/work-orders/G1/M5-staging-operators-cicd.yaml) | Sun | 4.0 | EKS 插件（ALB 控制器、EBS CSI、Cluster Autoscaler）；RabbitMQ 和 Keycloak operator；消息拓扑；Alloy；可复用 CI/CD（GHCR、PR 上的 kind、OIDC 部署）；`staging-up/down` 和 G1 收尾窗口 | Huang | [STCN-10](https://hxj04121.atlassian.net/browse/STCN-10) |

依赖关系：Sun 的集群挡着所有人部署；Zhu 的 Keycloak 挡着 Xu 的登录；Huang 的 starter 挡着各服务骨架。有了本地 compose，开发不必等 staging。

### G2 四个服务（12–16 Oct，14 Oct 中期联调）

**完成标准**：四个服务各用自己的库；组织模型就位；存储过程改写完成；审批发布的 API 和页面可用；事件流通；等价性测试通过。

| 工单 | 负责人 | md | 一句话 | 评审 | Jira |
|---|---|---|---|---|---|
| [G2-M1](../../.project-control/work-orders/G2/M1-specification-formulation-org-model.yaml) | Huang | 5.0 | 抽出 Spec 和 Form；组织种子和 BR-11 校验；两个事件的 producer；Formulation 消费者；staging 种子数据回放 | Cai | [STCN-11](https://hxj04121.atlassian.net/browse/STCN-11) |
| [G2-M2](../../.project-control/work-orders/G2/M2-compliance-projections-phase1.yaml) | Cai | 4.0 | 抽出 Compliance（校验接口幂等）；三个投影；重复和过期事件测试；Phase 1（POTENTIAL） | Zhu | [STCN-12](https://hxj04121.atlassian.net/browse/STCN-12) |
| [G2-M3](../../.project-control/work-orders/G2/M3-review-approval-publication-ui.yaml) | Xu | 3.0 | 审批发布页面经 Gateway 接 LW；按权限显示操作按钮；Playwright | Zhu | [STCN-13](https://hxj04121.atlassian.net/browse/STCN-13) |
| [G2-M4](../../.project-control/work-orders/G2/M4-label-workflow-procedure-rewrite.yaml) | Zhu | 5.0 | 抽出 LW；3 个存储过程改成本地事务；REST API；校验客户端（2 s、熔断、幂等键）；FormulaPublished 消费者 | Xu（涉及校验契约时加 Cai） | [STCN-14](https://hxj04121.atlassian.net/browse/STCN-14) |
| [G2-M5](../../.project-control/work-orders/G2/M5-databases-gates-networkpolicy.yaml) | Sun | 2.0 | RDS 上每个服务的库、用户和授权；所有门禁转绿；NetworkPolicy 和安全组；secret 注入；证据绑定；DLQ 告警；G2 收尾窗口 | Huang | [STCN-15](https://hxj04121.atlassian.net/browse/STCN-15) |

### G3 影响分析与证据（17–20 Oct）

**完成标准**：两阶段流程在 staging 上端到端跑通；1 对 3 副本压测和 failover 有结果；进度报告已提交。

| 工单 | 负责人 | md | 一句话 | 评审 | Jira |
|---|---|---|---|---|---|
| [G3-M1](../../.project-control/work-orders/G3/M1-dataset-demo-e2e-progress-report.yaml) | Huang | 0.7 | 压测数据集；soy-lecithin 演示数据；端到端联调；用 Loki 查审计链；进度报告 | Cai | [STCN-16](https://hxj04121.atlassian.net/browse/STCN-16) |
| [G3-M2](../../.project-control/work-orders/G3/M2-phase2-load-failover.yaml) | Cai | 3.0 | Phase 2（CONFIRMED）；preview 和 inbox API；不可变版本缓存；1 对 3 副本压测；failover；瓶颈分析 | Zhu | [STCN-17](https://hxj04121.atlassian.net/browse/STCN-17) |
| [G3-M3](../../.project-control/work-orders/G3/M3-impact-inbox-demo-e2e.yaml) | Xu | 3.5 | impact inbox 页面；demo 全路径写成 Playwright。Should：partner client 和 Notification（记录耗时与步骤） | Zhu | [STCN-18](https://hxj04121.atlassian.net/browse/STCN-18) |
| [G3-M4](../../.project-control/work-orders/G3/M4-equivalence-review-tasks-security.yaml) | Zhu | 1.5 | 补完等价性测试；ImpactFinding → ReviewTask；LabelPublished；staging 安全证据 | Xu | [STCN-19](https://hxj04121.atlassian.net/browse/STCN-19) |
| [G3-M5](../../.project-control/work-orders/G3/M5-experiment-infra-pipeline-demo.yaml) | Sun | 1.5 | 实验基础设施（c6i.large 扩容、EC2 上的 k6）；演示一次改动走完流水线；按剩余额度监控成本，G3 前升级付费账号。Should：HPA 和 Cluster Autoscaler | Huang | [STCN-20](https://hxj04121.atlassian.net/browse/STCN-20) |

### Freeze（21–26 Oct）和 Report（27 Oct–16 Nov）

- **Freeze**：
  - 全员做回归测试。
  - Cai 和 Sun 跑最终的测量。
  - Xu 组织两次彩排，并录一段备份视频。
  - 答辩结束后由 Sun 销毁 staging。
- **Report**：
  - 各章节 1 Nov 前交初稿，避开 2–6 Nov 笔试。
  - Huang 在 9–13 Nov 统稿，16 Nov 提交。
- 这两段的 5 张父任务等 21 Oct 做 freeze 规划时再建。

## 4. 课程验收项 → 负责人

| 要求 | 负责人 | 要求 | 负责人 |
|---|---|---|---|
| 平台/生态，两阶段 demo | Huang + Cai | 安全控制、负向测试 | Zhu |
| 可扩展性（k6，1 对 3 副本） | Cai + Sun | ≥ 1 个应用（React） | Xu |
| Cloud native、流水线脚本 | Sun | 测试脚本 | 各服务负责人（Testcontainers）、Cai（k6）、Xu（Playwright）、Zhu（安全） |
| 进度报告、终稿报告、代码库整洁 | Huang | 答辩和 demo | Xu 统筹，全员讲 |

## 5. 报告章节与答辩分段

**报告**（模板章节号）：

| 章节 | 负责人 |
|---|---|
| §1 Introduction、§2 Project Conduct、§3.1.1 Key Architectural Decisions、§3.3 Other ADs、§5.1 Source Control | Huang |
| §3.1.3 Nodes & Subsystems、§3.1.4 Platform Design、§3.4 Limitations、§4.1 Performance、§4.2 Availability | Cai（Sun 补可用性的平台战术，Huang 补 Spec/Form 上下文） |
| §3.1.2 Tiers & Layers、§4.4 Extensibility & Maintainability、§6 复用证据 | Xu（Huang 补 starter 部分） |
| §3.2.3 Persistence、§3.2.4 Detailed Design、§4.3 Security | Zhu（Huang 补拆库，Cai 补两阶段） |
| §3.2.1 Physical ADs、§3.2.2 Technology & Services、§5.2 CI、§5.3 CD | Sun |

**答辩**（每个人都要讲）：

| 段 | 负责人 |
|---|---|
| 1 Overview、2 Project Conduct | Huang |
| 3 Logical Architecture & Design | Huang（AD、平台设计）+ Cai（核心域、两阶段） |
| 4 Physical Architecture & Design | Sun（DO 拓扑）+ Zhu（持久化、详细设计、安全） |
| 5 DevOps | Sun |
| 6 Demo | Xu（用例）+ Cai（扩容和 failover 看板）+ Sun（现场跑一次流水线） |

## 6. 协作规则（沿用 5006，并改掉 5006 复盘里提到的问题）

- **分支和合并**：一个分支对应一个子任务，PR 进 main。需要 1 个 approval 加 CI 全绿；**main 红的时候不准合并**。
- **Jira 和工时**：Jira Sprint 在动工前就开启；每人每天在 Jira 记工时，用来填进度报告的 effort 表。
- **评审对**：谁消费接口，谁评审提供方。`.github/CODEOWNERS` 会自动请求对应的人评审。
  - Huang → Cai
  - Cai → Zhu
  - Zhu → Xu（涉及校验契约时加 Cai）
  - Xu → Zhu
  - Sun → Huang
- **改契约**：改 OpenAPI 或事件 schema 要经 Cai 批准，并补一份 ADR。
- **联调和评审**：每个 Gate 中途做一次联调检查；Gate 收尾时做现场 demo 评审，并把反馈记下来。
- **和 5006 的关系**：
  - 两门课在 8–26 Oct 时间重叠，5006 Sprint 4 每人的 SP 要减少。
  - 团队自己在 `a3520e1` 之后写的 5006 代码可以移植过来，但 commit 里要注明 `upstream-5006` 的 SHA，并在报告 §2 里如实说明。
- **Should 项**：只在 G1、G2 都过了之后才开始做；进度一滑就先砍 Should。
