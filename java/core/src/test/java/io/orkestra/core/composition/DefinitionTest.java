package io.orkestra.core.composition;

import io.orkestra.core.task.Task;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DefinitionTest {
    @Test void buildersAndNamesRemainIndependentWithoutAnExecutor() {
        Task<Void, Integer, String> task = (context, input) -> input.toString();
        var base = Compositions.<Void, Integer>create();
        var first = base.named("first").perform(task).build();
        var second = base.named("second").perform(task).build();
        assertEquals("first", first.name().orElseThrow());
        assertEquals("second", second.name().orElseThrow());
        assertTrue(base.build().name().isEmpty());
    }

    @Test void duplicateParticipationIsRejectedDuringDefinition() {
        var reference = TaskRef.<Void, Integer, Integer>of((context, input) -> input);
        assertThrows(IllegalArgumentException.class, () -> Compositions.<Void, Integer>create()
                .parallel(group -> group.perform(reference).perform(reference)));
    }
}
