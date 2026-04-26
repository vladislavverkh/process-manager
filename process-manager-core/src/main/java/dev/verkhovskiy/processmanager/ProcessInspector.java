package dev.verkhovskiy.processmanager;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Публичный API чтения состояния процессов без изменения runtime-состояния. */
public interface ProcessInspector {

  int DEFAULT_HISTORY_LIMIT = 200;

  Optional<ProcessInstanceView> findInstance(UUID instanceId);

  Optional<ProcessInstanceView> findActiveInstance(String processType, String businessKey);

  List<ProcessInstanceView> findInstances(ProcessInstanceQuery query);

  List<ProcessWaitView> findWaits(UUID instanceId);

  default List<ProcessHistoryView> findHistory(UUID instanceId) {
    return findHistory(instanceId, DEFAULT_HISTORY_LIMIT);
  }

  List<ProcessHistoryView> findHistory(UUID instanceId, int limit);

  default Optional<ProcessDetailsView> findDetails(UUID instanceId) {
    return findInstance(instanceId)
        .map(
            instance ->
                new ProcessDetailsView(instance, findWaits(instanceId), findHistory(instanceId)));
  }
}
