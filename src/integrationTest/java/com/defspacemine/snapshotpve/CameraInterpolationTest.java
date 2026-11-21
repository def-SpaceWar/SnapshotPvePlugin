package com.defspacemine.snapshotpve;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CameraInterpolationTest {
    @Test
    void interpolationTypesReachExactEndpointsForEveryEasingFactor() {
        for (CameraInterpolation.Type type : CameraInterpolation.Type.values()) {
            for (int easingFactor = 0; easingFactor <= 4; easingFactor++) {
                assertEquals(0, CameraInterpolation.apply(type, easingFactor, 0));
                assertEquals(1, CameraInterpolation.apply(type, easingFactor, 1));
            }
        }
    }

    @Test
    void easingFactorStrengthensTheCurveAroundItsEndpoints() {
        double defaultProgress = CameraInterpolation.apply(
            CameraInterpolation.Type.LINEAR,
            0,
            0.25
        );
        double strongestProgress = CameraInterpolation.apply(
            CameraInterpolation.Type.LINEAR,
            4,
            0.25
        );

        assertTrue(strongestProgress < defaultProgress);
        assertEquals(
            1 - strongestProgress,
            CameraInterpolation.apply(
                CameraInterpolation.Type.LINEAR,
                4,
                0.75
            ),
            0.000001
        );
    }

    @Test
    void acceptsTheRequestedSinusoidalSpelling() {
        assertEquals(
            CameraInterpolation.Type.SINUSOIDAL,
            CameraInterpolation.parseType("sinosudial")
        );
    }
}
