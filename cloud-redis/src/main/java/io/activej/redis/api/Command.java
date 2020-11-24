package io.activej.redis.api;

import java.util.ArrayList;
import java.util.List;

import static java.util.Collections.unmodifiableList;

public enum Command {
	// Connection
	AUTH, CLIENT_GETNAME, CLIENT_SETNAME, CLIENT_PAUSE, ECHO, PING, QUIT, SELECT,


	// Keys
	DEL, DUMP, EXISTS, EXPIRE, EXPIREAT, KEYS, MOVE, PERSIST, PEXPIRE, PEXPIREAT, PTTL, RANDOMKEY,
	RENAME, RENAMENX, RESTORE, TOUCH, TTL, TYPE, UNLINK, WAIT,


	// Strings
	APPEND, BITCOUNT, BITOP, BITPOS, DECR, DECRBY, GET, GETBIT, GETRANGE, GETSET, INCR, INCRBY, INCRBYFLOAT,
	MGET, MSET, MSETNX, PSETEX, SET, SETBIT, SETEX, SETNX, SETRANGE, STRLEN,


	// Lists
	BLPOP, BRPOP, BRPOPLPUSH, LINDEX, LINSERT, LLEN, LPOP, LPOS, LPUSH, LPUSHX, LRANGE, LREM, LSET, LTRIM,
	RPOP, RPOPLPUSH, RPUSH, RPUSHX,


	// Sets
	SADD, SCARD, SDIFF, SDIFFSTORE, SINTER, SINTERSTORE, SISMEMBER, SMEMBERS, SMOVE, SPOP, SRANDMEMBER, SREM,
	SUNION, SUNIONSTORE,


	// Hashes
	HDEL, HEXISTS, HGET, HGETALL, HINCRBY, HINCRBYFLOAT, HKEYS, HLEN, HMGET, HMSET, HSET, HSETNX, HSTRLEN, HVALS,


	// Sorted Sets
	BZPOPMIN, BZPOPMAX, ZADD, ZCARD, ZCOUNT, ZINCRBY, ZINTERSTORE, ZLEXCOUNT, ZPOPMAX, ZPOPMIN, ZRANGE, ZRANGEBYLEX,
	ZREVRANGEBYLEX, ZRANGEBYSCORE, ZRANK, ZREM, ZREMRANGEBYLEX, ZREMRANGEBYRANK, ZREMRANGEBYSCORE, ZREVRANGE,
	ZREVRANGEBYSCORE, ZREVRANK, ZSCORE, ZUNIONSTORE,


	// HyperLogLog
	PFADD, PFCOUNT, PFMERGE;

	private final List<byte[]> parts;

	Command() {
		String[] stringParts = name().split("_");
		List<byte[]> parts = new ArrayList<>(stringParts.length);
		for (String stringPart : stringParts) {
			parts.add(stringPart.getBytes());
		}
		this.parts = unmodifiableList(parts);
	}

	public List<byte[]> getParts() {
		return parts;
	}
}
