import React from "react";
import { Provider } from "react-redux";
import configureStore from "redux-mock-store";
import { render } from "@testing-library/react";
import { EditorReadOnlyBanner } from "./EditorReadOnlyBanner";

const mockStore = configureStore([]);

const renderWith = (readOnly: boolean) =>
  render(
    <Provider
      store={mockStore({
        ui: {
          users: {
            featureFlag: {
              data: { fork_editor_read_only: readOnly },
              overriddenFlags: {},
            },
          },
        },
      })}
    >
      <EditorReadOnlyBanner />
    </Provider>,
  );

describe("EditorReadOnlyBanner", () => {
  it("tells the user to edit through the projects repo when read-only", () => {
    const { getByTestId } = renderWith(true);

    expect(getByTestId("t--editor-read-only-banner").textContent).toBe(
      "Read-only: edit this app through the projects repo (MCP).",
    );
  });

  it("renders nothing otherwise", () => {
    const { queryByTestId } = renderWith(false);

    expect(queryByTestId("t--editor-read-only-banner")).toBeNull();
  });
});
