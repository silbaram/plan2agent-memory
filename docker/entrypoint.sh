#!/bin/sh
set -eu

model_directory=/opt/p2a/model

if [ "${DJL_OFFLINE:-}" != "true" ]; then
    echo "DJL_OFFLINE must be true" >&2
    exit 1
fi

if [ "${P2A_EMBEDDING_PROVIDER:-none}" = "transformers" ]; then
    if [ "${P2A_EMBEDDING_MODEL_ARTIFACT_URI:-}" != "file:///opt/p2a/model/model.onnx" ] || \
        [ "${P2A_EMBEDDING_TOKENIZER_ARTIFACT_URI:-}" != "file:///opt/p2a/model/tokenizer.json" ]; then
        echo "Transformers artifacts must use the container model mount" >&2
        exit 1
    fi

    if [ ! -r "$model_directory/model.onnx" ] || [ ! -r "$model_directory/tokenizer.json" ]; then
        echo "Required Transformers artifacts are unavailable" >&2
        exit 1
    fi

    if ! awk '$5 == "/opt/p2a/model" && $6 ~ /(^|,)ro(,|$)/ { found = 1 } END { exit !found }' /proc/self/mountinfo; then
        echo "Transformers artifacts must be mounted read-only" >&2
        exit 1
    fi
fi

exec java -jar /opt/p2a/app.jar "$@"
