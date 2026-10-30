#!/usr/bin/env bash

# Source the helper script
source pg-utils.sh
# shellcheck source=deploy/docker/fs/opt/appsmith/load-env.sh
source "$(dirname "$0")/load-env.sh" || exit 1

set -e

stacks_path=/appsmith-stacks

export SUPERVISORD_CONF_TARGET="$TMP/supervisor-conf.d/"  # export for use in supervisord.conf

mkdir -pv "$SUPERVISORD_CONF_TARGET" "$WWW_PATH"

if [ "$(id -u)" != "0" ]; then
    # if user is non-root setup nss_wrapper
    # If this is a container restart, the files may already exist and we want them to remain read-only
    if [[ ! -f /tmp/appsmith/passwd ]]; then
        echo "appsmith:x:$(id -u):$(id -g):Appsmith:/opt/appsmith:/bin/bash" > /tmp/appsmith/passwd
        chmod 444 /tmp/appsmith/passwd
    fi
    if [[ ! -f /tmp/appsmith/group ]]; then
        echo "appsmith:x:$(id -g):" > /tmp/appsmith/group
        chmod 444 /tmp/appsmith/group
    fi
    # NSS_WRAPPER_PASSWD, NSS_WRAPPER_GROUP, and NSS_WRAPPER_SYMLINK are set in Dockerfile
    export LD_PRELOAD="$NSS_WRAPPER_SYMLINK"
fi

tlog "Running as: $(id)"

setup_proxy_variables() {
  export NO_PROXY="${NO_PROXY-localhost,127.0.0.1}"

  # Ensure `localhost` and `127.0.0.1` are in always present in `NO_PROXY`.
  local no_proxy_lines
  no_proxy_lines="$(echo "$NO_PROXY" | tr , \\n)"
  if ! echo "$no_proxy_lines" | grep -q '^localhost$'; then
    export NO_PROXY="localhost,$NO_PROXY"
  fi
  if ! echo "$no_proxy_lines" | grep -q '^127.0.0.1$'; then
    export NO_PROXY="127.0.0.1,$NO_PROXY"
  fi

  # If one of NO_PROXY or no_proxy are set, copy it to the other. If both are set, prefer NO_PROXY.
  if [[ -n ${NO_PROXY-} ]]; then
    export no_proxy="$NO_PROXY"
  elif [[ -n ${no_proxy-} ]]; then
    export NO_PROXY="$no_proxy"
  fi

  # If one of HTTPS_PROXY or https_proxy are set, copy it to the other. If both are set, prefer HTTPS_PROXY.
  if [[ -n ${HTTPS_PROXY-} ]]; then
    export https_proxy="$HTTPS_PROXY"
  elif [[ -n ${https_proxy-} ]]; then
    export HTTPS_PROXY="$https_proxy"
  fi

  # If one of HTTP_PROXY or http_proxy are set, copy it to the other. If both are set, prefer HTTP_PROXY.
  if [[ -n ${HTTP_PROXY-} ]]; then
    export http_proxy="$HTTP_PROXY"
  elif [[ -n ${http_proxy-} ]]; then
    export HTTP_PROXY="$http_proxy"
  fi
}


init_env_file() {
  CONF_PATH="/appsmith-stacks/configuration"
  ENV_PATH="$CONF_PATH/docker.env"
  TEMPLATES_PATH="/opt/appsmith/templates"

  # Preserve externally supplied values verbatim, including embedded newlines.
  (umask 077; /usr/bin/python3 /opt/appsmith/env-file.py snapshot "$TMP/pre-define.json")

  tlog "Initialize .env file"
  if ! [[ -e "$ENV_PATH" ]]; then
    # Generate new docker.env file when initializing container for first time or in Heroku which does not have persistent volume
    tlog "Generating default configuration file"
    mkdir -p "$CONF_PATH"

    # Fork: the password of the embedded PostgreSQL's appsmith user (letters and digits, so the URL needs no escaping).
    local generated_appsmith_db_password=$(
      tr -dc A-Za-z0-9 </dev/urandom | head -c 13
      echo ""
    )
    local generated_appsmith_encryption_password=$(
      tr -dc A-Za-z0-9 </dev/urandom | head -c 13
      echo ""
    )
    local generated_appsmith_encription_salt=$(
      tr -dc A-Za-z0-9 </dev/urandom | head -c 13
      echo ""
    )
    local generated_appsmith_redis_password=$(
      tr -dc A-Za-z0-9 </dev/urandom | head -c 13
      echo ''
    )

    bash "$TEMPLATES_PATH/docker.env.sh" "$generated_appsmith_db_password" "$generated_appsmith_encryption_password" "$generated_appsmith_encription_salt" "$generated_appsmith_redis_password" > "$ENV_PATH"
  else
    tlog "Configuration file already exists"
    # Validate before backfills mutate the persistent configuration.
    /usr/bin/python3 /opt/appsmith/env-file.py validate "$ENV_PATH" || exit 1
    # Backfill APPSMITH_REDIS_PASSWORD for existing installs that don't have it yet.
    # Only inject auth into the Redis URL when it points to the embedded (localhost) Redis.
    if ! grep -q "APPSMITH_REDIS_PASSWORD" "$ENV_PATH"; then
      local generated_appsmith_redis_password=$(
        tr -dc A-Za-z0-9 </dev/urandom | head -c 13
        echo ''
      )
      echo $'\nAPPSMITH_REDIS_PASSWORD='"$generated_appsmith_redis_password" >> "$ENV_PATH"
      # Update the Redis URL to include the password, but only for the embedded Redis.
      local current_redis_url
      current_redis_url=$(grep "^APPSMITH_REDIS_URL=" "$ENV_PATH" | tail -1 | cut -d= -f2-)
      if [[ "$current_redis_url" == *"localhost"* || "$current_redis_url" == *"127.0.0.1"* ]]; then
        sed -i "s|^APPSMITH_REDIS_URL=.*|APPSMITH_REDIS_URL=redis://:${generated_appsmith_redis_password}@127.0.0.1:6379|" "$ENV_PATH"
      fi
    fi
  fi

  tlog "Load environment configuration"

  # Load the ones in `docker.env` in the stacks folder.
  load_env_file env "$ENV_PATH" || exit 1

  # Load the ones set from outside, should take precedence, and so will overwrite anything from `docker.env` above.
  load_env_file json "$TMP/pre-define.json" || exit 1
}

init_env_file
setup_proxy_variables

# Fork: the "Instance Start" ping to cs.appsmith.com and Segment is removed. See FORK.md.

if [[ -n "${FILESTORE_IP_ADDRESS-}" ]]; then

  ## Trim APPSMITH_FILESTORE_IP and FILE_SHARE_NAME
  FILESTORE_IP_ADDRESS="$(echo "$FILESTORE_IP_ADDRESS" | xargs)"
  FILE_SHARE_NAME="$(echo "$FILE_SHARE_NAME" | xargs)"

  tlog "Running appsmith for cloudRun"
  tlog "creating mount point"
  mkdir -p "$stacks_path"
  tlog "Mounting File Sytem"
  mount -t nfs -o nolock "$FILESTORE_IP_ADDRESS:/$FILE_SHARE_NAME" /appsmith-stacks
  tlog "Mounted File Sytem"
  tlog "Setting HOSTNAME for Cloudrun"
  export HOSTNAME="cloudrun"
fi


function get_maximum_heap() {
    resource=$(ulimit -u)
    tlog "Resource : $resource"
    if [[ "$resource" -le 256 ]]; then
        maximum_heap=128
    elif [[ "$resource" -le 512 ]]; then
        maximum_heap=256
    fi
}

function setup_backend_heap_arg() {
    if [[ ! -z ${maximum_heap} ]]; then
      export APPSMITH_JAVA_HEAP_ARG="-Xmx${maximum_heap}m"
    fi
}

unset_unused_variables() {
  # Check for enviroment vairalbes
  tlog "Checking environment configuration"
  if [[ -z "${APPSMITH_MAIL_ENABLED}" ]]; then
    unset APPSMITH_MAIL_ENABLED # If this field is empty is might cause application crash
  fi

  if [[ -z "${APPSMITH_OAUTH2_GITHUB_CLIENT_ID}" ]] || [[ -z "${APPSMITH_OAUTH2_GITHUB_CLIENT_SECRET}" ]]; then
    unset APPSMITH_OAUTH2_GITHUB_CLIENT_ID # If this field is empty is might cause application crash
    unset APPSMITH_OAUTH2_GITHUB_CLIENT_SECRET
  fi

  if [[ -z "${APPSMITH_OAUTH2_GOOGLE_CLIENT_ID}" ]] || [[ -z "${APPSMITH_OAUTH2_GOOGLE_CLIENT_SECRET}" ]]; then
    unset APPSMITH_OAUTH2_GOOGLE_CLIENT_ID # If this field is empty is might cause application crash
    unset APPSMITH_OAUTH2_GOOGLE_CLIENT_SECRET
  fi

  if [[ -z "${APPSMITH_RECAPTCHA_SITE_KEY}" ]] || [[ -z "${APPSMITH_RECAPTCHA_SECRET_KEY}" ]] || [[ -z "${APPSMITH_RECAPTCHA_ENABLED}" ]]; then
    unset APPSMITH_RECAPTCHA_SITE_KEY # If this field is empty is might cause application crash
    unset APPSMITH_RECAPTCHA_SECRET_KEY
    unset APPSMITH_RECAPTCHA_ENABLED
  fi
}

# Fork: the server only runs on PostgreSQL. A volume first started by a MongoDB build still has a mongodb:// URL in
# docker.env; stop with what to do rather than let the server fail on it. There is no import of MongoDB data.
configure_database_connection_url() {
  tlog "Configuring database connection URL"
  if [[ "${APPSMITH_DB_URL-}" == "postgresql://"* ]]; then
    return
  fi
  tlog "====================================================================================================" >&2
  tlog "==" >&2
  if [[ "${APPSMITH_DB_URL-}" == "mongodb"* ]]; then
    tlog "== APPSMITH_DB_URL is a MongoDB URL. This image runs only on PostgreSQL and does not import MongoDB data." >&2
  else
    tlog "== APPSMITH_DB_URL is not set or not a postgresql:// URL." >&2
  fi
  tlog "== Start on a fresh volume (the embedded PostgreSQL is then set up), or set APPSMITH_DB_URL to" >&2
  tlog "== postgresql://<user>:<password>@<host>:<port>/<database>, in the container's environment or in" >&2
  tlog "== $ENV_PATH. For the embedded PostgreSQL, use postgresql://appsmith:<password>@localhost:5432/appsmith." >&2
  tlog "==" >&2
  tlog "====================================================================================================" >&2
  exit 1
}

check_db_uri() {
  tlog "Checking APPSMITH_DB_URL"
  isUriLocal=1
  if [[ $APPSMITH_DB_URL == *"localhost"* || $APPSMITH_DB_URL == *"127.0.0.1"* ]]; then
    tlog "Detected local DB"
    isUriLocal=0
  fi
}

is_empty_directory() {
  [[ -d $1 && -z "$(ls -A "$1")" ]]
}

check_setup_custom_ca_certificates() {
  # old, deprecated, should be removed.
  local stacks_ca_certs_path
  stacks_ca_certs_path="$stacks_path/ca-certs"

  local container_ca_certs_path
  container_ca_certs_path="/usr/local/share/ca-certificates"

  if [[ -d $stacks_ca_certs_path ]]; then
    if [[ ! -L $container_ca_certs_path ]]; then
      if is_empty_directory "$container_ca_certs_path"; then
        rmdir -v "$container_ca_certs_path"
      else
        tlog "The 'ca-certificates' directory inside the container is not empty. Please clear it and restart to use certs from 'stacks/ca-certs' directory." >&2
        return
      fi
    fi

    ln --verbose --force --symbolic --no-target-directory "$stacks_ca_certs_path" "$container_ca_certs_path"

  elif [[ ! -e $container_ca_certs_path ]]; then
    rm -vf "$container_ca_certs_path"  # If it exists as a broken symlink, this will be needed.
    mkdir -v "$container_ca_certs_path"

  fi

  update-ca-certificates --fresh
}

setup-custom-ca-certificates() (
  local stacks_ca_certs_path="$stacks_path/ca-certs"
  local store="$TMP/cacerts"
  local opts_file="$TMP/java-cacerts-opts"
  local temp_cert_dir="$TMP/ca-certs-temp"

  rm -f "$store" "$opts_file"
  rm -rf "$temp_cert_dir"
  mkdir -p "$temp_cert_dir"

  if [[ -n "$(ls "$stacks_ca_certs_path"/*.pem 2>/dev/null)" ]]; then
    tlog "Looks like you have some '.pem' files in your 'ca-certs' folder. Please rename them to '.crt' to be picked up automatically.".
  fi

  if ! [[ -d "$stacks_ca_certs_path" && "$(find "$stacks_ca_certs_path" -maxdepth 1 \( -type f -name '*.crt' -o -type l -name '*.crt' \) | wc -l)" -gt 0 ]]; then
    tlog "No custom CA certificates found."
    return
  fi

  # Import the system CA certificates into the store.
  keytool -importkeystore \
    -srckeystore /opt/java/lib/security/cacerts \
    -destkeystore "$store" \
    -srcstorepass changeit \
    -deststorepass changeit

  # Split every .crt file (bundle or single) into individual certs
  cert_index=0
  while read -r cert_file; do
    awk -v prefix="$temp_cert_dir/cert" -v ext=".crt" -v idx="$cert_index" '
      BEGIN {n=0}
      /-----BEGIN CERTIFICATE-----/,/-----END CERTIFICATE-----/ {
        print > (prefix idx "_" n ext)
        if (/-----END CERTIFICATE-----/) n++
      }
    ' "$cert_file"
    cert_index=$((cert_index + 1))
  done < <(find -L "$stacks_ca_certs_path" -maxdepth 1 -type f -o -type l -name '*.crt')

  # Import all certificates from the temp directory
  find "$temp_cert_dir" -type f -name '*.crt' | while read -r cert_file; do
    keytool -import -alias "$(basename "$cert_file")" -noprompt -keystore "$store" -file "$cert_file" -storepass changeit
  done

  {
    echo "-Djavax.net.ssl.trustStore=$store"
    echo "-Djavax.net.ssl.trustStorePassword=changeit"
  } > "$opts_file"

  # Cleanup
  rm -rf "$temp_cert_dir"
)

configure_supervisord() {
  local supervisord_conf_source="/opt/appsmith/templates/supervisord"
  if [[ -n "$(ls -A "$SUPERVISORD_CONF_TARGET")" ]]; then
    rm -f "$SUPERVISORD_CONF_TARGET"/*
  fi

  cp -f "$supervisord_conf_source"/application_process/*.conf "$SUPERVISORD_CONF_TARGET"

  # Disable services based on configuration
  if [[ -z "${DYNO}" ]]; then
    if [[ $APPSMITH_REDIS_URL == *"localhost"* || $APPSMITH_REDIS_URL == *"127.0.0.1"* ]]; then
      cp "$supervisord_conf_source/redis.conf" "$SUPERVISORD_CONF_TARGET"
      mkdir -p "$stacks_path/data/redis"
      # Write Redis server config so the password is not visible in the process list.
      # Placed in $TMP so it is regenerated each startup.
      cat > "$TMP/redis.conf" <<REDIS_CONF
save 15 1
dir /appsmith-stacks/data/redis
daemonize no
logfile ""
bind 127.0.0.1 -::1
requirepass ${APPSMITH_REDIS_PASSWORD:-}
REDIS_CONF
      chmod 600 "$TMP/redis.conf"
    fi
    if [[ $runEmbeddedPostgres -eq 1 ]]; then
      cp "$supervisord_conf_source/postgres.conf" "$SUPERVISORD_CONF_TARGET"
    fi
  fi

}

# This is a workaround to get Redis working on different memory pagesize
# https://github.com/appsmithorg/appsmith/issues/11773
check_redis_compatible_page_size() {
  local page_size
  page_size="$(getconf PAGE_SIZE)"
  if [[ $page_size -gt 4096 ]]; then
    tlog "Compile Redis stable with page size of $page_size"
    apt-get update
    apt-get install --yes build-essential
    curl --connect-timeout 5 --location https://download.redis.io/redis-stable.tar.gz | tar -xz -C /tmp
    pushd /tmp/redis-stable
    make
    make install
    popd
    rm -rf /tmp/redis-stable
  else
    tlog "Redis is compatible with page size of $page_size"
  fi
}

init_postgres() {
  # Fork: the embedded PostgreSQL is the internal database, so it runs when APPSMITH_DB_URL points at this container
  # (the default docker.env), and not with an external database. APPSMITH_ENABLE_EMBEDDED_DB=0 still turns it off.
  if [[ $isUriLocal -eq 0 && ${APPSMITH_ENABLE_EMBEDDED_DB: -1} != 0 ]]; then
    if [[ "$(id -u)" != "0" ]]; then
      tlog "== When running as a non-root user embedded PostgreSQL cannot be used. Please use an external PostgreSQL instance instead." >&2
      exit 1
    fi

    tlog "Checking initialized local postgres"
    POSTGRES_DB_PATH="$stacks_path/data/postgres/main"

    mkdir -p "$POSTGRES_DB_PATH" "$TMP/pg-runtime"

    # Postgres does not allow it's server to be run with super user access, we use user postgres and the file system owner also needs to be the same user postgres
    chown -R postgres:postgres "$POSTGRES_DB_PATH" "$TMP/pg-runtime"

    if [[ -e "$POSTGRES_DB_PATH/PG_VERSION" ]]; then
      /opt/appsmith/pg-upgrade.sh
    else
      tlog "Initializing local Postgres data folder"
      su postgres -c "env PATH='$PATH' initdb -D $POSTGRES_DB_PATH"
    fi
    cp /opt/appsmith/postgres/appsmith_hba.conf "$POSTGRES_DB_PATH/pg_hba.conf"
    # PostgreSQL requires strict file permissions for the pg_hba.conf file. Add file permission settings after copying the configuration file.
    # 600 is the recommended permission for pg_hba.conf file for read and write access to the owner only.
    chown postgres:postgres "$POSTGRES_DB_PATH/pg_hba.conf"
    chmod 600 "$POSTGRES_DB_PATH/pg_hba.conf"

    create_appsmith_pg_db "$POSTGRES_DB_PATH"
  else
    runEmbeddedPostgres=0
  fi

}

safe_init_postgres() {
  runEmbeddedPostgres=1
  # Fork: upstream carried on without the embedded PostgreSQL (it only held sample data). It is the internal database
  # now, so the container stops instead of starting a server that cannot reach its database.
  if init_postgres; then
    tlog "init_postgres succeeded."
  else
    local exit_status=$?
    tlog "init_postgres failed with exit status $exit_status; the embedded PostgreSQL cannot start." >&2
    exit 1
  fi
}

# Method to create a appsmith database in the postgres 
# Args:
#     POSTGRES_DB_PATH (string): Path to the postgres data directory
# Returns:
#     None
# Example:
#     create_appsmith_pg_db "/appsmith-stacks/data/postgres/main"
create_appsmith_pg_db() {
  local -r pg_database=appsmith
  local POSTGRES_DB_PATH=$1
  local db_exists database_status=0
  # Start the postgres , wait for it to be ready and create a appsmith db
  su postgres -c "env PATH='$PATH' pg_ctl -D $POSTGRES_DB_PATH -l $POSTGRES_DB_PATH/logfile start"
  echo "Waiting for Postgres to start"
  local max_attempts=300
  local attempt=0

  local unix_socket_directory
  unix_socket_directory=$(get_unix_socket_directory "$POSTGRES_DB_PATH")
  echo "Unix socket directory is $unix_socket_directory"
  until su postgres -c "env PATH='$PATH' pg_isready -h $unix_socket_directory"; do
    if (( attempt >= max_attempts )); then
      echo "Postgres failed to start within 300 seconds."
      return 1
    fi
    tlog "Waiting for Postgres to be ready... Attempt $((++attempt))/$max_attempts"
    sleep 1
  done
  # Check if the appsmith DB is present
  # Forward arguments through a fixed shell command; psql quotes the SQL literal.
  db_exists=$(su postgres -s /bin/sh -c 'exec "$@"' -- postgres \
    env "PATH=$PATH" psql -X -v ON_ERROR_STOP=1 -v "pg_database=$pg_database" -tA <<'SQL'
SELECT 1 FROM pg_database WHERE datname=:'pg_database';
SQL
  ) || database_status=$?

  if [[ $database_status == 0 ]]; then
    if [[ "$db_exists" != "1" ]]; then
      # createdb quotes database identifiers, including embedded punctuation.
      su postgres -s /bin/sh -c 'exec "$@"' -- postgres \
        env "PATH=$PATH" createdb -- "$pg_database" || database_status=$?
    else
      echo "Database $pg_database already exists."
    fi
  fi
  su postgres -c "env PATH='$PATH' pg_ctl -D $POSTGRES_DB_PATH stop" || return
  return "$database_status"
}

setup_caddy() {
  export _APPSMITH_CADDY="/opt/caddy/caddy"
}

init_loading_pages(){
  export XDG_DATA_HOME=/appsmith-stacks/data  # so that caddy saves tls certs and other data under stacks/data/caddy
  export XDG_CONFIG_HOME=/appsmith-stacks/configuration
  mkdir -p "$XDG_DATA_HOME" "$XDG_CONFIG_HOME"
  cp templates/loading.html "$WWW_PATH"
  node caddy-reconfigure.mjs
  "$_APPSMITH_CADDY" start --config "$TMP/Caddyfile"
}

function setup_auto_heal(){
   if [[ ${APPSMITH_AUTO_HEAL-} = 1 ]]; then
     # By default APPSMITH_AUTO_HEAL=0
     # To enable auto heal set APPSMITH_AUTO_HEAL=1
     bash /opt/appsmith/auto_heal.sh $APPSMITH_AUTO_HEAL_CURL_TIMEOUT >> "$APPSMITH_LOG_DIR"/cron/auto_heal.log 2>&1 &
   fi
}

function setup_monitoring(){
   if [[ ${APPSMITH_MONITORING-} = 1 ]]; then
     # By default APPSMITH_MONITORING=0
     # To enable auto heal set APPSMITH_MONITORING=1
     bash /opt/appsmith/JFR-recording-24-hours.sh $APPSMITH_LOG_DIR 2>&1 &
   fi
}

print_appsmith_info(){
  tr '\n' ' ' < /opt/appsmith/info.json
}

function capture_infra_details(){
  bash /opt/appsmith/generate-infra-details.sh || true
}

# Main Section
print_appsmith_info
setup_caddy
init_loading_pages
unset_unused_variables

configure_database_connection_url
check_db_uri
if [[ -n "${DYNO}" ]]; then
  # These functions are used to limit heap size for Backend process when deployed on Heroku
  get_maximum_heap
  setup_backend_heap_arg
  # set the hostname for heroku dyno
  export HOSTNAME="heroku_dyno"
fi

check_setup_custom_ca_certificates
setup-custom-ca-certificates

check_redis_compatible_page_size

safe_init_postgres

configure_supervisord

# Ensure the restore path exists in the container, so an archive can be copied to it, if need be.
mkdir -p /appsmith-stacks/data/{backup,restore} /appsmith-stacks/ssl

# Create sub-directory to store services log in the container mounting folder
export APPSMITH_LOG_DIR="${APPSMITH_LOG_DIR:-/appsmith-stacks/logs}"
mkdir -p "$APPSMITH_LOG_DIR"/{supervisor,backend,mcp,cron,editor,rts,redis,postgres,appsmithctl}

setup_auto_heal
capture_infra_details
setup_monitoring || echo true

# Handle CMD command
exec "$@"
