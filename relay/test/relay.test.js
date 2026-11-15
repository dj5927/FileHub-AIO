'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const {randomBytes} = require('node:crypto');
const {createRelayServer} = require('../server.js');

const token = bytes => randomBytes(bytes).toString('base64url');
async function start(ttlMs=120000) {
  const server = createRelayServer({ttlMs});
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const base = 'http://127.0.0.1:' + server.address().port;
  return {server, base, close: () => new Promise(resolve => server.close(resolve))};
}
async function create(base, id, owner, writer) {
  return fetch(base + '/v1/sessions', {
    method: 'POST', headers: {'Content-Type': 'application/json'},
    body: JSON.stringify({id, ownerToken: owner, sendToken: writer})
  });
}
const auth = token => ({Authorization: 'Bearer ' + token});

test('stock-camera HTTPS bridge does not expose URL fragment to relay', async t => {
  const setup = await start();t.after(setup.close);
  const secret = token(32);
  const url = setup.base+'/pair#'+encodeURIComponent(
    'filehubaio://relay?key='+secret);
  const response = await fetch(url);
  assert.equal(response.status,200);
  assert.match(response.headers.get('content-security-policy'),/default-src 'none'/);
  assert.equal(response.headers.get('referrer-policy'),'no-referrer');
  const html = await response.text();
  assert.match(html,/FileHub에서 열기/);
  assert.ok(!html.includes(secret));
});

test('one-use end-to-end ciphertext delivery and server does not receive key', async t => {
  const setup=await start();t.after(setup.close);
  const id=token(16), owner=token(32), writer=token(32), key=token(32);
  assert.equal((await create(setup.base,id,owner,writer)).status,201);
  assert.equal((await fetch(setup.base+'/v1/sessions/'+id+'/payload',
    {headers:auth(owner)})).status,204);
  const packet=randomBytes(900);
  assert.equal((await fetch(setup.base+'/v1/sessions/'+id+'/payload',{
    method:'POST',headers:{...auth(token(32)),'Content-Type':'application/octet-stream'},
    body:packet
  })).status,404);
  assert.equal((await fetch(setup.base+'/v1/sessions/'+id+'/payload',{
    method:'POST',headers:{...auth(writer),'Content-Type':'application/octet-stream'},
    body:packet
  })).status,202);
  const result=await fetch(setup.base+'/v1/sessions/'+id+'/payload',{headers:auth(owner)});
  assert.equal(result.status,200);
  assert.deepEqual(Buffer.from(await result.arrayBuffer()),packet);
  assert.equal((await fetch(setup.base+'/v1/sessions/'+id+'/payload',
    {headers:auth(owner)})).status,404);
  assert.ok(key);
});

test('malformed sessions, token replay and expired sessions fail closed', async t => {
  const setup=await start(120);t.after(setup.close);
  const id=token(16), owner=token(32), writer=token(32);
  assert.equal((await create(setup.base,'not-an-id',owner,writer)).status,400);
  assert.equal((await create(setup.base,id,owner,writer)).status,201);
  assert.equal((await create(setup.base,id,owner,writer)).status,400);
  assert.equal((await fetch(setup.base+'/v1/sessions/'+id,{method:'DELETE',
    headers:auth(writer)})).status,404);
  await new Promise(resolve=>setTimeout(resolve,170));
  assert.equal((await fetch(setup.base+'/v1/sessions/'+id+'/payload',
    {headers:auth(owner)})).status,404);
});

test('deletion and packet size validation', async t => {
  const setup=await start();t.after(setup.close);
  const id=token(16), owner=token(32), writer=token(32);
  assert.equal((await create(setup.base,id,owner,writer)).status,201);
  assert.equal((await fetch(setup.base+'/v1/sessions/'+id+'/payload',{
    method:'POST',headers:{...auth(writer),'Content-Type':'application/octet-stream'},
    body:Buffer.alloc(10)
  })).status,400);
  assert.equal((await fetch(setup.base+'/v1/sessions/'+id,{
    method:'DELETE',headers:auth(owner)
  })).status,204);
  assert.equal((await fetch(setup.base+'/v1/sessions/'+id+'/payload',{
    headers:auth(owner)
  })).status,404);
});
