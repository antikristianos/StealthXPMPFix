package antikristianos.stealthxpmpfix;

/** Recent accepted positions confirm the network movement state, including tiny steps. */
public final class MovementEvidence {
    private boolean initialized;
    private float x, y, z;
    private long sampledAt;
    private long movedAt = Long.MIN_VALUE;
    private int frame = Integer.MIN_VALUE;
    public boolean duplicate(int currentFrame) { return initialized && frame == currentFrame; }
    public boolean update(int currentFrame, long now, float newX, float newY, float newZ, boolean moving) {
        if (duplicate(currentFrame)) return false;
        boolean valid = Float.isFinite(newX) && Float.isFinite(newY) && Float.isFinite(newZ);
        if (valid && initialized && currentFrame > frame && now > sampledAt && now - sampledAt <= 1_000_000_000L) {
            double distance = Math.hypot(newX - x, newY - y);
            // Reject teleports/large jumps; normal walking is far below 20 tiles per real second.
            double allowed = Math.max(0.5, 20.0 * (now - sampledAt) / 1_000_000_000.0);
            if (newZ != z || distance > allowed) movedAt = Long.MIN_VALUE;
            else if (distance > 0.0001) movedAt = now;
        } else movedAt = Long.MIN_VALUE;
        initialized = valid; x = newX; y = newY; z = newZ; sampledAt = now; frame = currentFrame;
        if (!moving) movedAt = Long.MIN_VALUE;
        return valid && moving && movedAt != Long.MIN_VALUE && now - movedAt <= 250_000_000L;
    }
}
