package org.spring.claude.spring_claude_tools.utils;

import org.spring.claude.spring_claude_tools.utils.RedisSessionRepository.EventDto.MsgDto;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.session.EventFilter;
import org.springframework.ai.session.Session;
import org.springframework.ai.session.SessionEvent;
import org.springframework.ai.session.SessionRepository;
import org.springframework.util.Assert;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.resps.ScanResult;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 基于 Redis（Jedis 客户端）的 {@link SessionRepository} 实现，
 * 用于把 SessionMemoryAdvisor 的会话与事件日志持久化到 Redis。
 *
 * <p>数据结构（key 前缀默认为 "session:"）：
 * <ul>
 *   <li>{@code session:{id}:meta}    Hash：userId、createdAt、expiresAt、metadata</li>
 *   <li>{@code session:{id}:events}  List：SessionEvent 的 JSON 事件日志（按时间顺序）</li>
 *   <li>{@code session:{id}:eventIds Set：事件 id 集合，用于 appendEvent 幂等去重</li>
 *   <li>{@code session:{id}:version} String：事件日志版本号（乐观并发控制用）</li>
 *   <li>{@code session:user:{userId}:sessions} Set：用户的会话 id 索引</li>
 * </ul>
 *
 * <p>并发说明：通过按 sessionId 加 JVM 内锁保证单实例内的原子性，
 * 语义与 {@code InMemorySessionRepository} 完全对齐（appendEvent 幂等、
 * compactEvents 乐观 CAS、findEvents 空会话返回空列表）。
 * 多实例部署时需要改用 Lua 脚本或 WATCH/MULTI 保证跨实例原子性。
 */
public class RedisSessionRepository implements SessionRepository {

	private final JedisPooled jedis;

	private final ObjectMapper mapper = new ObjectMapper();

	private final String prefix;

	private final Map<String, Object> sessionLocks = new ConcurrentHashMap<>();

	public RedisSessionRepository(String host, int port, String keyPrefix) {
		this.jedis = new JedisPooled(host, port);
		this.prefix = (keyPrefix != null) ? keyPrefix : "session:";
	}

	// --------------------------------------------------------------- Sessions

	@Override
	public Session save(Session session) {
		Assert.notNull(session, "session must not be null");
		String metaKey = metaKey(session.id());
		synchronized (lockFor(session.id())) {
			boolean isNew = !jedis.exists(metaKey);
			Map<String, String> fields = new HashMap<>();
			fields.put("userId", session.userId());
			fields.put("createdAt", session.createdAt().toString());
			if (session.expiresAt() != null) {
				fields.put("expiresAt", session.expiresAt().toString());
			}
			if (session.metadata() != null && !session.metadata().isEmpty()) {
				fields.put("metadata", toJson(session.metadata()));
			}
			jedis.hset(metaKey, fields);
			jedis.sadd(userIndexKey(session.userId()), session.id());
			if (isNew) {
				jedis.set(versionKey(session.id()), "0");
			}
			if (session.expiresAt() != null) {
				long ttlSeconds = Math.max(1, session.expiresAt().getEpochSecond() - Instant.now().getEpochSecond());
				jedis.expire(metaKey, ttlSeconds);
				jedis.expire(eventsKey(session.id()), ttlSeconds);
				jedis.expire(eventIdsKey(session.id()), ttlSeconds);
				jedis.expire(versionKey(session.id()), ttlSeconds);
				jedis.expire(userIndexKey(session.userId()), ttlSeconds);
			}
		}
		return session;
	}

	@Override
	public Session findById(String sessionId) {
		Assert.hasText(sessionId, "sessionId must not be null or empty");
		return readSession(sessionId);
	}

	@Override
	public List<Session> findByUserId(String userId) {
		Assert.hasText(userId, "userId must not be null or empty");
		Set<String> ids = jedis.smembers(userIndexKey(userId));
		return ids.stream()
			.map(this::readSession)
			.filter(s -> s != null && userId.equals(s.userId()))
			.toList();
	}

	@Override
	public List<String> findExpiredSessionIds(Instant before) {
		Assert.notNull(before, "before must not be null");
		List<String> expired = new ArrayList<>();
		String cursor = "0";
		do {
			ScanResult<String> scan = jedis.scan(cursor,
					new ScanParams().match(prefix + "*:meta").count(100));
			for (String metaKey : scan.getResult()) {
				String expiresAt = jedis.hget(metaKey, "expiresAt");
				if (expiresAt != null && Instant.parse(expiresAt).isBefore(before)) {
					expired.add(metaKey.substring(prefix.length(), metaKey.length() - ":meta".length()));
				}
			}
			cursor = scan.getCursor();
		}
		while (!"0".equals(cursor));
		return expired;
	}

	@Override
	public void delete(String sessionId) {
		Assert.hasText(sessionId, "sessionId must not be null or empty");
		synchronized (lockFor(sessionId)) {
			String userId = jedis.hget(metaKey(sessionId), "userId");
			jedis.del(metaKey(sessionId), eventsKey(sessionId), eventIdsKey(sessionId), versionKey(sessionId));
			if (userId != null) {
				jedis.srem(userIndexKey(userId), sessionId);
			}
		}
	}

	// --------------------------------------------------------------- Events

	@Override
	public void appendEvent(SessionEvent event) {
		Assert.notNull(event, "event must not be null");
		String sessionId = event.getSessionId();
		synchronized (lockFor(sessionId)) {
			if (!jedis.exists(metaKey(sessionId))) {
				throw new IllegalArgumentException("Session not found: " + sessionId);
			}
			// 幂等去重：同 id 事件已存在时不追加、不递增版本（重放安全）
			if (jedis.sismember(eventIdsKey(sessionId), event.getId())) {
				return;
			}
			jedis.rpush(eventsKey(sessionId), toJson(EventDto.of(event)));
			jedis.sadd(eventIdsKey(sessionId), event.getId());
			jedis.incr(versionKey(sessionId));
		}
	}

	@Override
	public boolean compactEvents(String sessionId, List<SessionEvent> archivedEvents,
			List<SessionEvent> retainedEvents, long expectedVersion) {
		Assert.hasText(sessionId, "sessionId must not be null or empty");
		Assert.notNull(archivedEvents, "archivedEvents must not be null");
		Assert.notNull(retainedEvents, "retainedEvents must not be null");
		synchronized (lockFor(sessionId)) {
			if (!jedis.exists(metaKey(sessionId))) {
				throw new IllegalArgumentException("Session not found: " + sessionId);
			}
			long currentVersion = currentVersion(sessionId);
			if (currentVersion != expectedVersion) {
				// 乐观 CAS 失败：其他写入方已修改事件日志
				return false;
			}
			// 保留已归档事件（始终是最老的前缀），追加新归档事件，再接新的活跃窗口
			List<SessionEvent> newEvents = new ArrayList<>(loadEvents(sessionId).stream()
				.filter(SessionEvent::isArchived)
				.toList());
			archivedEvents.forEach(e -> newEvents.add(e.asArchived()));
			newEvents.addAll(retainedEvents);

			jedis.del(eventsKey(sessionId));
			if (!newEvents.isEmpty()) {
				jedis.rpush(eventsKey(sessionId),
						newEvents.stream().map(this::toJson).toArray(String[]::new));
			}
			jedis.incr(versionKey(sessionId));
			return true;
		}
	}

	@Override
	public long getEventVersion(String sessionId) {
		Assert.hasText(sessionId, "sessionId must not be null or empty");
		return currentVersion(sessionId);
	}

	@Override
	public List<SessionEvent> findEvents(String sessionId, EventFilter filter) {
		Assert.hasText(sessionId, "sessionId must not be null or empty");
		Assert.notNull(filter, "filter must not be null");
		synchronized (lockFor(sessionId)) {
			if (!jedis.exists(metaKey(sessionId))) {
				return List.of();
			}
			List<SessionEvent> matched = loadEvents(sessionId).stream()
				.filter(filter::matches)
				.collect(Collectors.toCollection(ArrayList::new));
			if (filter.lastN() != null && matched.size() > filter.lastN()) {
				matched = new ArrayList<>(matched.subList(matched.size() - filter.lastN(), matched.size()));
			}
			if (filter.pageSize() != null) {
				int pageNum = (filter.page() != null) ? filter.page() : 0;
				int size = filter.pageSize();
				int fromIdx = pageNum * size;
				if (fromIdx >= matched.size()) {
					matched = new ArrayList<>();
				}
				else {
					matched = new ArrayList<>(matched.subList(fromIdx, Math.min(fromIdx + size, matched.size())));
				}
			}
			// 必须在 filter/lastN/pageSize 之后清洗：archived 事件被排除或窗口截断
			// 都可能把 assistant(tool_calls) 与其 tool 响应拆散，留下悬空的 tool 消息触发 400
			return List.copyOf(sanitizeToolCallSequence(matched));
		}
	}

	// --------------------------------------------------------------- 内部实现

	private Object lockFor(String sessionId) {
		return sessionLocks.computeIfAbsent(sessionId, k -> new Object());
	}

	private long currentVersion(String sessionId) {
		String version = jedis.get(versionKey(sessionId));
		return (version != null) ? Long.parseLong(version) : 0L;
	}

	private List<SessionEvent> loadEvents(String sessionId) {
		return jedis.lrange(eventsKey(sessionId), 0, -1)
			.stream()
			.map(this::fromJson)
			.toList();
	}

	/**
	 * 剔除事件日志中未闭合的 tool_calls 序列。
	 *
	 * <p>OpenAI 兼容协议要求：assistant 消息中的每个 tool_call_id 必须紧跟着对应数量的
	 * tool 响应消息。若上一轮请求中断（HTTP 400、进程被终止、工具执行抛异常），会话里会
	 * 残留「有 tool_calls 但没有 tool 响应」的事件；由于会话持久化在 Redis，之后每一轮
	 * 都会把这条残缺序列原样回放，DeepSeek 会持续返回
	 * {@code 400 ... insufficient tool messages following tool_calls message}。
	 *
	 * <p>处理策略：以「assistant(tool_calls) + 紧随的 tool 响应」为一组，遇到下一条
	 * USER/SYSTEM 消息或日志结尾仍未闭合时，整组回滚丢弃；同时丢弃没有对应 tool_calls
	 * 的悬空 tool 响应。清洗只作用于读取视图，不改动 Redis 中的原始事件日志。
	 */
	private List<SessionEvent> sanitizeToolCallSequence(List<SessionEvent> events) {
		List<SessionEvent> clean = new ArrayList<>(events.size());
		Set<String> pendingCallIds = new HashSet<>();
		int pendingAssistantIndex = -1;

		for (SessionEvent event : events) {
			Message message = event.getMessage();

			if (message instanceof AssistantMessage assistant && hasToolCalls(assistant)) {
				rollbackUnclosed(clean, pendingAssistantIndex);
				pendingAssistantIndex = clean.size();
				pendingCallIds.clear();
				assistant.getToolCalls().forEach(call -> pendingCallIds.add(call.id()));
				clean.add(event);
			}
			else if (message instanceof ToolResponseMessage toolResponse) {
				if (pendingAssistantIndex < 0) {
					continue; // 悬空的 tool 响应，没有对应的 assistant tool_calls
				}
				if (toolResponse.getResponses() != null) {
					toolResponse.getResponses().forEach(response -> pendingCallIds.remove(response.id()));
				}
				clean.add(event);
				if (pendingCallIds.isEmpty()) {
					pendingAssistantIndex = -1;
				}
			}
			else {
				rollbackUnclosed(clean, pendingAssistantIndex);
				pendingAssistantIndex = -1;
				pendingCallIds.clear();
				clean.add(event);
			}
		}
		rollbackUnclosed(clean, pendingAssistantIndex);
		return clean;
	}

	private boolean hasToolCalls(AssistantMessage assistant) {
		return assistant.getToolCalls() != null && !assistant.getToolCalls().isEmpty();
	}

	private void rollbackUnclosed(List<SessionEvent> clean, int pendingAssistantIndex) {
		if (pendingAssistantIndex >= 0 && pendingAssistantIndex < clean.size()) {
			clean.subList(pendingAssistantIndex, clean.size()).clear();
		}
	}

	private Session readSession(String sessionId) {
		Map<String, String> fields = jedis.hgetAll(metaKey(sessionId));
		if (fields.isEmpty()) {
			return null;
		}
		Session.Builder builder = Session.builder()
			.id(sessionId)
			.userId(fields.get("userId"))
			.createdAt(Instant.parse(fields.get("createdAt")));
		if (fields.get("expiresAt") != null) {
			builder.expiresAt(Instant.parse(fields.get("expiresAt")));
		}
		if (fields.get("metadata") != null) {
			builder.metadata(fromJsonMap(fields.get("metadata")));
		}
		return builder.build();
	}

	private String metaKey(String sessionId) {
		return prefix + sessionId + ":meta";
	}

	private String eventsKey(String sessionId) {
		return prefix + sessionId + ":events";
	}

	private String eventIdsKey(String sessionId) {
		return prefix + sessionId + ":eventIds";
	}

	private String versionKey(String sessionId) {
		return prefix + sessionId + ":version";
	}

	private String userIndexKey(String userId) {
		return prefix + "user:" + userId + ":sessions";
	}

	// --------------------------------------------------------------- 序列化

	private String toJson(Object value) {
		try {
			return mapper.writeValueAsString(value);
		}
		catch (JacksonException e) {
			throw new IllegalStateException("JSON 序列化失败", e);
		}
	}

	private SessionEvent fromJson(String json) {
		try {
			EventDto dto = mapper.readValue(json, EventDto.class);
			return SessionEvent.builder()
				.id(dto.id())
				.sessionId(dto.sessionId())
				.timestamp(Instant.parse(dto.timestamp()))
				.message(toMessage(dto.message()))
				.metadata(dto.metadata() != null ? dto.metadata() : Map.of())
				.branch(dto.branch())
				.archived(dto.archived())
				.build();
		}
		catch (JacksonException e) {
			throw new IllegalStateException("JSON 反序列化失败: " + json, e);
		}
	}

	private Map<String, Object> fromJsonMap(String json) {
		try {
			return mapper.readValue(json, new ObjectMapper().getTypeFactory()
				.constructMapType(HashMap.class, String.class, Object.class));
		}
		catch (JacksonException e) {
			throw new IllegalStateException("JSON 反序列化失败: " + json, e);
		}
	}

	private Message toMessage(MsgDto dto) {
		MessageType type = MessageType.valueOf(dto.type());
		return switch (type) {
			case USER -> UserMessage.builder()
				.text(dto.text())
				.metadata(dto.metadata() != null ? dto.metadata() : Map.of())
				.build();
			case ASSISTANT -> AssistantMessage.builder()
				.content(dto.text())
				.toolCalls(dto.toolCalls() != null ? dto.toolCalls() : List.of())
				.build();
			case SYSTEM -> new SystemMessage(dto.text() != null ? dto.text() : "");
			case TOOL -> ToolResponseMessage.builder()
				.responses(dto.responses() != null ? dto.responses() : List.of())
				.build();
		};
	}

	/**
	 * SessionEvent 的 JSON 传输结构。
	 */
	record EventDto(String id, String sessionId, String timestamp, MsgDto message,
			Map<String, Object> metadata, String branch, boolean archived) {

		static EventDto of(SessionEvent event) {
			return new EventDto(event.getId(), event.getSessionId(), event.getTimestamp().toString(),
					MsgDto.of(event.getMessage()), new HashMap<>(event.getMetadata()), event.getBranch(),
					event.isArchived());
		}

		/**
		 * Message 的 JSON 传输结构，按 MessageType 还原为对应的 Spring AI 消息实现。
		 */
		record MsgDto(String type, String text, Map<String, Object> metadata,
				List<AssistantMessage.ToolCall> toolCalls,
				List<ToolResponseMessage.ToolResponse> responses) {

			static MsgDto of(Message message) {
				return switch (message.getMessageType()) {
					case USER -> new MsgDto(MessageType.USER.name(), ((UserMessage) message).getText(),
							message.getMetadata(), null, null);
					case ASSISTANT -> new MsgDto(MessageType.ASSISTANT.name(),
							((AssistantMessage) message).getText(), message.getMetadata(),
							((AssistantMessage) message).getToolCalls(), null);
					case SYSTEM -> new MsgDto(MessageType.SYSTEM.name(), ((SystemMessage) message).getText(),
							message.getMetadata(), null, null);
					case TOOL -> new MsgDto(MessageType.TOOL.name(), null, message.getMetadata(), null,
							((ToolResponseMessage) message).getResponses());
				};
			}
		}
	}

}
