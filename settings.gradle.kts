rootProject.name = "process-manager"

val includeTaskQueueAdapter =
    providers
        .gradleProperty("processManager.includeTaskQueueAdapter")
        .map(String::toBoolean)
        .orElse(false)
        .get()

include(
    "process-manager-core",
    "process-manager-postgres",
    "process-manager-rest",
    "process-manager-sample-app",
    "process-manager-spring-boot-starter",
    "process-manager-testkit"
)

if (includeTaskQueueAdapter) {
    include("process-manager-task-queue")
}
