package justfatlard.lead_line;

import justfatlard.lead_line.mixin.AbstractArrowAccessor;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Leashable;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.entity.decoration.LeashFenceKnotEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * A lead arrow's lead, tied back to whoever shot it from where the arrow sticks.
 *
 * <p>The tying is fence-posts' trick without the fence: a small copper golem nobody sees, pinned to
 * the arrow, wears the lead, and the player holds the other end. So everything {@link Tethers} does
 * with a lead from a post it does with this one: it measures, holds at a length, and is hung from,
 * dropped down a step at a jump and climbed while sneaking. It lands held at the length it flew, so a
 * shot into a ceiling or the lip of a ledge is a line to climb straight away.
 *
 * <p>The lead lives in the arrow. Whenever this lets go (the arrow taken back, shaken loose when its
 * block goes, another lead arrow shot) nothing drops: the arrow is still a lead arrow, to pick up.
 * Only when the game lets go its own way (snapped by distance, its holder gone) does it drop the lead
 * itself, at the arrow, and then the arrow left behind is a plain one. Nothing is ever doubled.
 *
 * <p>Clicked on a fence, the lead is tied off there, running from the arrow to the fence, a fixed rope;
 * clicking the knot takes it back in hand. The game ties leads to fences too, but only those within
 * sixteen blocks, and lets them snap at twelve; an arrow's lead runs to forty-eight, so these are
 * tied here, ahead of the game and of fence-posts.
 *
 * <p>Shot into a creature that can wear a lead, the arrow does it no harm: the lead goes on it, held
 * by the shooter as if put on by hand, and the arrow drops beside it a plain arrow. Too far off, or
 * already on somebody's lead, the arrow glances off and drops a lead arrow still.
 *
 * <p>None of it outlasts the session: the golem is never loaded back from disk, so after a restart a
 * stuck lead arrow is only an arrow to fetch.
 */
public final class ArrowLeads {
	private ArrowLeads() {}

	/** Marks a lead arrow in flight. Taken off where it first sticks, so a lead ties once, not again wherever it falls to. */
	public static final String TAG = Main.MOD_ID + ":lead_arrow";
	/** Marks the golem as ours to anyone with a command line: {@code @e[tag=lead-line.arrow]}. */
	public static final String HOLDER_TAG = "lead-line.arrow";
	/** fence-posts' size: small enough to sit inside the arrow's block, large enough that clients draw its lead from afar. */
	private static final double SCALE = 0.75;

	private static final Map<CopperGolem, AbstractArrow> LINES = new IdentityHashMap<>();
	private static final Map<AbstractArrow, CopperGolem> TIED = new IdentityHashMap<>();

	static void register() {
		// Ahead of the game and fence-posts, which would each take the click their own way.
		Identifier first = Identifier.fromNamespaceAndPath(Main.MOD_ID, "arrow_leads");
		UseBlockCallback.EVENT.addPhaseOrdering(first, Event.DEFAULT_PHASE);
		UseBlockCallback.EVENT.register(first, ArrowLeads::onUseBlock);
		UseEntityCallback.EVENT.addPhaseOrdering(first, Event.DEFAULT_PHASE);
		UseEntityCallback.EVENT.register(first, ArrowLeads::onUseEntity);
		// A golem saved with its chunk is a lead that ended with the session; loaded back, the game
		// would drop a second lead for it beside the lead arrow still stuck there.
		ServerEntityEvents.ALLOW_LOAD.register((entity, level, reason, fromDisk) ->
			!(fromDisk && entity instanceof CopperGolem golem && isHolder(golem)));
		ServerEntityEvents.ENTITY_UNLOAD.register((entity, level) -> {
			if (entity instanceof CopperGolem golem) forget(golem);
		});
		ServerTickEvents.END_LEVEL_TICK.register(ArrowLeads::tick);
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			LINES.clear();
			TIED.clear();
		});
	}

	public static boolean isHolder(Entity entity) {
		return entity instanceof CopperGolem && entity.entityTags().contains(HOLDER_TAG);
	}

	/** Whether a lead runs from this arrow, which keeps it from despawning under someone hanging on it. */
	public static boolean isTied(AbstractArrow arrow) {
		return TIED.containsKey(arrow);
	}

	/**
	 * A lead arrow has stuck: tie its lead back to the player who shot it, held at the length it flew.
	 * Shot by nobody, by a crossbow's extra arrows (which carry no lead of their own), or from farther
	 * than a lead can be held, it lands untied, a lead arrow to fetch.
	 */
	public static void stuck(AbstractArrow arrow) {
		if (!(arrow.level() instanceof ServerLevel level) || !arrow.removeTag(TAG)) return;
		if (!(arrow.getOwner() instanceof ServerPlayer player) || player.level() != level || player.isSpectator()) return;
		if (arrow.pickup != AbstractArrow.Pickup.ALLOWED && !player.hasInfiniteMaterials()) return;
		double reach = Tethers.waist(player).distanceTo(arrow.position());
		if (reach > Tethers.LONGEST_HELD) return;

		Tethers.letGo(player);
		CopperGolem holder = spawn(level, arrow);
		if (holder == null) return;
		holder.setLeashedTo(player, true);
		LINES.put(holder, arrow);
		TIED.put(arrow, holder);
		level.playSound(null, arrow.getX(), arrow.getY(), arrow.getZ(), SoundEvents.LEAD_TIED, SoundSource.PLAYERS, 1.0F, 1.0F);
		Tethers.tied(player, holder, reach);
	}

	/**
	 * A lead arrow has struck a creature. One that can wear a lead gets this one, held by the shooter,
	 * and takes no harm; true when the hit is spent so. Anything else is hit as any arrow hits it.
	 */
	public static boolean struck(AbstractArrow arrow, Entity target) {
		if (!(arrow.level() instanceof ServerLevel level) || !arrow.entityTags().contains(TAG)) return false;
		if (!(target instanceof Leashable leashable) || !leashable.canBeLeashed() || isHolder(target)
			|| target.entityTags().contains(Tethers.HOLDER_TAG)) return false;
		if (!(arrow.getOwner() instanceof ServerPlayer player) || player.level() != level) return false;
		boolean carriesLead = arrow.pickup == AbstractArrow.Pickup.ALLOWED;
		if (!carriesLead && !player.hasInfiniteMaterials()) return false;

		arrow.discard();
		if (leashable.getLeashHolder() instanceof Player || !leashable.canHaveALeashAttachedTo(player)) {
			// Glanced off: the lead is still on the arrow.
			if (carriesLead) arrow.spawnAtLocation(level, arrow.getPickupItemStackOrigin().copy());
			if (!(leashable.getLeashHolder() instanceof Player)) {
				player.sendOverlayMessage(Component.translatableWithFallback("lead-line-justfatlard.too_far", "Too far off to lead it"));
			}
			return true;
		}
		if (leashable.isLeashed()) leashable.dropLeash();
		leashable.setLeashedTo(player, true);
		if (carriesLead) arrow.spawnAtLocation(level, Items.ARROW);
		level.playSound(null, target.getX(), target.getY(), target.getZ(), SoundEvents.LEAD_TIED, SoundSource.NEUTRAL, 1.0F, 1.0F);
		return true;
	}

	private static InteractionResult onUseBlock(Player player, Level level, InteractionHand hand, BlockHitResult hit) {
		if (!(player instanceof ServerPlayer server) || player.isSpectator() || player.isSecondaryUseActive()) return InteractionResult.PASS;
		BlockPos pos = hit.getBlockPos();
		if (!level.getBlockState(pos).is(BlockTags.FENCES) || linesOn(server).isEmpty()) return InteractionResult.PASS;
		return tieOff(server, LeashFenceKnotEntity.getOrCreateKnot((ServerLevel) level, pos));
	}

	/** The knot sits over its post, so a click on a knotted fence lands on the knot: tie off there, or take the lead back. */
	private static InteractionResult onUseEntity(Player player, Level level, InteractionHand hand, Entity entity, EntityHitResult hit) {
		if (!(entity instanceof LeashFenceKnotEntity knot) || !(player instanceof ServerPlayer server) || player.isSpectator()) return InteractionResult.PASS;
		if (!linesOn(server).isEmpty()) return tieOff(server, knot);
		if (player.isSecondaryUseActive()) return InteractionResult.PASS;
		boolean taken = false;
		for (CopperGolem holder : linesOn(knot)) {
			if (holder.canHaveALeashAttachedTo(server)) {
				holder.setLeashedTo(server, true);
				taken = true;
			}
		}
		if (!taken) return InteractionResult.PASS;
		knot.playSound(SoundEvents.LEAD_TIED);
		return InteractionResult.SUCCESS;
	}

	/** Every arrow's lead in this player's hand, tied off at this knot: it runs from the arrow to the fence now, and the player is free of it. */
	private static InteractionResult tieOff(ServerPlayer player, LeashFenceKnotEntity knot) {
		for (CopperGolem holder : linesOn(player)) holder.setLeashedTo(knot, true);
		player.removeAttached(Tethers.HELD);
		knot.playPlacementSound();
		player.level().gameEvent(GameEvent.BLOCK_ATTACH, knot.getPos(), GameEvent.Context.of(player));
		return InteractionResult.SUCCESS;
	}

	/** The arrows' leads this player or knot holds. */
	private static List<CopperGolem> linesOn(Entity end) {
		return LINES.keySet().stream().filter(holder -> holder.getLeashHolder() == end).toList();
	}

	/**
	 * Whether this player may not take this arrow back: someone else has it in hand, or they do and
	 * hang on it, the lead held and nothing under their feet. Pulling it out then would drop them. Tied
	 * off at a fence, anyone may take it, and the lead comes with it.
	 */
	public static boolean keepsHold(AbstractArrow arrow, Player player) {
		CopperGolem holder = TIED.get(arrow);
		if (holder == null) return false;
		Entity end = holder.getLeashHolder();
		if (!(end instanceof Player)) return false;
		if (!(player instanceof ServerPlayer server) || end != server) return true;
		return Tethers.lengthOf(server) != null
			&& server.level().noCollision(server, server.getBoundingBox().move(0, -0.1, 0).deflate(1.0E-3, 0, 1.0E-3));
	}

	/** Let go of this lead without dropping it: the lead is still in its arrow. */
	static void release(CopperGolem holder) {
		holder.removeLeash();
		forget(holder);
		holder.discard();
	}

	private static void forget(CopperGolem holder) {
		AbstractArrow arrow = LINES.remove(holder);
		if (arrow != null) TIED.remove(arrow);
	}

	private static CopperGolem spawn(ServerLevel level, AbstractArrow arrow) {
		CopperGolem holder = EntityTypes.COPPER_GOLEM.create(level, EntitySpawnReason.TRIGGERED);
		if (holder == null) return null;
		holder.addTag(HOLDER_TAG);
		holder.setNoAi(true);
		holder.setSilent(true);
		holder.setPermanentlyInvulnerable(true);
		holder.setNoGravity(true);
		holder.setPersistenceRequired();
		AttributeInstance scale = holder.getAttribute(Attributes.SCALE);
		if (scale != null) scale.setBaseValue(SCALE);
		holder.refreshDimensions();
		holder.setInvisible(true);
		holder.snapTo(pinFor(holder, arrow));
		return level.addFreshEntity(holder) ? holder : null;
	}

	/** Where the golem stands so its lead leaves from the arrow. */
	private static Vec3 pinFor(CopperGolem holder, AbstractArrow arrow) {
		return arrow.position().subtract(holder.getLeashOffset());
	}

	private static void tick(ServerLevel level) {
		if (LINES.isEmpty()) return;
		for (Map.Entry<CopperGolem, AbstractArrow> line : List.copyOf(LINES.entrySet())) {
			if (line.getKey().level() == level) tend(level, line.getKey(), line.getValue());
		}
	}

	private static void tend(ServerLevel level, CopperGolem holder, AbstractArrow arrow) {
		if (holder.isRemoved()) {
			forget(holder);
			return;
		}
		// The game let go its own way, snapped or its holder gone, and dropped the lead at the arrow:
		// what is left stuck there is a plain arrow.
		if (holder.getLeashData() == null) {
			if (!arrow.isRemoved()) ((AbstractArrowAccessor) arrow).leadLine$setPickupItemStack(new ItemStack(Items.ARROW));
			forget(holder);
			holder.discard();
			return;
		}
		// Taken back, shaken loose, or left behind in another dimension: the lead goes with the arrow.
		Entity end = holder.getLeashHolder();
		boolean heldHere = (end instanceof ServerPlayer || end instanceof LeashFenceKnotEntity) && end.level() == level && !end.isRemoved();
		if (arrow.isRemoved() || !((AbstractArrowAccessor) arrow).leadLine$isInGround() || !heldHere) {
			release(holder);
			return;
		}
		Vec3 pin = pinFor(holder, arrow);
		if (holder.position().distanceToSqr(pin) > 1.0E-6) holder.snapTo(pin);
		holder.setDeltaMovement(Vec3.ZERO);
		if (!holder.isInvisible()) holder.setInvisible(true);
		if (holder.isOnFire()) holder.clearFire();
	}
}
