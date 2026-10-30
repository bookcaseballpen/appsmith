import { Button, Text } from "@appsmith/ads";
import styles from "./styles.module.css";
import React from "react";
import { CUSTOM_WIDGET_FEATURE, createMessage } from "ee/constants/messages";
import { CUSTOM_WIDGET_DOC_URL } from "../../constants";
import { areAppsmithLinksHidden } from "ee/utils/forkHiddenFeatures";

export default function Help() {
  return (
    <div>
      <Text
        color="#6A7585"
        renderAs="p"
        style={{
          lineHeight: "18px",
        }}
      >
        {createMessage(CUSTOM_WIDGET_FEATURE.referrences.help.message)}
      </Text>
      {/* Fork: the button opens Appsmith's docs (ce/utils/forkHiddenFeatures.ts). */}
      {!areAppsmithLinksHidden() && (
        <Button
          className={styles.marginTop}
          href={CUSTOM_WIDGET_DOC_URL}
          kind="secondary"
          renderAs="a"
          size="md"
          startIcon="book"
          target="_blank"
        >
          {createMessage(CUSTOM_WIDGET_FEATURE.referrences.help.buttonCTA)}
        </Button>
      )}
    </div>
  );
}
