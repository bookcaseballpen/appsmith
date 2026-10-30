import React from "react";
import { Button } from "@appsmith/ads";

import Page from "./Page";
import {
  createMessage,
  PAGE_CLIENT_ERROR_DESCRIPTION,
  PAGE_CLIENT_ERROR_TITLE,
} from "ee/constants/messages";
import { flushErrors } from "actions/errorActions";
import { useDispatch } from "react-redux";
import { DISCORD_URL } from "constants/ThirdPartyConstants";
import { areAppsmithLinksHidden } from "ee/utils/forkHiddenFeatures";

function GenericError(props: { errorCode?: string }) {
  const dispatch = useDispatch();

  return (
    <Page
      // Fork: no "Contact us on discord" (ce/utils/forkHiddenFeatures.ts).
      cta={
        !areAppsmithLinksHidden() && (
          <Button
            className="button-position"
            endIcon="right-arrow"
            kind="primary"
            onClick={() => {
              dispatch(flushErrors());
              window.open(DISCORD_URL, "_blank");
            }}
            size="md"
          >
            Contact us on discord
          </Button>
        )
      }
      description={createMessage(PAGE_CLIENT_ERROR_DESCRIPTION)}
      errorCode={props.errorCode}
      title={createMessage(PAGE_CLIENT_ERROR_TITLE)}
    />
  );
}

export default GenericError;
