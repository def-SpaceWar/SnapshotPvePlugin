package com.defspacemine.snapshotpve;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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

    @Test
    void editsEachPointParameterWithoutChangingTheOthers() {
        Map<String, Object> changes = Map.of(
            "ticks", 75L, "x", 15.5, "y", 80.0, "z", -12.0,
            "pitch", 45.0f, "yaw", -170.0f, "type", "sinusoidal", "easing", 3
        );
        Map<String, String> values = Map.of(
            "ticks", "75", "x", "15.5", "y", "80", "z", "-12",
            "pitch", "45", "yaw", "190", "type", "sinusoidal", "easing", "3"
        );
        Map<String, String> keys = Map.of(
            "ticks", "ticksToNext", "type", "interpolation", "easing", "easingFactor"
        );
        for (String parameter : changes.keySet()) {
            List<SnapshotPveCamera.InterpolationPoint> points = new ArrayList<>(
                List.of(point(20), point(0))
            );
            Map<String, Object> expected = new java.util.LinkedHashMap<>(
                points.get(0).toMap()
            );
            expected.put(keys.getOrDefault(parameter, parameter), changes.get(parameter));
            Map<String, Object> nextPoint = points.get(1).toMap();
            SnapshotPveCamera.editInterpolationPoint(
                points, "dolly", 1, parameter, values.get(parameter)
            );
            assertEquals(expected, points.get(0).toMap(), parameter);
            assertEquals(nextPoint, points.get(1).toMap(), parameter);
        }
    }

    @Test
    void rejectsInvalidEditsWithoutChangingThePath() {
        List<SnapshotPveCamera.InterpolationPoint> points = new ArrayList<>(
            List.of(point(20), point(0))
        );
        Map<String, Object> original = points.get(0).toMap();
        for (String[] edit : List.of(
            new String[] {"ticks", "0"},
            new String[] {"x", "30000000"},
            new String[] {"y", "NaN"},
            new String[] {"pitch", "90.1"},
            new String[] {"type", "unknown"},
            new String[] {"easing", "5"},
            new String[] {"unknown", "1"}
        )) {
            assertThrows(IllegalArgumentException.class, () ->
                SnapshotPveCamera.editInterpolationPoint(points, "dolly", 1, edit[0], edit[1])
            );
            assertEquals(original, points.get(0).toMap());
        }
        assertThrows(IllegalArgumentException.class, () ->
            SnapshotPveCamera.editInterpolationPoint(points, "dolly", 3, "x", "1")
        );
        SnapshotPveCamera.editInterpolationPoint(points, "dolly", 2, "ticks", "0");
    }

    @Test
    void editedPointSurvivesYamlRoundTrip(@TempDir Path directory) throws IOException {
        List<SnapshotPveCamera.InterpolationPoint> points = new ArrayList<>(
            List.of(point(20), point(0))
        );
        SnapshotPveCamera.editInterpolationPoint(points, "dolly", 1, "type", "exponential");
        SnapshotPveCamera.editInterpolationPoint(points, "dolly", 1, "easing", "4");
        YamlConfiguration config = new YamlConfiguration();
        config.set("points", points.stream().map(SnapshotPveCamera.InterpolationPoint::toMap).toList());
        java.io.File file = directory.resolve("camera.yml").toFile();
        config.save(file);
        Object loaded = YamlConfiguration.loadConfiguration(file).getList("points").get(0);
        assertTrue(loaded instanceof Map<?, ?>);
        assertEquals(
            points.get(0).toMap(),
            SnapshotPveCamera.InterpolationPoint.fromMap((Map<?, ?>) loaded, "dolly").toMap()
        );
    }

    private static SnapshotPveCamera.InterpolationPoint point(long ticks) {
        return new SnapshotPveCamera.InterpolationPoint(
            new SnapshotPveCamera.CameraPoint(1, 2, 3, 10, 20),
            ticks,
            CameraInterpolation.Type.LINEAR,
            1
        );
    }
}
