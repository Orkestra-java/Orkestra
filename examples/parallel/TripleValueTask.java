import io.orkestra.core.task.Task;

public final class TripleValueTask implements Task<Void, Integer, Integer> {
    @Override
    public Integer execute(Void context, Integer input) {
        return input * 3;
    }
}
