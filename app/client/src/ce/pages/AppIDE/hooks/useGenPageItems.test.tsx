import { renderHook } from "@testing-library/react-hooks";
import { useGenPageItems } from "./useGenPageItems";

// A fixed-layout app, where upstream offers both "Generate page with data" and "Add page from template".
jest.mock("layoutSystems/common/useLayoutSystemFeatures", () => ({
  ...jest.requireActual("layoutSystems/common/useLayoutSystemFeatures"),
  useLayoutSystemFeatures: () => (features: string[]) =>
    features.map(() => true),
}));
jest.mock("react-redux", () => ({
  ...jest.requireActual("react-redux"),
  useDispatch: () => jest.fn(),
}));

describe("useGenPageItems (fork: templates are hidden)", () => {
  it("offers 'Generate page with data' but not 'Add page from template'", () => {
    const { result } = renderHook(() => useGenPageItems());

    const keys = result.current.map((item) => item.key);

    expect(keys).toContain("GENERATE_PAGE");
    expect(keys).not.toContain("ADD_PAGE_FROM_TEMPLATE");
  });
});
