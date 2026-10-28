# syntax=docker/dockerfile:1
#
# Fork: builds the whole image from source, with no Appsmith-published image or host involved.
# Run it through scripts/fork-build/build-image.sh (or .ps1) from the repo root; see FORK.md "Build and deploy".
#
#   BASE        the runtime base, built locally from deploy/docker/base.dockerfile (upstream pulls appsmith/base-ce)
#   NODE_IMAGE  official Node image for the client, RTS and MCP builds (matches app/client/.nvmrc)
#   MAVEN_IMAGE official Maven image with Temurin JDK 25 for the server build
#
# The last stage repeats the upstream root Dockerfile, but copies the artifacts from the build stages instead of
# from the host. Keep the two in sync after a cherry-pick that changes the root Dockerfile.

ARG BASE=creative-app-builder-base:local
ARG NODE_IMAGE=node:24.14.1-bookworm
ARG MAVEN_IMAGE=maven:3.9-eclipse-temurin-25

# ---- Client, RTS and MCP ----
FROM ${NODE_IMAGE} AS client

WORKDIR /src/app/client
COPY app/client ./

# --mode=skip-build skips the install hooks: the root postinstall patches Cypress and installs Husky (needs .git),
# neither is needed to build. The yarn release is committed (.yarn/releases), so nothing is fetched to get yarn.
ENV YARN_ENABLE_GLOBAL_CACHE=true \
    YARN_ENABLE_TELEMETRY=0 \
    CYPRESS_INSTALL_BINARY=0 \
    PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1
RUN --mount=type=cache,target=/root/.yarn/berry/cache \
    node .yarn/releases/yarn-3.5.1.cjs install --immutable --mode=skip-build

# Same as build.sh, without the airgap asset download (assets are committed under public/assets-cdn).
# DEVELOPMENT, like upstream's non-release builds: PRODUCTION would also enable the Faro source-map upload plugin.
ARG REACT_APP_ENVIRONMENT=DEVELOPMENT
RUN REACT_APP_ENVIRONMENT=${REACT_APP_ENVIRONMENT} \
    REACT_APP_VERSION_EDITION=Community \
    REACT_APP_CLIENT_LOG_LEVEL=ERROR \
    node --max-old-space-size=12288 scripts/build.js

RUN cd packages/rts && rm -rf dist && node build.js
RUN cd packages/mcp && rm -rf dist && node build.js

# ---- Server and plugins ----
FROM ${MAVEN_IMAGE} AS server

WORKDIR /src/app/server
COPY app/server ./

RUN --mount=type=cache,target=/root/.m2/repository \
    mvn --batch-mode --no-transfer-progress clean package -DskipTests -Dspotless.skip=true

# Same layout as app/server/build.sh followed by scripts/prepare_server_artifacts.sh: the plugin jars directly in
# each target/, not the dependency jars some plugins also copy into target/lib/.
RUN mkdir -p /dist/mongo/plugins \
  && cp -v appsmith-server/target/server-*.jar /dist/mongo/server.jar \
  && find appsmith-plugins -mindepth 3 -maxdepth 3 -path '*/target/*.jar' ! -name 'original-*.jar' \
       -exec cp -v '{}' /dist/mongo/plugins/ ';'

# ---- Container filesystem ----
FROM ${BASE} AS fs

COPY deploy/docker/fs /fs

# A Windows checkout with core.autocrlf=true has CRLF scripts; bash cannot run them. Normalise every text file.
RUN find /fs -type f -exec grep -Il . '{}' + | xargs -r sed -i 's/\r$//'

ARG COMMIT_SHA=unknown
ARG VERSION=v0.0.0-local
ARG IMAGE_BUILT_AT=unknown
RUN printf '{\n  "commitSha": "%s",\n  "commitUrl": "",\n  "githubRef": "",\n  "githubRunUrl": "",\n  "version": "%s",\n  "imageBuiltAt": "%s",\n  "isCI": false\n}\n' \
      "$COMMIT_SHA" "$VERSION" "$IMAGE_BUILT_AT" > /fs/opt/appsmith/info.json

# ---- Runtime image (mirrors the root Dockerfile) ----
FROM ${BASE}

COPY --from=fs /fs /

COPY --from=server /dist/mongo server/mongo/

# Add client UI - Application Layer
COPY --from=client /src/app/client/build editor/

# Add RTS - Application Layer
COPY --from=client /src/app/client/packages/rts/dist rts/

# Add MCP - Application Layer
COPY --from=client /src/app/client/packages/mcp/dist mcp/

# Create the git-storage directory with group writeable permissions so non-root users can write to it.
RUN mkdir --mode 775 "/dev/shm/git-storage"

ENV PATH=/opt/bin:/opt/java/bin:/opt/node/bin:$PATH

# Same steps as the root Dockerfile's heredoc, written as one chain: a heredoc breaks when a Windows checkout gives
# this file CRLF line endings.
# Make all `*.sh` files executable (excluding `node_modules`) and the custom command-scripts, disable setuid/setgid
# bits for the files inside the container, then create the writable directories.
RUN set -o errexit \
  && find . \( -name node_modules -prune \) -o \( -type f -name '*.sh' \) -exec chmod +x '{}' + \
  && chmod +x /opt/bin/* /watchtower-hooks/*.sh \
  && { find / \( -path /proc -prune \) -o \( \( -perm -2000 -o -perm -4000 \) -exec chmod -s '{}' + \) || true; } \
  && mkdir -p /.mongodb/mongosh /appsmith-stacks \
  && chmod ugo+w /etc /appsmith-stacks \
  && chmod -R ugo+w /var/run /.mongodb /etc/ssl /usr/local/share

EXPOSE 80
EXPOSE 443
ENTRYPOINT [ "/opt/appsmith/entrypoint.sh" ]
HEALTHCHECK --interval=15s --timeout=15s --start-period=45s CMD "/opt/appsmith/healthcheck.sh"
CMD ["/usr/bin/supervisord", "-n"]
