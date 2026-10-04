// npm install --no-save @asyncapi/parser@3.6.3
// node scripts/validate-asyncapi.cjs
const fs = require('fs');
const path = require('path');
const { Parser } = require('@asyncapi/parser');
(async () => {
  const parser = new Parser();
  const root = path.join(__dirname, '..', 'kafka');
  for (const filename of fs.readdirSync(root).filter(f => f.endsWith('.asyncapi.yaml'))) {
    const result = await parser.parse(fs.readFileSync(path.join(root, filename), 'utf8'));
    const errors = result.diagnostics.filter(d => d.severity === 0);
    console.log(filename, JSON.stringify(errors));
    if (!result.document || errors.length) process.exitCode = 1;
  }
})().catch(error => { console.error(error); process.exitCode = 1; });
