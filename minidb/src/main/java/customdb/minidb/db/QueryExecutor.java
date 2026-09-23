package customdb.minidb.db;

import customdb.minidb.operator.Operator;
import customdb.minidb.parser.Statement;
import customdb.minidb.planner.Planner;
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
      catalog.createTable(new Schema(create.tableName(), create.columns()));
      System.out.println("Table created: " + create.tableName());
      return;
    }

    try (Operator plan = planner.createPlan(statement)) {
      if (statement instanceof Statement.Select) {
        System.out.println(plan);
      }
      plan.open();

      int count = 0;
      Row row;
      while ((row = plan.next()) != null) {
        if (statement instanceof Statement.Select) {
          System.out.println(row);
        } else if (statement instanceof Statement.Insert insert) {
          System.out.println(
              "Inserted into "
                  + catalog.requireSchema(insert.tableName()).getTableName()
                  + ": "
                  + row);
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
    }
  }

  @Override
  public void close() throws IOException {
    catalog.close();
  }
}
