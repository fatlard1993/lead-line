package justfatlard.lead_line;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import justfatlard.pandorical.api.KeybindApi;
import justfatlard.pandorical.api.PandoricalApi;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.commands.Commands;

/**
 * Lead Line: a lead tied to a post and held, to measure by, to walk a circle round, and to hang
 * from and climb.
 */
public class Main implements ModInitializer {
	public static final String MOD_ID = "lead-line-justfatlard";

	@Override
	public void onInitialize() {
		PandoricalApi.keybinds().register(MOD_ID + ":hold", KeybindApi.letter('K'), "Hold the lead at this length", Tethers::toggle);
		PandoricalApi.keybinds().bindByDefault(MOD_ID + ":hold");
		PandoricalApi.commandHelp().describe("/lead hold", "Hold the lead you are holding at the length it runs now.");
		PandoricalApi.commandHelp().describe("/lead hold <length>", "Hold the lead at this many blocks.");
		PandoricalApi.commandHelp().describe("/lead free", "Let the lead run free again.");

		CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) -> dispatcher.register(Commands.literal("lead")
			.then(Commands.literal("hold")
				.executes(context -> Tethers.hold(context.getSource().getPlayerOrException(), null) ? 1 : 0)
				.then(Commands.argument("length", DoubleArgumentType.doubleArg(Tethers.SHORTEST, Tethers.LONGEST - 2))
					.executes(context -> Tethers.hold(context.getSource().getPlayerOrException(),
						DoubleArgumentType.getDouble(context, "length")) ? 1 : 0)))
			.then(Commands.literal("free").executes(context -> {
				Tethers.free(context.getSource().getPlayerOrException());
				return 1;
			}))));

		// Loads the class, which registers the saved length, before any player does.
		Tethers.HELD.identifier();
		ServerTickEvents.END_SERVER_TICK.register(Tethers::tick);
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> Tethers.forget(handler.getPlayer().getUUID()));
	}
}
