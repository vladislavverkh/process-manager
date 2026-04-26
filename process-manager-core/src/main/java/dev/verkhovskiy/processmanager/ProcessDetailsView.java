package dev.verkhovskiy.processmanager;

import java.util.List;

/** Детальная карточка процесса: текущее состояние, ожидания и история. */
public record ProcessDetailsView(
    ProcessInstanceView instance, List<ProcessWaitView> waits, List<ProcessHistoryView> history) {

  public ProcessDetailsView {
    waits = List.copyOf(waits == null ? List.of() : waits);
    history = List.copyOf(history == null ? List.of() : history);
  }
}
