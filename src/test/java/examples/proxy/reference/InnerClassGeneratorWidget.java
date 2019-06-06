package examples.proxy.reference;

import edu.illinois.cs.cs125.answerable.Generator;
import edu.illinois.cs.cs125.answerable.Solution;
import edu.illinois.cs.cs125.answerable.TestOutput;
import edu.illinois.cs.cs125.answerable.Verify;
import org.junit.jupiter.api.Assertions;

import java.util.Random;

public class InnerClassGeneratorWidget {
    private int numSprings;

    public InnerClassGeneratorWidget(int springs) {
        numSprings = springs;
    }

    @Solution
    public void moreSprings(int extras) {
        numSprings += extras + 1;
    }

    public int getSprings() {
        return numSprings;
    }

    @Generator
    public static InnerClassGeneratorWidget generate(int complexity, Random random) {
        // Intentionally not using lambda syntax to guarantee that an inner class is generated
        final InnerClassGeneratorWidget[] widgetHolder = new InnerClassGeneratorWidget[1];
        new Runnable() {
            @Override
            public void run() {
                NamedInner inner = new NamedInner(new InnerClassGeneratorWidget(random.nextInt(complexity + 1)));
                widgetHolder[0] = inner.fiddle();
                System.out.print("Runnable ran");
            }
        }.run();
        System.out.println(", outer method ran");
        return widgetHolder[0];
    }

    @Verify
    public static void verify(TestOutput<InnerClassGeneratorWidget> ours, TestOutput<InnerClassGeneratorWidget> theirs) {
        Assertions.assertEquals(ours.getReceiver().getSprings(), theirs.getReceiver().getSprings());
    }

    private static class NamedInner {
        private InnerClassGeneratorWidget widget;
        private NamedInner(InnerClassGeneratorWidget setWidget) {
            widget = setWidget;
        }
        private InnerClassGeneratorWidget fiddle() {
            widget.moreSprings(1);
            return widget;
        }
    }
}
