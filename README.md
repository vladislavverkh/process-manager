# process-manager

Durable process orchestration library for business scenarios with conditional transitions,
retry, external events and PostgreSQL-backed state.

## Modules

- `process-manager-core` - process model, DSL and runtime contracts.
- `process-manager-postgres` - PostgreSQL storage for instances, waits, inbox and history.
- `process-manager-task-queue` - adapter that schedules process resume commands through
  `task-queue-postgres`.
- `process-manager-spring-boot-starter` - Spring Boot autoconfiguration.
- `process-manager-testkit` - testing helpers for process definitions.

The project uses `task-queue-postgres` as a composite build:

```kotlin
includeBuild("../task-queue-postgres")
```

