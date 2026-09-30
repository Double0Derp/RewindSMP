package dev.smp.rewind;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.ArrayDeque;

/** Per-player rewind state: the energy bar plus a ring buffer of recent snapshots. */
public final class PlayerTimeline {

    public record Snapshot(Location loc, Vector velocity, double health, int food,
                           float saturation, int fireTicks, int air, float fallDistance) {}

    private final ArrayDeque<Snapshot> buffer = new ArrayDeque<>();
    private final int capacity;
    private double seconds;
    private boolean rewinding;
    private Snapshot last;

    public PlayerTimeline(int capacity, double seconds) {
        this.capacity = Math.max(1, capacity);
        this.seconds = seconds;
    }

    public void record(Player p) {
        if (buffer.size() >= capacity) buffer.pollFirst();
        buffer.addLast(new Snapshot(
                p.getLocation().clone(),
                p.getVelocity().clone(),
                p.getHealth(),
                p.getFoodLevel(),
                p.getSaturation(),
                Math.max(0, p.getFireTicks()),
                p.getRemainingAir(),
                p.getFallDistance()));
    }

    public Snapshot pop() { return buffer.pollLast(); }
    public boolean isEmpty() { return buffer.isEmpty(); }
    public void clear() { buffer.clear(); last = null; }

    public double getSeconds() { return seconds; }
    public void setSeconds(double s) { this.seconds = s; }
    public void addSeconds(double delta, double max) {
        this.seconds = Math.max(0, Math.min(max, this.seconds + delta));
    }

    public boolean isRewinding() { return rewinding; }
    public void setRewinding(boolean r) { this.rewinding = r; }

    public Snapshot getLast() { return last; }
    public void setLast(Snapshot s) { this.last = s; }
}
