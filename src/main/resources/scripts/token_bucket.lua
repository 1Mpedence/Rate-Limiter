-- TOKEN BUCKET: a bucket that refills steadily; each request takes one token.

local key          = KEYS[1]
local capacity     = tonumber(ARGV[1])
local refillPerSec = tonumber(ARGV[2])

local t     = redis.call('TIME')
local nowMs = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)

local data   = redis.call('HMGET', key, 'tokens', 'ts')
local tokens = tonumber(data[1])
local ts     = tonumber(data[2])

if tokens == nil then
  tokens = capacity
  ts = nowMs
end

local elapsedMs = math.max(0, nowMs - ts)
tokens = math.min(capacity, tokens + elapsedMs * refillPerSec / 1000)

local allowed = 0
local retryMs = 0
if tokens >= 1 then
  tokens = tokens - 1
  allowed = 1
else
  retryMs = math.ceil((1 - tokens) * 1000 / refillPerSec)
end

redis.call('HSET', key, 'tokens', tostring(tokens), 'ts', nowMs)
redis.call('PEXPIRE', key, math.ceil(capacity * 1000 / refillPerSec) + 1000)

return {allowed, math.floor(tokens), retryMs}
