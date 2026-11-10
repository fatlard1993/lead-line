package justfatlard.lead_line;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.entity.decoration.LeashFenceKnotEntity;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * A rope tied off at both ends, climbed as a lead in hand is climbed.
 *
 * <p>A lead arrow's lead tied off at a fence, or a lead from one post to another, is a rope nobody
 * holds. Sneak within reach of it and you take hold where you are; from then on it is {@link Tethers}'
 * own climbing, measured from the rope's high end: holding sneak climbs, each jump lets a step down,
 * and the key lets go. Where a lead in hand lets the player swing anywhere within its length, a tied
 * rope keeps them on its line, so it goes up a cliff, along the underside of a bridge, or across a gap
 * hand over hand. A jump past its foot drops them off the end. Let go, sneak must come up before the
 * rope is taken hold of again, or the key would only let go for a tick.
 */
public final class TiedRopes {
	private TiedRopes() {}

	/** How near the waist a rope must pass to be taken hold of: an arm's length, and the lead's sag. */
	private static final double REACH = 1.2;
	/** Far enough to find a rope passing within reach, from either end of the longest. */
	private static final double SEARCH = Tethers.LONGEST / 2 + 2;

	/** Which rope a player holds, by the golem at one end of it, and how far down it from its high end. */
	private record Grip(CopperGolem holder, double down) {}

	/** A rope's two ends, high first. */
	private record Rope(Vec3 top, Vec3 bottom) {
		double length() {
			return top.distanceTo(bottom);
		}

		Vec3 at(double down) {
			double length = length();
			return length < 1.0E-6 ? top : top.add(bottom.subtract(top).scale(down / length));
		}

		/** How far down the rope the point on it nearest this lies. */
		double nearest(Vec3 point) {
			double length = length();
			if (length < 1.0E-6) return 0;
			Vec3 line = bottom.subtract(top).scale(1 / length);
			return Math.max(0, Math.min(length, point.subtract(top).dot(line)));
		}
	}

	private static final Map<UUID, Grip> grips = new HashMap<>();
	/** Let go and not yet stopped sneaking. */
	private static final Set<UUID> released = new HashSet<>();

	/** The rope this golem holds one end of, tied off at a fence knot; null while it is in a hand or gone. */
	private static Rope ropeOf(CopperGolem holder) {
		if (holder.isRemoved() || !Tethers.isHolder(holder) || !(holder.getLeashHolder() instanceof LeashFenceKnotEntity knot) || knot.isRemoved()) return null;
		Vec3 one = Tethers.anchorOf(holder);
		Vec3 other = knot.getRopeHoldPosition(1.0F);
		return one.y >= other.y ? new Rope(one, other) : new Rope(other, one);
	}

	/** This tick, for a player on a tied rope or sneaking to take hold of one; true when the rope has them and nothing else should. */
	static boolean tend(ServerPlayer player, boolean jumped, boolean tell) {
		UUID id = player.getUUID();
		boolean sneaking = player.getLastClientInput().shift();
		if (!sneaking) released.remove(id);
		Grip grip = grips.get(id);
		if (grip == null) {
			if (!sneaking || released.contains(id) || player.isSpectator()) return false;
			grip = grab(player);
			// A lead in hand is climbed the way it is, not left for one passing by.
			if (grip == null || Tethers.holderOf(player) != null) return false;
		}

		Rope rope = grip.holder().level() == player.level() ? ropeOf(grip.holder()) : null;
		if (rope == null) {
			release(id);
			return false;
		}
		double down = Tethers.climb(player, jumped, grip.down());
		if (down > rope.length()) {
			release(id);
			return false;
		}
		down = Math.max(Math.min(Tethers.SHORTEST, rope.length()), down);
		grips.put(id, new Grip(grip.holder(), down));
		Tethers.drawTo(player, rope.at(down), null);
		if (tell) player.sendOverlayMessage(Component.literal(describe(down, rope)));
		return true;
	}

	/** The nearest tied rope passing within reach of the player's waist, held where it passes nearest. */
	private static Grip grab(ServerPlayer player) {
		Vec3 waist = Tethers.waist(player);
		Grip best = null;
		double nearest = REACH;
		for (CopperGolem holder : player.level().getEntitiesOfClass(CopperGolem.class, player.getBoundingBox().inflate(SEARCH), Tethers::isHolder)) {
			Rope rope = ropeOf(holder);
			if (rope == null) continue;
			double down = rope.nearest(waist);
			double distance = waist.distanceTo(rope.at(down));
			if (distance < nearest) {
				nearest = distance;
				best = new Grip(holder, down);
			}
		}
		return best;
	}

	private static void release(UUID id) {
		grips.remove(id);
		released.add(id);
	}

	/** Let go of the tied rope this player is on; false if they are on none. */
	static boolean letGo(ServerPlayer player) {
		if (!grips.containsKey(player.getUUID())) return false;
		release(player.getUUID());
		player.sendOverlayMessage(Component.translatableWithFallback("lead-line-justfatlard.let_go", "Let go of the rope"));
		return true;
	}

	static void forget(UUID id) {
		grips.remove(id);
		released.remove(id);
	}

	/** How far down the tied rope this player is from its high end, or null when they are on none. */
	public static Double downOf(ServerPlayer player) {
		Grip grip = grips.get(player.getUUID());
		return grip == null ? null : grip.down();
	}

	/** The reading for a player on a tied rope, or null when they are on none. */
	static String describe(ServerPlayer player) {
		Grip grip = grips.get(player.getUUID());
		Rope rope = grip == null ? null : ropeOf(grip.holder());
		return rope == null ? null : describe(grip.down(), rope);
	}

	private static String describe(double down, Rope rope) {
		return "On the rope, " + Tethers.one(down) + " down of " + Tethers.one(rope.length());
	}
}
