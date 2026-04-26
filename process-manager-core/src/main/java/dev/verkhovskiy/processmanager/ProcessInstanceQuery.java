package dev.verkhovskiy.processmanager;

import java.time.Instant;
import java.util.Set;

/** Фильтр поиска экземпляров процессов для операционной диагностики. */
public record ProcessInstanceQuery(
    String processType,
    String businessKey,
    String state,
    Set<ProcessInstanceStatus> statuses,
    Instant deadlineAtOrBefore,
    int limit) {

  public static final int DEFAULT_LIMIT = 100;
  public static final int MAX_LIMIT = 1_000;

  public ProcessInstanceQuery {
    statuses = Set.copyOf(statuses == null ? Set.of() : statuses);
    if (limit <= 0) {
      limit = DEFAULT_LIMIT;
    }
    if (limit > MAX_LIMIT) {
      throw new IllegalArgumentException("limit must not be greater than " + MAX_LIMIT);
    }
  }

  /** Возвращает пустой фильтр с лимитом по умолчанию. */
  public static ProcessInstanceQuery all() {
    return builder().build();
  }

  /** Возвращает фильтр только по активным экземплярам процессов. */
  public static ProcessInstanceQuery active() {
    return builder().activeOnly().build();
  }

  /** Создает builder для читаемого задания фильтров. */
  public static Builder builder() {
    return new Builder();
  }

  /** Builder фильтра поиска экземпляров процессов. */
  public static final class Builder {
    private String processType;
    private String businessKey;
    private String state;
    private Set<ProcessInstanceStatus> statuses = Set.of();
    private Instant deadlineAtOrBefore;
    private int limit = DEFAULT_LIMIT;

    private Builder() {}

    public Builder processType(String processType) {
      this.processType = processType;
      return this;
    }

    public Builder businessKey(String businessKey) {
      this.businessKey = businessKey;
      return this;
    }

    public Builder state(String state) {
      this.state = state;
      return this;
    }

    public Builder status(ProcessInstanceStatus status) {
      this.statuses = status == null ? Set.of() : Set.of(status);
      return this;
    }

    public Builder statuses(Set<ProcessInstanceStatus> statuses) {
      this.statuses = Set.copyOf(statuses == null ? Set.of() : statuses);
      return this;
    }

    public Builder activeOnly() {
      this.statuses = Set.of(ProcessInstanceStatus.RUNNING, ProcessInstanceStatus.WAITING);
      return this;
    }

    public Builder deadlineAtOrBefore(Instant deadlineAtOrBefore) {
      this.deadlineAtOrBefore = deadlineAtOrBefore;
      return this;
    }

    public Builder limit(int limit) {
      this.limit = limit;
      return this;
    }

    public ProcessInstanceQuery build() {
      return new ProcessInstanceQuery(
          processType, businessKey, state, statuses, deadlineAtOrBefore, limit);
    }
  }
}
