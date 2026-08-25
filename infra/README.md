# USI infrastructure

This directory is the repository home for local deployment support and the
production deployment/IaC definitions introduced by dedicated infrastructure
tickets.

The frozen deployment boundary is:

```text
/       -> Next.js
/api/*  -> Spring Boot
/ws/*   -> Spring Boot WebSocket/STOMP
```

Infrastructure added here must keep CORS deny/off by default, keep data services
private, use only dummy development credentials locally, and inject staging or
production secrets at runtime. USI-41 intentionally adds no Compose stack,
Terraform/Ansible implementation, environment file, or production action.

