package customdb.minidbchap8.Parser;

import customdb.minidbchap8.DB.Schema.Column;
import java.util.List;

public interface Statement {
  record CreateTable(String tableName, List<Column> columns) implements Statement {}

  record Insert(String tableName, List<String> columnNames, List<String> values)
      implements Statement {}

  record Select(
      List<String> selectColumns, String tableName, Condition whereCondition, JoinClause joinClause)
      implements Statement {}

  record Update(String tableName, String columnName, String value, Condition whereCondition)
      implements Statement {}

  record Delete(String tableName, Condition whereCondition) implements Statement {}

  // トランザクション制御
  record Begin() implements Statement {}

  record Commit() implements Statement {}

  record Abort() implements Statement {}

  record Condition(String left, String operator, String right) {}

  record JoinClause(String tableName, Condition onCondition) {}
}
