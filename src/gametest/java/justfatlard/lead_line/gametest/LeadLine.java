package justfatlard.lead_line.gametest;

import justfatlard.lead_line.ArrowLeads;
import justfatlard.lead_line.Main;
import justfatlard.lead_line.Tethers;
import justfatlard.lead_line.TiedRopes;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.ItemTags;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.entity.decoration.LeashFenceKnotEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/**
 * A lead tied to a post through fence-posts' own click: it measures how far it runs, past the
 * twelve blocks at which vanilla would snap it; held at a length it draws the player back to it;
 * and from a post on an overhang it holds a player hanging, drops them a step at each jump and takes
 * them up while they sneak, with the client's own keys, and no fall hurts them. A lead arrow shot
 * into an overhang ties back to its shooter and is climbed the same way; shaken loose it keeps its
 * lead, and snapped it leaves the lead and a plain arrow, never both a lead and a lead arrow. Tied
 * off at a fence it is a rope, climbed the same way: sneak to take hold and climb, jump to let a step
 * down. A lead arrow into a cow puts the cow on the lead, and villagers, zombie villagers and piglins take one. With
 * block-tip installed, the reading is on its card.
 */
public final class LeadLine implements FabricClientGameTest {
	@Override
	public void runTest(ClientGameTestContext context) {
		net.fabricmc.fabric.api.client.gametest.v1.world.TestWorldSave save;
		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			TestServerContext server = world.getServer();
			TestServerConnection connection = world.getConnection();
			connection.waitForChunksRender();
			server.runCommand("gamemode survival @a");
			BlockPos feet = server.computeOnServer(s -> connection.getServerPlayer().blockPosition());

			// Tied to a fence on the ground, the way a player ties one: a lead in hand, the fence clicked.
			BlockPos fence = feet.east(2);
			tie(server, connection, fence);
			check(server.computeOnServer(s -> Tethers.holderOf(connection.getServerPlayer()) != null), "no lead is held from the post");

			// Measured: walked eight blocks out, it runs about eight.
			stand(server, fence.east(8), context);
			double reach = reachOf(server, connection);
			check(reach > 7.5 && reach < 8.6, "eight blocks out, the lead reads " + reach);

			// Past twelve, where vanilla snaps a lead, it still holds.
			stand(server, fence.east(20), context);
			check(server.computeOnServer(s -> Tethers.holderOf(connection.getServerPlayer()) != null), "the lead snapped at twenty blocks");

			// Held at five, the player is drawn back to five.
			stand(server, fence.east(4), context);
			check(server.computeOnServer(s -> Tethers.hold(connection.getServerPlayer(), 5.0)), "the lead could not be held");
			stand(server, fence.east(10), context);
			context.waitTicks(10);
			reach = reachOf(server, connection);
			check(reach < 5.4, "held at five, the player stayed " + reach + " out");

			// Untied by hand: an empty-hand click on the post, and fence-posts drops the lead there.
			server.runOnServer(s -> {
				ServerPlayer player = connection.getServerPlayer();
				player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
				player.gameMode.useItemOn(player, s.overworld(), ItemStack.EMPTY, InteractionHand.MAIN_HAND,
					new BlockHitResult(Vec3.atCenterOf(fence), Direction.UP, fence, false));
			});
			context.waitTicks(5);
			check(server.computeOnServer(s -> Tethers.holderOf(connection.getServerPlayer()) == null), "an empty-hand click on the post did not untie the lead");
			int dropped = server.computeOnServer(s -> s.overworld().getEntitiesOfClass(ItemEntity.class, new AABB(fence).inflate(2),
				item -> item.getItem().is(Items.LEAD)).size());
			check(dropped == 1, "the untied lead did not drop at the post: " + dropped);
			check(server.computeOnServer(s -> Tethers.lengthOf(connection.getServerPlayer()) == null), "the untied lead is still held at a length");

			// A post at the end of an arm reaching out from a pillar nine high, over nothing: a lead held at
			// two, and the player stepped off under it.
			BlockPos base = feet.west(6);
			BlockPos top = base.above(9).west(2);
			server.runOnServer(s -> {
				for (int y = 0; y < 9; y++) s.overworld().setBlockAndUpdate(base.above(y), Blocks.STONE.defaultBlockState());
				s.overworld().setBlockAndUpdate(base.above(8).west(), Blocks.STONE.defaultBlockState());
				s.overworld().setBlockAndUpdate(base.above(8).west(2), Blocks.STONE.defaultBlockState());
				s.overworld().setBlockAndUpdate(top, Blocks.OAK_FENCE.defaultBlockState());
				connection.getServerPlayer().teleportTo(top.getX() - 1.5, top.getY(), top.getZ() + 0.5);
			});
			context.waitTicks(5);
			tie(server, connection, top);
			check(server.computeOnServer(s -> Tethers.holderOf(connection.getServerPlayer()) != null), "no lead from the pillar's post");
			check(server.computeOnServer(s -> Tethers.hold(connection.getServerPlayer(), 2.0)), "the pillar's lead could not be held");
			server.runOnServer(s -> connection.getServerPlayer().teleportTo(top.getX() - 1.5, top.getY() - 1, top.getZ() + 0.5));
			context.waitTicks(40);
			double hanging = server.computeOnServer(s -> connection.getServerPlayer().getY());
			check(hanging > top.getY() - 3.5, "the player fell past a lead held at two: " + (top.getY() - hanging) + " below the post");
			check(server.computeOnServer(s -> !connection.getServerPlayer().onGround()), "the player is on the ground, not hanging");

			// Each jump drops a step down the rope; holding one down drops no more than one.
			context.getInput().holdKeyFor(options -> options.keyJump, 2);
			context.waitTicks(15);
			double stepped = server.computeOnServer(s -> connection.getServerPlayer().getY());
			check(stepped < hanging - 1.0, "a jump did not drop the player a step: " + hanging + " to " + stepped);
			context.getInput().holdKeyFor(options -> options.keyJump, 20);
			context.waitTicks(15);
			double held = server.computeOnServer(s -> connection.getServerPlayer().getY());
			check(held > stepped - 2.5, "holding jump dropped more than a step: " + stepped + " to " + held);
			context.getInput().holdKeyFor(options -> options.keyJump, 2);
			context.waitTicks(15);
			double lowered = server.computeOnServer(s -> connection.getServerPlayer().getY());
			check(lowered < held - 1.0, "a second jump did not drop another step: " + held + " to " + lowered);

			// Holding on, sneaking, climbs back up.
			context.getInput().holdKeyFor(options -> options.keyShift, 20);
			context.waitTicks(10);
			double climbed = server.computeOnServer(s -> connection.getServerPlayer().getY());
			check(climbed > lowered + 1.0, "sneaking did not take the player up: " + lowered + " to " + climbed);

			// The reading is on block-tip's card, under it, while the lead is held.
			UUID id = server.computeOnServer(s -> connection.getServerPlayer().getUUID());
			context.waitTicks(10);
			check(justfatlard.block_tip.Slices.showing(id).stream().anyMatch(line -> line.contains("blocks out") && line.contains("held at")),
				"block-tip's card does not show the reading: " + justfatlard.block_tip.Slices.showing(id));

			// A picture of it, from in front: the player hanging, the lead up to the post.
			server.runCommand("execute as @a at @s run tp @s ~ ~ ~ 90 -25");
			context.runOnClient(client -> {
				client.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_FRONT);
			});
			context.waitTicks(15);
			justfatlard.pandorical.gametest.Pictures.frame(context, connection, "hanging");
			context.runOnClient(client -> {
				client.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON);
			});

			float health = server.computeOnServer(s -> connection.getServerPlayer().getHealth());
			check(health == server.computeOnServer(s -> connection.getServerPlayer().getMaxHealth()), "hanging from the lead hurt: " + health);

			// A lead arrow is ammunition, and crafted from a lead and an arrow.
			check(new ItemStack(Main.LEAD_ARROW).is(ItemTags.ARROWS), "the lead arrow is not in #arrows, so no bow will fire it");
			check(server.computeOnServer(s -> s.getRecipeManager().byKey(ResourceKey.create(Registries.RECIPE, Main.LEAD_ARROW_ID)).isPresent()),
				"the lead arrow has no recipe");

			// Shot up into an overhang eight high: the lead ties back to the shooter, held at the length it
			// flew. The pillar's post goes first, and with it that lead.
			BlockPos range = feet.north(10);
			BlockPos roof = range.above(8);
			server.runOnServer(s -> {
				s.overworld().setBlockAndUpdate(top, Blocks.AIR.defaultBlockState());
				for (int x = -2; x <= 2; x++) {
					for (int z = -2; z <= 2; z++) s.overworld().setBlockAndUpdate(roof.offset(x, 0, z), Blocks.STONE.defaultBlockState());
				}
				connection.getServerPlayer().teleportTo(range.getX() + 0.5, range.getY(), range.getZ() + 0.5);
			});
			context.waitTicks(40);
			server.runOnServer(s -> connection.getServerPlayer().setHealth(connection.getServerPlayer().getMaxHealth()));
			shootUp(server, connection);
			server.waitFor(s -> Tethers.holderOf(connection.getServerPlayer()) != null);
			Double flown = server.computeOnServer(s -> Tethers.lengthOf(connection.getServerPlayer()));
			check(flown != null && flown > 6.0 && flown < 7.5, "the lead arrow's lead is held at " + flown + ", not the seven it flew");

			// Sneaking climbs it, off the ground and up toward the overhang.
			double ground = server.computeOnServer(s -> connection.getServerPlayer().getY());
			context.getInput().holdKeyFor(options -> options.keyShift, 30);
			context.waitTicks(5);
			double up = server.computeOnServer(s -> connection.getServerPlayer().getY());
			check(up > ground + 2.0, "sneaking did not climb the lead arrow's lead: " + ground + " to " + up);

			// Hanging on it, the arrow stays in: taking it out now would drop the player.
			check(server.computeOnServer(s -> {
				Arrow arrow = stuckArrow(s, roof);
				return arrow != null && ArrowLeads.isTied(arrow) && ArrowLeads.keepsHold(arrow, connection.getServerPlayer());
			}), "the lead arrow could be pulled out from under the player hanging on it");

			server.runCommand("execute as @a at @s run tp @s ~ ~ ~ 90 -25");
			context.runOnClient(client -> client.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_FRONT));
			context.waitTicks(15);
			justfatlard.pandorical.gametest.Pictures.frame(context, connection, "lead-arrow");
			context.runOnClient(client -> client.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON));

			// Its block broken, the arrow is shaken loose and the lead goes with it: no lead drops, and
			// what lands is still a lead arrow, to pick up.
			server.runOnServer(s -> {
				ServerPlayer player = connection.getServerPlayer();
				player.getInventory().clearContent();
				// Run free first, or held at its length the lead draws them back under it, into the arrow's fall.
				Tethers.free(player);
				player.teleportTo(range.getX() + 4.5, range.getY(), range.getZ() + 0.5);
			});
			context.waitTicks(5);
			server.runOnServer(s -> {
				Arrow arrow = stuckArrow(s, roof);
				s.overworld().setBlockAndUpdate(BlockPos.containing(arrow.position().add(0, 0.1, 0)), Blocks.AIR.defaultBlockState());
			});
			context.waitTicks(60);
			check(server.computeOnServer(s -> Tethers.holderOf(connection.getServerPlayer()) == null), "the lead held on after its arrow fell");
			check(leadsAround(server, range, roof) == 0, "a lead dropped when the lead arrow fell, beside the lead arrow itself");
			Vec3 landed = server.computeOnServer(s -> {
				Arrow arrow = stuckArrow(s, range);
				return arrow == null || !arrow.getPickupItemStackOrigin().is(Main.LEAD_ARROW) ? null : arrow.position();
			});
			check(landed != null, "what fell is not a lead arrow");
			server.runOnServer(s -> connection.getServerPlayer().teleportTo(landed.x, landed.y, landed.z));
			context.waitTicks(20);
			check(server.computeOnServer(s -> connection.getServerPlayer().getInventory().contains(stack -> stack.is(Main.LEAD_ARROW))),
				"the fallen lead arrow did not come back to the player as a lead arrow");

			// Tied off at a fence twenty blocks along, farther than the game looks for leads to tie: the lead
			// runs from the arrow to the fence, out of the player's hand, holds past the twelve at which the
			// game would snap it, and the arrow is anyone's to take back now.
			stand(server, range.west(1), context);
			shootUp(server, connection);
			server.waitFor(s -> Tethers.holderOf(connection.getServerPlayer()) != null);
			server.runOnServer(s -> Tethers.free(connection.getServerPlayer()));
			BlockPos post = range.west(22);
			server.runOnServer(s -> {
				s.overworld().setBlockAndUpdate(post, Blocks.OAK_FENCE.defaultBlockState());
				connection.getServerPlayer().teleportTo(post.getX() + 1.5, post.getY(), post.getZ() + 0.5);
			});
			context.waitTicks(5);
			server.runOnServer(s -> {
				ServerPlayer player = connection.getServerPlayer();
				player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
				player.gameMode.useItemOn(player, s.overworld(), ItemStack.EMPTY, InteractionHand.MAIN_HAND,
					new BlockHitResult(Vec3.atCenterOf(post), Direction.UP, post, false));
			});
			context.waitTicks(40);
			check(server.computeOnServer(s -> Tethers.holderOf(connection.getServerPlayer()) == null), "the arrow's lead is still in hand after tying it off at a fence");
			check(server.computeOnServer(s -> arrowLine(s, roof) != null && arrowLine(s, roof).getLeashHolder() instanceof LeashFenceKnotEntity knot
				&& knot.getPos().equals(post)), "the arrow's lead does not run to the fence it was tied off at");
			check(server.computeOnServer(s -> !ArrowLeads.keepsHold(stuckArrow(s, roof), connection.getServerPlayer())),
				"tied off at a fence, the arrow cannot be taken back");
			check(leadsAround(server, range, roof) == 0, "a lead dropped tying the arrow's lead off at a fence");

			// The tied-off rope is climbed as a lead in hand is: sneaking within reach of it takes hold, holding
			// sneak climbs toward its high end, it holds the player without, a jump lets a step down, and the key
			// lets go.
			Vec3 middle = server.computeOnServer(s -> stuckArrow(s, roof).position()
				.add(LeashFenceKnotEntity.getKnot(s.overworld(), post).orElseThrow().getRopeHoldPosition(1.0F)).scale(0.5));
			context.getInput().holdKey(options -> options.keyShift);
			server.runOnServer(s -> connection.getServerPlayer().teleportTo(middle.x, middle.y - 0.9, middle.z));
			context.waitTicks(5);
			Double grabbed = server.computeOnServer(s -> TiedRopes.downOf(connection.getServerPlayer()));
			check(grabbed != null, "sneaking at the middle of a tied-off rope did not take hold of it");
			context.waitTicks(20);
			double climbedTo = server.computeOnServer(s -> TiedRopes.downOf(connection.getServerPlayer()));
			check(climbedTo < grabbed - 1.0, "holding sneak on the tied-off rope did not climb it: " + grabbed + " to " + climbedTo + " down");
			context.getInput().releaseKey(options -> options.keyShift);
			double heldAt = server.computeOnServer(s -> connection.getServerPlayer().getY());
			context.waitTicks(20);
			check(server.computeOnServer(s -> TiedRopes.downOf(connection.getServerPlayer()) != null
				&& connection.getServerPlayer().getY() > heldAt - 0.5), "the tied-off rope did not hold the player once they stopped sneaking");
			context.getInput().holdKeyFor(options -> options.keyJump, 2);
			context.waitTicks(10);
			double letOut = server.computeOnServer(s -> TiedRopes.downOf(connection.getServerPlayer()));
			check(letOut > climbedTo + 1.0, "a jump on the tied-off rope did not let a step down: " + climbedTo + " to " + letOut + " down");
			server.runOnServer(s -> Tethers.toggle(connection.getServerPlayer()));
			check(server.computeOnServer(s -> TiedRopes.downOf(connection.getServerPlayer()) == null), "the key did not let go of the tied-off rope");
			context.waitTicks(40);

			// Clicking the knot takes the lead back in hand; and the arrow taken away, it goes with it, dropping nothing.
			server.runOnServer(s -> {
				LeashFenceKnotEntity knot = LeashFenceKnotEntity.getKnot(s.overworld(), post).orElseThrow();
				UseEntityCallback.EVENT.invoker().interact(connection.getServerPlayer(), s.overworld(), InteractionHand.MAIN_HAND, knot, new EntityHitResult(knot));
			});
			context.waitTicks(5);
			check(server.computeOnServer(s -> Tethers.holderOf(connection.getServerPlayer()) != null), "clicking the knot did not take the arrow's lead back in hand");
			server.runOnServer(s -> stuckArrow(s, roof).discard());
			context.waitTicks(5);
			check(server.computeOnServer(s -> Tethers.holderOf(connection.getServerPlayer()) == null && arrowLine(s, roof) == null),
				"the lead held on after its arrow was taken away");
			check(leadsAround(server, range, roof) == 0, "a lead dropped when its arrow was taken away");

			// Run free and walked off past where it snaps, the game drops the lead at the arrow, and what is
			// left in the overhang is a plain arrow. The overhang is laid again first, whole.
			server.runOnServer(s -> {
				for (int x = -2; x <= 2; x++) {
					for (int z = -2; z <= 2; z++) s.overworld().setBlockAndUpdate(roof.offset(x, 0, z), Blocks.STONE.defaultBlockState());
				}
			});
			stand(server, range.east(1), context);
			shootUp(server, connection);
			server.waitFor(s -> Tethers.holderOf(connection.getServerPlayer()) != null);
			server.runOnServer(s -> Tethers.free(connection.getServerPlayer()));
			stand(server, range.east(60), context);
			context.waitTicks(40);
			check(server.computeOnServer(s -> Tethers.holderOf(connection.getServerPlayer()) == null), "the lead arrow's lead did not snap at sixty blocks");
			check(leadsAround(server, range, roof) == 1, "the snapped lead did not drop at its arrow: " + leadsAround(server, range, roof));
			check(server.computeOnServer(s -> {
				Arrow arrow = stuckArrow(s, roof);
				return arrow != null && arrow.getPickupItemStackOrigin().is(Items.ARROW);
			}), "the snapped lead's arrow still carries a lead, which would make two");

			// A lead arrow into a cow six blocks off: the cow is on the player's lead, unhurt, and a plain arrow
			// drops beside it. Into one twenty off, past where a lead reaches, it glances off, a lead arrow still.
			BlockPos pen = range.east(60);
			UUID near = server.computeOnServer(s -> cow(s, pen.east(6)));
			UUID far = server.computeOnServer(s -> cow(s, pen.east(20).south(4)));
			shootAt(server, connection, near);
			context.waitTicks(10);
			check(server.computeOnServer(s -> ((Mob) s.overworld().getEntity(near)).getLeashHolder() == connection.getServerPlayer()),
				"the cow a lead arrow struck is not on the player's lead");
			check(server.computeOnServer(s -> ((Mob) s.overworld().getEntity(near)).getHealth() == ((Mob) s.overworld().getEntity(near)).getMaxHealth()),
				"the lead arrow hurt the cow it led");
			check(server.computeOnServer(s -> dropped(s, near, Items.ARROW) == 1 && dropped(s, near, Main.LEAD_ARROW) == 0),
				"leading the cow did not leave a plain arrow beside it");
			shootAt(server, connection, far);
			context.waitTicks(10);
			check(server.computeOnServer(s -> !((Mob) s.overworld().getEntity(far)).isLeashed()), "a cow twenty blocks off was led");
			check(server.computeOnServer(s -> ((Mob) s.overworld().getEntity(far)).getHealth() == ((Mob) s.overworld().getEntity(far)).getMaxHealth()),
				"the lead arrow that glanced off hurt the cow");
			check(server.computeOnServer(s -> dropped(s, far, Main.LEAD_ARROW) == 1), "the lead arrow that glanced off did not drop as a lead arrow");

			// A villager takes a lead put on by hand, as vanilla's will not, and a zombie villager one shot on an
			// arrow, to be led off and cured.
			UUID villager = server.computeOnServer(s -> spawn(s, net.minecraft.world.entity.EntityTypes.VILLAGER, pen.north(3)));
			server.runOnServer(s -> {
				ServerPlayer player = connection.getServerPlayer();
				player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.LEAD));
				Mob mob = (Mob) s.overworld().getEntity(villager);
				mob.interact(player, InteractionHand.MAIN_HAND, mob.position());
			});
			context.waitTicks(5);
			check(server.computeOnServer(s -> ((Mob) s.overworld().getEntity(villager)).getLeashHolder() == connection.getServerPlayer()),
				"a lead put on a villager by hand did not take");
			UUID zombie = server.computeOnServer(s -> spawn(s, net.minecraft.world.entity.EntityTypes.ZOMBIE_VILLAGER, pen.south(6).west(3)));
			shootAt(server, connection, zombie);
			context.waitTicks(10);
			check(server.computeOnServer(s -> ((Mob) s.overworld().getEntity(zombie)).getLeashHolder() == connection.getServerPlayer()),
				"a lead arrow into a zombie villager did not put it on the lead");

			// A piglin takes one, to be led to where it barters; a piglin brute does not.
			UUID piglin = server.computeOnServer(s -> spawn(s, net.minecraft.world.entity.EntityTypes.PIGLIN, pen.south(6).east(3)));
			shootAt(server, connection, piglin);
			context.waitTicks(10);
			check(server.computeOnServer(s -> ((Mob) s.overworld().getEntity(piglin)).getLeashHolder() == connection.getServerPlayer()),
				"a lead arrow into a piglin did not put it on the lead");
			check(server.computeOnServer(s -> !((Mob) spawnedNow(s, net.minecraft.world.entity.EntityTypes.PIGLIN_BRUTE, pen.north(6))).canBeLeashed()),
				"a piglin brute can be put on a lead");

			// One more, held at three on the ground, to come back to after a restart.
			BlockPos last = feet.south(6);
			server.runOnServer(s -> connection.getServerPlayer().teleportTo(last.getX() + 2.5, last.getY(), last.getZ() + 0.5));
			context.waitTicks(5);
			tie(server, connection, last);
			check(server.computeOnServer(s -> Tethers.hold(connection.getServerPlayer(), 3.0)), "the last lead could not be held");
			context.waitTicks(5);
			save = world.getWorldSave();
		}

		// Closed and opened again: the lead is still held at three, and still holds.
		try (TestSingleplayerContext again = save.open()) {
			TestServerContext server = again.getServer();
			TestServerConnection connection = again.getConnection();
			connection.waitForChunksRender();
			server.waitFor(s -> Tethers.holderOf(connection.getServerPlayer()) != null);
			Double length = server.computeOnServer(s -> Tethers.lengthOf(connection.getServerPlayer()));
			check(length != null && Math.abs(length - 3.0) < 1.0E-6, "after a restart the lead is held at " + length + ", not three");
			BlockPos post = server.computeOnServer(s -> net.minecraft.core.BlockPos.containing(Tethers.anchorOf(Tethers.holderOf(connection.getServerPlayer()))));
			stand(server, post.east(9), context);
			context.waitTicks(10);
			double reach = reachOf(server, connection);
			check(reach < 3.4, "after a restart, held at three, the player stayed " + reach + " out");
			System.out.println("[lead-line-test] passed");
		}
	}

	private static void tie(TestServerContext server, TestServerConnection connection, BlockPos fence) {
		server.runOnServer(s -> {
			s.overworld().setBlockAndUpdate(fence, s.overworld().getBlockState(fence).isAir() ? Blocks.OAK_FENCE.defaultBlockState() : s.overworld().getBlockState(fence));
			ServerPlayer player = connection.getServerPlayer();
			player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.LEAD));
			player.gameMode.useItemOn(player, s.overworld(), player.getMainHandItem(), InteractionHand.MAIN_HAND,
				new BlockHitResult(Vec3.atCenterOf(fence), Direction.UP, fence, false));
		});
	}

	/** A lead arrow fired by the player straight up, as a bow would loose it. */
	private static void shootUp(TestServerContext server, TestServerConnection connection) {
		server.runOnServer(s -> {
			ServerPlayer player = connection.getServerPlayer();
			AbstractArrow arrow = Main.LEAD_ARROW.createArrow(s.overworld(), new ItemStack(Main.LEAD_ARROW), player, null);
			arrow.shoot(0, 1, 0, 3.0F, 0.0F);
			s.overworld().addFreshEntity(arrow);
		});
	}

	/** A cow that stands where it is put. */
	private static UUID cow(net.minecraft.server.MinecraftServer s, BlockPos at) {
		return spawn(s, net.minecraft.world.entity.EntityTypes.COW, at);
	}

	private static net.minecraft.world.entity.Entity spawnedNow(net.minecraft.server.MinecraftServer s, net.minecraft.world.entity.EntityType<? extends Mob> type, BlockPos at) {
		return s.overworld().getEntity(spawn(s, type, at));
	}

	/** A creature that stands where it is put. */
	private static UUID spawn(net.minecraft.server.MinecraftServer s, net.minecraft.world.entity.EntityType<? extends Mob> type, BlockPos at) {
		Mob mob = type.create(s.overworld(), net.minecraft.world.entity.EntitySpawnReason.TRIGGERED);
		mob.setNoAi(true);
		mob.snapTo(Vec3.atBottomCenterOf(at));
		s.overworld().addFreshEntity(mob);
		return mob.getUUID();
	}

	/** A lead arrow of the player's, loosed at this creature from two blocks short of it, wherever the player stands. */
	private static void shootAt(TestServerContext server, TestServerConnection connection, UUID target) {
		server.runOnServer(s -> {
			ServerPlayer player = connection.getServerPlayer();
			Vec3 at = s.overworld().getEntity(target).getBoundingBox().getCenter();
			Vec3 path = at.subtract(player.getEyePosition()).normalize();
			AbstractArrow arrow = Main.LEAD_ARROW.createArrow(s.overworld(), new ItemStack(Main.LEAD_ARROW), player, null);
			arrow.setPos(at.subtract(path.scale(2)));
			arrow.shoot(path.x, path.y, path.z, 2.0F, 0.0F);
			s.overworld().addFreshEntity(arrow);
		});
	}

	private static int dropped(net.minecraft.server.MinecraftServer s, UUID by, net.minecraft.world.item.Item item) {
		return s.overworld().getEntitiesOfClass(ItemEntity.class, s.overworld().getEntity(by).getBoundingBox().inflate(3),
			drop -> drop.getItem().is(item)).size();
	}

	/** The golem that holds a lead arrow's lead near here, or null. */
	private static CopperGolem arrowLine(net.minecraft.server.MinecraftServer s, BlockPos roof) {
		return s.overworld().getEntitiesOfClass(CopperGolem.class, new AABB(roof).inflate(3), ArrowLeads::isHolder).stream().findFirst().orElse(null);
	}

	private static Arrow stuckArrow(net.minecraft.server.MinecraftServer s, BlockPos roof) {
		return s.overworld().getEntitiesOfClass(Arrow.class, new AABB(roof).inflate(3)).stream().findFirst().orElse(null);
	}

	private static int leadsAround(TestServerContext server, BlockPos range, BlockPos roof) {
		return server.computeOnServer(s -> s.overworld().getEntitiesOfClass(ItemEntity.class, new AABB(range).minmax(new AABB(roof)).inflate(4),
			item -> item.getItem().is(Items.LEAD)).size());
	}

	private static void stand(TestServerContext server, BlockPos at, ClientGameTestContext context) {
		server.runCommand("tp @a " + (at.getX() + 0.5) + " " + at.getY() + " " + (at.getZ() + 0.5));
		context.waitTicks(5);
	}

	private static double reachOf(TestServerContext server, TestServerConnection connection) {
		return server.computeOnServer(s -> {
			ServerPlayer player = connection.getServerPlayer();
			CopperGolem holder = Tethers.holderOf(player);
			return holder == null ? -1 : Tethers.reach(player, holder);
		});
	}

	private static void check(boolean holds, String otherwise) {
		if (!holds) throw new AssertionError(otherwise);
	}
}
