/* eslint-disable react-perf/jsx-no-new-object-as-prop */
import React from "react";
import { render, screen } from "@testing-library/react";
import "@testing-library/jest-dom";
import { HOW_TO_ADD_DEPLOY_KEY, createMessage } from "ee/constants/messages";
import AddDeployKey from "./AddDeployKey";
import type { GitProvider } from "./types";

const defaultProps = {
  error: null,
  isSubmitLoading: false,
  isSSHKeyLoading: false,
  onChange: jest.fn(),
  onFetchSSHKey: jest.fn(),
  onGenerateSSHKey: jest.fn(),
  sshPublicKey: "ecdsa-sha2-nistp256 AAAAE2VjZHNh",
};

describe("AddDeployKey demo section", () => {
  // Fork: the demo GIFs were on assets.appsmith.com and are not vendored, so the demo is hidden.
  it.each(["github", "gitlab", "bitbucket", "others"] as GitProvider[])(
    "renders no demo and no empty image for %s",
    (gitProvider) => {
      const { container } = render(
        <AddDeployKey
          {...defaultProps}
          value={{ gitProvider, remoteUrl: "git@example.com:user/repo.git" }}
        />,
      );

      expect(
        screen.queryByText(createMessage(HOW_TO_ADD_DEPLOY_KEY)),
      ).not.toBeInTheDocument();
      expect(container.querySelector("img")).toBeNull();
    },
  );
});
