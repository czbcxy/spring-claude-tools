package org.spring.claude.spring_claude_tools.utils;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.session.DefaultSessionService;
import org.springframework.ai.session.advisor.SessionMemoryAdvisor;
import org.springframework.ai.session.compaction.RecursiveSummarizationCompactionStrategy;
import org.springframework.ai.session.compaction.TurnCountTrigger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ChatMemoryConfigyurationConfiguration {

    @Value("${spring.ai.chat.memory.repository.redis.host:localhost}") 
    public String redisHost;
    @Value("${spring.ai.chat.memory.repository.redis.port:6379}") 
    public int redisPort;
    @Value("${spring.ai.chat.memory.repository.redis.key-prefix:chat-memory:}") 
    public String keyPrefix;
    
    //  不带压缩的记忆   
    @Bean
    ChatMemory chatMemory(ChatMemoryRepository chatMemoryRepository) {
        return MessageWindowChatMemory.builder()
                .maxMessages(200)
                .chatMemoryRepository(chatMemoryRepository).build();
    }
    
    @Bean
    public RedisSessionRepository redisSessionRepository() {
        return new RedisSessionRepository(redisHost, redisPort, keyPrefix);
    }

    //  带压缩的记忆   
    @Bean
    SessionMemoryAdvisor sessionMemoryAdvisor(DeepSeekChatModel deepSeekChatModel, RedisSessionRepository redisSessionRepository) {
        DefaultSessionService service = DefaultSessionService.builder()
                //  存储到 Redis，重启后会话与事件日志不丢失               
                .sessionRepository(redisSessionRepository)
                .build();
        return SessionMemoryAdvisor.builder(service).defaultUserId("shuzhongren")
                // 必须排在 ToolCallingAdvisor（order = Integer.MIN_VALUE + 300）之前：
                // 否则 ChatClient 检测到 MemoryAdvisor 在其后会关闭 conversationHistoryEnabled，
                // 工具循环的第二次调用只发 [system, tool响应]，触发 DeepSeek 400
                .order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE)
                .compactionTrigger(new TurnCountTrigger(200)) // 超过 200 轮触发压缩；压缩会把旧消息摘要化，不是简单丢弃
                // SlidingWindowCompactionStrategy这种硬丢失的方式不行,需要换一种关键信息回收的策略.
//                .compactionStrategy(SlidingWindowCompactionStrategy.builder().maxEvents(1).build()) 
//                这种是带摘要提取的压缩策略,不丢失关键信息,利用大模型提取关键信息
                .compactionStrategy(
                        RecursiveSummarizationCompactionStrategy.builder(
                                ChatClient.builder(deepSeekChatModel).build())
                                .maxEventsToKeep(200) // 最近200份聊天不压缩.
                                .overlapSize(10) // 超出上限的部分压缩 重叠200份中的2条一起压缩 
                                .build())
                .build();
    }

}
