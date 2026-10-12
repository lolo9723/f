"""AWS Lambda entrypoints. SQS retry + DLQ replay; API never runs training inline."""

import base64
import json
import logging
import os
from .api import API
from .config import Config, PERIOD
from .market import Market
from .pipeline import run_pair
from .storage import CloudStore, clock_ms

log = logging.getLogger(__name__)
log.setLevel(logging.INFO)


def enqueue(pair, job, force=False):
    import boto3

    return boto3.client("sqs").send_message(
        QueueUrl=os.environ["WORK_QUEUE"],
        MessageBody=json.dumps({"pair": pair, "job": job, "force_train": force}),
    )


def scheduled(event, context):
    import boto3

    cfg, now = Config(), clock_ms()
    # EventBridge remains independent of phones and browser activity.
    for pair in cfg.pairs:
        enqueue(pair, f"scheduled:{pair}:{now // PERIOD}")
    # Recover failed jobs as well as scheduling new work; acknowledge only after durable enqueue.
    sqs = boto3.client("sqs")
    messages = sqs.receive_message(
        QueueUrl=os.environ["DEAD_QUEUE"], MaxNumberOfMessages=10, VisibilityTimeout=60
    ).get("Messages", [])
    for message in messages:
        body = json.loads(message["Body"])
        if body.get("pair") not in cfg.pairs:
            log.error("Unexpected DLQ pair; message retained")
            continue
        enqueue(body["pair"], body["job"], body.get("force_train", False))
        sqs.delete_message(
            QueueUrl=os.environ["DEAD_QUEUE"], ReceiptHandle=message["ReceiptHandle"]
        )
    CloudStore().heartbeat(now)
    return {"queued": len(cfg.pairs), "replayed": len(messages)}


def worker(event, context):
    store, cfg = CloudStore(), Config()
    failed = []
    for record in event.get("Records", []):
        try:
            body = json.loads(record["body"])
            if body["pair"] not in cfg.pairs:
                raise ValueError("Unconfigured pair")
            meta = store.metadata(body["pair"])
            if meta.get("job") == body["job"] and meta.get("stage") == "COMPLETED":
                continue
            result = run_pair(
                store,
                Market(),
                cfg,
                body["pair"],
                body["job"],
                force_train=body.get("force_train", False),
            )
            if result["status"] == "BUSY":
                failed.append({"itemIdentifier": record["messageId"]})
            log.info("pipeline %s", json.dumps(result))
        except Exception:
            log.exception("Pipeline failed; SQS will retry")
            failed.append({"itemIdentifier": record["messageId"]})
    return {"batchItemFailures": failed}


def http(event, context):
    cfg = Config()
    # Never deploy an accidentally public write API through missing configuration.
    if not cfg.token:
        return response(503, {"error": "API authentication not configured"})
    body = event.get("body") or ""
    if event.get("isBase64Encoded"):
        body = base64.b64decode(body).decode()
    method = event.get("requestContext", {}).get("http", {}).get("method", "GET")
    try:
        status, payload = API(CloudStore(), cfg, enqueue).handle(
            method,
            event.get("rawPath", "/"),
            event.get("rawQueryString", ""),
            event.get("headers", {}),
            body,
            clock_ms(),
        )
    except (ValueError, KeyError, TypeError):
        status, payload = 400, {"error": "Invalid request"}
    except Exception:
        log.exception("API failure")
        status, payload = (
            503,
            {"error": "Backend temporarily unavailable; last local snapshot retained"},
        )
    return response(status, payload)


def response(status, payload):
    return {
        "statusCode": status,
        "headers": {
            "Content-Type": "application/json; charset=utf-8",
            "Cache-Control": "no-store",
        },
        "body": json.dumps(payload, allow_nan=False, ensure_ascii=False),
    }
