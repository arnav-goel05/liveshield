#!/bin/bash

wait_for_relay_publication() {
    local relay_log=$1
    local publisher_pid=$2
    local attempts=$3
    local interval_seconds=$4
    local attempt
    for ((attempt = 0; attempt < attempts; attempt++)); do
        if grep -Fq "is publishing to path 'liveshield'" "$relay_log" 2>/dev/null; then
            return 0
        fi
        if ! kill -0 "$publisher_pid" 2>/dev/null; then
            return 2
        fi
        sleep "$interval_seconds"
    done
    return 1
}
