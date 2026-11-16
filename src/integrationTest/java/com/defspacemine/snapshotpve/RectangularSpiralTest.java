package com.defspacemine.snapshotpve;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.bukkit.Color;
import org.junit.jupiter.api.Test;

class RectangularSpiralTest {
    @Test
    void countsStepsWithoutFloatingPointRoundingErrors() {
        assertEquals(150, PvePrologue.spiralStepCount("15", "0.1"));
        assertEquals(4, PvePrologue.spiralStepCount("1", "0.3"));
        assertEquals(1, PvePrologue.spiralStepCount("0.1", "1"));
        assertThrows(IllegalArgumentException.class,
            () -> PvePrologue.spiralStepCount("2147483648", "1"));
    }

    @Test
    void followsAllFourSidesOfARectangle() {
        assertArrayEquals(new double[] {-2, -3}, PvePrologue.rectangularSpiralOffset(4, 6, 0));
        assertArrayEquals(new double[] {2, -3}, PvePrologue.rectangularSpiralOffset(4, 6, 4));
        assertArrayEquals(new double[] {2, 3}, PvePrologue.rectangularSpiralOffset(4, 6, 10));
        assertArrayEquals(new double[] {-2, 3}, PvePrologue.rectangularSpiralOffset(4, 6, 14));
        assertArrayEquals(new double[] {-2, 0}, PvePrologue.rectangularSpiralOffset(4, 6, 17));
    }

    @Test
    void wrapsAcrossMultipleLoopsAndCapsTheFinalHeight() {
        double distance = 0;
        for (int i = 0; i < 9; i++) {
            distance = PvePrologue.advanceSpiralDistance(distance, 7, 20);
        }
        assertEquals(3, distance);
        assertArrayEquals(new double[] {1, -3}, PvePrologue.rectangularSpiralOffset(4, 6, distance));
        assertEquals(0.3, PvePrologue.spiralHeight(0, 0.3, 1));
        assertEquals(1, PvePrologue.spiralHeight(3, 0.3, 1));
    }

    @Test
    void startsAtEachCornerAndSpreadsParticlesAlongEverySide() {
        assertArrayEquals(new double[] {-2, -3}, PvePrologue.rectangularSpiralOffset(
            4, 6, PvePrologue.spiralCornerDistance(4, 6, 0)));
        assertArrayEquals(new double[] {2, -3}, PvePrologue.rectangularSpiralOffset(
            4, 6, PvePrologue.spiralCornerDistance(4, 6, 1)));
        assertArrayEquals(new double[] {2, 3}, PvePrologue.rectangularSpiralOffset(
            4, 6, PvePrologue.spiralCornerDistance(4, 6, 2)));
        assertArrayEquals(new double[] {-2, 3}, PvePrologue.rectangularSpiralOffset(
            4, 6, PvePrologue.spiralCornerDistance(4, 6, 3)));
        assertArrayEquals(new int[] {3, 4}, PvePrologue.spiralSideParticles(4, 6, 1.5));
        assertArrayEquals(new int[] {1, 1}, PvePrologue.spiralSideParticles(4, 6, 100));
        assertThrows(IllegalArgumentException.class,
            () -> PvePrologue.spiralSideParticles(4, 6, 0.001));
    }

    @Test
    void dustGradientStartsWithTheChosenColorAndEndsWhite() {
        Color orange = Color.fromRGB(255, 0, 0);
        assertEquals(orange, PvePrologue.spiralDustColor(orange, 0, 3));
        assertEquals(Color.fromRGB(255, 128, 128), PvePrologue.spiralDustColor(orange, 1, 3));
        assertEquals(Color.WHITE, PvePrologue.spiralDustColor(orange, 2, 3));
        assertEquals(orange, PvePrologue.spiralDustColor(orange, 0, 1));
    }

    @Test
    void rotatesRectangleAroundItsCenterInEitherDirection() {
        double[] offset = {2, -3};
        assertArrayEquals(offset, PvePrologue.rotateSpiralOffset(offset, 1, 0), 1e-10);
        assertEquals(0, PvePrologue.spiralRotationRadians(45, 0));
        assertEquals(Math.PI / 2, PvePrologue.spiralRotationRadians(45, 2), 1e-10);
        assertEquals(-Math.PI / 2, PvePrologue.spiralRotationRadians(-45, 2), 1e-10);
        assertEquals(Math.PI / 2, PvePrologue.spiralRotationRadians(405, 2), 1e-10);
        assertArrayEquals(new double[] {3, 2},
            PvePrologue.rotateSpiralOffset(offset, Math.cos(Math.PI / 2), Math.sin(Math.PI / 2)), 1e-10);
        assertArrayEquals(new double[] {-3, -2},
            PvePrologue.rotateSpiralOffset(offset, Math.cos(-Math.PI / 2), Math.sin(-Math.PI / 2)), 1e-10);
    }

    @Test
    void acceptsOnlyFiniteRotationAngles() {
        assertEquals(-5, PvePrologue.parseSpiralAngle("-5"));
        assertEquals(0, PvePrologue.parseSpiralAngle("0"));
        assertThrows(IllegalArgumentException.class, () -> PvePrologue.parseSpiralAngle("NaN"));
        assertThrows(IllegalArgumentException.class, () -> PvePrologue.parseSpiralAngle("Infinity"));
        assertThrows(IllegalArgumentException.class, () -> PvePrologue.parseSpiralAngle("bad"));
    }
}
