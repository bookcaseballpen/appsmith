import React from "react";
import "@testing-library/jest-dom";
import { fireEvent, render, screen } from "@testing-library/react";
import { ThemeProvider } from "styled-components";
import { lightTheme } from "selectors/themeSelectors";
import SaveAdminSettings, {
  RESTART_HINT,
  UNSAVED_CHANGES_HINT,
} from "./SaveSettings";

const renderFooter = (
  props: Partial<React.ComponentProps<typeof SaveAdminSettings>> = {},
) => {
  const onSave = jest.fn();
  const onClear = jest.fn();

  render(
    <ThemeProvider theme={lightTheme}>
      <SaveAdminSettings
        onClear={onClear}
        onSave={onSave}
        settings={{}}
        valid
        {...props}
      />
    </ThemeProvider>,
  );

  return {
    onClear,
    onSave,
    reset: screen.getByRole("button", { name: "Reset" }),
    save: document.querySelector(
      ".t--admin-settings-save-button",
    ) as HTMLButtonElement,
    hint: screen.getByTestId("t--admin-settings-footer-hint"),
  };
};

// Fork: the settings shell's sticky footer (ee/pages/AdminSettings/SettingsShell).
describe("the admin settings footer", () => {
  it("disables Reset and Save until something changes, and says saving restarts the server", () => {
    const { hint, reset, save } = renderFooter();

    expect(reset).toBeDisabled();
    expect(save).toBeDisabled();
    expect(save.textContent).toBe("Save & Restart");
    expect(hint.textContent).toBe(RESTART_HINT);
  });

  it("enables both once a setting changes", () => {
    const { hint, onClear, onSave, reset, save } = renderFooter({
      settings: { APPSMITH_MAIL_HOST: "smtp.example.com" },
    });

    expect(reset).toBeEnabled();
    expect(save).toBeEnabled();
    expect(hint.textContent).toBe(`${UNSAVED_CHANGES_HINT} ${RESTART_HINT}`);

    fireEvent.click(reset);
    fireEvent.click(save);

    expect(onClear).toHaveBeenCalled();
    expect(onSave).toHaveBeenCalled();
  });

  it("keeps Save disabled while a changed value is invalid", () => {
    const { reset, save } = renderFooter({
      settings: { APPSMITH_MAIL_HOST: "" },
      valid: false,
    });

    expect(reset).toBeEnabled();
    expect(save).toBeDisabled();
  });

  it("names a plain save, without a restart, for organization settings", () => {
    const { hint, save } = renderFooter({
      isOnlyOrganizationConfig: true,
      settings: { instanceName: "Apps" },
      updatedOrganizationSettings: ["instanceName"],
    });

    expect(save.textContent).toBe("Save Changes");
    expect(hint.textContent).toBe(UNSAVED_CHANGES_HINT);
  });
});
