FROM redis:7.4.8 AS redis-source

FROM caddy:builder-alpine AS caddybuilder

# caddy:builder-alpine sets XCADDY_SETCAP=1, which calls setcap on the output
# binary. Linux refuses to execve a file with file capabilities when the
# calling process's bounding set has those caps dropped (e.g. Kubernetes
# restricted profile with cap-drop ALL). The image binds low ports via
# net.ipv4.ip_unprivileged_port_start, so the setcap is unnecessary.
#
# --replace pins mitigate x/crypto, x/net and x/text CVEs from the May 22, 2026
# coordinated Go security disclosure. None are reachable in Caddy's HTTP
# path (the x/crypto CVEs are all in the SSH subsystem), but scanners
# flag the embedded library version regardless.
#
# Verify these with `go version -m /opt/caddy/caddy`, which prints the effective
# "dep X => Y" pairs. Grepping the binary for module@version strings reports the
# pre-replace version and will make a working pin look inert.
RUN XCADDY_SETCAP=0 xcaddy build \
  --with github.com/mholt/caddy-ratelimit \
  --replace golang.org/x/crypto=golang.org/x/crypto@v0.52.0 \
  --replace golang.org/x/net=golang.org/x/net@v0.56.0 \
  --replace golang.org/x/text=golang.org/x/text@v0.39.0

# Fork: no MongoDB server, shell or database tools (upstream built mongo-tools from source here). The internal
# database is PostgreSQL 16, embedded below or external. See FORK.md "PostgreSQL".

FROM ubuntu:24.04

LABEL maintainer="tech@appsmith.com"

WORKDIR /opt/appsmith

# The env variables are needed for Appsmith server to correctly handle non-roman scripts like Arabic.
ENV LANG=C.UTF-8
ENV LC_ALL=C.UTF-8

# Install dependency packages
RUN set -o xtrace \
  # Make apt resilient to transient Ubuntu-mirror connection failures on the build
  # host. ubuntu:24.04 ships no retry config (apt default is 0 retries), so a single
  # dropped connection fails the whole build. This drop-in is build-scoped: it is
  # removed in the cleanup step below, so the shipped image's apt behavior is
  # unchanged. Does not rescue a sustained egress outage, only transient blips. APP-15960.
  && printf 'Acquire::Retries "3";\nAcquire::http::Timeout "30";\nAcquire::https::Timeout "30";\n' > /etc/apt/apt.conf.d/80-appsmith-retries \
  && apt-get update \
  && apt-get upgrade --yes \
  && DEBIAN_FRONTEND=noninteractive apt-get install --no-install-recommends --yes \
    supervisor curl nfs-common gnupg \
    gettext \
    ca-certificates \
    libnss-wrapper \
    software-properties-common \
    git \
  && add-apt-repository -y ppa:git-core/ppa \
  # Install PostgreSQL v16 (fork: the internal database; upstream also installed MongoDB v7 here, and PostgreSQL v14)
  && echo "deb http://apt.postgresql.org/pub/repos/apt $(grep CODENAME /etc/lsb-release | cut -d= -f2)-pgdg main" | tee /etc/apt/sources.list.d/pgdg.list \
  && curl --fail --retry 3 --retry-connrefused --connect-timeout 15 --retry-max-time 60 --silent --show-error --location -o /tmp/pgdg-ACCC4CF8.asc https://www.postgresql.org/media/keys/ACCC4CF8.asc \
  && apt-key add /tmp/pgdg-ACCC4CF8.asc \
  && apt update \
  && DEBIAN_FRONTEND=noninteractive apt-get install --no-install-recommends --yes \
    postgresql-16 \
    git tar zstd openssh-client \
  # software-properties-common is only needed for the add-apt-repository call above, but it
  # pulls in python3-launchpadlib, which drags python3-cryptography, python3-jwt and
  # python3-httplib2 into the runtime image and accounts for 12 scanner findings. Purge it
  # once the PPA is registered — the sources.list entry and its signing key persist
  # independently of the tool that wrote them.
  && DEBIAN_FRONTEND=noninteractive apt-get purge --yes --auto-remove software-properties-common \
  && apt-get clean \
  && rm -rf \
    /root/.cache \
    /root/.npm \
    /usr/local/share/doc \
    /usr/share/doc \
    /usr/share/man \
    /var/lib/apt/lists/* \
    /etc/apt/apt.conf.d/80-appsmith-retries \
    /tmp/*

# Install Redis from official image to avoid false positive CVE reports from dpkg-based scanners.
COPY --from=redis-source /usr/local/bin/redis-server /usr/local/bin/redis-server
COPY --from=redis-source /usr/local/bin/redis-cli /usr/local/bin/redis-cli

ENV PATH="/usr/lib/postgresql/16/bin:${PATH}"

# Install Java
RUN set -o xtrace \
  && mkdir -p /opt/java \
  && arch="$(uname -m | sed 's/x86_64/x64/; s/aarch64/aarch64/')" \
  && curl --fail --retry 3 --retry-connrefused --connect-timeout 15 --retry-max-time 60 --location -o /tmp/adoptium-jdk.tar.gz "https://api.adoptium.net/v3/binary/latest/25/ga/linux/${arch}/jdk/hotspot/normal/eclipse" \
  && tar -xzf /tmp/adoptium-jdk.tar.gz -C /opt/java --strip-components 1 \
  && rm -f /tmp/adoptium-jdk.tar.gz

# Install NodeJS
RUN <<END
  set -eo xtrace

  mkdir -p /opt/node
  arch="$(uname -m | sed 's/x86_64/x64/; s/aarch64/arm64/')"

  curl -LOsS "https://nodejs.org/dist/latest-v24.x/SHASUMS256.txt"
  filename="$(awk '/linux-'"$arch"'.tar.gz/ {print $2}' SHASUMS256.txt)"

  curl -LOsS "https://nodejs.org/dist/latest-v24.x/$filename"
  grep "$filename" SHASUMS256.txt | sha256sum -c -
  tar -xzf "$filename" -C /opt/node --strip-components 1

  rm "$filename" SHASUMS256.txt

  # Node 24.x bundles npm 11.16/11.17, whose bundled tar (<=7.5.16) is vulnerable
  # to CVE-2026-59873 (node-tar gzip-bomb DoS). npm 11.18.0 is the first release
  # bundling the patched tar 7.5.19; pin it since no Node 24.x ships a fixed npm yet.
  export PATH="/opt/node/bin:$PATH"
  npm install -g npm@11.18.0
  # npm 11.18.0 / 11.19.0 still vendor brace-expansion 5.0.7 (CVE-2026-69152 /
  # CVE-2026-14257) and ip-address 10.2.0 (CVE-2026-69192). Unpack patched
  # tarballs over the nested copies; `npm install --prefix` on npm's own
  # package.json tries to resolve private @npmcli/* deps and 404s.
  npm_nm="$(npm root -g)/npm/node_modules"
  tmp="$(mktemp -d)"
  (cd "$tmp" && npm pack --silent brace-expansion@5.0.9 ip-address@10.3.1)
  rm -rf "$npm_nm/brace-expansion" "$npm_nm/ip-address"
  mkdir -p "$npm_nm/brace-expansion" "$npm_nm/ip-address"
  tar -xzf "$tmp"/brace-expansion-*.tgz -C "$npm_nm/brace-expansion" --strip-components 1
  tar -xzf "$tmp"/ip-address-*.tgz -C "$npm_nm/ip-address" --strip-components 1
  rm -rf "$tmp"
  npm cache clean --force
END

# Install Caddy (built with rate-limit module via xcaddy; the module is inert unless configured)
RUN mkdir -p /opt/caddy
COPY --from=caddybuilder /usr/bin/caddy /opt/caddy/caddy

VOLUME [ "/appsmith-stacks" ]

ENV TMP="/tmp/appsmith"
ENV WWW_PATH="$TMP/www"

# libnss_wrapper.so is written to an architecture-specific directory, so we symlink to it in a common location to make it easier to activate
ENV NSS_WRAPPER_SYMLINK=/usr/local/lib/libnss_wrapper.so
RUN NSS_WRAPPER_LIB=$(find /usr/lib -name libnss_wrapper.so -type f 2>/dev/null | head -n1) && \
    ln -sf "$NSS_WRAPPER_LIB" $NSS_WRAPPER_SYMLINK
# these env vars need to be set for NSS Wrapper to work but don't matter until LD_PRELOAD is set which is optionally done at runtime
ENV NSS_WRAPPER_PASSWD="${TMP}/passwd"
ENV NSS_WRAPPER_GROUP="${TMP}/group"
