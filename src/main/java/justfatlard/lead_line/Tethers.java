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
import net.minecraft.world.entity.player.Input;
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
 * round the post traces a circle; and hanging from it below the post, sneaking lets it out and
 * jumping climbs it. A held lead takes the fall out of whatever it catches.
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
	/** The shortest a lead can be held at. */
	public static final double SHORTEST = 1.0;
	/** Where on a player the lead pulls: about the waist. */
	private static final double WAIST = 0.9;
	private static final double LOWER_PER_TICK = 0.15;
	private static final double CLIMB_PER_TICK = 0.1;
	/** Past the length by more than this, the player is put back on it outright rather than drawn. */
	private static final double SNAP_BACK = 1.5;
	private static final int TELL_EVERY = 5;

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

	/** Whether this golem holds the post end of a lead in a player's hand. */
	public static boolean holdsForPlayer(Entity entity) {
		return entity instanceof CopperGolem golem && golem.entityTags().contains(HOLDER_TAG)
			&& golem.getLeashHolder() instanceof ServerPlayer;
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
		return !golem.isRemoved() && golem.entityTags().contains(HOLDER_TAG) && golem.getLeashHolder() == player;
	}

	/** Where the lead leaves the post. */
	public static Vec3 anchorOf(CopperGolem holder) {
		return holder.position().add(holder.getLeashOffset());
	}

	/** How far the lead runs, post to waist. */
	public static double reach(ServerPlayer player, CopperGolem holder) {
		return waist(player).distanceTo(anchorOf(holder));
	}

	private static Vec3 waist(ServerPlayer player) {
		return player.position().add(0, WAIST, 0);
	}

	/** The length this player's lead is held at, or null for none. */
	public static Double lengthOf(ServerPlayer player) {
		Held held = player.getAttached(HELD);
		CopperGolem holder = holderOf(player);
		return held != null && holder != null && held.holder().equals(holder.getUUID()) ? held.length() : null;
	}

	private static void setLength(ServerPlayer player, CopperGolem holder, double length) {
		player.setAttached(HELD, new Held(holder.getUUID(), Math.max(SHORTEST, Math.min(LONGEST - 2, length))));
	}

	/** Hold the lead at this length, or at however far it runs now when null. False if no lead is held. */
	public static boolean hold(ServerPlayer player, Double length) {
		CopperGolem holder = holderOf(player);
		if (holder == null) return say(player, "lead-line-justfatlard.none", "You are not holding a lead tied to a post.");
		setLength(player, holder, length != null ? length : reach(player, holder));
		player.sendOverlayMessage(Component.translatableWithFallback("lead-line-justfatlard.held", "Lead held at %s blocks", one(lengthOf(player))));
		return true;
	}

	/** Let the lead run free again. */
	public static void free(ServerPlayer player) {
		player.removeAttached(HELD);
		player.sendOverlayMessage(Component.translatableWithFallback("lead-line-justfatlard.freed", "Lead running free"));
	}

	/** The key: held at the length it runs now, or let go if it is held. */
	public static void toggle(ServerPlayer player) {
		if (lengthOf(player) != null) free(player);
		else hold(player, null);
	}

	public static void forget(UUID player) {
		tethers.remove(player);
	}

	/** Every tick, for every player holding a lead from a post: hold them to its length, let it out or take it in, and say how far it runs. */
	public static void tick(MinecraftServer server) {
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
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
			if (held != null) pull(player, holder, held.length());
			if (server.getTickCount() % TELL_EVERY == 0) tell(player, holder, lengthOf(player));
		}
	}

	private static void pull(ServerPlayer player, CopperGolem holder, double held) {
		double length = held;
		Vec3 anchor = anchorOf(holder);
		Vec3 waist = waist(player);
		// Below the post with the lead taut: hanging from it, or against a wall on it, where the game
		// may well call the player on the ground. Either way the lead is what they are on.
		boolean hanging = waist.y < anchor.y - 1 && waist.distanceTo(anchor) >= length - 0.3;

		if (hanging) {
			Input input = player.getLastClientInput();
			if (input.shift()) length = Math.min(LONGEST - 2, length + LOWER_PER_TICK);
			else if (input.jump()) length = Math.max(SHORTEST, length - CLIMB_PER_TICK);
			if (length != held) setLength(player, holder, length);
		}

		Vec3 out = waist.subtract(anchor);
		double distance = out.length();
		if (distance <= length) {
			if (hanging) player.resetFallDistance();
			return;
		}
		Vec3 along = out.scale(1 / distance);
		double over = distance - length;
		if (over > SNAP_BACK) {
			Vec3 back = anchor.add(along.scale(length)).subtract(0, WAIST, 0);
			player.teleportTo((ServerLevel) player.level(), back.x, back.y, back.z, Set.<Relative>of(Relative.X_ROT, Relative.Y_ROT), 0, 0, false);
		} else {
			// Drawn back along the lead, as much as it overshot and a little more, so it settles taut.
			player.setDeltaMovement(along.scale(-(over * 0.6 + 0.05)));
			player.needsSync = true;
		}
		player.resetFallDistance();
	}

	private static void tell(ServerPlayer player, CopperGolem holder, Double length) {
		Vec3 anchor = anchorOf(holder);
		BlockPos post = BlockPos.containing(anchor);
		double across = Math.hypot(player.getX() - (post.getX() + 0.5), player.getZ() - (post.getZ() + 0.5));
		double rise = player.getY() - post.getY();
		String height = Math.abs(rise) < 0.5 ? "level with the post" : one(Math.abs(rise)) + (rise > 0 ? " up" : " down");
		Component line = length == null
			? Component.translatableWithFallback("lead-line-justfatlard.reach", "%s blocks out, %s", one(across), height)
			: Component.translatableWithFallback("lead-line-justfatlard.reach_held", "%s blocks out, %s, held at %s", one(across), height, one(length));
		player.sendOverlayMessage(line);
	}

	static String one(double value) {
		return String.format(java.util.Locale.ROOT, "%.1f", value);
	}

	private static boolean say(ServerPlayer player, String key, String fallback) {
		player.sendOverlayMessage(Component.translatableWithFallback(key, fallback));
		return false;
	}
}
