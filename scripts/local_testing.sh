#!/usr/bin/env bash
set -o errexit

# Fork: builds the current working tree through scripts/fork-build/build-image.sh (everything is compiled inside
# Docker, on a base image built locally), so neither an Appsmith base image nor a cloud-services URL is involved.
# Upstream's branch and remote modes (git fetch, checkout and pull) are removed: the script never pulls code.
# See FORK.md.

container=creative-app-builder-local

display_help()
{
  echo "---------------------------------------------------------------------------------------"
  echo "Use this script to run a local instance on http://127.0.0.1:<port> (default 80)."
  echo "It builds the image from the current working tree, including uncommitted changes,"
  echo "and runs it as the container '$container' with data in ./stacks."
  echo "It does not fetch, check out or pull any branch; switch branches yourself first."
  echo "---------------------------------------------------------------------------------------"
  echo
  echo "Syntax: $0 [-h] [tag] [port]"
  echo "options:"
  echo "-h or --help    Print this help"
  echo "tag             Image tag, creative-app-builder:<tag> (default latest)"
  echo "port            Port on 127.0.0.1 (default 80)"
  echo
}

pretty_print()
{
  echo "----------------"
  echo $1
  echo "----------------"
  echo
}

if [[ $1 == "--help" || $1 == "-h" ]]
then
  display_help
  exit 0
fi

# Upstream's -l/--local is what the script always does now; accept it so old habits still work.
if [[ $1 == "--local" || $1 == "-l" ]]
then
  shift
fi

if [[ $1 == "--remote" || $1 == "-r" || $1 == -* ]]
then
  echo "Unknown option: $1 (remote and branch modes are removed in the fork)" >&2
  display_help >&2
  exit 1
fi

tag="${1:-latest}"
port="${2:-80}"
image="creative-app-builder:$tag"
pretty_print "Building $image from the working tree ..."

if ! "$(dirname "$0")/fork-build/build-image.sh" "$image"; then
  echo Docker image build failed >&2
  exit 1
fi
pretty_print "Docker image build successful. Triggering run now ..."

(docker stop "$container" || true) && (docker rm "$container" || true)
docker run -d --name "$container" -p "127.0.0.1:$port:80" -v "$PWD/stacks:/appsmith-stacks" \
  -e "APPSMITH_BASE_URL=http://localhost:$port" "$image" \
  && pretty_print "Started. Open http://localhost:$port once /api/v1/health answers 200 (about two minutes)."
