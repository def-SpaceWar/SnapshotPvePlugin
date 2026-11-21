package com.defspacemine.snapshotpve;

import java.util.Locale;

final class CameraInterpolation {
    private CameraInterpolation() {}

    static Type parseType(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "linear" -> Type.LINEAR;
            case "sinusoidal", "sinosudial" -> Type.SINUSOIDAL;
            case "exponential" -> Type.EXPONENTIAL;
            default -> throw new IllegalArgumentException(
                "Interpolation type must be linear, sinusoidal, or exponential."
            );
        };
    }

    static double apply(Type type, int easingFactor, double progress) {
        if (easingFactor < 0 || easingFactor > 4) {
            throw new IllegalArgumentException(
                "Easing factor must be from 0 to 4."
            );
        }
        if (!Double.isFinite(progress) || progress < 0 || progress > 1) {
            throw new IllegalArgumentException(
                "Interpolation progress must be between 0 and 1."
            );
        }
        if (progress == 0 || progress == 1) {
            return progress;
        }

        double value = switch (type) {
            case LINEAR -> progress;
            case SINUSOIDAL -> 0.5 - 0.5 * Math.cos(Math.PI * progress);
            case EXPONENTIAL -> progress < 0.5
                ? 0.5 * Math.pow(2, 20 * progress - 10)
                : 1 - 0.5 * Math.pow(2, -20 * progress + 10);
        };
        if (easingFactor == 0) {
            return value;
        }

        double exponent = 1 + easingFactor;
        double valuePower = Math.pow(value, exponent);
        return valuePower /
            (valuePower + Math.pow(1 - value, exponent));
    }

    enum Type {
        LINEAR,
        SINUSOIDAL,
        EXPONENTIAL
    }
}
