import test from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {encryptBackup,decryptBackup,validateEnvelope,MAX_ENVELOPE_BYTES} from '../dist/backup-crypto.js';

const password='synthetic-test-password-26';
test('encrypted backup decrypts to identical Unicode JSON',async()=>{
  const plain=JSON.stringify({note:'A quiet morning 🌱 — नमस्ते'}),encoded=await encryptBackup(plain,password),envelope=JSON.parse(encoded);
  assert.equal(envelope.iterations,600000);assert.equal(await decryptBackup(encoded,password),plain);
});
test('each export uses fresh salt and IV',async()=>{const a=JSON.parse(await encryptBackup('{}',password)),b=JSON.parse(await encryptBackup('{}',password));assert.notEqual(a.salt,b.salt);assert.notEqual(a.iv,b.iv);assert.notEqual(a.ciphertext,b.ciphertext);});
test('wrong password cannot decrypt',async()=>{const encoded=await encryptBackup('{}',password);await assert.rejects(()=>decryptBackup(encoded,'not-the-test-password'),/Wrong password/);});
test('ciphertext and header tampering are authenticated',async()=>{
  const envelope=JSON.parse(await encryptBackup('{}',password)),cipher=Buffer.from(envelope.ciphertext,'base64');cipher[0]^=1;
  await assert.rejects(()=>decryptBackup(JSON.stringify({...envelope,ciphertext:cipher.toString('base64')}),password),/Wrong password/);
  await assert.rejects(()=>decryptBackup(JSON.stringify({...envelope,iterations:599999}),password),/Wrong password/);
});
test('malformed envelopes and unbounded KDF parameters fail before key derivation',()=>{
  const base={kind:'lifetrack-encrypted',version:1,kdf:'PBKDF2-HMAC-SHA256',iterations:600000,salt:Buffer.alloc(16).toString('base64'),iv:Buffer.alloc(12).toString('base64'),ciphertext:Buffer.alloc(16).toString('base64')};
  for(const changed of [{...base,iterations:999999999},{...base,salt:'bad!'},{...base,iv:Buffer.alloc(16).toString('base64')},{...base,extra:true}])assert.throws(()=>validateEnvelope(JSON.stringify(changed)));
  assert.throws(()=>validateEnvelope('x'.repeat(MAX_ENVELOPE_BYTES+1)),/15 MB/);
});
test('short new-export passwords are refused',async()=>{await assert.rejects(()=>encryptBackup('{}','short'),/10/);});
test('an envelope produced by actual native BackupCrypto decrypts identically',async()=>{
  const fixture=JSON.parse(await readFile(new URL('./fixtures/native-crypto.json',import.meta.url),'utf8'));
  const plain=await decryptBackup(JSON.stringify(fixture.envelope),fixture.password);
  assert.equal(Buffer.from(plain,'utf8').toString('base64'),fixture.plaintextBase64);
});
