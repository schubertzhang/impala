#!/usr/bin/env bash
#
#/**
# * Copyright 2007 The Apache Software Foundation
# *
# * Licensed to the Apache Software Foundation (ASF) under one
# * or more contributor license agreements.  See the NOTICE file
# * distributed with this work for additional information
# * regarding copyright ownership.  The ASF licenses this file
# * to you under the Apache License, Version 2.0 (the
# * "License"); you may not use this file except in compliance
# * with the License.  You may obtain a copy of the License at
# *
# *     http://www.apache.org/licenses/LICENSE-2.0
# *
# * Unless required by applicable law or agreed to in writing, software
# * distributed under the License is distributed on an "AS IS" BASIS,
# * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# * See the License for the specific language governing permissions and
# * limitations under the License.
# */
# 
# Run a impala command on all slave hosts.
# Modelled after $HADOOP_HOME/bin/hadoop-daemons.sh

usage="Usage: impala-daemons.sh  \
 [--hosts backends] [start|stop] command args..."

# if no args specified, show usage
if [ $# -le 1 ]; then
  echo $usage
  exit 1
fi

bin=`dirname "${BASH_SOURCE-$0}"`
bin=`cd "$bin">/dev/null; pwd`

. $bin/impala-config.sh

remote_cmd="cd ${IMPALA_HOME}; $bin/impala-daemon.sh $@"
args="$remote_cmd"

command=$2
case $command in
  (impalaserver)
    exec "$bin/impala-backends.sh" $args
    ;;
  (*)
    echo "unrecognized  command $command"
    ;;
esac


