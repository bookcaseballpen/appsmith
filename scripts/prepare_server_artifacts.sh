#!/bin/bash -eux

cd "$(git rev-parse --show-toplevel)"

if [[ -z "${EDITION-}" ]]; then
  export EDITION=ce
  if [[ "$(git remote get-url origin)" == *appsmithorg/appsmith-ee* ]]; then
    export EDITION=ee
  fi
fi

# Fork: the server only runs on PostgreSQL, so its artifacts go in server/pg, where run-java.sh runs them from.
echo "Building server artifacts for $EDITION edition (PostgreSQL)"

target="deploy/docker/fs/opt/appsmith/server"
mkdir -p "$target"
rm -rf "$target"/{pg,mongo}

cp -r "app/server/dist" "$target/pg"
mv "$target/pg"/server-*.jar "$target/pg/server.jar"

echo "Server artifacts: $target/pg/"
