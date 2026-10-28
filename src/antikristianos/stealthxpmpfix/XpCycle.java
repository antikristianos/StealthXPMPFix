package antikristianos.stealthxpmpfix;

import java.util.function.IntUnaryOperator;

/** One player's Vanilla-order detection/XP attempts; no shared seen flags. */
public final class XpCycle {
    private boolean blocked;
    private final int bound;
    private final IntUnaryOperator random;
    private int sneak;
    private int lightfoot;
    public XpCycle(float multiplier, boolean debug, IntUnaryOperator random) {
        if (!Float.isFinite(multiplier) || multiplier <= 0) throw new IllegalArgumentException("Invalid game time");
        bound = Math.max(1, (int) ((debug ? 100f : 1100f) * (1f / multiplier)));
        this.random = random;
    }
    public void consider(long sample) {
        if (sample == -1L) return;
        float chance = Float.intBitsToFloat((int) (sample >>> 32));
        float distance = Float.intBitsToFloat((int) sample);
        if (!Float.isFinite(chance) || !Float.isFinite(distance)) return;
        boolean seen = random.applyAsInt(10000) / 100f < chance;
        if (seen || (chance > 20f && distance < 15f)) blocked = true;
        if (blocked) return;
        if (random.applyAsInt(bound) == 0) sneak++;
        if (random.applyAsInt(bound) == 0) lightfoot++;
    }
    public int sneak() { return sneak; }
    public int lightfoot() { return lightfoot; }
}
