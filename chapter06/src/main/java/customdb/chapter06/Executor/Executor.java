
// Plannerが作ったPlanを実行する
public class Executor {

    public void execute(Plan plan) {
        if (plan instanceof SeqScanPlan seq) {
            executeSeqScan(seq);
        } else if (plan instanceof IndexScanPlan idx) {
            executeIndexScan(idx);
        }
    }

    private void executeSeqScan(SeqScanPlan plan) {
        System.out.println("SeqScan : " + plan.getTableName());
    }

    private void executeIndexScan(IndexScanPlan plan) {
        System.out.println("IndexScan : " + plan.getTableName());
    }
}