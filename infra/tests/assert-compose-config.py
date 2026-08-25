#!/usr/bin/env python3
"""Assert security and contract invariants in normalized Compose JSON."""

from __future__ import annotations

import json
import sys
from typing import Any


def require(condition: bool, message: str) -> None:
    if not condition:
        raise AssertionError(message)


def environment(service: dict[str, Any]) -> dict[str, str]:
    value = service.get("environment", {})
    require(isinstance(value, dict), "service environment must be a mapping")
    return value


def volume_targets(service: dict[str, Any]) -> set[str]:
    return {mount["target"] for mount in service.get("volumes", [])}


def main() -> None:
    model = json.load(sys.stdin)
    services = model.get("services", {})
    require(
        set(services) == {"postgres", "rabbitmq", "minio", "minio-init"},
        "Compose must contain only the three data services and MinIO initializer",
    )

    expected_images = {
        "postgres": "postgres:18.6",
        "rabbitmq": "rabbitmq:4.3.5-management",
        "minio": "minio/minio:RELEASE.2025-09-07T16-13-09Z",
        "minio-init": "minio/mc:RELEASE.2025-08-13T08-35-41Z",
    }
    for service_name, image in expected_images.items():
        require(
            services[service_name].get("image") == image,
            f"{service_name} must use the reviewed pinned image",
        )

    expected_ports = {
        "postgres": {5432},
        "rabbitmq": {5672, 15672},
        "minio": {9000, 9001},
    }
    for service_name, targets in expected_ports.items():
        ports = services[service_name].get("ports", [])
        require(
            {int(port["target"]) for port in ports} == targets,
            f"{service_name} exposes an unexpected container port",
        )
        require(
            {int(port["published"]) for port in ports} == targets,
            f"{service_name} must use the documented default host ports",
        )
        require(
            all(port.get("host_ip") == "127.0.0.1" for port in ports),
            f"{service_name} ports must bind only to IPv4 loopback",
        )

    for service_name, service in services.items():
        require(
            service.get("privileged") is not True,
            f"{service_name} must not run privileged",
        )
        require(
            set(service.get("networks", {})) == {"private"},
            f"{service_name} must attach only to the private network",
        )
        if service_name != "minio-init":
            require(
                service.get("restart") == "unless-stopped",
                f"{service_name} must restart unless explicitly stopped",
            )
            healthcheck = service.get("healthcheck", {})
            require(
                healthcheck.get("test"),
                f"{service_name} must define a healthcheck",
            )

    require(
        "check_running"
        in " ".join(services["rabbitmq"]["healthcheck"]["test"]),
        "RabbitMQ health must verify that the broker application is running",
    )
    require(
        "/minio/health/ready"
        in " ".join(services["minio"]["healthcheck"]["test"]),
        "MinIO health must use its readiness endpoint",
    )

    private_network = model.get("networks", {}).get("private", {})
    require(
        private_network.get("external") is not True,
        "the data-service network must be project-scoped",
    )
    require(
        private_network.get("driver") == "bridge",
        "the private network must use the bridge driver",
    )

    expected_volume_targets = {
        "postgres": {"/var/lib/postgresql"},
        "rabbitmq": {"/var/lib/rabbitmq"},
        "minio": {"/data"},
    }
    require(
        set(model.get("volumes", {})) == {
            "postgres_data",
            "rabbitmq_data",
            "minio_data",
        },
        "Compose must declare exactly the three persistent named volumes",
    )
    for service_name, targets in expected_volume_targets.items():
        mounts = services[service_name].get("volumes", [])
        require(
            volume_targets(services[service_name]) == targets,
            f"{service_name} must mount exactly its persistent data path",
        )
        require(
            all(mount.get("type") == "volume" for mount in mounts),
            f"{service_name} persistence must use named volumes",
        )

    postgres = services["postgres"]
    postgres_env = environment(postgres)
    require(
        postgres_env.get("POSTGRES_DB") == "application_support_dev",
        "the development database name is part of USI-42",
    )
    require(
        postgres_env.get("POSTGRES_USER") not in {None, "", "postgres"},
        "PostgreSQL must use a non-default development user",
    )
    require(
        postgres_env.get("POSTGRES_PASSWORD") == "postgres_dev_only_change_me",
        "the checked-in environment may contain only the reviewed dummy DB password",
    )
    require(
        postgres_env.get("POSTGRES_HOST_AUTH_METHOD") != "trust",
        "host authentication must not be configured as trust",
    )
    require(
        not any(
            target.startswith("/docker-entrypoint-initdb.d")
            for target in volume_targets(postgres)
        ),
        "Compose must not inject SQL schema or init scripts",
    )

    rabbitmq_env = environment(services["rabbitmq"])
    require(
        rabbitmq_env.get("RABBITMQ_DEFAULT_USER") not in {None, "", "guest"},
        "RabbitMQ must use a non-default development user",
    )
    require(
        rabbitmq_env.get("RABBITMQ_DEFAULT_VHOST") == "application_support_dev",
        "RabbitMQ must use the development vhost",
    )
    require(
        rabbitmq_env.get("RABBITMQ_DEFAULT_PASS")
        == "rabbitmq_dev_only_change_me",
        "the checked-in environment may contain only the reviewed dummy broker password",
    )

    minio_env = environment(services["minio"])
    require(
        minio_env.get("MINIO_ROOT_USER") == "usi-dev-root"
        and minio_env.get("MINIO_ROOT_PASSWORD") == "minio_dev_only_change_me",
        "the checked-in environment may contain only reviewed dummy MinIO credentials",
    )

    minio_init = services["minio-init"]
    dependency = minio_init.get("depends_on", {}).get("minio", {})
    require(
        dependency.get("condition") == "service_healthy",
        "bucket initialization must wait for healthy MinIO",
    )
    raw_init_command = minio_init.get("command", [])
    init_command = (
        raw_init_command
        if isinstance(raw_init_command, str)
        else "\n".join(raw_init_command)
    )
    require(
        "mc mb --ignore-existing" in init_command,
        "bucket initialization must be idempotent",
    )
    require(
        "anonymous" not in init_command,
        "development buckets must remain private",
    )
    init_env = environment(minio_init)
    require(
        init_env.get("MINIO_ATTACHMENTS_BUCKET") == "usi-attachments-dev"
        and init_env.get("MINIO_EXPORTS_BUCKET") == "usi-exports-dev",
        "both private development buckets must be configured",
    )


if __name__ == "__main__":
    try:
        main()
    except (AssertionError, KeyError, TypeError, ValueError) as error:
        print(f"Compose contract check failed: {error}", file=sys.stderr)
        raise SystemExit(1) from error
