rootProject.name = "process-manager"

val taskQueuePostgresDir = file("../task-queue-postgres")
val includeTaskQueueAdapter =
    providers
        .gradleProperty("processManager.includeTaskQueueAdapter")
        .map(String::toBoolean)
        .orElse(taskQueuePostgresDir.isDirectory)
        .get()

if (includeTaskQueueAdapter && taskQueuePostgresDir.isDirectory) {
    includeBuild(taskQueuePostgresDir)
}

include(
    "process-manager-core",
    "process-manager-postgres",
    "process-manager-rest",
    "process-manager-spring-boot-starter",
    "process-manager-testkit"
)

if (includeTaskQueueAdapter) {
    include("process-manager-task-queue")
}
