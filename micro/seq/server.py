
from os import environ
from pathlib import Path
from dataclasses import dataclass
from traceback import print_exc
from boto3 import client
from botocore.client import BaseClient
from botocore.exceptions import ClientError
from fastapi import FastAPI
import uvicorn

@dataclass
class SeqRecord:
    value: int
    etag: str

@dataclass
class SeqChangeResult:
    ok: bool

@dataclass
class SeqService:
    s3: BaseClient
    bucket: str
    obj_key: str

    def get(self) -> SeqRecord:
        r = self.s3.get_object(Bucket=self.bucket, Key=self.obj_key)
        return SeqRecord(value=int(r["Body"].read()), etag=r["ETag"].strip('"'))

    def put(self, body: SeqRecord) -> SeqChangeResult:
        try:
            data = str(body.value).encode()
            self.s3.put_object(
                Bucket=self.bucket, Key=self.obj_key, Body=data, ContentType="text/plain", IfMatch=body.etag
            )
            return SeqChangeResult(ok=True)
        except ClientError as e:
            print_exc()
            return SeqChangeResult(ok=False)

def main():
    s3conf = lambda k: (Path(environ["C4S3_CONF_DIR"]) / k).read_bytes().decode().strip()
    s3address = s3conf("address")
    s3key = s3conf("key")
    s3secret = s3conf("secret")
    s3 = client("s3", endpoint_url=s3address, aws_access_key_id=s3key, aws_secret_access_key=s3secret)
    # later operations with s3 seems to be thread safe
    seq_service = SeqService(s3 = s3, bucket = environ["C4S3_SEQ_BUCKET"], obj_key = environ["C4S3_SEQ_OBJECT"])
    app = FastAPI()
    app.add_api_route("/c4/seq", seq_service.get, methods=["GET"])
    app.add_api_route("/c4/seq", seq_service.put, methods=["PUT"])
    uvicorn.run(app, host="0.0.0.0", port=int(environ["C4HTTP_PORT"]))

if __name__ == "__main__": main()
