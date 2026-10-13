# FoodLabelFlow / SpecTrace

Frontend (local implementation, 2026-09-07): React + TypeScript + Tailwind CSS + shadcn/ui. The redesigned dashboard provides read-only seed-data browsing; business write APIs are not connected. See [the frontend decision](docs/FRONTEND_STACK_DECISION.md) and [UI validation](docs/evidence/M3-dashboard-design.md).

This repository contains the Java 21 Spring Boot modular-monolith baseline and
React/TypeScript web shell for SpecTrace. The canonical database baseline is
the supplied `SpecTrace_PORTABLE_DATABASE_PACKAGE_v3` and is installed through
Flyway migrations under `backend/src/main/resources/db/migration`.

## Local run

```bash
docker-compose up --build
```

Then open <http://localhost:5173>. The local database is exposed on port 3307;
the application API is exposed on port 8080.

## AWS Compose demo alternative

This is a temporary Stage 0 modular-monolith demo; it does not satisfy the EKS/RDS/ALB course acceptance. The override keeps MySQL and the backend off host ports, uses a separate MySQL application user, and publishes only the frontend. Copy `.env.aws-demo.example` to an ignored `.env.aws-demo`, set two strong passwords, and limit the EC2 security-group ingress for port 80 to the team-approved CIDR before starting it. Local Compose defaults bind every host port to loopback; the AWS demo env sets only the frontend bind address to all interfaces behind that restricted security group:

```bash
cp .env.aws-demo.example .env.aws-demo
chmod 600 .env.aws-demo
# Fill the two password fields before continuing.
docker compose --env-file .env.aws-demo -f docker-compose.yml -f docker-compose.aws-demo.yml config --quiet
docker compose --env-file .env.aws-demo -f docker-compose.yml -f docker-compose.aws-demo.yml up --build -d
docker compose --env-file .env.aws-demo -f docker-compose.yml -f docker-compose.aws-demo.yml logs --tail=200 -f
```

The backend currently has unauthenticated write endpoints, so do not expose this demo to `0.0.0.0/0`; HTTP also remains until a team-owned domain and TLS termination are configured. Back up data before destroying the EC2 instance. Use Docker Compose `down` to remove containers, then terminate the approved EC2 resource separately; do not use `down -v` unless deleting the database volume is intended.

The preferred cloud acceptance remains EKS + private Multi-AZ RDS + ALB. Its service chart still uses placeholder images until service owners publish commit-tagged images and confirm the runtime health/auth contracts.

For a backend-only build, use `mvn -B -ntp -f backend/pom.xml verify`. For the
frontend, run `npm ci && npm run build` from `frontend/`.

Stage 0 control-plane state and Sprint 1 handoff work orders are under
`.project-control/`. The five Sprint 1 domain slices are intentionally not
implemented in the Stage 0 bootstrap.
