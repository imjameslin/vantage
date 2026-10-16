package bench;

/** Repeated constant-speed circle; benchmark-only, one identical route per A/B block. */
public final class FlightLoop {
    public final double originX, originZ, radius, period;
    public double x, z;
    public float yaw;

    public FlightLoop(double originX, double originZ, double speed, double period) {
        if (!Double.isFinite(speed) || speed <= 0 || !Double.isFinite(period) || period <= 0) {
            throw new IllegalArgumentException("flight loop needs positive finite speed and period");
        }
        this.originX = originX;
        this.originZ = originZ;
        this.period = period;
        this.radius = speed * period / (2 * Math.PI);
        at(0);
    }

    public void at(double seconds) {
        double phase = Math.max(0, seconds) % period / period;
        double angle = phase * (2 * Math.PI);
        x = originX + radius * Math.sin(angle);
        z = originZ + radius * (1 - Math.cos(angle));
        yaw = (float) (-90 + 360 * phase);
    }
}
