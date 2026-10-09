-- SLIDING WINDOW LOG: remember the timestamp of every request in the last window.

local key      = KEYS[1]
local limit    = tonumber(ARGV[1])
local windowMs = tonumber(ARGV[2])
local member   = ARGV[3]

local t     = redis.call('TIME')
local nowMs = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)

redis.call('ZREMRANGEBYSCORE', key, '-inf', nowMs - windowMs)

local count = redis.call('ZCARD', key)

if count >= limit then
  local oldest = redis.call('ZRANGE', key, 0, 0, 'WITHSCORES')
  local retry = windowMs
  if oldest[2] then
    retry = tonumber(oldest[2]) + windowMs - nowMs
  end
  return {0, 0, retry}
end

redis.call('ZADD', key, nowMs, member)
redis.call('PEXPIRE', key, windowMs)
return {1, limit - count - 1, 0}
