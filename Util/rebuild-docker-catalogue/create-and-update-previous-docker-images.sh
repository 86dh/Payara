#!/bin/bash

### VARIABLES ###

SCRIPT=$(realpath $0)
SCRIPTPATH=$(dirname $SCRIPT)

REPO_DIR="$SCRIPTPATH/payara-enterprise"

#Change to HTTPS if running locally
ENTERPRISE_REMOTE="git@github.com:payara/payara-enterprise.git"

VERSION_REGEX="(5|4).(1.2.191|\d{2}).(\d+)"

#cd into script location
cd $SCRIPTPATH

### VALIDATIONS ###

if [ ! -f payara.versions ]; then
    echo "payara.versions file is missing!"
    exit -1
fi

#Should return 0. All payara versions should match the regex pattern.
invalidVersions=$(grep -P -x -v -c "$VERSION_REGEX" payara.versions)
if [ ! ${invalidVersions} == 0 ]; then
    echo "There is one or more invalid versions in payara.versions"
    #Return invalid versions
    echo $(grep -P -v "$VERSION_REGEX" payara.versions)
    exit -1
fi

if [ ! -d payara-enterprise ]; then
    echo "Source code directory is not where expected or does not exist."
    git clone $ENTERPRISE_REMOTE
fi

### SETUP ###

#Map payara versions to a local array
mapfile -t versionArray < payara.versions

#Sort array
sortedVersionArray=( $( printf "%s\n" "${versionArray[@]}" | sort -nr ) )

cd ${REPO_DIR}

#Payara 5+ will be processed first in the sorted list
git checkout master

#bool value to track if the payara4 maintenance branch is checked out.
#1 is true, 0 is false.
onPayara4=0

### BUILD, TAG, TEST, PUSH ###

#For loop for every version in version array
for i in "${sortedVersionArray[@]}"
do
    cd ${REPO_DIR}

    #Check out maintenance if payara4
    if [[ ${i:0:1} -eq 4 && onPayara4 -ne 1 ]]; then
        git checkout payara-server-4.1.2.191.maintenance
        if [ $(git branch --show-current) != "payara-server-4.1.2.191.maintenance" ]; then
            echo "Could not checkout payara 4 maintenance branch"
            exit -1
        fi
        #If branch was checked out successfully, set onPayara4 to 1.
        onPayara4=1
    fi

    docker system prune --all
    mvn clean install -PBuildDockerImages -Dpayara.version=$i -pl :docker-images -amd -U
    if [[ $? -ne 0 ]] ; then
        echo "Maven build returned non zero code!"
        exit -1
    fi
    
    cd $SCRIPTPATH
    ../docker-tag.sh ${i}
    
    ../docker-test.sh ${i}
    if [[ $? -ne 0 ]] ; then
    	echo "Test script did not pass! There are failures."
    	exit -1
    fi
    
    echo "---YOU ARE ABOUT TO PUSH TAGGED IMAGES TO PUBLIC DOCKER REPOSITORIES---"
    read -r -p "ARE YOU SURE? [Y/n]" input
    input=${input,,} #Lower case
    if [[ $input =~ ^(yes|y)$ ]]; then
        . ../docker-push ${i}
    fi
done