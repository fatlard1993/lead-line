package justfatlard.lead_line.gametest;

import justfatlard.lead_line.Tethers;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * A lead tied to a post through fence-posts' own click: it measures how far it runs, past the
 * twelve blocks at which vanilla would snap it; held at a length it draws the player back to it;
 * and from a post on a pillar it holds a player hanging, lets them down while they sneak and takes
 * them up while they jump, with the client's own keys, and no fall hurts them.
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

			// Sneaking lets the lead out; jumping takes it in.
			context.getInput().holdKeyFor(options -> options.keyShift, 20);
			context.waitTicks(10);
			double lowered = server.computeOnServer(s -> connection.getServerPlayer().getY());
			check(lowered < hanging - 1.5, "sneaking did not let the player down: " + hanging + " to " + lowered);
			context.getInput().holdKeyFor(options -> options.keyJump, 20);
			context.waitTicks(10);
			double climbed = server.computeOnServer(s -> connection.getServerPlayer().getY());
			check(climbed > lowered + 1.0, "jumping did not take the player up: " + lowered + " to " + climbed);

			float health = server.computeOnServer(s -> connection.getServerPlayer().getHealth());
			check(health == server.computeOnServer(s -> connection.getServerPlayer().getMaxHealth()), "hanging from the lead hurt: " + health);

			// One more, held at three on the ground, to come back to after a restart. The pillar's post
			// goes first, or the click would hand that lead over to the new post rather than tie a new one.
			BlockPos last = feet.south(6);
			server.runOnServer(s -> {
				s.overworld().setBlockAndUpdate(top, Blocks.AIR.defaultBlockState());
				connection.getServerPlayer().teleportTo(last.getX() + 2.5, last.getY(), last.getZ() + 0.5);
			});
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
