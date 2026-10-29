import type { AppsmithUIConfigs } from "./types";

declare global {
  interface Window {
    // Pylon chat widget (https://docs.usepylon.com/pylon-docs/chat-widget/javascript-api)
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    Pylon: any;
    pylon?: {
      chat_settings: {
        app_id: string;
        email?: string;
        email_hash?: string;
        name?: string;
        avatar_url?: string;
        account_external_id?: string;
      };
    };
  }
}

// Fork: the Sentry, Smartlook, BetterBugs, Segment, Mixpanel and Pylon settings are removed with their SDKs.
export interface INJECTED_CONFIGS {
  observability: {
    deploymentName: string;
    serviceInstanceId: string;
    tracingUrl: string;
  };
  fusioncharts: {
    licenseKey: string;
  };
  cloudHosting: boolean;
  logLevel: "debug" | "error";
  appVersion: {
    id: string;
    sha: string;
    releaseDate: string;
    edition: string;
  };
  mailEnabled: boolean;
  googleRecaptchaSiteKey: string;
  supportEmail: string;
  disableIframeWidgetSandbox: boolean;
  disableHelpIcon: boolean;
  defaultHtmlLang: string;
  pricingUrl: string;
  customerPortalUrl: string;
}

export const getConfigsFromEnvVars = (): INJECTED_CONFIGS => {
  return {
    fusioncharts: {
      licenseKey: process.env.REACT_APP_FUSIONCHARTS_LICENSE_KEY || "",
    },
    observability: {
      deploymentName: "self-hosted",
      serviceInstanceId: "appsmith-0",
      tracingUrl: "",
    },
    logLevel:
      (process.env.REACT_APP_CLIENT_LOG_LEVEL as
        | "debug"
        | "error"
        | undefined) || "error",
    cloudHosting: process.env.REACT_APP_CLOUD_HOSTING
      ? process.env.REACT_APP_CLOUD_HOSTING.length > 0
      : false,
    appVersion: {
      id: "",
      sha: "",
      releaseDate: "",
      edition: process.env.REACT_APP_VERSION_EDITION || "",
    },
    mailEnabled: process.env.REACT_APP_MAIL_ENABLED
      ? process.env.REACT_APP_MAIL_ENABLED.length > 0
      : false,
    googleRecaptchaSiteKey:
      process.env.REACT_APP_GOOGLE_RECAPTCHA_SITE_KEY || "",
    supportEmail: process.env.APPSMITH_SUPPORT_EMAIL || "support@appsmith.com",

    disableIframeWidgetSandbox: process.env
      .APPSMITH_DISABLE_IFRAME_WIDGET_SANDBOX
      ? process.env.APPSMITH_DISABLE_IFRAME_WIDGET_SANDBOX.length > 0
      : false,
    disableHelpIcon: process.env.APPSMITH_DISABLE_HELP_ICON
      ? process.env.APPSMITH_DISABLE_HELP_ICON.length > 0
      : false,
    defaultHtmlLang: process.env.APPSMITH_DEFAULT_HTML_LANG || "",
    pricingUrl: process.env.REACT_APP_PRICING_URL || "",
    customerPortalUrl: process.env.REACT_APP_CUSTOMER_PORTAL_URL || "",
  };
};

const getConfig = (fromENV: string, fromWindow = "") => {
  if (fromWindow.length > 0) return { enabled: true, value: fromWindow };
  else if (fromENV.length > 0) return { enabled: true, value: fromENV };

  return { enabled: false, value: "" };
};

// TODO(Abhinav): See if this is called so many times, that we may need some form of memoization.
export const getAppsmithConfigs = (): AppsmithUIConfigs => {
  const APPSMITH_FEATURE_CONFIGS =
    // This code might be called both from the main thread and a web worker
    typeof window === "undefined" ? undefined : window.APPSMITH_FEATURE_CONFIGS;
  const ENV_CONFIG = getConfigsFromEnvVars();
  const observabilityDeploymentName = getConfig(
    ENV_CONFIG.observability.deploymentName,
    APPSMITH_FEATURE_CONFIGS?.observability.deploymentName,
  );
  const observabilityServiceInstanceId = getConfig(
    ENV_CONFIG.observability.serviceInstanceId,
    APPSMITH_FEATURE_CONFIGS?.observability.serviceInstanceId,
  );
  const observabilityFrontendTracingUrl = getConfig(
    ENV_CONFIG.observability.tracingUrl,
    APPSMITH_FEATURE_CONFIGS?.observability.tracingUrl,
  );
  const fusioncharts = getConfig(
    ENV_CONFIG.fusioncharts.licenseKey,
    APPSMITH_FEATURE_CONFIGS?.fusioncharts.licenseKey,
  );

  const googleRecaptchaSiteKey = getConfig(
    ENV_CONFIG.googleRecaptchaSiteKey,
    APPSMITH_FEATURE_CONFIGS?.googleRecaptchaSiteKey,
  );

  return {
    observability: {
      deploymentName: observabilityDeploymentName.value,
      serviceInstanceId: observabilityServiceInstanceId.value,
      serviceName: "appsmith-client",
      tracingUrl: observabilityFrontendTracingUrl.value,
    },
    fusioncharts: {
      enabled: fusioncharts.enabled,
      licenseKey: fusioncharts.value,
    },
    googleRecaptchaSiteKey: {
      enabled: googleRecaptchaSiteKey.enabled,
      apiKey: googleRecaptchaSiteKey.value,
    },
    cloudHosting:
      ENV_CONFIG.cloudHosting ||
      APPSMITH_FEATURE_CONFIGS?.cloudHosting ||
      false,
    logLevel:
      ENV_CONFIG.logLevel || APPSMITH_FEATURE_CONFIGS?.logLevel || false,
    appVersion: {
      id: APPSMITH_FEATURE_CONFIGS?.appVersion?.id || "",
      sha: APPSMITH_FEATURE_CONFIGS?.appVersion?.sha || "",
      releaseDate: APPSMITH_FEATURE_CONFIGS?.appVersion?.releaseDate || "",
      edition:
        ENV_CONFIG.appVersion?.edition ||
        APPSMITH_FEATURE_CONFIGS?.appVersion?.edition ||
        "",
    },
    mailEnabled:
      ENV_CONFIG.mailEnabled || APPSMITH_FEATURE_CONFIGS?.mailEnabled || false,
    appsmithSupportEmail: ENV_CONFIG.supportEmail,
    disableIframeWidgetSandbox:
      ENV_CONFIG.disableIframeWidgetSandbox ||
      APPSMITH_FEATURE_CONFIGS?.disableIframeWidgetSandbox ||
      false,
    disableHelpIcon: !!(
      ENV_CONFIG.disableHelpIcon || APPSMITH_FEATURE_CONFIGS?.disableHelpIcon
    ),
    defaultHtmlLang:
      ENV_CONFIG.defaultHtmlLang ||
      APPSMITH_FEATURE_CONFIGS?.defaultHtmlLang ||
      "",
    pricingUrl:
      ENV_CONFIG.pricingUrl || APPSMITH_FEATURE_CONFIGS?.pricingUrl || "",
    customerPortalUrl:
      ENV_CONFIG.customerPortalUrl ||
      APPSMITH_FEATURE_CONFIGS?.customerPortalUrl ||
      "",
  };
};
