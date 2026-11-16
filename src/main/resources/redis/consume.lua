-- KEYS: persistent revision fence, bounded cardinality index, then <=16 buckets.
-- ARGV: revision, cost, max bucket count, then capacity/refill-per-second pairs.
local revision=tonumber(ARGV[1]);local cost=tonumber(ARGV[2]);local maximum=tonumber(ARGV[3])
local fence=tonumber(redis.call('GET',KEYS[1]) or '0')
if revision<fence then return {-1,0,0} end
local time=redis.call('TIME');local now=tonumber(time[1])*1000+math.floor(tonumber(time[2])/1000)
-- Bounded index cleanup; no token writes before all policies accept.
local expired=redis.call('ZRANGEBYSCORE',KEYS[2],'-inf',now,'LIMIT',0,64)
if #expired>0 then redis.call('ZREM',KEYS[2],unpack(expired)) end
local balances={};local times={};local ttls={};local existing={};local remaining=1000000000;local retry=0;local added=0;local available=1000000000
for i=3,#KEYS do
 local cap=tonumber(ARGV[2*i-2]);local rate=tonumber(ARGV[2*i-1])/1000
 local state=redis.call('HMGET',KEYS[i],'tokens','at','rate')
 existing[i]=state[1]~=false
 local last=tonumber(state[2]) or now;local effective=math.max(now,last)
 local oldrate=tonumber(state[3]) or rate
 local tokens=math.min(cap,(tonumber(state[1]) or cap)+math.min(cap,math.max(0,effective-last)*math.min(rate,oldrate)))
 balances[i]=tokens;times[i]=effective;ttls[i]=math.ceil(cap/rate)+math.max(0,effective-now)+1000
 available=math.min(available,math.floor(tokens))
 remaining=math.min(remaining,math.max(0,math.floor(tokens-cost)))
 if tokens<cost then retry=math.max(retry,math.ceil((cost-tokens)/rate)) end
 if not redis.call('ZSCORE',KEYS[2],KEYS[i]) then added=added+1 end
end
if ARGV[#ARGV]=="inspect" then return {retry==0 and 1 or 0,available,retry} end
if retry>0 then
 -- Persist refill/parameter transitions only for existing buckets; never deduct or create
 -- unrelated buckets on rejection. Otherwise a rate increase could remain stuck at
 -- the old rate until the first successful consumption.
 redis.call('SET',KEYS[1],ARGV[1])
 for i=3,#KEYS do
  if existing[i] then
   redis.call('HSET',KEYS[i],'tokens',balances[i],'at',times[i],'rate',tonumber(ARGV[2*i-1])/1000)
   redis.call('PEXPIRE',KEYS[i],ttls[i]);redis.call('ZADD',KEYS[2],'XX',now+ttls[i],KEYS[i])
  end
 end
 return {0,0,retry}
end
if redis.call('ZCARD',KEYS[2])+added>maximum then return {-2,0,0} end
redis.call('SET',KEYS[1],ARGV[1])
for i=3,#KEYS do
 local rate=tonumber(ARGV[2*i-1])/1000
 redis.call('HSET',KEYS[i],'tokens',balances[i]-cost,'at',times[i],'rate',rate)
 redis.call('PEXPIRE',KEYS[i],ttls[i]);redis.call('ZADD',KEYS[2],now+ttls[i],KEYS[i])
end
return {1,remaining,0}
