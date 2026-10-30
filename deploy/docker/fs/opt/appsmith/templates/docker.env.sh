#!/bin/bash

set -o nounset
# Fork: the internal database is PostgreSQL (embedded unless APPSMITH_DB_URL points elsewhere); no MongoDB user.
DB_PASSWORD="$1"
ENCRYPTION_PASSWORD="$2"
ENCRYPTION_SALT="$3"
REDIS_PASSWORD="${4:-}"

cat <<EOF
# Google OAuth
APPSMITH_OAUTH2_GOOGLE_CLIENT_ID=
APPSMITH_OAUTH2_GOOGLE_CLIENT_SECRET=

# Github OAuth
APPSMITH_OAUTH2_GITHUB_CLIENT_ID=
APPSMITH_OAUTH2_GITHUB_CLIENT_SECRET=

# Form Login/Signup
APPSMITH_FORM_LOGIN_DISABLED=
APPSMITH_SIGNUP_DISABLED=

#Client log level (debug | error)
APPSMITH_CLIENT_LOG_LEVEL=

# Email server
APPSMITH_MAIL_ENABLED=
APPSMITH_MAIL_HOST=
APPSMITH_MAIL_PORT=
APPSMITH_MAIL_USERNAME=
APPSMITH_MAIL_PASSWORD=
APPSMITH_MAIL_FROM=
APPSMITH_REPLY_TO=

# Email server feature toggles
# true | false values
APPSMITH_MAIL_SMTP_AUTH=
APPSMITH_MAIL_SMTP_TLS_ENABLED=

# Fork: telemetry (Segment, Mixpanel, Sentry, Smartlook, BetterBugs, Pylon) is removed from the code.
# This stays true so any remaining check reads telemetry as off.
APPSMITH_DISABLE_TELEMETRY=true

# Fork: Appsmith cloud services (cs.appsmith.com) stay off unless this is set to true.
APPSMITH_CLOUD_SERVICES_ENABLED=false

# Fork: the built-in AI (AI assistant, Ask AI, AI settings, AI datasources) stays off unless this is set to true.
# MCP works either way.
APPSMITH_AI_ENABLED=false

# Fork: Appsmith's built-in Git (connect, branches, commit, pull, deploy keys, import from Git) stays off unless this
# is set to true; apps are versioned by export and import instead.
APPSMITH_GIT_ENABLED=false

# Google Recaptcha Config
APPSMITH_RECAPTCHA_SITE_KEY=
APPSMITH_RECAPTCHA_SECRET_KEY=
APPSMITH_RECAPTCHA_ENABLED=

APPSMITH_DB_URL=postgresql://appsmith:$DB_PASSWORD@localhost:5432/appsmith

APPSMITH_REDIS_URL=redis://:$REDIS_PASSWORD@127.0.0.1:6379
APPSMITH_REDIS_PASSWORD=$REDIS_PASSWORD

APPSMITH_ENCRYPTION_PASSWORD=$ENCRYPTION_PASSWORD
APPSMITH_ENCRYPTION_SALT=$ENCRYPTION_SALT

APPSMITH_CUSTOM_DOMAIN=

# APPSMITH_PLUGIN_MAX_RESPONSE_SIZE_MB=5
# MAX PAYLOAD SIZE
# APPSMITH_CODEC_SIZE=

# Set this to a space separated list of addresses that should be allowed to load Appsmith in a frame.
# Example: "https://mydomain.com https://another-trusted-domain.com" will allow embedding on those two domains.
# Default value, if commented or not set, is "'self'", which allows framing only by the Appsmith
# instance itself (same origin) and blocks all cross-origin framing. Use "'none'" to block framing
# entirely, or "*" to allow any origin (not recommended: exposes published apps to clickjacking).
# https://developer.mozilla.org/en-US/docs/Web/HTTP/Headers/Content-Security-Policy/frame-ancestors
#APPSMITH_ALLOWED_FRAME_ANCESTORS=

APPSMITH_DISABLE_IFRAME_WIDGET_SANDBOX=false

EOF
