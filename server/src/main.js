import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { createServer } from './server.js';

const here = path.dirname(fileURLToPath(import.meta.url));
const port = Number(process.env.PORT ?? 8787);
// 기본은 이 PC에서만 접속(에뮬레이터는 10.0.2.2로 이 PC의 127.0.0.1에 닿음). 외부 공개는 HTTPS 프록시 뒤에서.
const host = process.env.HOST ?? '127.0.0.1';
const dbFile = process.env.CARMARADE_DB ?? path.join(here, '..', 'data', 'carmarade.db');

const server = createServer({ dbFile, log: (m) => console.log(new Date().toISOString(), m) });
const actual = await server.listen(port, host);
console.log(`Carmarade server on http://${host}:${actual} (db: ${dbFile})`);

for (const sig of ['SIGINT', 'SIGTERM']) process.on(sig, async () => { await server.close(); process.exit(0); });
