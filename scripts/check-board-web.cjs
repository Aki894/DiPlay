// Run the shipped page against a fake DOM/network. No browser or npm packages needed.
const fs=require('node:fs'),vm=require('node:vm'),assert=require('node:assert/strict');
const html=fs.readFileSync('board/src/main/assets/index.html','utf8');
const script=html.match(/<script>([\s\S]*?)<\/script>/)[1];
const nodes=new Map(),button={disabled:false};
function node(id){if(!nodes.has(id))nodes.set(id,{value:'',checked:false,hidden:false,textContent:'',dataset:{},querySelectorAll:()=>[button],replaceChildren(){},add(){}});return nodes.get(id);}
let calls=[],mode='ok',desired=true;
const config={revision:25,wireless:true,autoStart:false,microphone:false,webLan:false,width:800,height:480,fps:30,p2pChannel:0,ssid:'',hotspotMode:'WIFI_P2P',band:'GHZ_2_4',phone:''};
const context=vm.createContext({
 document:{getElementById:node,hidden:false},sessionStorage:{getItem:()=>'',setItem(){},removeItem(){}},
 AbortController,TypeError,Date,JSON,Number,String,Error,Option:function(){},
 setTimeout:(fn,ms)=>setTimeout(fn,ms===8000?20:ms),clearTimeout,setInterval:()=>1,clearInterval(){},confirm:()=>true,
 fetch:async(url,options)=>{
  calls.push({url,options});
  if(mode==='timeout')return new Promise((_,reject)=>options.signal.addEventListener('abort',()=>{let e=new Error('aborted');e.name='AbortError';reject(e);}));
  if(mode==='offline')throw new TypeError('Failed to fetch');
  const path=url.split('/api/v1/')[1];
  if(path==='session/stop')desired=false;
  if(path==='session/start')desired=true;
  const data=path==='config'?config:path==='phones'?{phones:[]}:path==='status'?{state:desired?'WirelessActive':'idle',requested:desired,generation:2,carLife:{settings:{usbMedia:true,ttsCompatibility:false,ttsRate:48000}}}:{accepted:true};
  return {ok:true,status:200,json:async()=>data};
 }
});
vm.runInContext(script,context);
(async()=>{
 node('token').value='test-token';node('side').value='both';
 await vm.runInContext('login()',context);
 assert.equal(button.disabled,false);
 await vm.runInContext("command('stop')",context);
 assert.equal(JSON.parse(node('status').textContent).requested,false);
 assert.deepEqual(JSON.parse(calls.find(c=>c.url.endsWith('session/stop')).options.body),{side:'both'});
 mode='offline';
 await vm.runInContext("command('start')",context);
 assert.equal(button.disabled,true);
 assert.match(node('connection').textContent,/请求未确认/);
 const posts=calls.filter(c=>c.options.method==='POST').length;
 mode='ok';await vm.runInContext('watch()',context);
 assert.equal(button.disabled,false);
 assert.equal(calls.filter(c=>c.options.method==='POST').length,posts,'Recovery must not replay a mutation');
 mode='timeout';await vm.runInContext('watch()',context);
 assert.equal(button.disabled,true);
 assert.match(node('connection').textContent,/可能已过期/);
 mode='ok';await vm.runInContext('watch()',context);
 assert.equal(button.disabled,false);
 console.log('Board web checks passed: stop, bounded timeout, recovery, no mutation replay');
})().catch(e=>{console.error(e);process.exitCode=1;});
