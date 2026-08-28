local capacity = tonumber(ARGV[1])
local refill_per_second = tonumber(ARGV[2])
local now_millis = tonumber(ARGV[3])
local requested = tonumber(ARGV[4])

local tokens = tonumber(redis.call('HGET', KEYS[1], 'tokens'))
local last_refill = tonumber(redis.call('HGET', KEYS[1], 'last_refill'))
if tokens == nil then tokens = capacity end
if last_refill == nil then last_refill = now_millis end

local elapsed = math.max(0, now_millis - last_refill)
tokens = math.min(capacity, tokens + elapsed * refill_per_second / 1000)
local allowed = 0
if tokens >= requested then
    tokens = tokens - requested
    allowed = 1
end

redis.call('HSET', KEYS[1], 'tokens', tokens, 'last_refill', now_millis)
local ttl = math.ceil((capacity / refill_per_second) * 2000)
redis.call('PEXPIRE', KEYS[1], math.max(ttl, 1000))
return {allowed, math.floor(tokens)}
