package justfatlard.lead_line;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import justfatlard.pandorical.api.ItemRegistration;
import justfatlard.pandorical.api.KeybindApi;
import justfatlard.pandorical.api.PandoricalApi;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.commands.Commands;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/**
 * Lead Line: a lead tied to a post, or shot on an arrow, and held, to measure by, to walk a circle
 * round, and to hang from and climb.
 */
public class Main implements ModInitializer {
	public static final String MOD_ID = "lead-line-justfatlard";
	public static final Identifier LEAD_ARROW_ID = Identifier.fromNamespaceAndPath(MOD_ID, "lead_arrow");
	public static final LeadArrowItem LEAD_ARROW = new LeadArrowItem(new Item.Properties().setId(ResourceKey.create(Registries.ITEM, LEAD_ARROW_ID)));

	@Override
	public void onInitialize() {
		Registry.register(BuiltInRegistries.ITEM, LEAD_ARROW_ID, LEAD_ARROW);
		PandoricalApi.content().registerItem(LEAD_ARROW_ID.toString(), new ItemRegistration()
			.model(MOD_ID + ":item/lead_arrow")
			.maxStackSize(64));
		PandoricalApi.content().registerModAssets(MOD_ID);
		CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.COMBAT).register(output ->
			output.insertAfter(Items.SPECTRAL_ARROW, LEAD_ARROW));
		ArrowLeads.register();

		PandoricalApi.keybinds().register(MOD_ID + ":hold", KeybindApi.letter('K'), "Hold the lead at this length", Tethers::toggle);
		PandoricalApi.keybinds().bindByDefault(MOD_ID + ":hold");
		PandoricalApi.commandHelp().describe("/lead hold", "Hold the lead you are holding at the length it runs now.");
		PandoricalApi.commandHelp().describe("/lead hold <length>", "Hold the lead at this many blocks.");
		PandoricalApi.commandHelp().describe("/lead free", "Let the lead run free again.");

		CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) -> dispatcher.register(Commands.literal("lead")
			.then(Commands.literal("hold")
				.executes(context -> Tethers.hold(context.getSource().getPlayerOrException(), null) ? 1 : 0)
				.then(Commands.argument("length", DoubleArgumentType.doubleArg(Tethers.SHORTEST, Tethers.LONGEST_HELD))
					.executes(context -> Tethers.hold(context.getSource().getPlayerOrException(),
						DoubleArgumentType.getDouble(context, "length")) ? 1 : 0)))
			.then(Commands.literal("free").executes(context -> {
				Tethers.free(context.getSource().getPlayerOrException());
				return 1;
			}))));

		// Loads the class, which registers the saved length, before any player does.
		Tethers.HELD.identifier();
		// The reading goes on block-tip's card when it is here and new enough to take it; the action bar otherwise.
		if (net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("block-tip")) {
			Tethers.useCard(justfatlard.lead_line.integration.LeadTips.register());
		}
		ServerTickEvents.END_SERVER_TICK.register(Tethers::tick);
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> Tethers.forget(handler.getPlayer().getUUID()));
	}
}
