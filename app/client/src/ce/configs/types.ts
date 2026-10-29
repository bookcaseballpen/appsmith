import type { LogLevelDesc } from "loglevel";

export interface AppVersionData {
  id: string;
  sha: string;
  releaseDate: string;
  edition: string;
}

// Fork: the Sentry, Smartlook, BetterBugs, Segment, Mixpanel and Pylon settings are removed with their SDKs.
export interface AppsmithUIConfigs {
  observability: {
    deploymentName: string;
    serviceInstanceId: string;
    serviceName: string;
    tracingUrl: string;
  };
  fusioncharts: {
    enabled: boolean;
    licenseKey: string;
  };

  cloudHosting: boolean;

  logLevel: LogLevelDesc;
  appVersion: AppVersionData;
  mailEnabled: boolean;

  googleRecaptchaSiteKey: {
    enabled: boolean;
    apiKey: string;
  };
  appsmithSupportEmail: string;
  disableIframeWidgetSandbox: boolean;
  disableHelpIcon: boolean;
  defaultHtmlLang: string;
  pricingUrl: string;
  customerPortalUrl: string;
}

export interface DatasourceMeta {
  configuredDatasources: number;
  totalDatasources: number;
}

// Type for one environment
export interface EnvironmentType {
  id: string;
  name: string;
  workspaceId: string;
  isDefault?: boolean;
  isLocked: boolean; // Whether the environment is locked (disables editing and deleting of the env)
  userPermissions?: string[];
  datasourceMeta?: DatasourceMeta;
}
