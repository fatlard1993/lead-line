package justfatlard.lead_line.integration;

import justfatlard.block_tip.api.BlockTipApi;
import justfatlard.lead_line.Tethers;

/**
 * The reading on block-tip's card rather than the action bar: a slice under the card, up for as long
 * as a lead is held from a post, whatever the player is looking at. Only reached when block-tip is
 * installed, and only once it is new enough to take a slice.
 */
public final class LeadTips {
	private LeadTips() {}

	/** True once the slice is on block-tip's card; false with a block-tip too old to take one. */
	public static boolean register() {
		try {
			BlockTipApi.slice(Tethers::reading);
			return true;
		} catch (LinkageError tooOld) {
			return false;
		}
	}
}
