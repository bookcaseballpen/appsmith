# Fork: the image the fork builds and deploys comes from deploy/fork/Dockerfile (scripts/fork-build/build-image.sh),
# not from this file. Its final stages (the fs stage and the runtime stage) repeat this file's steps; keep the two in
# sync when this file changes, e.g. after a cherry-pick. See FORK.md "Build and deploy".
ARG BASE
FROM ${BASE}

# Fork: no cloud-services URL, Segment, BetterBugs or Pylon keys are baked into the image. See FORK.md.

COPY deploy/docker/fs /

RUN <<END
  if ! [ -f info.json ]; then
    echo "Missing info.json" >&2
    exit 1
  fi

  if ! [ -f server/mongo/server.jar ]; then
    echo "Missing MongoDB server.jar file. Are you using the build script?" >&2
    exit 1
  fi
END

# Add client UI - Application Layer
COPY ./app/client/build editor/

# Add RTS - Application Layer
COPY ./app/client/packages/rts/dist rts/

# Add MCP - Application Layer
COPY ./app/client/packages/mcp/dist mcp/

# Create the git-storage directory with group writeable permissions so non-root users can write to it.
RUN mkdir --mode 775 "/dev/shm/git-storage"

ENV PATH /opt/bin:/opt/java/bin:/opt/node/bin:$PATH

RUN <<END
  set -o errexit

  # Make all `*.sh` files executable, excluding `node_modules`.
  find . \( -name node_modules -prune \) -o \( -type f -name '*.sh' \) -exec chmod +x '{}' +

  # Ensure all custom command-scripts have executable permission
  chmod +x /opt/bin/* /watchtower-hooks/*.sh

  # Disable setuid/setgid bits for the files inside container.
  find / \( -path /proc -prune \) -o \( \( -perm -2000 -o -perm -4000 \) -exec chmod -s '{}' + \) || true

  mkdir -p /.mongodb/mongosh /appsmith-stacks
  chmod ugo+w /etc /appsmith-stacks
  chmod -R ugo+w /var/run /.mongodb /etc/ssl /usr/local/share
END

LABEL com.centurylinklabs.watchtower.lifecycle.pre-check=/watchtower-hooks/pre-check.sh
LABEL com.centurylinklabs.watchtower.lifecycle.pre-update=/watchtower-hooks/pre-update.sh

EXPOSE 80
EXPOSE 443
ENTRYPOINT [ "/opt/appsmith/entrypoint.sh" ]
HEALTHCHECK --interval=15s --timeout=15s --start-period=45s CMD "/opt/appsmith/healthcheck.sh"
CMD ["/usr/bin/supervisord", "-n"]
