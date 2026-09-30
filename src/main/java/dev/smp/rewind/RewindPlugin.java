package dev.smp.rewind;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class RewindPlugin extends JavaPlugin implements Listener {

    private final Map<UUID, PlayerTimeline> timelines = new HashMap<>();
    private final Map<UUID, BossBar> bars = new HashMap<>();
    private NamespacedKey secondsKey;

    private double maxSeconds, gainPerSecond, initialSeconds, secondsPerSnapshot;
    private int intervalTicks, snapshotsPerTick, capacity;
    private boolean requireSneak, protectWhileRewinding;
    private long tickCount;
    private boolean useGauge, useBossBar;
    private boolean holdToRewind, fatigueEnabled;
    private double fatiguePerSecond, fatigueMin, fatigueMax;
    private int weaknessLvl, slownessLvl, miningFatigueLvl;

    /** Number of gauge fill steps; must match STEPS in tools/generate_pack.py */
    private static final int GAUGE_STEPS = 32;

    // ------------------------------------------------------------ lifecycle

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadSettings();
        secondsKey = new NamespacedKey(this, "rewind_seconds");
        getServer().getPluginManager().registerEvents(this, this);
        for (Player p : Bukkit.getOnlinePlayers()) attach(p);
        Bukkit.getScheduler().runTaskTimer(this, this::tick, 1L, 1L);
    }

    @Override
    public void onDisable() {
        for (Player p : Bukkit.getOnlinePlayers()) detach(p);
    }

    private void loadSettings() {
        var c = getConfig();
        maxSeconds = c.getDouble("max-seconds", 300);
        double gainSeconds = c.getDouble("gain-seconds", 300);
        double gainHours = c.getDouble("gain-per-hours", 6);
        gainPerSecond = gainSeconds / (gainHours * 3600.0);
        initialSeconds = Math.min(c.getDouble("initial-seconds", 0), maxSeconds);
        intervalTicks = Math.max(1, c.getInt("snapshot-interval-ticks", 5));
        snapshotsPerTick = Math.max(1, c.getInt("snapshots-per-tick", 1));
        secondsPerSnapshot = intervalTicks / 20.0;
        capacity = (int) Math.ceil(maxSeconds / secondsPerSnapshot);
        requireSneak = c.getBoolean("require-sneak-swap", true);
        protectWhileRewinding = c.getBoolean("protect-while-rewinding", true);
        String mode = c.getString("hud-mode", "gauge").toLowerCase();
        useGauge = mode.equals("gauge") || mode.equals("both");
        useBossBar = mode.equals("bossbar") || mode.equals("both");
        holdToRewind = c.getBoolean("hold-to-rewind", true);
        fatigueEnabled = c.getBoolean("fatigue.enabled", true);
        fatiguePerSecond = c.getDouble("fatigue.seconds-per-rewound-second", 2.0);
        fatigueMin = c.getDouble("fatigue.min-seconds", 2.0);
        fatigueMax = c.getDouble("fatigue.max-seconds", 60.0);
        weaknessLvl = c.getInt("fatigue.weakness", 1);
        slownessLvl = c.getInt("fatigue.slowness", 1);
        miningFatigueLvl = c.getInt("fatigue.mining-fatigue", 0);
    }

    // ------------------------------------------------------------ player setup

    private void attach(Player p) {
        Double saved = p.getPersistentDataContainer().get(secondsKey, PersistentDataType.DOUBLE);
        double secs = saved != null ? saved : initialSeconds;
        PlayerTimeline t = new PlayerTimeline(capacity, Math.min(secs, maxSeconds));
        timelines.put(p.getUniqueId(), t);

        if (useBossBar) {
            BossBar bar = BossBar.bossBar(Component.empty(), 0f, BossBar.Color.BLUE, BossBar.Overlay.PROGRESS);
            bars.put(p.getUniqueId(), bar);
            p.showBossBar(bar);
        }
        updateHud(p, t);
    }

    private void detach(Player p) {
        PlayerTimeline t = timelines.remove(p.getUniqueId());
        if (t != null) {
            p.getPersistentDataContainer().set(secondsKey, PersistentDataType.DOUBLE, t.getSeconds());
        }
        BossBar bar = bars.remove(p.getUniqueId());
        if (bar != null) p.hideBossBar(bar);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) { attach(e.getPlayer()); }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) { detach(e.getPlayer()); }

    // ------------------------------------------------------------ main loop

    private void tick() {
        tickCount++;
        boolean secondMark = tickCount % 20 == 0;
        boolean recordMark = tickCount % intervalTicks == 0;

        for (Player p : Bukkit.getOnlinePlayers()) {
            PlayerTimeline t = timelines.get(p.getUniqueId());
            if (t == null || p.isDead()) continue;

            if (t.isRewinding()) {
                if (t.isHoldRequired() && !p.isSneaking()) {
                    stopRewind(p, t);
                    continue;
                }
                stepRewind(p, t);
                if (tickCount % 4 == 0) updateHud(p, t);
            } else {
                if (recordMark) t.record(p);
                if (secondMark) {
                    t.addSeconds(gainPerSecond, maxSeconds);
                    updateHud(p, t);
                }
            }
        }
    }

    // ------------------------------------------------------------ rewinding

    /** fromKey = triggered by Shift+F (hold mode applies); false = /rewind command (toggle). */
    private void toggle(Player p, boolean fromKey) {
        if (!p.hasPermission("rewind.use")) return;
        PlayerTimeline t = timelines.get(p.getUniqueId());
        if (t == null) return;
        if (t.isRewinding()) {
            if (!(fromKey && holdToRewind)) stopRewind(p, t);
        } else {
            startRewind(p, t, fromKey && holdToRewind);
        }
    }

    private void startRewind(Player p, PlayerTimeline t, boolean hold) {
        if (t.isEmpty()) {
            p.sendActionBar(Component.text("Nothing to rewind yet - keep playing!", NamedTextColor.YELLOW));
            return;
        }
        if (t.getSeconds() < secondsPerSnapshot) {
            p.sendActionBar(Component.text("Not enough rewind time!", NamedTextColor.RED));
            return;
        }
        t.setRewinding(true);
        t.setHoldRequired(hold);
        t.resetRewound();
        t.setLast(null);
        p.playSound(p.getLocation(), Sound.BLOCK_BEACON_DEACTIVATE, 1f, 1.5f);
        updateHud(p, t);
    }

    private void stopRewind(Player p, PlayerTimeline t) {
        t.setRewinding(false);
        t.setHoldRequired(false);
        PlayerTimeline.Snapshot last = t.getLast();
        if (last != null) p.setVelocity(last.velocity());
        applyFatigue(p, t.getRewound());
        t.resetRewound();
        p.playSound(p.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 1f, 1.5f);
        updateHud(p, t);
    }

    private void stepRewind(Player p, PlayerTimeline t) {
        for (int i = 0; i < snapshotsPerTick; i++) {
            if (t.isEmpty() || t.getSeconds() < secondsPerSnapshot) {
                stopRewind(p, t);
                return;
            }
            PlayerTimeline.Snapshot s = t.pop();
            t.addSeconds(-secondsPerSnapshot, maxSeconds);
            apply(p, s);
            t.setLast(s);
            t.addRewound(secondsPerSnapshot);
        }
        p.getWorld().spawnParticle(Particle.REVERSE_PORTAL,
                p.getLocation().add(0, 1, 0), 12, 0.3, 0.5, 0.3, 0.05);
    }

    /** The weakness: rewinding leaves you drained for a while. Longer rewind = longer fatigue. */
    private void applyFatigue(Player p, double rewoundSeconds) {
        if (!fatigueEnabled || rewoundSeconds <= 0) return;
        double secs = Math.max(fatigueMin, Math.min(fatigueMax, rewoundSeconds * fatiguePerSecond));
        int ticks = (int) Math.round(secs * 20);
        int maxTicks = (int) Math.round(fatigueMax * 20);
        addFatigue(p, PotionEffectType.WEAKNESS, weaknessLvl, ticks, maxTicks);
        addFatigue(p, PotionEffectType.SLOWNESS, slownessLvl, ticks, maxTicks);
        addFatigue(p, PotionEffectType.MINING_FATIGUE, miningFatigueLvl, ticks, maxTicks);
        p.sendMessage(Component.text("Time sickness for " + Math.round(secs) + "s...", NamedTextColor.GRAY));
    }

    private void addFatigue(Player p, PotionEffectType type, int level, int ticks, int maxTicks) {
        if (level <= 0) return;
        PotionEffect existing = p.getPotionEffect(type);
        int carry = (existing != null && existing.getDuration() > 0 && existing.getAmplifier() == level - 1)
                ? existing.getDuration() : 0;   // back-to-back rewinds stack
        p.addPotionEffect(new PotionEffect(type, Math.min(maxTicks, ticks + carry), level - 1, false, true, true));
    }

    @SuppressWarnings("deprecation")
    private void apply(Player p, PlayerTimeline.Snapshot s) {
        p.teleport(s.loc());
        p.setVelocity(new Vector(0, 0, 0));
        p.setFallDistance(s.fallDistance());
        p.setHealth(Math.max(0.5, Math.min(s.health(), p.getMaxHealth())));
        p.setFoodLevel(s.food());
        p.setSaturation(s.saturation());
        p.setFireTicks(s.fireTicks());
        p.setRemainingAir(s.air());
    }

    @EventHandler
    public void onSwap(PlayerSwapHandItemsEvent e) {
        Player p = e.getPlayer();
        if ((requireSneak || holdToRewind) && !p.isSneaking()) return;
        e.setCancelled(true);
        toggle(p, true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (!protectWhileRewinding || !(e.getEntity() instanceof Player p)) return;
        PlayerTimeline t = timelines.get(p.getUniqueId());
        if (t != null && t.isRewinding()) e.setCancelled(true);
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        Player p = e.getEntity();
        PlayerTimeline t = timelines.get(p.getUniqueId());
        if (t == null) return;
        t.setRewinding(false);
        t.clear();
        updateHud(p, t);
    }

    // ------------------------------------------------------------ ui + command

    private void updateHud(Player p, PlayerTimeline t) {
        if (useGauge) p.sendActionBar(gauge(t));
        BossBar bar = bars.get(p.getUniqueId());
        if (bar == null) return;
        boolean r = t.isRewinding();
        bar.progress((float) Math.max(0, Math.min(1, t.getSeconds() / maxSeconds)));
        bar.color(r ? BossBar.Color.PURPLE : BossBar.Color.BLUE);
        bar.name(Component.text((r ? "<< Rewinding  " : "Rewind  ") + fmt(t.getSeconds()),
                r ? NamedTextColor.LIGHT_PURPLE : NamedTextColor.AQUA));
    }

    /** The clock gauge is a single custom-font glyph from the resource pack. */
    private Component gauge(PlayerTimeline t) {
        int level = (int) Math.round(t.getSeconds() / maxSeconds * GAUGE_STEPS);
        level = Math.max(0, Math.min(GAUGE_STEPS, level));
        char glyph = (char) ((t.isRewinding() ? 0xE100 : 0xE000) + level);
        return Component.text(String.valueOf(glyph), NamedTextColor.WHITE).font(Key.key("rewind", "hud"));
    }

    private static String fmt(double seconds) {
        int total = (int) Math.floor(seconds);
        return String.format("%d:%02d", total / 60, total % 60);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("give")) {
            if (!sender.hasPermission("rewind.admin")) {
                sender.sendMessage(Component.text("You don't have permission.", NamedTextColor.RED));
                return true;
            }
            if (args.length < 3) {
                sender.sendMessage(Component.text("Usage: /rewind give <player> <seconds>", NamedTextColor.YELLOW));
                return true;
            }
            Player target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                sender.sendMessage(Component.text("Player not found.", NamedTextColor.RED));
                return true;
            }
            double amount;
            try {
                amount = Double.parseDouble(args[2]);
            } catch (NumberFormatException ex) {
                sender.sendMessage(Component.text("Seconds must be a number.", NamedTextColor.RED));
                return true;
            }
            PlayerTimeline t = timelines.get(target.getUniqueId());
            if (t == null) return true;
            t.addSeconds(amount, maxSeconds);
            updateHud(target, t);
            sender.sendMessage(Component.text(target.getName() + " now has " + fmt(t.getSeconds()) + " of rewind.",
                    NamedTextColor.GREEN));
            return true;
        }

        if (!(sender instanceof Player p)) {
            sender.sendMessage(Component.text("Players only."));
            return true;
        }
        toggle(p, false);
        return true;
    }
}
