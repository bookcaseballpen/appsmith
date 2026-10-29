// Fork: Segment, Mixpanel and Smartlook are removed. AnalyticsUtil stays as an inert shim because `logEvent` has
// hundreds of call sites; nothing is collected or sent from here. See FORK.md.
import type { User } from "constants/userConstants";
import type { EventName } from "ee/utils/analyticsUtilTypes";
import type { APP_MODE } from "entities/App";

import {
  initLicense,
  initInstanceId,
  getEventExtraProperties,
} from "ee/utils/Analytics/getEventExtraProperties";

export enum AnalyticsEventType {
  error = "error",
}

// eslint-disable-next-line @typescript-eslint/no-explicit-any
export type EventProperties = Record<string, any>;

// eslint-disable-next-line @typescript-eslint/no-unused-vars
async function initialize(user: User) {}

// eslint-disable-next-line @typescript-eslint/no-unused-vars
function setAppMode(appMode: APP_MODE | undefined) {}

function logEvent(
  // eslint-disable-next-line @typescript-eslint/no-unused-vars
  eventName: EventName,
  // eslint-disable-next-line @typescript-eslint/no-unused-vars
  eventData?: EventProperties,
  // eslint-disable-next-line @typescript-eslint/no-unused-vars
  eventType?: AnalyticsEventType,
) {}

// eslint-disable-next-line @typescript-eslint/no-unused-vars
async function identifyUser(userData: User, sendAdditionalData?: boolean) {}

// eslint-disable-next-line @typescript-eslint/no-unused-vars
function setBlockErrorLogs(value: boolean) {}

function getAnonymousId(): string | undefined | null {
  return undefined;
}

function reset() {}

function avoidTracking() {}

export {
  initialize,
  logEvent,
  identifyUser,
  initInstanceId,
  setBlockErrorLogs,
  getAnonymousId,
  reset,
  getEventExtraProperties,
  initLicense,
  avoidTracking,
  setAppMode,
};
