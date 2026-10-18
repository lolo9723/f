import {test} from 'node:test';import assert from 'node:assert/strict';
import {selectUniverse} from '../src/universe.mjs';
const quotes=Object.fromEntries(Array.from({length:100},(_,i)=>['COIN'+String(i).padStart(3,'0')+'TRY',{quote_volume:i+1}]));
test('rotation is stable within a quarter across cold starts and servers',()=>{const at=100*900000;assert.deepEqual(selectUniverse(quotes,at+1),selectUniverse(quotes,at+899999));assert.equal(selectUniverse(quotes,at+1).deep_scan_at,at);});
test('liquid pairs stay selected and the rotating queue advances without storage',()=>{const a=selectUniverse(quotes,100*900000),b=selectUniverse(quotes,101*900000);assert.notDeepEqual(a.pairs,b.pairs);for(let i=84;i<100;i++)assert(a.pairs.includes('COIN'+String(i).padStart(3,'0')+'TRY'));assert(a.pairs.length<=24);});
test('successive quarters cover the full supported universe without adding foreign pairs',()=>{const visited=new Set();for(let i=0;i<13;i++)for(const p of selectUniverse(quotes,i*900000).pairs){assert(Object.hasOwn(quotes,p));visited.add(p);}assert.equal(visited.size,100);assert.deepEqual(selectUniverse({},0).pairs,[]);});
