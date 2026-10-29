import { render, screen } from "test/testUtils";
import React from "react";
import "@testing-library/jest-dom";
import { SignupSuccess } from "./SignupSuccess";

const useSelector = jest.fn();
const values = {
  isSuperUser: true,
};

useSelector.mockReturnValue(values);

function renderComponent() {
  return render(<SignupSuccess />);
}

describe("SignupSuccess", () => {
  it("If we are intending to redirect do not show the signup form", () => {
    renderComponent();
    expect(screen.queryByTestId("welcome-page")).toBeNull();
  });

  // Fork: onboarding is hidden, so a new (non-invited) user never gets the profiling form.
  it("does not show the profiling form to a new user who would get the onboarding flow", () => {
    window.history.pushState(
      {},
      "",
      "/signup-success?redirectUrl=%2Fapplications&enableFirstTimeUserExperience=true",
    );

    renderComponent();

    expect(screen.queryByTestId("welcome-page")).toBeNull();
    window.history.pushState({}, "", "/");
  });
});
