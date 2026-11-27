"""Run from any directory. Generate using ONLY ds03-api-contracts-v1."""
import os
from pathlib import Path

import grpc_tools
from grpc_tools import protoc

root = Path(__file__).resolve().parents[1]
contracts_env = os.environ.get("CONTRACTS_DIR")
contracts = Path(contracts_env) if contracts_env else next(
    (parent / "ds03-api-contracts-v1" for parent in root.parents
     if (parent / "ds03-api-contracts-v1").is_dir()),
    Path("/contracts"),
)
output = root / "generated"
output.mkdir(exist_ok=True)
proto = contracts / "proto"
includes = Path(grpc_tools.__file__).parent / "_proto"
files = ["ds03/common/v1/common.proto", "ds03/driver/v1/driver.proto"]
result = protoc.main(["protoc", f"-I{proto}", f"-I{includes}",
    f"--python_out={output}", f"--grpc_python_out={output}", *[str(proto / p) for p in files]])
if result:
    raise SystemExit(result)
print(f"Generated Driver client into {output}")
