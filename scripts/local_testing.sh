#!/usr/bin/env bash
set -o errexit

# Fork: builds through scripts/fork-build/build-image.sh (everything is compiled inside Docker, on a base image
# built locally), so neither an Appsmith base image nor a cloud-services URL is involved. See FORK.md.

display_help()
{
  echo "---------------------------------------------------------------------------------------"
  echo "Use this script to run a local instance on http://127.0.0.1:80."
  echo "The script will build all the artefacts required for a fat Docker container to come up."
  echo "If no argument is given, the build defaults to release branch."
  echo "If --local or -l is passed, it will build with local changes"
  echo "---------------------------------------------------------------------------------------"
  echo
  echo "Syntax: $0 [-h] [-l] [-r [remote_url]] [branch_name] [tag]"
  echo "options:"
  echo "-h     			Print this help"
  echo "-l or --local    	Use the local codebase and not git"
  echo "-r or --remote    	Use the branch from a remote repository"
  echo
}

pretty_print()
{
  echo "----------------"
  echo $1
  echo "----------------"
  echo
}

# Check whether user had supplied -h or --help. If yes display usage
if [[ ( $@ == "--help") ||  $@ == "-h" ]]
then
  display_help
  exit 0
fi

LOCAL=false
if [[ ($1 == "--local" || $1 == "-l")]]
then
  LOCAL=true
fi

REMOTE=false
if [[ ($1 == "--remote" || $1 == "-r")]]
then
  REMOTE=true
fi

if [[ ($LOCAL == true) ]]
then
  pretty_print "Setting up instance with local changes"
  BRANCH=release
  tag=$2
elif [[ ($REMOTE == true) ]]
then
  pretty_print "Setting up instance with remote repository branch ..."
  REMOTE_REPOSITORY_URL=$2
  REMOTE_BRANCH=$3
  tag=$4
  pretty_print "Please ignore if the following error occurs: remote remote_origin_for_local_test already exists."
  git remote add remote_origin_for_local_test $REMOTE_REPOSITORY_URL || git remote set-url remote_origin_for_local_test $REMOTE_REPOSITORY_URL
  git fetch remote_origin_for_local_test
  git checkout $REMOTE_BRANCH
  git pull remote_origin_for_local_test $REMOTE_BRANCH
else
  BRANCH=$1
  tag=$2
  pretty_print "Setting up instance to run on branch: $BRANCH"
  cd "$(dirname "$0")"/..
  git fetch origin $BRANCH
  git checkout $BRANCH
  git pull origin $BRANCH
  pretty_print "Local branch is now up to date. Starting build ..."
fi

if [[ -z "$tag" ]]; then
  tag=latest
fi
image="creative-app-builder:$tag"
pretty_print "Building $image ..."

if ! "$(dirname "$0")/fork-build/build-image.sh" "$image"; then
  echo Docker image build failed >&2
  exit 1
fi
pretty_print "Docker image build successful. Triggering run now ..."

(docker stop appsmith || true) && (docker rm appsmith || true)
docker run -d --name appsmith -p 127.0.0.1:80:80 -v "$PWD/stacks:/appsmith-stacks" -e APPSMITH_BASE_URL=http://localhost "$image" && sleep 15 && pretty_print "Local instance is up! Open it at http://localhost! "
