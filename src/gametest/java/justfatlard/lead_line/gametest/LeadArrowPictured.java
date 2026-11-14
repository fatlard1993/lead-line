package justfatlard.lead_line.gametest;

import justfatlard.lead_line.ArrowLeads;
import justfatlard.lead_line.Main;
import justfatlard.lead_line.Tethers;
import justfatlard.pandorical.api.PandoricalApi;
import justfatlard.pandorical.gametest.Pictures;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.CameraType;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * The picture for the readme's Lead Arrows: a lead arrow shot from a bow into the underside of a
 * stone bridge, and its lead running from where it stuck back down to the hand of the player who
 * shot it, standing out on the grass at the length it flew.
 *
 * <p>The player is the one holding the lead, as the mod has it, so the player is in the picture and
 * the camera cannot be a spectator: it is the player's own third-person view from behind, stood
 * off with Pandorical's camera distance, and aimed by which way the player faces.
 */
public final class LeadArrowPictured implements FabricClientGameTest {
	private static final long SEED = 20261114L;
	/** How high the bridge's underside is over the ground. */
	private static final int DECK = 6;
	/**
	 * Where the player stands to shoot: south of the bridge's middle, out from under it, and east of
	 * it, so seen from behind them the lead runs aslant, up and away to the arrow.
	 */
	private static final int OUT = 6;
	private static final int ASIDE = 4;
	/**
	 * Which way the player faces, and so where the third-person camera stands behind them: north, at
	 * the bridge, looking a little up, so the camera stands back and low and sees past them up the
	 * lead to the arrow and the bridge.
	 */
	private static final float YAW = 180F;
	private static final float PITCH = -12F;
	/** How far back the camera stands from the player. */
	private static final float DISTANCE = 5F;

	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestSingleplayerContext world = Pictures.world(context, SEED)) {
			TestServerContext server = world.getServer();
			TestServerConnection connection = world.getConnection();
			Pictures.stage(context, server, Pictures.MORNING);

			// Searched from the world's middle, not from where the player spawned: the game spawns a
			// player anywhere in a few blocks of the spawn point, and a search from there found a
			// different stretch of plains each run.
			BlockPos plains = server.computeOnServer(s -> Pictures.flattest(s.overworld(), BlockPos.ZERO, "minecraft:plains", 800, 48));
			check(plains != null, "no plains near the world's middle on seed " + SEED);
			Pictures.look(server, Vec3.atCenterOf(plains).add(0, 12, -14), Vec3.atCenterOf(plains));
			Pictures.settle(context, connection);

			BlockPos ground = server.computeOnServer(s -> Pictures.dryGround(s.overworld(), plains.getX(), plains.getZ(), 16));
			check(ground != null, "no dry ground in the plains at " + plains);
			server.runOnServer(s -> {
				ServerLevel level = s.overworld();
				Pictures.clearAround(level, ground, 40, 24);
				flatten(level, ground);
				bridge(level, ground);
			});

			// The player in survival out on the grass south of the bridge and off to one side, a bow in
			// hand, and a lead arrow loosed from there up into the middle of the bridge's underside.
			BlockPos stand = ground.south(OUT).east(ASIDE);
			server.runCommand("gamemode survival @a");
			server.runOnServer(s -> {
				ServerPlayer player = connection.getServerPlayer();
				player.getInventory().clearContent();
				player.teleportTo(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5);
				player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.BOW));
			});
			context.waitTicks(20);
			server.runOnServer(s -> {
				ServerPlayer player = connection.getServerPlayer();
				AbstractArrow arrow = Main.LEAD_ARROW.createArrow(s.overworld(), new ItemStack(Main.LEAD_ARROW), player, null);
				Vec3 path = new Vec3(ground.getX() + 0.5, ground.getY() + DECK + 0.5, ground.getZ()).subtract(arrow.position()).normalize();
				arrow.shoot(path.x, path.y, path.z, 3.0F, 0.0F);
				s.overworld().addFreshEntity(arrow);
			});
			server.waitFor(s -> Tethers.holderOf(connection.getServerPlayer()) != null);
			check(server.computeOnServer(s -> ArrowLeads.isHolder(Tethers.holderOf(connection.getServerPlayer()))),
				"the lead the player holds is not the lead arrow's");
			Vec3 pin = server.computeOnServer(s -> Tethers.anchorOf(Tethers.holderOf(connection.getServerPlayer())));
			check(Math.abs(pin.y - (ground.getY() + DECK)) < 0.5 && Math.abs(pin.z - ground.getZ()) < 2.5,
				"the lead arrow did not stick in the bridge's underside, but at " + pin);

			// Seen from behind and low, looking up the lead as the player does.
			server.runCommand("execute as @a at @s run tp @s ~ ~ ~ " + YAW + " " + PITCH);
			server.runOnServer(s -> PandoricalApi.camera().setDistance(connection.getServerPlayer(), DISTANCE));
			context.runOnClient(client -> client.options.setCameraType(CameraType.THIRD_PERSON_BACK));
			context.waitTicks(20);
			check(server.computeOnServer(s -> Tethers.holderOf(connection.getServerPlayer()) != null), "the lead let go before the picture");
			System.out.println("[lead-line test] picture " + Pictures.shoot(context, connection, "lead-arrow-bridge").toAbsolutePath());
			context.runOnClient(client -> client.options.setCameraType(CameraType.FIRST_PERSON));
			server.runOnServer(s -> PandoricalApi.camera().reset(connection.getServerPlayer()));
		}
	}

	/**
	 * The plains made level round the bridge and back past where the camera stands, so no step in the
	 * ground cuts across the foot of the picture.
	 */
	private static void flatten(ServerLevel level, BlockPos middle) {
		for (int x = -24; x <= 24; x++) {
			for (int z = -30; z <= 20; z++) {
				for (int y = -4; y <= DECK + 4; y++) {
					var state = y < -1 ? Blocks.DIRT.defaultBlockState()
						: y == -1 ? Blocks.GRASS_BLOCK.defaultBlockState() : Blocks.AIR.defaultBlockState();
					level.setBlock(middle.offset(x, y, z), state, 2 | 16);
				}
			}
		}
	}

	/**
	 * A stone brick bridge, its deck {@link #DECK} over the ground and five wide, running east to west
	 * over {@code middle} on piers either side of it.
	 */
	private static void bridge(ServerLevel level, BlockPos middle) {
		var bricks = Blocks.STONE_BRICKS.defaultBlockState();
		var wall = Blocks.STONE_BRICK_WALL.defaultBlockState();
		for (int x = -14; x <= 14; x++) {
			for (int z = -2; z <= 2; z++) {
				level.setBlockAndUpdate(middle.offset(x, DECK, z), bricks);
				if (Math.abs(z) == 2) level.setBlockAndUpdate(middle.offset(x, DECK + 1, z), wall);
				if (Math.abs(x) == 7) {
					for (int y = -3; y < DECK; y++) level.setBlockAndUpdate(middle.offset(x, y, z), bricks);
				}
			}
		}
	}

	private static void check(boolean holds, String otherwise) {
		if (!holds) throw new AssertionError(otherwise);
	}
}
