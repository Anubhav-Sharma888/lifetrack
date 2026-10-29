/** Password-protected backup format shared with the native Android application. */
export const MAX_ENVELOPE_BYTES=15*1024*1024;
const MAX_PLAIN_BYTES=10*1024*1024;
const ITERATIONS=600000;
const KEYS=['kind','version','kdf','iterations','salt','iv','ciphertext'];
const encoder=new TextEncoder();
function require(value,message){if(!value)throw new Error(message);}
function base64(bytes){let binary='';for(let offset=0;offset<bytes.length;offset+=8192)binary+=String.fromCharCode(...bytes.subarray(offset,offset+8192));return btoa(binary);}
function unbase64(text){
  require(typeof text==='string'&&text.length%4===0&&/^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/.test(text),'The encrypted backup contains invalid base64.');
  let binary;try{binary=atob(text);}catch{throw new Error('The encrypted backup contains invalid base64.');}
  const bytes=Uint8Array.from(binary,c=>c.charCodeAt(0));require(base64(bytes)===text,'Non-canonical backup encoding.');return bytes;
}
const aad=envelope=>encoder.encode(['LifeTrack encrypted backup v1',envelope.kind,String(envelope.version),envelope.kdf,String(envelope.iterations),envelope.salt,envelope.iv].join('\n'));
async function keyFrom(password,salt,iterations,usage){
  require(globalThis.crypto?.subtle,'Encrypted backups require a browser with Web Crypto on localhost or HTTPS.');
  const passwordBytes=encoder.encode(password);
  try {
    const material=await crypto.subtle.importKey('raw',passwordBytes,'PBKDF2',false,['deriveKey']);
    return await crypto.subtle.deriveKey({name:'PBKDF2',hash:'SHA-256',salt,iterations},material,{name:'AES-GCM',length:256},false,[usage]);
  } finally {passwordBytes.fill(0);}
}
export function validateEnvelope(text){
  require(typeof text==='string'&&encoder.encode(text).length<=MAX_ENVELOPE_BYTES,'Encrypted backups must be 15 MB or smaller.');
  let envelope;try{envelope=JSON.parse(text);}catch{throw new Error('The encrypted backup is not valid JSON.');}
  require(envelope&&typeof envelope==='object'&&!Array.isArray(envelope)&&Object.keys(envelope).length===KEYS.length&&KEYS.every(key=>Object.hasOwn(envelope,key)),'The encrypted backup has missing or unsupported fields.');
  require(envelope.kind==='lifetrack-encrypted'&&envelope.version===1&&envelope.kdf==='PBKDF2-HMAC-SHA256','Unsupported encrypted backup format.');
  require(Number.isInteger(envelope.iterations)&&envelope.iterations>=210000&&envelope.iterations<=ITERATIONS,'Unsupported backup key-derivation cost.');
  const salt=unbase64(envelope.salt),iv=unbase64(envelope.iv),ciphertext=unbase64(envelope.ciphertext);
  require(salt.length===16&&iv.length===12&&ciphertext.length>=16&&ciphertext.length<=MAX_PLAIN_BYTES+16,'Invalid encrypted backup salt, IV or payload size.');
  return {envelope,salt,iv,ciphertext};
}
export async function encryptBackup(plain,password){
  require(typeof password==='string'&&password.length>=10&&password.length<=1024,'Choose a backup password with 10–1,024 characters.');
  const data=encoder.encode(plain);require(data.length<=MAX_PLAIN_BYTES,'The backup exceeds the 10 MB plaintext limit.');
  const salt=crypto.getRandomValues(new Uint8Array(16)),iv=crypto.getRandomValues(new Uint8Array(12));
  const envelope={kind:'lifetrack-encrypted',version:1,kdf:'PBKDF2-HMAC-SHA256',iterations:ITERATIONS,salt:base64(salt),iv:base64(iv),ciphertext:''};
  const key=await keyFrom(password,salt,ITERATIONS,'encrypt');
  const ciphertext=await crypto.subtle.encrypt({name:'AES-GCM',iv,additionalData:aad(envelope),tagLength:128},key,data);
  envelope.ciphertext=base64(new Uint8Array(ciphertext));
  return JSON.stringify(envelope,null,2);
}
export async function decryptBackup(text,password){
  require(typeof password==='string'&&password.length>0&&password.length<=1024,'Enter your backup password.');
  const {envelope,salt,iv,ciphertext}=validateEnvelope(text);
  const key=await keyFrom(password,salt,envelope.iterations,'decrypt');
  try {
    const result=await crypto.subtle.decrypt({name:'AES-GCM',iv,additionalData:aad(envelope),tagLength:128},key,ciphertext);
    return new TextDecoder('utf-8',{fatal:true}).decode(result);
  } catch {throw new Error('Wrong password or damaged backup. Your current data is unchanged.');}
}
