package customdb.chapter08.Executor;

import customdb.chapter08.DB.Catalog;
import customdb.chapter08.DB.Row;
import customdb.chapter08.DB.Schema;
import customdb.chapter08.Operator.Operator;
import customdb.chapter08.Parser.Statement;
import customdb.chapter08.Planner.Planner;
import customdb.chapter08.Transaction.LockMode;
import customdb.chapter08.Transaction.Transaction;
import customdb.chapter08.Transaction.TransactionManager;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class QueryExecutor {
  private final Catalog catalog;
  private final Planner planner;
  private final TransactionManager transactionManager;

  public QueryExecutor(Path baseDir) throws IOException {
    this.catalog = new Catalog(baseDir);
    this.planner = new Planner(this.catalog);
    this.transactionManager = new TransactionManager();
  }

  public void execute(Statement statement) throws IOException {
    if (statement instanceof Statement.Begin) {
      Transaction transaction = transactionManager.begin();
      System.out.println("Transaction started: tx" + transaction.id());
      return;
    }

    if (statement instanceof Statement.Commit) {
      Transaction transaction = transactionManager.commit();
      System.out.println("Transaction committed: tx" + transaction.id());
      return;
    }

    if (statement instanceof Statement.Abort) {
      Transaction transaction = transactionManager.currentTransaction();
      int undone = transactionManager.abort();
      System.out.println(
          "Transaction aborted: tx" + transaction.id() + " (" + undone + " operation(s) undone)");
      return;
    }

    if (statement instanceof Statement.CreateTable createTableStmt) {
      if (transactionManager.hasActiveTransaction()) {
        throw new IllegalStateException("CREATE TABLE is not allowed inside a transaction.");
      }
      executeCreateTable(createTableStmt);
      return;
    }

    // トランザクション外の文は、その文だけのトランザクションとして自動コミットする
    boolean autoCommit = !transactionManager.hasActiveTransaction();
    Transaction transaction =
        autoCommit ? transactionManager.begin() : transactionManager.currentTransaction();

    try {
      acquireLocks(transaction, statement);
      Operator rootPlan = planner.createPlan(statement, transaction);
      executePlan(rootPlan, statement);

      if (autoCommit) {
        transactionManager.commit();
      }
    } catch (IOException | RuntimeException e) {
      // 文の途中でエラーが起きたら、それまでの変更をすべて取り消す
      try {
        if (transactionManager.hasActiveTransaction()) {
          transactionManager.abort();
        }
      } catch (IOException abortError) {
        e.addSuppressed(abortError);
      }
      throw e;
    }
  }

  // 文の種類に応じてテーブルのロックを取得する（Commit/Abortまで保持する）
  private void acquireLocks(Transaction transaction, Statement statement) {
    List<String> tableNames = new ArrayList<>();
    LockMode mode;

    if (statement instanceof Statement.Select selectStmt) {
      tableNames.add(selectStmt.tableName());
      if (selectStmt.joinClause() != null) {
        tableNames.add(selectStmt.joinClause().tableName());
      }
      mode = LockMode.SHARED;
    } else if (statement instanceof Statement.Insert insertStmt) {
      tableNames.add(insertStmt.tableName());
      mode = LockMode.EXCLUSIVE;
    } else if (statement instanceof Statement.Update updateStmt) {
      tableNames.add(updateStmt.tableName());
      mode = LockMode.EXCLUSIVE;
    } else if (statement instanceof Statement.Delete deleteStmt) {
      tableNames.add(deleteStmt.tableName());
      mode = LockMode.EXCLUSIVE;
    } else {
      return;
    }

    // デッドロック対策：複数のテーブルは常に同じ順序（名前順）でロックする
    Collections.sort(tableNames);
    for (String tableName : tableNames) {
      transactionManager.acquireLock(transaction, tableName, mode);
    }
  }

  private void executeCreateTable(Statement.CreateTable statement) throws IOException {
    Schema schema = new Schema(statement.tableName(), statement.columns());
    catalog.createTable(schema);
    System.out.println("Table created: " + statement.tableName());
  }

  private void executePlan(Operator rootPlan, Statement statement) throws IOException {
    rootPlan.open();

    try {
      int count = 0;
      while (true) {
        Row row = rootPlan.next();
        if (row == null) {
          break;
        }
        if (statement instanceof Statement.Select) {
          System.out.println(row);
        } else if (statement instanceof Statement.Insert insertStmt) {
          System.out.println("Inserted into " + insertStmt.tableName() + ": " + row);
        }
        count++;
      }

      if (count == 0 && statement instanceof Statement.Select) {
        System.out.println("(empty)");
      } else if (statement instanceof Statement.Update) {
        System.out.println("Updated " + count + " row(s).");
      } else if (statement instanceof Statement.Delete) {
        System.out.println("Deleted " + count + " row(s).");
      }
    } finally {
      rootPlan.close();
    }
  }

  public void close() throws IOException {
    if (transactionManager.hasActiveTransaction()) {
      Transaction transaction = transactionManager.currentTransaction();
      int undone = transactionManager.abort();
      System.out.println(
          "Active transaction aborted: tx"
              + transaction.id()
              + " ("
              + undone
              + " operation(s) undone)");
    }

    catalog.close();
  }
}
