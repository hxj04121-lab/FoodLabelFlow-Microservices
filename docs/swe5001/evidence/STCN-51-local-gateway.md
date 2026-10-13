# STCN-51 本地网关验证记录

日期：2026-10-11。分支：XFY。实现从 `XFY-G1-local` 恢复；当前基线为已合并 main `42341c0`，重新运行 Maven verify，35项测试与打包通过。下列 JAR 启动/health smoke 为同日先前验证记录，恢复后未重复运行；覆盖率由当前 target 报告生成。

实现与运行说明：[platform/gateway](../../../platform/gateway/README.md)。范围为本地网关实现，不是 STCN-8 全部验收，也不是 AWS/Keycloak 运行证据。

## 构建环境与命令

- Maven 3.9.10；本机实际测试 JDK 25.0.4；编译 `--release 21`，产物 class major=65（Java 21）。未声称已在 Java 21 JVM 上运行此测试。
- Spring Boot 4.1.1、Spring Cloud 2025.1.3、Bucket4j 8.21.0，依赖来自 Maven Central。
- 本机全局 Maven 设置引用不可达的历史内网镜像。本次使用 `.git/xfy-review/maven-central.xml` 的独立 HTTPS Central mirror，未改全局配置；其他机器可使用自己的可用 Maven 设置。

```powershell
# JAVA_HOME 指向本机的 JDK 21+；本次使用 JDK 25.0.4。
mvn -s .git/xfy-review/maven-central.xml -B -ntp -f platform/gateway/pom.xml verify
```

本机日志保留在 `.git/xfy-review/gateway-verify.log`，可移植 JUnit/覆盖率报告由上述命令生成在 `platform/gateway/target/`。

## 结果

| 验证 | 本次实际结果 |
|---|---|
| HTTP 集成测试 | 32 项；0 failure、0 error、0 skipped |
| 策略测试 | 3 项；0 failure、0 error、0 skipped |
| Maven verify / 可执行 JAR 打包 | BUILD SUCCESS |
| JaCoCo 行覆盖 | 169/177，约95.5% |
| JaCoCo 分支覆盖 | 137/194，约70.6% |
| 打包 JAR 的 local profile 启动 | 成功；使用临时随机端口，检查后停止进程 |
| JAR readiness 请求 | HTTP 200 |
| JAR 无 token 业务请求 | HTTP 401，canonical AUTHENTICATION_REQUIRED，含 traceId 与 nullable evidenceId |

集成测试真的启动随机端口 Gateway、独立 JWKS HTTP 服务及四个可区分的下游 HTTP fixture。JWT 由临时生成的 RSA 私钥签名，JWKS 只返回公钥；不使用 mockJwt 绕过验签。

## 已覆盖的边界

- 四个 API 服务路由选择、原始 path/query、bearer token 和 correlation 传递。
- 真实 JWKS 获取；未知 key、已知 kid 的错误签名、错误 issuer/audience、过期/future nbf、缺 expiry/org、空 subject、组织多值/空白、非法 org_type、错误/空 roles、重复 Authorization 均被拒绝。
- 写角色不足返回403；模拟下游 owner 403保持403。后者只验证透传，不能当作真实业务服务的二次鉴权证据。
- 允许来源的 preflight 无需 token；非法 origin/请求头拒绝；允许来源的401仍可由前端读取；credentials未开启。
- 固定长度和分块上传的1025-byte body在1024-byte测试上限下返回413，且下游没有收到请求；正好1024-byte分块内容完整转发。
- Bucket4j 单实例共享桶耗尽返回429和 Retry-After；health不消耗业务桶。未宣称跨副本全局限流或容量测量。
- legacy identity、组织和 forwarding header 清理；非法 correlation 重新生成。
- internal/admin/account/其他realm/未实现Notification/未配置management路径不公开；歧义编码和不支持的方法被拒绝。
- 公共 OIDC 路径可路由，health可访问；网络和内部错误不泄露私有异常消息。

## 仍需联调的内容

M4 的真实 realm import、issuer/audience/角色映射和 organisation provisioning；M5 的 ALB/TLS/可信代理、网络隔离与部署；实际服务的权限、组织和 maker-checker 校验。STCN-52 本地协议登录已实现并测试，真实 Keycloak 联调仍待进行；见 [G1 本地进度](G1-M3-local-progress.md)。这些不能由本地 HTTP/JWKS fixture 代替。

本次没有部署 AWS、启用 Pages、修改 Jira 状态或宣布全 G1 完成。网关验证已写入 `.github/workflows/swe5001-m3.yml`，待提交运行；staging 窗口再按实际配置验收。
