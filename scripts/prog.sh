#!/bin/bash

mvn -f bigdata-jar/pom.xml clean package

if [ -z "$JAVA_OPTS" ] ; then
	JAVA_OPTS="-ea -Xmx4g -server"
fi

if [ -z "$JAVA_HOME" ] ; then
	JAVA="${JAVA_HOME}/bin/java"
else
	JAVA=`which java`
fi

#$JAVA ${JAVA_OPTS} -jar bigdata-jar/target/bigdata-jar*.jar

echo "Starting with JAVA_OPTS: $JAVA_OPTS."

"$JAVA" ${JAVA_OPTS} -cp bigdata-jar/target/bigdata-jar*.jar $*

