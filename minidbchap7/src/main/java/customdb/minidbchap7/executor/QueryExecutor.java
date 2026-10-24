package customdb.minidbchap7.executor;

import customdb.minidbchap7.operator.Operator;
import customdb.minidbchap7.parser.Statement;
import customdb.minidbchap7.planner.Planner;
import customdb.minidbchap7.table.Catalog;
import customdb.minidbchap7.table.Row;
import customdb.minidbchap7.table.Schema;
import java.io.IOException;
import java.nio.file.Path;

public class QueryExecutor implements AutoCloseable {
  private final Catalog catalog;
  private final Planner planner;

  public QueryExecutor(Path baseDir) throws IOException {
    this.catalog = new Catalog(baseDir);
    this.planner = new Planner(catalog);
  }

  public void execute(Statement statement) throws IOException {
    if (statement instanceof Statement.CreateTable create) {
      Schema schema = new Schema(create.tableName(), create.columns());
      catalog.createTable(schema);
      System.out.println("Table created: " + create.tableName());
      return;
    }
    executePlan(planner.createPlan(statement), statement);
  }

  private void executePlan(Operator root, Statement statement) throws IOException {
    try {
      root.open();
      int count = 0;
      Row row;
      while ((row = root.next()) != null) {
        if (statement instanceof Statement.Select) {
          System.out.println(row);
        } else if (statement instanceof Statement.Insert insert) {
          System.out.println("Inserted into " + insert.tableName() + ": " + row);
        }
        count++;
      }

      if (statement instanceof Statement.Select && count == 0) {
        System.out.println("(empty)");
      } else if (statement instanceof Statement.Update) {
        System.out.println("Updated " + count + " row(s).");
      } else if (statement instanceof Statement.Delete) {
        System.out.println("Deleted " + count + " row(s).");
      }
    } finally {
      root.close();
    }
  }

  @Override
  public void close() throws IOException {
    catalog.close();
  }
}
