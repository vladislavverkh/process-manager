rootProject.name = "process-manager"

includeBuild("../task-queue-postgres")

include(
    "process-manager-core",
    "process-manager-postgres",
    "process-manager-task-queue",
    "process-manager-spring-boot-starter",
    "process-manager-testkit"
)
