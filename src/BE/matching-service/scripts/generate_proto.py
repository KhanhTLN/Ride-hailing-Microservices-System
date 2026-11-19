"""Run from any directory with the matching-service dev environment installed."""

from pathlib import Path
from shutil import copyfile

import grpc_tools
from grpc_tools import protoc

service = Path(__file__).resolve().parents[1]
repo = service.parents[2]
source = repo / "ds03-api-contracts-v1" / "proto"
output = service / "src"
files = [source / "ds03/common/v1/common.proto", source / "ds03/driver/v1/driver.proto"]
args = [
    "protoc",
    f"-I{source}",
    f"-I{Path(grpc_tools.__file__).parent / '_proto'}",
    f"--python_out={output}",
    f"--grpc_python_out={output}",
    *map(str, files),
]
if protoc.main(args):
    raise SystemExit("Protobuf generation failed")
resources = output / "matching_service/resources"
(resources / "schemas").mkdir(parents=True, exist_ok=True)
copyfile(repo / "scripts/sql/matchingdb_init.sql", resources / "schema.sql")
for name in ("TripRequested", "MatchingCancelled", "DriverReserved", "MatchingFailed", "DeadLetter"):
    copyfile(
        repo / f"ds03-api-contracts-v1/kafka/schemas/{name}.schema.json",
        resources / f"schemas/{name}.schema.json",
    )
print("Generated Python gRPC code and synchronized shared schemas/SQL")
