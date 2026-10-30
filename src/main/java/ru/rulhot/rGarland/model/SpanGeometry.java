package ru.rulhot.rGarland.model;

import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;
import org.joml.Vector3d;
import org.joml.Vector3dc;

@UtilityClass
public class SpanGeometry {

    private final double PARALLEL = 1.0E-9;

    public double angleDegrees(@NotNull Vector3dc node, @NotNull Vector3dc first, @NotNull Vector3dc second) {
        Vector3d toFirst = new Vector3d(first).sub(node);
        Vector3d toSecond = new Vector3d(second).sub(node);
        return Math.toDegrees(toFirst.angle(toSecond));
    }

    public double segmentDistance(@NotNull Vector3dc firstStart, @NotNull Vector3dc firstEnd,
                                  @NotNull Vector3dc secondStart, @NotNull Vector3dc secondEnd) {
        Vector3d firstDirection = new Vector3d(firstEnd).sub(firstStart);
        Vector3d secondDirection = new Vector3d(secondEnd).sub(secondStart);
        Vector3d between = new Vector3d(firstStart).sub(secondStart);
        double firstLength = firstDirection.dot(firstDirection);
        double secondLength = secondDirection.dot(secondDirection);
        if (firstLength <= PARALLEL && secondLength <= PARALLEL) {
            return firstStart.distance(secondStart);
        }
        if (firstLength <= PARALLEL) {
            return pointDistance(firstStart, secondStart, secondDirection, secondLength);
        }
        if (secondLength <= PARALLEL) {
            return pointDistance(secondStart, firstStart, firstDirection, firstLength);
        }
        double secondAlongBetween = secondDirection.dot(between);
        double firstAlongBetween = firstDirection.dot(between);
        double cross = firstDirection.dot(secondDirection);
        double denominator = firstLength * secondLength - cross * cross;
        double firstT = denominator > PARALLEL
                ? Math.clamp((cross * secondAlongBetween - firstAlongBetween * secondLength) / denominator, 0, 1)
                : 0;
        double secondT = (cross * firstT + secondAlongBetween) / secondLength;
        if (secondT < 0) {
            secondT = 0;
            firstT = Math.clamp(-firstAlongBetween / firstLength, 0, 1);
        } else if (secondT > 1) {
            secondT = 1;
            firstT = Math.clamp((cross - firstAlongBetween) / firstLength, 0, 1);
        }
        Vector3d closestFirst = new Vector3d(firstDirection).mul(firstT).add(firstStart);
        Vector3d closestSecond = new Vector3d(secondDirection).mul(secondT).add(secondStart);
        return closestFirst.distance(closestSecond);
    }

    private double pointDistance(@NotNull Vector3dc point, @NotNull Vector3dc start, @NotNull Vector3dc direction,
                                 double lengthSquared) {
        double fraction = Math.clamp(new Vector3d(point).sub(start).dot(direction) / lengthSquared, 0, 1);
        return new Vector3d(direction).mul(fraction).add(start).distance(point);
    }
}
