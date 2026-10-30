import { createSelector } from "reselect";
import type { DefaultRootState } from "react-redux";
import { showProductRamps as CE_showProductRamps } from "ce/selectors/rampSelectors";
import { areUpsellsHidden } from "ee/utils/forkHiddenFeatures";

export * from "ce/selectors/rampSelectors";

const noRamp = createSelector(
  (state: DefaultRootState) => state.ui,
  () => false,
);

/**
 * Fork: no product ramp, i.e. a locked paid-plan option that points at Appsmith's pricing: the "Staging" environment
 * in the bottom bar and the datasource editor, private embedding, custom roles and the business-plan invite text (see
 * ce/utils/forkHiddenFeatures.ts, `areUpsellsHidden`).
 */
export const showProductRamps = (
  rampName: string,
  isEnterpriseOnlyFeature = false,
  isFeatureFlagEnabled?: boolean,
) =>
  areUpsellsHidden()
    ? noRamp
    : CE_showProductRamps(
        rampName,
        isEnterpriseOnlyFeature,
        isFeatureFlagEnabled,
      );
