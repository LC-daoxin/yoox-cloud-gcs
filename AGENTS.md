# Repository Guidelines

## Project Structure & Module Organization

The Java 17/Spring Boot Maven reactor contains `cloud-api` contracts, the runnable `cloud-service`, and shared `yoox-framework/` modules. Java uses standard `src/main/java` and `src/main/resources` layouts. The Vue 3 frontend is under `web-console/src`, with `views/`, `services/`, and `stores/`. Static API documentation is in `api-portal/`; deployment and project assets are in `deploy/`, `sql/`, `scripts/`, and `docs/`. Do not commit `target/`, `dist/`, or `node_modules/`.

## Build, Test, and Development Commands

- `make init` creates `.env`; `make preflight` checks Docker and Compose configuration.
- `make verify` validates Compose and builds both backend and frontend.
- `mvn -B test` runs Maven tests; use JDK 17.
- `cd web-console && npm ci && npm run dev` starts Vite on port 5173 with API proxies.
- `make up` starts the stack; use `make logs`, `make ps`, or `make down` to manage it.
- `make smoke` checks the running health, OpenAPI, login, and WebSocket endpoints.

## Coding Style & Naming Conventions

Java uses four spaces, lowercase packages, PascalCase classes, and role suffixes such as `Controller`, `ServiceImpl`, and `Mapper`. TypeScript, Vue, YAML, and CSS use two spaces. Vue views use PascalCase (`DevicesView.vue`); services and stores use lowercase names (`api.ts`, `session.ts`). Strict TypeScript uses single quotes without semicolons. No formatter is configured; match nearby code.

## Testing Guidelines

There is no automated suite or coverage threshold. Add JUnit 5 tests under each module’s `src/test/java`, mirror production packages, and name classes `*Test`. Frontend tests should use `*.spec.ts` and a documented package script. Always run `make verify`; for integration changes, also run `make up` then `make smoke`.

## Commit & Pull Request Guidelines

History uses Conventional Commit-style subjects, for example `feat: YOOX Cloud GCS ...`. Use concise `feat:`, `fix:`, `docs:`, or `chore:` prefixes. Pull requests should explain scope and risk, link issues, list verification, and include UI screenshots. Call out environment variables, SQL migrations, ports, and flight-control safety implications.

## Security & Configuration

This private repository intentionally tracks all project `.env` and `.env.*` files, including populated deployment configuration, so environments can be restored from Git. Keep repository access tightly controlled, review environment-file diffs before pushing, and rotate exposed credentials if repository access broadens.

## Deployment

Deploy only when the user explicitly requests deployment; completing an update does not require deployment. Use the deployment target and remote checkout path specified for that request. Authenticate with an SSH key, agent, or runtime secret; never store passwords here. Before a requested deployment, run relevant checks and review the diff. Preserve remote `.env` and data volumes, rebuild with Docker Compose, and run `make smoke`.

## Troubleshooting Priority

For Nano/Autel cloud connections that succeed and immediately disconnect, check Nano internet access, default route, DNS, actual clock accuracy, NTP synchronization, and external service dependencies first, before changing IPs, restarting services, or deploying code fixes. On 2026-10-21, the user reported that connecting Nano to the internet stopped the disconnections; Nano had also been about 11 hours 49 minutes behind, with NTP unsynchronized. Connectivity was restored after a separate clock correction, so neither NTP nor any particular external dependency is a proven sole cause. Treat MQTT `takenover` and protocol errors as evidence requiring correlation, not automatic proof of the root cause. Follow `docs/09-遥控器云服务频繁断连排查.md`, including its dated incident record and safe online/offline comparison procedure.
