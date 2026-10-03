# ADR-09 — On-demand staging windows

- **Status:** Accepted (3 Oct 2026)
- **Owners:** Sun Huajian (create/destroy automation), Huang Xiangjia (seed replay), Cai Runchen (experiment window)
- **Related:** [ADR-08](ADR-08-aws-instead-of-digitalocean.md), architecture v3 §8.3–8.4, §14.1

## Context

AWS staging costs about USD 14–16 per running day. Keeping it up from Gate 1 to the presentation (about 19 days) would cost about USD 280, more than the up to USD 200 Free Tier credits of one new account. The course is assessed on evidence (deployments, measurements, demo), not on how long an environment stays up.

## Decision

Staging exists only during evidence windows. Each window is created by `staging-up` and removed by `staging-down`, both Terraform plus scripts:

| Window | Date (2026) | Length | Evidence |
|---|---|---|---|
| Gate 0 spike | before 7 Oct | hours | Terraform modules create and destroy cleanly |
| Gate 1 close | ≈ 10–11 Oct | ~1 day | CI deploys every service skeleton; sign-in via ALB → Gateway → Keycloak returns organisation claims |
| Gate 2 close | 16 Oct | ~1 day | four services on RDS; events flowing; review/approval/publication end to end |
| Gate 3 experiments | ≈ 18–20 Oct | 2–3 days | two-phase flow, 1 vs 3 replica load test, failover, pipeline demo (one window, one account) |
| Presentation | rehearsal day + presentation day | ~2 days | live demo; destroyed right after |

That is about 7 running days, or about USD 110–140, in one team member's AWS account:

- The account's Free Tier credits are used first, and the account pays any remainder.
- AWS Budgets alerts fire at 50 %, 80 % and 100 % of the credits.
- The account is upgraded to the Paid plan before Gate 3, so running out of credits cannot suspend it.

Rules:

- `staging-up` restores everything from code: VPC, EKS and its add-ons, the operators, RDS, the Keycloak realm, and seed data replayed from deterministic bootstrap events.
- Only the Terraform state bucket survives between windows. Images live in GHCR and the front end on GitHub Pages.
- CI deploy jobs run only while a window is open and are skipped otherwise; they must never turn `main` red. Every pull request also installs the Helm charts into a throw-away kind cluster.
- Evidence is captured inside the window: pipeline runs, image digests, k6 summaries and Grafana exports. Grafana Cloud keeps metrics and logs for 14 days.
- All runs of one experiment happen in a single window.

## Consequences

- Positive:
  - Cost fits one account's credits.
  - Reproducible infrastructure becomes a demonstrable cloud-native and automation property.
- Negative:
  - There is no always-on shared environment.
  - Kubernetes and integration problems can surface only at window time; local Compose and kind in CI mitigate this.
  - Every window needs about 30–60 minutes of automated set-up.
- The report states that staging is not permanent and that availability claims cover the windows only.
