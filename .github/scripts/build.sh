#!/bin/bash
set -e
echo "Make"
make SKIP_TESTS=true

echo "Build"
make docker_build

if [[ -v RELEASE ]]
then
    echo "Logging in to Docker Hub"
    docker login -u ${REGISTRY_USER} -p ${REGISTRY_PASS} ${DOCKER_REGISTRY} 
fi

echo "Push to registry"
make -j 4 docker_tag docker_push

echo "Generate templates"
make templates