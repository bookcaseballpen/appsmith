import type { DefaultRootState } from "react-redux";
import { getHideWatermark as getHideWatermark_CE } from "ce/selectors/organizationSelectors";
import { areAppsmithLinksHidden } from "ee/utils/forkHiddenFeatures";

export * from "ce/selectors/organizationSelectors";

// Fork: no "Built on Appsmith" badge in the app viewer (ce/utils/forkHiddenFeatures.ts).
export const getHideWatermark = (state: DefaultRootState): boolean =>
  areAppsmithLinksHidden() || getHideWatermark_CE(state);
