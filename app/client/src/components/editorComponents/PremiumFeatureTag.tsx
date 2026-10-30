import React from "react";
import { Icon, Tag } from "@appsmith/ads";
import { VisuallyHidden } from "@react-aria/visually-hidden";
import { areUpsellsHidden } from "ee/utils/forkHiddenFeatures";

function PremiumFeatureTag() {
  // Fork: no paid-plan badge (ce/utils/forkHiddenFeatures.ts).
  if (areUpsellsHidden()) return null;

  return (
    <Tag isClosable={false} size="md">
      <Icon name="star-line" size="sm" />
      <VisuallyHidden>Premium feature</VisuallyHidden>
    </Tag>
  );
}

export default PremiumFeatureTag;
