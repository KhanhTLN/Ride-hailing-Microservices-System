"""Validate contract structure, references, examples and compile all protobuf files."""
from pathlib import Path
import json, tempfile, copy
import yaml, jsonschema, grpc_tools
from grpc_tools import protoc
from openapi_spec_validator import validate as validate_openapi

ROOT=Path(__file__).resolve().parents[1]
def refs(value,root):
 if isinstance(value,dict):
  if '$ref' in value:
   r=value['$ref']; assert r.startswith('#/'),r
   target=root
   for part in r[2:].split('/'): target=target[part.replace('~1','/').replace('~0','~')]
  for v in value.values():refs(v,root)
 elif isinstance(value,list):
  for v in value:refs(v,root)

count=0
for p in sorted((ROOT/'rest').glob('*.yaml')):
 d=yaml.safe_load(p.read_text());validate_openapi(d);refs(d,d)
 ids=[o['operationId'] for methods in d['paths'].values() for o in methods.values()]
 assert len(ids)==len(set(ids)),p
 count+=1
print(f'PASS: {count} OpenAPI documents with resolved local references')

count=0
for p in sorted((ROOT/'kafka/schemas').glob('*.schema.json')):
 s=json.loads(p.read_text());jsonschema.Draft7Validator.check_schema(s)
 example=ROOT/'examples/events'/p.name.replace('.schema.json','.json')
 if example.exists():
  e=json.loads(example.read_text());v=jsonschema.Draft7Validator(s,format_checker=jsonschema.FormatChecker());v.validate(e)
  bad=copy.deepcopy(e);bad.pop('event_id',None)
  if 'event_id' in s.get('required',[]):assert list(v.iter_errors(bad)),p
 count+=1
print(f'PASS: {count} JSON Schemas; all event examples and missing-event-id rejection')

for p in sorted((ROOT/'kafka').glob('*.asyncapi.yaml')):
 d=yaml.safe_load(p.read_text());assert d['asyncapi']=='3.0.0';refs(d,d)
 for op in d['operations'].values():assert op['action'] in ['send','receive']
 for n,m in d['components']['messages'].items():
  s=json.loads((ROOT/'kafka/schemas'/f'{n}.schema.json').read_text());assert m['payload']['schema']==s
print('PASS: AsyncAPI local references and embedded schema consistency (not a full AsyncAPI spec validator)')

with tempfile.TemporaryDirectory() as td:
 files=sorted((ROOT/'proto').rglob('*.proto'))
 include=Path(grpc_tools.__file__).parent/'_proto'
 args=['protoc',f'-I{ROOT / "proto"}',f'-I{include}',f'--descriptor_set_out={td}/contracts.pb','--include_imports',f'--python_out={td}',f'--grpc_python_out={td}']+[str(f) for f in files]
 assert protoc.main(args)==0,'Protobuf compilation failed'
 from google.protobuf.descriptor_pb2 import FileDescriptorSet
 ds=FileDescriptorSet();ds.ParseFromString(Path(td,'contracts.pb').read_bytes())
 methods=sum(len(service.method) for f in ds.file for service in f.service)
 assert methods==10
 print(f'PASS: {len(files)} proto files compiled, {methods} RPC methods, Python gRPC stubs generated')
print('All Python validation checks passed.')
