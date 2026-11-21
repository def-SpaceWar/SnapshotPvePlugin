package com.defspacemine.snapshotpve;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class CameraPolynomialTest {
    @Test
    void evaluatesPolynomialExpressionsAtTickValues() {
        CameraPolynomial polynomial = CameraPolynomial.parse(
            "100+0.5*t-0.01*t^2"
        );

        assertEquals(100, polynomial.evaluate(0), 0.000001);
        assertEquals(104, polynomial.evaluate(10), 0.000001);
    }

    @Test
    void supportsParenthesesAndConstantDivision() {
        CameraPolynomial polynomial = CameraPolynomial.parse(
            "(2+t)^2/4"
        );

        assertEquals(6.25, polynomial.evaluate(3), 0.000001);
    }

    @Test
    void rejectsExpressionsThatAreNotDegreeTwelvePolynomials() {
        assertThrows(
            IllegalArgumentException.class,
            () -> CameraPolynomial.parse("t^13")
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> CameraPolynomial.parse("1/t")
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> CameraPolynomial.parse("sin(t)")
        );
    }
}
