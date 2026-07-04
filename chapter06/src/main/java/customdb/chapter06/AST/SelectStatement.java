public class SelectStatement {

    private final String tableName;
    private final Condition condition;

    public SelectStatement(String tableName,
                           Condition condition) {

        this.tableName = tableName;
        this.condition = condition;
    }

    public String getTableName() {
        return tableName;
    }

    public Condition getCondition() {
        return condition;
    }
}