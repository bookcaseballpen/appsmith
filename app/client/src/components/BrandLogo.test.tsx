import React from "react";
import { Provider } from "react-redux";
import configureStore from "redux-mock-store";
import { render } from "@testing-library/react";
import BrandLogo from "./BrandLogo";
import { ThemeMode } from "selectors/themeSelectors";
import {
  BRAND_LOGO_ON_DARK_URL,
  BRAND_LOGO_URL,
  PRODUCT_NAME,
} from "constants/Brand";

const mockStore = configureStore([]);

const renderLogo = (
  props: React.ComponentProps<typeof BrandLogo>,
  mode = ThemeMode.LIGHT,
) =>
  render(
    <Provider store={mockStore({ ui: { theme: { mode } } })}>
      <BrandLogo {...props} />
    </Provider>,
  );

describe("BrandLogo", () => {
  it("shows the mono wordmark with the product name on light backgrounds", () => {
    const { getByAltText, getByTestId } = renderLogo({ height: 32 });

    expect(getByAltText(PRODUCT_NAME).getAttribute("src")).toBe(
      "/brand/creative-logo-mono.svg",
    );
    expect(getByTestId("t--brand-logo").textContent).toBe("App Builder");
  });

  it("treats the default organization logo as the brand logo", () => {
    const { getByAltText } = renderLogo({
      height: 20,
      logoUrl: BRAND_LOGO_URL,
    });

    expect(getByAltText(PRODUCT_NAME).getAttribute("src")).toBe(BRAND_LOGO_URL);
  });

  it("shows the white wordmark on dark backgrounds", () => {
    const { getByAltText } = renderLogo({ height: 20, onDark: true });

    expect(getByAltText(PRODUCT_NAME).getAttribute("src")).toBe(
      BRAND_LOGO_ON_DARK_URL,
    );
  });

  it("follows the dark theme mode when the background is not given", () => {
    const { getByAltText } = renderLogo({ height: 20 }, ThemeMode.DARK);

    expect(getByAltText(PRODUCT_NAME).getAttribute("src")).toBe(
      BRAND_LOGO_ON_DARK_URL,
    );
  });

  it("shows a custom organization logo as it is, without the product name", () => {
    const { getByAltText, queryByTestId } = renderLogo({
      height: 20,
      logoUrl: "/custom/logo.png",
    });

    expect(getByAltText("Logo").getAttribute("src")).toBe("/custom/logo.png");
    expect(queryByTestId("t--brand-logo")).toBeNull();
  });
});
