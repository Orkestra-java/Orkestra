import io.orkestra.core.Orchestrator;
import io.orkestra.core.composition.Compositions;
import io.orkestra.core.composition.TaskRef;

public final class ParallelExample {
    public static void main(String[] args) throws Exception {
        var doubleValue = TaskRef.of(new DoubleValueTask());
        var tripleValue = TaskRef.of(new TripleValueTask());

        var composition = Compositions.<Void, Integer>create()
                .parallel(group -> group.concurrently(2)
                        .perform(doubleValue)
                        .perform(tripleValue))
                .join((context, results) -> new Totals(
                        results.get(doubleValue), results.get(tripleValue)))
                .build();

        try (var orchestrator = new Orchestrator()) {
            var execution = orchestrator.perform(composition, null, 7);
            System.out.println(execution.output());
        }
    }
}
