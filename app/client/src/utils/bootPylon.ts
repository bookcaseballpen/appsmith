import type { User } from "constants/userConstants";

// Fork: the Pylon support chat is removed (no loader in index.html, no SDK script). These no-op shims keep the
// existing call sites compiling; every `window.Pylon(...)` call is guarded by `isPylonChatAvailable()`.

/** Always false: the Pylon chat is not part of this fork. */
export function isPylonChatAvailable(): boolean {
  return false;
}

// eslint-disable-next-line @typescript-eslint/no-unused-vars
export default function bootPylon(user?: User) {}

export const updatePylonChatIdentity = (
  // eslint-disable-next-line @typescript-eslint/no-unused-vars
  instanceId: string,
  // eslint-disable-next-line @typescript-eslint/no-unused-vars
  user?: User,
) => {};
