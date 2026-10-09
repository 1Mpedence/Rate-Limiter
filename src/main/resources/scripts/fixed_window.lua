-- FIXED WINDOW: a counter that resets at the start of each time slot.

local key      = KEYS[1]
local limit    = tonumber(ARGV[1])
local windowMs = tonumber(ARGV[2])

local t     = redis.call('TIME')
local nowMs = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)

local data  = redis.call('HMGET', key, 'start', 'count')
local start = tonumber(data[1])
local count = tonumber(data[2])

if start == nil or nowMs - start >= windowMs then
  start = nowMs - (nowMs % windowMs)
  count = 0
end

if count >= limit then
  return {0, 0, start + windowMs - nowMs}
end

count = count + 1
redis.call('HSET', key, 'start', start, 'count', count)
redis.call('PEXPIRE', key, windowMs)
return {1, limit - count, 0}
