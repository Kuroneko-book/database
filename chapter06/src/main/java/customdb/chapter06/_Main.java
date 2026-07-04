public class _Main {

    public static void main(String[] args) {

        SelectStatement statement =
                new SelectStatement(
                        "users",
                        new Condition("id", "100"));

        Planner planner = new Planner();

        Plan plan =
                planner.createPlan(statement);

        Executor executor =
                new Executor();

        executor.execute(plan);

    }

}