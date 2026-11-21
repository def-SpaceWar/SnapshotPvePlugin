package com.defspacemine.snapshotpve;

import java.util.Arrays;

final class CameraPolynomial {
    private static final int MAX_DEGREE = 12;

    private final double[] coefficients;

    private CameraPolynomial(double[] coefficients) {
        this.coefficients = coefficients;
    }

    static CameraPolynomial parse(String equation) {
        if (equation == null || equation.isBlank()) {
            throw new IllegalArgumentException("A polynomial equation is required.");
        }

        Parser parser = new Parser(equation);
        double[] coefficients = parser.parse();
        return new CameraPolynomial(coefficients);
    }

    double evaluate(double ticks) {
        double result = 0;
        for (int degree = MAX_DEGREE; degree >= 0; degree--) {
            result = result * ticks + coefficients[degree];
            if (!Double.isFinite(result)) {
                throw new IllegalArgumentException(
                    "The polynomial result is not finite at this time."
                );
            }
        }
        return result;
    }

    double constantTerm() {
        return coefficients[0];
    }

    private static final class Parser {
        private final String input;
        private int index;

        Parser(String input) {
            this.input = input;
        }

        double[] parse() {
            double[] result = parseExpression();
            skipWhitespace();
            if (index != input.length()) {
                throw invalidEquation();
            }
            return result;
        }

        private double[] parseExpression() {
            double[] result = parseTerm();
            while (true) {
                skipWhitespace();
                if (consume('+')) {
                    result = add(result, parseTerm());
                } else if (consume('-')) {
                    result = subtract(result, parseTerm());
                } else {
                    return result;
                }
            }
        }

        private double[] parseTerm() {
            double[] result = parseUnary();
            while (true) {
                skipWhitespace();
                if (consume('*')) {
                    result = multiply(result, parseUnary());
                } else if (consume('/')) {
                    result = divide(result, parseUnary());
                } else {
                    return result;
                }
            }
        }

        private double[] parseUnary() {
            skipWhitespace();
            if (consume('+')) {
                return parseUnary();
            }
            if (consume('-')) {
                return scale(parseUnary(), -1);
            }
            return parsePower();
        }

        private double[] parsePower() {
            double[] base = parsePrimary();
            skipWhitespace();
            if (!consume('^')) {
                return base;
            }

            skipWhitespace();
            int exponentStart = index;
            if (consume('+')) {
                exponentStart = index;
            } else if (peek('-')) {
                throw new IllegalArgumentException(
                    "Negative powers are not supported; use a polynomial of degree 12 or less."
                );
            }
            while (index < input.length() && Character.isDigit(input.charAt(index))) {
                index++;
            }
            if (index == exponentStart) {
                throw invalidEquation();
            }

            int exponent;
            try {
                exponent = Integer.parseInt(input.substring(exponentStart, index));
            } catch (NumberFormatException exception) {
                throw invalidEquation();
            }
            if (exponent > MAX_DEGREE) {
                throw new IllegalArgumentException(
                    "Polynomial powers cannot exceed 12."
                );
            }

            double[] result = constant(1);
            for (int power = 0; power < exponent; power++) {
                result = multiply(result, base);
            }
            return result;
        }

        private double[] parsePrimary() {
            skipWhitespace();
            if (consume('(')) {
                double[] expression = parseExpression();
                skipWhitespace();
                if (!consume(')')) {
                    throw invalidEquation();
                }
                return expression;
            }

            if (index < input.length() && (input.charAt(index) == 't' ||
                input.charAt(index) == 'T')) {
                index++;
                double[] variable = new double[MAX_DEGREE + 1];
                variable[1] = 1;
                return variable;
            }

            return constant(parseNumber());
        }

        private double parseNumber() {
            skipWhitespace();
            int start = index;
            boolean hasDigits = false;
            while (index < input.length() && Character.isDigit(input.charAt(index))) {
                index++;
                hasDigits = true;
            }
            if (consume('.')) {
                while (index < input.length() && Character.isDigit(input.charAt(index))) {
                    index++;
                    hasDigits = true;
                }
            }
            if (!hasDigits) {
                throw invalidEquation();
            }
            if (index < input.length() &&
                (input.charAt(index) == 'e' || input.charAt(index) == 'E')) {
                index++;
                if (!consume('+')) {
                    consume('-');
                }
                int exponentStart = index;
                while (index < input.length() && Character.isDigit(input.charAt(index))) {
                    index++;
                }
                if (index == exponentStart) {
                    throw invalidEquation();
                }
            }

            try {
                double value = Double.parseDouble(input.substring(start, index));
                if (!Double.isFinite(value)) {
                    throw invalidEquation();
                }
                return value;
            } catch (NumberFormatException exception) {
                throw invalidEquation();
            }
        }

        private double[] add(double[] first, double[] second) {
            double[] result = Arrays.copyOf(first, first.length);
            for (int i = 0; i < result.length; i++) {
                result[i] += second[i];
            }
            return validate(result);
        }

        private double[] subtract(double[] first, double[] second) {
            return add(first, scale(second, -1));
        }

        private double[] multiply(double[] first, double[] second) {
            double[] result = new double[MAX_DEGREE + 1];
            for (int left = 0; left < first.length; left++) {
                if (first[left] == 0) {
                    continue;
                }
                for (int right = 0; right < second.length; right++) {
                    if (second[right] == 0) {
                        continue;
                    }
                    int degree = left + right;
                    if (degree > MAX_DEGREE) {
                        throw new IllegalArgumentException(
                            "Polynomial degree cannot exceed 12."
                        );
                    }
                    result[degree] += first[left] * second[right];
                }
            }
            return validate(result);
        }

        private double[] divide(double[] dividend, double[] divisor) {
            for (int degree = 1; degree < divisor.length; degree++) {
                if (divisor[degree] != 0) {
                    throw new IllegalArgumentException(
                        "A polynomial can only be divided by a non-zero constant."
                    );
                }
            }
            if (divisor[0] == 0) {
                throw new IllegalArgumentException("Division by zero is not allowed.");
            }
            return scale(dividend, 1 / divisor[0]);
        }

        private double[] scale(double[] polynomial, double multiplier) {
            double[] result = Arrays.copyOf(polynomial, polynomial.length);
            for (int i = 0; i < result.length; i++) {
                result[i] *= multiplier;
            }
            return validate(result);
        }

        private double[] constant(double value) {
            double[] result = new double[MAX_DEGREE + 1];
            result[0] = value;
            return result;
        }

        private double[] validate(double[] polynomial) {
            for (double coefficient : polynomial) {
                if (!Double.isFinite(coefficient)) {
                    throw new IllegalArgumentException(
                        "Polynomial coefficients must be finite numbers."
                    );
                }
            }
            return polynomial;
        }

        private boolean consume(char expected) {
            if (index < input.length() && input.charAt(index) == expected) {
                index++;
                return true;
            }
            return false;
        }

        private boolean peek(char expected) {
            return index < input.length() && input.charAt(index) == expected;
        }

        private void skipWhitespace() {
            while (index < input.length() &&
                Character.isWhitespace(input.charAt(index))) {
                index++;
            }
        }

        private IllegalArgumentException invalidEquation() {
            return new IllegalArgumentException(
                "Invalid polynomial. Use numbers, t, +, -, *, / by constants, "
                    + "parentheses, and integer powers up to 12."
            );
        }
    }
}
