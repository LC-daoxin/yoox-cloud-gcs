SHELL := /bin/sh

.PHONY: init preflight config build up up-nano up-monitoring down logs ps smoke api-build web-build verify

init:
	@test -f .env || cp .env.example .env
	@echo "Edit .env and replace every value containing change_me or replace_with before use."

preflight:
	./scripts/preflight.sh

config: preflight

build: preflight
	docker compose --env-file .env build

up: preflight
	docker compose --env-file .env up -d --build --wait --wait-timeout 300

# Nano/Orin 资源受限部署：叠加 compose.nano.yml 覆盖资源限制。
# 先单独 build（不含 --build 以避免 compose.nano.yml 触发不必要的重建），
# 再 up 时使用双文件。
up-nano: preflight
	docker compose --env-file .env build
	docker compose -f compose.yml -f compose.nano.yml --env-file .env up -d --wait --wait-timeout 300

up-monitoring: preflight
	docker compose --env-file .env --profile monitoring up -d --build --wait --wait-timeout 300

down:
	docker compose --env-file .env down

logs:
	docker compose --env-file .env logs -f --tail=200

ps:
	docker compose --env-file .env ps

smoke:
	./scripts/smoke-test.sh

api-build:
	mvn -B -DskipTests clean package -pl cloud-service -am

web-build:
	cd web-console && npm ci && npm run build

verify: config api-build web-build
