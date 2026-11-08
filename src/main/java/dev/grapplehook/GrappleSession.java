package dev.grapplehook;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

/** One thrown hook: fly out -> (pull | retract). Visuals are two item displays, no particles. */
final class GrappleSession {

    private enum State { FLYING, PULLING, RETRACTING }

    private final GrappleHookPlugin plugin;
    private final Player player;
    private final World world;
    private final Vector anchor;        // where the hook hit (or the max-range point on a miss)
    private final boolean hit;
    private final Block hitBlock;

    private final double throwSpeed, pullSpeed, smoothing, arrive, boost;
    private final int maxPull;
    private final boolean sounds, sneakRelease;

    private State state = State.FLYING;
    private Vector hookPos;
    private Vector hookTarget;          // visual rest position (slightly sunk into the surface)
    private Vector dir;                 // direction from the hand toward the anchor
    private ItemDisplay head, rope;
    private BukkitTask task;
    private boolean ended;
    private int pullTicks, stuckTicks;
    private double lastDist = Double.MAX_VALUE;

    GrappleSession(GrappleHookPlugin plugin, Player player, Vector anchor, boolean hit, Block hitBlock) {
        this.plugin = plugin;
        this.player = player;
        this.world = player.getWorld();
        this.anchor = anchor;
        this.hit = hit;
        this.hitBlock = hitBlock;
        var c = plugin.getConfig();
        throwSpeed = c.getDouble("throw-speed", 4.0);
        pullSpeed = c.getDouble("pull-speed", 0.95);
        smoothing = Math.min(1.0, Math.max(0.05, c.getDouble("pull-smoothing", 0.35)));
        arrive = c.getDouble("arrive-distance", 2.0);
        boost = c.getDouble("release-boost", 0.30);
        maxPull = c.getInt("max-pull-ticks", 70);
        sounds = c.getBoolean("sounds", true);
        sneakRelease = c.getBoolean("sneak-to-release", true);
    }

    void start() {
        Vector origin = hand();
        hookPos = origin.clone();
        dir = anchor.clone().subtract(origin);
        if (dir.lengthSquared() < 1.0e-6) dir = player.getEyeLocation().getDirection();
        dir.normalize();
        hookTarget = anchor.clone().subtract(dir.clone().multiply(hit ? 0.2 : 0.0));

        head = spawnDisplay("hook_head", hookPos);
        rope = spawnDisplay("rope", hookPos);
        updateVisuals(origin);
        play(Sound.ENTITY_FISHING_BOBBER_THROW, 1.0f, 1.4f);
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    // ------------------------------------------------------------------ tick

    private void tick() {
        if (!player.isOnline() || player.isDead() || player.getWorld() != world) {
            end(false);
            return;
        }
        Vector origin = hand();

        switch (state) {
            case FLYING -> {
                double remaining = hookTarget.distance(hookPos);
                if (remaining <= throwSpeed) {
                    hookPos = hookTarget.clone();
                    if (hit) {
                        state = State.PULLING;
                        play(Sound.ITEM_TRIDENT_HIT_GROUND, 1.0f, 1.2f);
                        plugin.grantNoFall(player);
                    } else {
                        state = State.RETRACTING;
                    }
                } else {
                    hookPos.add(dir.clone().multiply(throwSpeed));
                }
            }
            case RETRACTING -> {
                Vector back = origin.clone().subtract(hookPos);
                double d = back.length();
                double step = throwSpeed * 1.5;
                if (d <= step) {
                    play(Sound.ENTITY_FISHING_BOBBER_RETRIEVE, 0.8f, 1.2f);
                    end(false);
                    return;
                }
                hookPos.add(back.normalize().multiply(step));
            }
            case PULLING -> {
                if (!pull()) return;
            }
        }
        updateVisuals(origin);
    }

    /** @return false if the session ended this tick */
    private boolean pull() {
        if (hitBlock != null && hitBlock.isPassable()) { // block was broken
            end(true);
            return false;
        }
        if (sneakRelease && player.isSneaking() && pullTicks > 4) {
            end(true);
            return false;
        }
        Vector body = player.getLocation().toVector().add(new Vector(0, 1.0, 0));
        Vector to = anchor.clone().subtract(body);
        double dist = to.length();

        if (dist <= arrive || pullTicks >= maxPull) {
            Vector v = player.getVelocity().multiply(0.7);
            v.setY(v.getY() + boost);
            player.setVelocity(v);
            player.setFallDistance(0f);
            end(true);
            return false;
        }
        // not getting closer (stuck on a wall/ceiling) -> let go
        if (dist > lastDist - 0.02) stuckTicks++; else stuckTicks = 0;
        lastDist = dist;
        if (stuckTicks > 12 && pullTicks > 6) {
            end(true);
            return false;
        }

        Vector desired = to.normalize().multiply(pullSpeed);
        Vector v = player.getVelocity().multiply(1.0 - smoothing).add(desired.multiply(smoothing));
        player.setVelocity(v);
        player.setFallDistance(0f);
        pullTicks++;
        return true;
    }

    void end(boolean sound) {
        if (ended) return;
        ended = true;
        if (task != null) task.cancel();
        if (head != null && head.isValid()) head.remove();
        if (rope != null && rope.isValid()) rope.remove();
        if (sound) play(Sound.BLOCK_CHAIN_BREAK, 0.8f, 1.1f);
        plugin.sessionEnded(player.getUniqueId());
    }

    // ---------------------------------------------------------------- visuals

    /** Roughly where the player's right hand is. */
    private Vector hand() {
        Location l = player.getLocation();
        double yaw = Math.toRadians(l.getYaw());
        Vector forward = new Vector(-Math.sin(yaw), 0, Math.cos(yaw));
        Vector right = new Vector(-Math.cos(yaw), 0, -Math.sin(yaw));
        double h = player.isSneaking() ? 1.0 : 1.25;
        return l.toVector().add(new Vector(0, h, 0)).add(right.multiply(0.3)).add(forward.multiply(0.45));
    }

    private ItemDisplay spawnDisplay(String model, Vector at) {
        Location loc = new Location(world, at.getX(), at.getY(), at.getZ());
        return world.spawn(loc, ItemDisplay.class, d -> {
            d.setItemStack(GrappleHookPlugin.modelItem(model));
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            d.setPersistent(false);
            d.setTeleportDuration(1);
            d.setInterpolationDuration(1);
            d.setInterpolationDelay(0);
            d.setViewRange(2.0f);
            d.setBrightness(new Display.Brightness(15, 15));
        });
    }

    private void updateVisuals(Vector origin) {
        // hook head: claws point away from the player
        Vector out = hookPos.clone().subtract(origin);
        if (out.lengthSquared() < 1.0e-4) out = dir.clone();
        out.normalize();
        place(head, hookPos, out);

        // rope: from the hand to the back of the hook, stretched along local Z
        Vector ropeEnd = hookPos.clone().subtract(out.clone().multiply(0.45));
        Vector span = ropeEnd.clone().subtract(origin);
        double len = span.length();
        if (len < 0.05) {
            rope.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(), new Vector3f(0, 0, 0), new AxisAngle4f()));
            return;
        }
        Vector mid = origin.clone().add(ropeEnd).multiply(0.5);
        place(rope, mid, span.clone().normalize());
        rope.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(),
                new Vector3f(1f, 1f, (float) len), new AxisAngle4f()));
        rope.setInterpolationDelay(0);
    }

    private void place(ItemDisplay d, Vector pos, Vector facing) {
        if (!d.isValid()) return;
        Location l = new Location(world, pos.getX(), pos.getY(), pos.getZ());
        l.setDirection(facing);
        d.teleport(l);
    }

    private void play(Sound s, float vol, float pitch) {
        if (!sounds) return;
        world.playSound(player.getLocation(), s, vol, pitch);
    }
}
