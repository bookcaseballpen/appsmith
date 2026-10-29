import React from "react";
import { Provider } from "react-redux";
import configureStore from "redux-mock-store";
import { render } from "@testing-library/react";
import { EditorEntityTab } from "IDE/Interfaces/EditorTypes";
import SegmentSwitcher from "./SegmentSwitcher";

jest.mock("../../../hooks/useCurrentEditorState", () => ({
  useCurrentEditorState: () => ({ segment: EditorEntityTab.QUERIES }),
}));

jest.mock("./useSegmentNavigation", () => ({
  useSegmentNavigation: () => ({ onSegmentChange: jest.fn() }),
}));

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
      <SegmentSwitcher />
    </Provider>,
  );

describe("SegmentSwitcher", () => {
  it("has no UI tab in the read-only editor", () => {
    const { getByText, queryByText } = renderWith(true);

    expect(getByText("Queries")).toBeTruthy();
    expect(getByText("JS")).toBeTruthy();
    expect(queryByText("UI")).toBeNull();
  });

  it("shows the UI tab otherwise", () => {
    const { getByText } = renderWith(false);

    expect(getByText("UI")).toBeTruthy();
  });
});
