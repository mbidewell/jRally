#!/bin/sh

cp=`find ext-lib local-lib -name \*.jar | tr '\n' ':'`
cp=$cp:build/classes/:configs/main  

java -cp "$cp" standup.application.RetrieveStoriesByID "$1"

