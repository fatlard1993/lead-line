package justfatlard.lead_line;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.Identifier;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * A lead tied to a post and held in the hand: how far out it reaches, and, held at a length, a
 * radius the player cannot go past and a rope they can hang from.
 *
 * <p>The tying is fence-posts': click a fence holding a lead and its knot holds one end, through a
 * golem nobody sees pinned under it, and the player holds the other. This finds that golem and
 * works on the lead between them. Held at no length it only measures: the action bar says how far
 * out the player is from the post and how far above or below it. Held at a length (the key, or
 * {@code /lead hold}), the player is drawn back whenever they would go past it, so walking it taut
 * round the post traces a circle; and hanging from it below the post, each jump drops a step down
 * it and holding sneak climbs back up. A held lead takes the fall out of whatever it catches.
 *
 * <p>The held length is kept on the player, with the golem it belongs to, so it survives a restart
 * (the golem keeps its id across one) and never carries over onto a lead tied afterwards.
 */
public final class Tethers {
	private Tethers() {}

	/** fence-posts' mark on the golem that holds the near end of a lead tied from a post. */
	public static final String HOLDER_TAG = "fence-posts.lead";
	/** As far as a lead held from a post reaches before it snaps; vanilla's twelve is too short to measure with. */
	public static final double LONGEST = 48.0;
	/** The longest a lead can be held at: short of where it snaps, so a held lead is never drawn to breaking. */
	public static final double LONGEST_HELD = LONGEST - 2;
	/** The shortest a lead can be held at. */
	public static final double SHORTEST = 1.0;
	/** Where on a player the lead pulls: about the waist. */
	private static final double WAIST = 0.9;
	/** A jump on the rope lets this much out: down the wall a step at a time. */
	private static final double DROP_STEP = 1.5;
	private static final double CLIMB_PER_TICK = 0.1;
	/** Past the length by more than this, the player is put back on it outright rather than drawn. */
	private static final double SNAP_BACK = 1.5;
	private static final int TELL_EVERY = 5;
	/**
	 * How much of the way to where the rope holds them a player is drawn each tick. The player's own
	 * client moves them, so this is sent to it ({@code syncVelocity}; {@code needsSync} reaches only
	 * those watching) and lands a tick late: much more and they overshoot and bob.
	 */
	private static final double PULL = 0.4;
	/** In the air under a lead in hand, this near its length the lead takes them: a jump's step is let fall the rest. */
	private static final double CATCH = 1.0;

	/** A player's tether: the golem holding the far end, found once and kept. */
	private static final class Tether {
		final CopperGolem holder;

		Tether(CopperGolem holder) {
			this.holder = holder;
		}
	}

	/** A length a lead is held at, and which lead: the golem holding its post end. */
	public record Held(UUID holder, double length) {
		static final Codec<Held> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			UUIDUtil.CODEC.fieldOf("holder").forGetter(Held::holder),
			Codec.DOUBLE.fieldOf("length").forGetter(Held::length)
		).apply(instance, Held::new));
	}

	public static final AttachmentType<Held> HELD = AttachmentRegistry.createPersistent(
		Identifier.fromNamespaceAndPath(Main.MOD_ID, "held"), Held.CODEC);

	private static final Map<UUID, Tether> tethers = new HashMap<>();
	/** Whether each player was holding jump last tick, so a press drops one step and holding it drops no more. */
	private static final Map<UUID, Boolean> jumping = new HashMap<>();
	/** Set once block-tip takes the reading onto its card, after which the action bar keeps quiet. */
	private static volatile boolean onCard;

	/** Whether this golem holds the far end of a lead in a player's hand, from a post or a lead arrow. */
	public static boolean holdsForPlayer(Entity entity) {
		return isHolder(entity) && ((CopperGolem) entity).getLeashHolder() instanceof ServerPlayer;
	}

	static boolean isHolder(Entity entity) {
		return entity instanceof CopperGolem golem && (golem.entityTags().contains(HOLDER_TAG) || ArrowLeads.isHolder(golem));
	}

	/** The post end of this player's lead, or null if they hold none. */
	public static CopperGolem holderOf(ServerPlayer player) {
		Tether tether = tethers.get(player.getUUID());
		if (tether != null && stillHeld(player, tether.holder)) return tether.holder;
		for (CopperGolem golem : player.level().getEntitiesOfClass(CopperGolem.class, player.getBoundingBox().inflate(LONGEST),
				golem -> stillHeld(player, golem))) {
			tethers.put(player.getUUID(), new Tether(golem));
			return golem;
		}
		tethers.remove(player.getUUID());
		return null;
	}

	private static boolean stillHeld(ServerPlayer player, CopperGolem golem) {
		return !golem.isRemoved() && isHolder(golem) && golem.getLeashHolder() == player;
	}

	/** Where the lead leaves the post. */
	public static Vec3 anchorOf(CopperGolem holder) {
		return holder.position().add(holder.getLeashOffset());
	}

	/** How far the lead runs, post to waist. */
	public static double reach(ServerPlayer player, CopperGolem holder) {
		return waist(player).distanceTo(anchorOf(holder));
	}

	static Vec3 waist(ServerPlayer player) {
		return player.position().add(0, WAIST, 0);
	}

	/** The length this player's lead is held at, or null for none. */
	public static Double lengthOf(ServerPlayer player) {
		Held held = player.getAttached(HELD);
		CopperGolem holder = holderOf(player);
		return held != null && holder != null && held.holder().equals(holder.getUUID()) ? held.length() : null;
	}

	private static void setLength(ServerPlayer player, CopperGolem holder, double length) {
		player.setAttached(HELD, new Held(holder.getUUID(), Math.max(SHORTEST, Math.min(LONGEST_HELD, length))));
	}

	/** Hold the lead at this length, or at however far it runs now when null. False if no lead is held. */
	public static boolean hold(ServerPlayer player, Double length) {
		CopperGolem holder = holderOf(player);
		if (holder == null) return say(player, "lead-line-justfatlard.none", "You are not holding a lead tied to a post.");
		setLength(player, holder, length != null ? length : reach(player, holder));
		player.sendOverlayMessage(Component.translatableWithFallback("lead-line-justfatlard.held", "Lead held at %s blocks", one(lengthOf(player))));
		return true;
	}

	/** A lead just tied from a lead arrow: known at once, and held at the length it flew. */
	static void tied(ServerPlayer player, CopperGolem holder, double length) {
		TiedRopes.forget(player.getUUID());
		tethers.put(player.getUUID(), new Tether(holder));
		setLength(player, holder, length);
		player.sendOverlayMessage(Component.translatableWithFallback("lead-line-justfatlard.held", "Lead held at %s blocks", one(lengthOf(player))));
	}

	/** Let go of every lead this player holds from a post or an arrow, each the way it is let go by hand. */
	static void letGo(ServerPlayer player) {
		for (CopperGolem holder = holderOf(player); holder != null; holder = holderOf(player)) {
			if (ArrowLeads.isHolder(holder)) ArrowLeads.release(holder);
			else holder.dropLeash();
		}
	}

	/** Let the lead run free again. */
	public static void free(ServerPlayer player) {
		if (TiedRopes.letGo(player)) return;
		player.removeAttached(HELD);
		player.sendOverlayMessage(Component.translatableWithFallback("lead-line-justfatlard.freed", "Lead running free"));
	}

	/** The key: held at the length it runs now, or let go if it is held, or of the tied rope they are on. */
	public static void toggle(ServerPlayer player) {
		if (TiedRopes.letGo(player)) return;
		if (lengthOf(player) != null) free(player);
		else hold(player, null);
	}

	static void useCard(boolean card) {
		onCard = card;
	}

	public static void forget(UUID player) {
		tethers.remove(player);
		jumping.remove(player);
		TiedRopes.forget(player);
	}

	/**
	 * Every tick, for every player on a rope: one held from a post or an arrow is held to its length,
	 * let out or taken in, and says how far it runs; one tied off is climbed (see {@link TiedRopes}).
	 */
	public static void tick(MinecraftServer server) {
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			boolean jumped = jumped(player);
			if (TiedRopes.tend(player, jumped, server.getTickCount() % TELL_EVERY == 0 && !onCard)) continue;
			Tether tether = tethers.get(player.getUUID());
			// A scan for a lead nobody is holding only now and then; a known one every tick.
			if (tether == null && (server.getTickCount() + player.getId()) % 10 != 0) continue;
			CopperGolem holder = holderOf(player);
			if (holder == null) continue;
			Held held = player.getAttached(HELD);
			// A length kept for a lead that is gone does not belong to this one.
			if (held != null && !held.holder().equals(holder.getUUID())) {
				player.removeAttached(HELD);
				held = null;
			}
			if (held != null) pull(player, holder, held.length(), jumped);
			if (server.getTickCount() % TELL_EVERY == 0) tell(player, holder, lengthOf(player));
		}
	}

	/** Whether this player pressed jump this tick: a press drops one step, and holding it drops no more. Asked once a tick. */
	private static boolean jumped(ServerPlayer player) {
		boolean jump = player.getLastClientInput().jump();
		boolean was = Boolean.TRUE.equals(jumping.put(player.getUUID(), jump));
		return jump && !was;
	}

	/**
	 * The one way up and down a rope, held or tied off: measured from the rope's top, a jump lets out a
	 * step, the way a climber kicks off and lets the rope run, and holding on (sneak) climbs.
	 */
	static double climb(ServerPlayer player, boolean jumped, double down) {
		if (jumped) return down + DROP_STEP;
		if (player.getLastClientInput().shift()) return down - CLIMB_PER_TICK;
		return down;
	}

	/**
	 * The one way a rope holds a player where it has them: every tick it sets how they move, a share of
	 * the way to the point it holds their waist at, so they come to it and stop there rather than spring
	 * past it; put there outright when well off. Given {@code along}, it corrects only along that line and
	 * leaves the rest of their motion their own, so a lead in hand swings. Nothing they fall while it
	 * holds them hurts.
	 */
	static void drawTo(ServerPlayer player, Vec3 point, Vec3 along) {
		Vec3 off = point.subtract(waist(player));
		if (off.length() > SNAP_BACK) {
			Vec3 feet = point.subtract(0, WAIST, 0);
			player.teleportTo((ServerLevel) player.level(), feet.x, feet.y, feet.z, Set.<Relative>of(Relative.X_ROT, Relative.Y_ROT), 0, 0, false);
		} else {
			Vec3 motion = off.scale(PULL);
			if (along != null) {
				// As the client last moved them: the server's own figure is only what it last sent.
				Vec3 own = player.getKnownMovement();
				motion = own.subtract(along.scale(own.dot(along))).add(along.scale(motion.dot(along)));
			}
			player.setDeltaMovement(motion);
			player.syncVelocity = true;
		}
		player.resetFallDistance();
	}

	/**
	 * A lead in hand: the player may go anywhere within its length, which walks a circle round its post.
	 * In the air under it, near its length or past it, the lead has them, and holds them at its length.
	 */
	private static void pull(ServerPlayer player, CopperGolem holder, double held, boolean jumped) {
		Vec3 anchor = anchorOf(holder);
		Vec3 waist = waist(player);
		// Below the post with the lead taut: hanging from it, or against a wall on it, where the game
		// may well call the player on the ground. Either way the lead is what they are on.
		boolean under = waist.y < anchor.y - 1;
		boolean hanging = under && waist.distanceTo(anchor) >= held - 0.3;
		double length = hanging ? Math.max(SHORTEST, Math.min(LONGEST_HELD, climb(player, jumped, held))) : held;
		if (length != held) setLength(player, holder, length);

		Vec3 out = waist.subtract(anchor);
		double distance = out.length();
		boolean caught = distance > length || (under && !player.onGround() && distance > length - CATCH);
		if (caught) {
			Vec3 outward = out.scale(1 / distance);
			drawTo(player, anchor.add(outward.scale(length)), outward);
		}
	}

	private static void tell(ServerPlayer player, CopperGolem holder, Double length) {
		if (!onCard) player.sendOverlayMessage(Component.literal(describe(player, holder, length)));
	}

	/** How far out the player is from the post, how far above or below it, and the length the lead is held at. */
	static String describe(ServerPlayer player, CopperGolem holder, Double length) {
		BlockPos post = BlockPos.containing(anchorOf(holder));
		double across = Math.hypot(player.getX() - (post.getX() + 0.5), player.getZ() - (post.getZ() + 0.5));
		double rise = player.getY() - post.getY();
		String height = Math.abs(rise) < 0.5 ? "level with the post" : one(Math.abs(rise)) + (rise > 0 ? " up" : " down");
		String line = one(across) + " blocks out, " + height;
		return length == null ? line : line + ", held at " + one(length);
	}

	/** The reading for this player, or null when they hold no lead from a post: what block-tip's card shows. */
	public static String reading(ServerPlayer player) {
		String onRope = TiedRopes.describe(player);
		if (onRope != null) return onRope;
		CopperGolem holder = holderOf(player);
		return holder == null ? null : describe(player, holder, lengthOf(player));
	}

	static String one(double value) {
		return String.format(java.util.Locale.ROOT, "%.1f", value);
	}

	private static boolean say(ServerPlayer player, String key, String fallback) {
		player.sendOverlayMessage(Component.translatableWithFallback(key, fallback));
		return false;
	}
}
