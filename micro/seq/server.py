
from os import environ
from pathlib import Path
from dataclasses import dataclass
from traceback import print_exc
from json import loads

from boto3 import client
from botocore.client import BaseClient

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse
from pydantic import TypeAdapter
import uvicorn
import asyncio

@dataclass
class SeqRecord:
    value: int
    etag: str

@dataclass
class SeqService:
    s3: BaseClient
    bucket: str
    obj_key: str

    def get(self) -> SeqRecord:
        r = self.s3.get_object(Bucket=self.bucket, Key=self.obj_key)
        return SeqRecord(value=int(r["Body"].read()), etag=r["ETag"].strip('"'))

    def put(self, body: SeqRecord) -> None:
        data = str(body.value).encode()
        self.s3.put_object(Bucket=self.bucket, Key=self.obj_key, Body=data, ContentType="text/plain", IfMatch=body.etag)

def add_seq_routes(app, path, seq_service, seq_record_adapter):
    app.add_api_route(path, seq_service.get, methods=["GET"])
    async def put(request: Request) -> JSONResponse:
        """ returning json with "ok" is required by client """
        try:
            rec = seq_record_adapter.validate_python(loads(await request.body()))
            await asyncio.get_running_loop().run_in_executor(None, seq_service.put, rec)
            return JSONResponse({"ok": True})
        except Exception as e:
            print_exc()
            return JSONResponse({"ok": False})
    app.add_api_route(path, put, methods=["PUT"])


def main():
    s3conf = lambda k: (Path(environ["C4S3_CONF_DIR"]) / k).read_bytes().decode().strip()
    s3address = s3conf("address")
    s3key = s3conf("key")
    s3secret = s3conf("secret")
    s3 = client("s3", endpoint_url=s3address, aws_access_key_id=s3key, aws_secret_access_key=s3secret)
    # later operations with s3 seems to be thread safe
    app = FastAPI()
    seq_record_adapter = TypeAdapter(SeqRecord)
    for path, bucket_obj_key in loads(environ["C4S3_SEQS"]).items():
        bucket, obj_key = bucket_obj_key.split("/")
        seq_service = SeqService(s3 = s3, bucket = bucket, obj_key = obj_key)
        add_seq_routes(app=app, path=path, seq_service=seq_service, seq_record_adapter=seq_record_adapter)
    uvicorn.run(app, host="0.0.0.0", port=int(environ["C4HTTP_PORT"]))

if __name__ == "__main__": main()
