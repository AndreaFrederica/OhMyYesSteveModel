package cc.sirrus.ysmlib.cpm.java;

import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CpmExpressionEvaluatorTest {
    @Test void implementsUpstreamMathFunctions() {
        assertEquals(2, CpmExpressionEvaluator.evaluate("clamp(5,0,2)", Map.of()), 1e-9);
        assertEquals(1.5, CpmExpressionEvaluator.evaluate("fmod(-0.5,2)+pi-pi", Map.of()), 1e-9);
        assertEquals(Math.PI, CpmExpressionEvaluator.evaluate("pi", Map.of()), 1e-9);
    }

    @Test void consumesLogicalRightHandSidesWithoutShortCircuitParsing() {
        assertEquals(1, CpmExpressionEvaluator.evaluate("0 || 1 && 0 || 1", Map.of()), 1e-9);
        assertEquals(1, CpmExpressionEvaluator.evaluate("1 && 0 || 1", Map.of()), 1e-9);
    }

    @Test void appliesComparisonTolerance() {
        assertEquals(1, CpmExpressionEvaluator.evaluate("1 > 0.999998", Map.of()), 1e-9);
        assertEquals(1, CpmExpressionEvaluator.evaluate("between(1,0.9999995,2)", Map.of()), 1e-9);
    }

    @Test void evaluatesIfBranchesLazily() {
        assertEquals(2, CpmExpressionEvaluator.evaluate("if(false,unknown(1,2),2)", Map.of()), 1e-9);
        assertEquals(3, CpmExpressionEvaluator.evaluate("if(true,1+2,1/0)", Map.of()), 1e-9);
    }
}
