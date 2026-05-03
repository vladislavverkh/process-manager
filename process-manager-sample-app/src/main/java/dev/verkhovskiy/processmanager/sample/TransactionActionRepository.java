package dev.verkhovskiy.processmanager.sample;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.sql.Date;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Зависимости являются внедренными инфраструктурными Spring-бинами.")
@RequiredArgsConstructor
public class TransactionActionRepository {

  private final JdbcTemplate jdbcTemplate;

  @Transactional
  public void replaceByTransactionId(String transactionId, List<TransactionAction> actions) {
    jdbcTemplate.update(
        "delete from sample_transaction_action where transaction_id = ?", transactionId);
    jdbcTemplate.batchUpdate(
        """
        insert into sample_transaction_action (
            action_id,
            action_date,
            action_type,
            contract_number,
            amount,
            account_type,
            transaction_id
        ) values (?, ?, ?, ?, ?, ?, ?)
        """,
        actions,
        actions.size(),
        (statement, action) -> {
          statement.setString(1, action.actionId());
          statement.setDate(2, Date.valueOf(action.actionDate()));
          statement.setString(3, action.actionType());
          statement.setString(4, action.contractNumber());
          statement.setBigDecimal(5, action.amount());
          statement.setString(6, action.accountType());
          statement.setString(7, action.transactionId());
        });
  }

  public List<TransactionAction> findByTransactionId(String transactionId) {
    return jdbcTemplate.query(
        """
        select action_id,
               action_date,
               action_type,
               contract_number,
               amount,
               account_type,
               transaction_id
          from sample_transaction_action
         where transaction_id = ?
         order by action_id
        """,
        (resultSet, rowNumber) ->
            new TransactionAction(
                resultSet.getString("action_id"),
                resultSet.getDate("action_date").toLocalDate(),
                resultSet.getString("action_type"),
                resultSet.getString("contract_number"),
                resultSet.getBigDecimal("amount"),
                resultSet.getString("account_type"),
                resultSet.getString("transaction_id")),
        transactionId);
  }
}
